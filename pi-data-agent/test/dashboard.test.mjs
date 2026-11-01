import test from 'node:test';
import assert from 'node:assert/strict';
import { attribute, runAttribution, renderAttribution } from '../src/attribution.mjs';
import { buildDashboard, renderDashboardHtml, renderDashboardMarkdown } from '../src/dashboard.mjs';

test('attribution decomposes a change, keeps the sum and reports presence', () => {
  const result = attribute({
    beforeRows: [{ value: 'A', measure: 100 }, { value: 'B', measure: 50 }],
    afterRows: [{ value: 'A', measure: 140 }, { value: 'B', measure: 30 }, { value: 'C', measure: 10 }],
    from: 2024, to: 2025, metric: 'metric/m', dimension: 'dim/d',
  });
  assert.equal(result.totalBefore, 150);
  assert.equal(result.totalAfter, 180);
  assert.equal(result.delta, 30);
  assert.equal(result.decompositionAddsUp, true);
  assert.equal(result.contributionsSum, 30);
  assert.equal(result.contributions[0].value, 'A');
  assert.equal(result.contributions[0].delta, 40);
  assert.equal(result.contributions[0].shareOfChange, 133.33);
  const c = result.contributions.find((item) => item.value === 'C');
  assert.equal(c.presence, 'after_only');
  assert.ok(result.warnings.some((warning) => /C has no value in 2024/.test(warning)));
  assert.ok(result.limits.some((limit) => /Descriptive, not causal/.test(limit)));
  assert.equal(result.direction, 'up');
});

test('equal totals report a flat change and no share', () => {
  const result = attribute({ beforeRows: [{ value: 'A', measure: 10 }], afterRows: [{ value: 'A', measure: 10 }], from: 1, to: 2 });
  assert.equal(result.delta, 0);
  assert.equal(result.direction, 'flat');
  assert.equal(result.contributions[0].shareOfChange, null);
  assert.equal(result.decompositionAddsUp, true);
});

const model = {
  graph: 'g',
  dataGraph: 'facts',
  vertexType: 'Fact',
  nodes: [
    { id: 'model/g', entityType: 'Model', properties: { dataGraph: 'facts', vertexType: 'Fact' } },
    { id: 'metric/revenue', entityType: 'Metric', properties: { name: 'revenue', expression: 'SUM(revenue)', graphProperty: 'revenue' } },
    { id: 'metric/ratio', entityType: 'Metric', properties: { name: 'ratio', expression: 'SUM(a)/SUM(b)', graphProperty: undefined } },
    { id: 'dim/channel', entityType: 'Dimension', properties: { name: 'channel', graphProperty: 'channel', dataType: 'VARCHAR' } },
    { id: 'dim/date', entityType: 'Dimension', properties: { name: 'date', graphProperty: 'year=year,month=month', dataType: 'DATE' } },
  ],
  edges: [
    { id: 'metric/revenue|computedFrom|table/t', head: 'metric/revenue', relation: 'computedFrom', tail: 'table/t', properties: {} },
    { id: 'metric/revenue|groupedBy|dim/channel', head: 'metric/revenue', relation: 'groupedBy', tail: 'dim/channel', properties: {} },
    { id: 'dim/channel|dimensionColumn|column/t.channel', head: 'dim/channel', relation: 'dimensionColumn', tail: 'column/t.channel', properties: {} },
    { id: 'dim/date|dimensionColumn|column/t.date', head: 'dim/date', relation: 'dimensionColumn', tail: 'column/t.date', properties: {} },
  ],
  stats: { metrics: 2, dimensions: 2, tables: 1, columns: 2 },
};
model.nodes.push({ id: 'table/t', entityType: 'Table', properties: { name: 't' } });
model.nodes.push({ id: 'column/t.channel', entityType: 'Column', properties: { name: 'channel', tableName: 't' } });
model.nodes.push({ id: 'column/t.date', entityType: 'Column', properties: { name: 'date', tableName: 't' } });

function factClient() {
  return {
    async query(_graph, gremlin) {
      if (gremlin.includes("group().by('month')")) return [{ 1: 10, 2: 20 }];
      if (gremlin.includes("group().by('channel')")) return [{ 搜索: 140, 联盟: 30 }];
      if (gremlin.includes("group().by('year')")) return [{ 2024: 40, 2025: 60 }];
      if (gremlin.includes("has('year', 2024)")) return [{ 搜索: 100, 联盟: 50 }];
      if (gremlin.includes("has('year', 2025)")) return [{ 搜索: 140, 联盟: 30 }];
      if (gremlin.includes('count()')) return [7];
      return [150];
    },
  };
}

function attributionClient() {
  return {
    async query(_graph, gremlin) {
      if (gremlin.includes("has('year', 2024)")) return [{ 搜索: 100, 联盟: 50 }];
      if (gremlin.includes("has('year', 2025)")) return [{ 搜索: 140, 联盟: 30 }];
      return [0];
    },
  };
}

function dashboardClient() {
  return {
    async query(_graph, gremlin) {
      if (gremlin.includes("group().by('month')")) return [{ 1: 10, 2: 20 }];
      if (gremlin.includes("group().by('channel')")) return [{ 搜索: 140, 联盟: 30 }];
      if (gremlin.includes("group().by('year')")) return [{ 2024: 40, 2025: 60 }];
      return [150];
    },
  };
}

test('runAttribution executes two periods and refuses a missing binding', async () => {
  const result = await runAttribution(attributionClient(), model, { metric: 'revenue', by: 'channel', from: 2024, to: 2025 });
  assert.equal(result.executed, true);
  assert.equal(result.attribution.totalBefore, 150);
  assert.equal(result.attribution.totalAfter, 170);
  assert.equal(result.attribution.delta, 20);
  assert.match(renderAttribution(result), /decomposition adds up = true/);
  assert.match(renderAttribution(result), /not causal/i);

  const refused = await runAttribution(attributionClient(), model, { metric: 'ratio', by: 'channel', from: 2024, to: 2025 });
  assert.equal(refused.executed, false);
  assert.match(refused.reason, /no graphProperty/);
});

test('a dashboard renders totals, a trend and a breakdown with provenance', async () => {
  const dashboard = await buildDashboard(dashboardClient(), model, { year: 2025, topN: 2, now: '2026-10-30T00:00:00.000Z' });
  assert.equal(dashboard.graph, 'g');
  assert.equal(dashboard.dataGraph, 'facts');
  assert.equal(dashboard.vertexType, 'Fact');
  assert.ok(dashboard.tiles.some((tile) => tile.kind === 'total' && tile.metric === 'metric/revenue' && tile.total === 150));
  assert.ok(dashboard.tiles.some((tile) => tile.kind === 'trend' && tile.grain === 'month'));
  assert.ok(dashboard.tiles.some((tile) => tile.kind === 'breakdown' && tile.dimension === 'dim/channel'));
  assert.ok(dashboard.tiles.some((tile) => tile.metric === 'metric/ratio' && tile.executed === false));
  const markdown = renderDashboardMarkdown(dashboard);
  assert.match(markdown, /g\.V\('Fact'\)/);
  assert.match(markdown, /refused: metric\/ratio/);
  const html = renderDashboardHtml(dashboard);
  assert.match(html, /<!doctype html>/);
  assert.match(html, /搜索/);
  assert.match(html, /provenance/);
  assert.doesNotMatch(html, /https?:\/\//);
});
