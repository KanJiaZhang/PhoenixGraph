import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveNode, resolveMetric, resolveDimension, choices } from '../src/resolve.mjs';
import { metricNode, dimensionNode } from '../src/execute.mjs';
import { buildDashboard } from '../src/dashboard.mjs';
import { runAttribution } from '../src/attribution.mjs';

const model = {
  graph: 'g',
  dataGraph: 'facts',
  vertexType: 'Fact',
  nodes: [
    { id: 'model/g', entityType: 'Model', properties: { dataGraph: 'facts', vertexType: 'Fact' } },
    { id: 'metric/营收', entityType: 'Metric', properties: { name: '营收', alias: ['收入', 'revenue'], expression: 'SUM(revenue)', graphProperty: 'revenue' } },
    { id: 'metric/净营收', entityType: 'Metric', properties: { name: '净营收', alias: ['净收入'], expression: 'SUM(net_revenue)', graphProperty: 'net_revenue' } },
    { id: 'metric/客单价', entityType: 'Metric', properties: { name: '客单价', graphProperty: 'aov' } },
    { id: 'dim/渠道', entityType: 'Dimension', properties: { name: '渠道', graphProperty: 'channel', column: 't.channel' } },
    { id: 'dim/地区', entityType: 'Dimension', properties: { name: '地区', graphProperty: 'region' } },
    { id: 'dim/chan', entityType: 'Dimension', properties: { name: '渠道维度', graphProperty: 'channel2' } },
    { id: 'dim/日期', entityType: 'Dimension', properties: { name: '日期', graphProperty: 'year=year,month=month', column: 't.date', dataType: 'DATE' } },
    { id: 'table/t', entityType: 'Table', properties: { name: 't' } },
  ],
  edges: [
    { id: 'metric/营收|groupedBy|dim/渠道', head: 'metric/营收', relation: 'groupedBy', tail: 'dim/渠道', properties: {} },
    { id: 'dim/渠道|dimensionColumn|column/t.channel', head: 'dim/渠道', relation: 'dimensionColumn', tail: 'column/t.channel', properties: {} },
  ],
};

test('a reference resolves by id, name, alias, bound property or column - in that order', () => {
  assert.equal(resolveMetric(model, 'metric/营收').how, 'id');
  assert.equal(resolveMetric(model, '营收').how, 'id', 'a bare name is also a valid id suffix');
  assert.equal(resolveMetric(model, '净营收').how, 'id');
  assert.equal(resolveMetric(model, '收入').how, 'alias');
  assert.equal(resolveMetric(model, 'revenue').how, 'alias', '营收 lists revenue as an alias');
  assert.equal(resolveMetric(model, 'aov').how, 'binding');
  assert.equal(resolveDimension(model, 'dim/渠道').how, 'id');
  assert.equal(resolveDimension(model, '渠道').how, 'id', 'the bare name is the id suffix here');
  assert.equal(resolveDimension(model, '渠道维度').how, 'name', 'this one has an id that differs from its name');
  assert.equal(resolveDimension(model, 'channel').how, 'binding');
  assert.equal(resolveDimension(model, 't.channel').how, 'column');
  assert.equal(resolveDimension(model, 'date').how, 'column', 'the date dimension is findable by its column');
  assert.equal(resolveDimension(model, 'year').how, 'binding', 'a grain binding resolves by its property');
  // ids are absolute: the same text as a name of another type does not leak across types
  assert.equal(resolveMetric(model, '渠道'), null);
  assert.equal(resolveDimension(model, 'revenue'), null);
  assert.equal(resolveMetric(model, 'nope'), null);
  assert.equal(resolveMetric(model, '  '), null);
  assert.equal(resolveMetric(model, '  营收  ').node.id, 'metric/营收', 'whitespace is not part of a name');
});

test('an ambiguous reference resolves deterministically, and the alternatives stay visible', () => {
  const shared = {
    ...model,
    nodes: [
      ...model.nodes,
      { id: 'metric/净额客单价', entityType: 'Metric', properties: { name: '净额客单价', graphProperty: 'aov' } },
    ],
  };
  const first = resolveMetric(shared, 'aov');
  const second = resolveMetric(shared, 'aov');
  assert.equal(first.node.id, second.node.id, 'the same model must resolve the same way every time');
  assert.equal(first.how, 'binding');
  assert.ok(first.node.id === 'metric/客单价' || first.node.id === 'metric/净额客单价');
  assert.deepEqual(choices(shared, 'Metric'), ['metric/净营收', 'metric/客单价', 'metric/营收', 'metric/净额客单价'].sort());
  assert.equal(metricNode(shared, '营收').id, 'metric/营收', 'a name still beats the binding');
});

test('the canonical helpers accept every spelling', () => {
  assert.equal(metricNode(model, '营收').id, 'metric/营收');
  assert.equal(metricNode(model, 'revenue').id, 'metric/营收');
  assert.equal(dimensionNode(model, 'channel').id, 'dim/渠道');
  assert.equal(dimensionNode(model, 'dim/渠道').id, 'dim/渠道');
  assert.equal(dimensionNode(model, 'nope'), null);
});

function factClient() {
  return {
    async query(_graph, gremlin) {
      if (gremlin.includes("group().by('channel')")) return [{ APP: 100, WEB: 50 }];
      if (gremlin.includes("group().by('month')")) return [{ 1: 60, 2: 90 }];
      return [150];
    },
  };
}

test('a dashboard asked in any spelling produces the same tiles, and an unresolvable name is refused out loud', async () => {
  const byName = await buildDashboard(factClient(), model, { metric: '营收', year: '2025' });
  const byId = await buildDashboard(factClient(), model, { metric: 'metric/营收', year: '2025' });
  const byAlias = await buildDashboard(factClient(), model, { metric: '收入', year: '2025' });
  const byBinding = await buildDashboard(factClient(), model, { metric: 'revenue', year: '2025' });
  const ids = (dashboard) => dashboard.tiles.map((tile) => tile.id).sort();
  assert.deepEqual(ids(byId), ids(byName));
  assert.deepEqual(ids(byAlias), ids(byName));
  assert.deepEqual(ids(byBinding), ids(byName));
  assert.equal(byName.executed, 3);

  const byDimensionName = await buildDashboard(factClient(), model, { metric: '营收', year: '2025', dimensions: ['渠道'] });
  const byDimensionBinding = await buildDashboard(factClient(), model, { metric: '营收', year: '2025', dimensions: ['channel'] });
  assert.deepEqual(ids(byDimensionBinding), ids(byDimensionName));
  assert.ok(ids(byDimensionName).includes('metric/营收|breakdown|dim/渠道'));

  // an unknown dimension is a refusal tile with the alternatives, not a silently shorter dashboard
  const unknown = await buildDashboard(factClient(), model, { metric: '营收', year: '2025', dimensions: ['渠道 ', 'nope'] });
  const refusal = unknown.tiles.find((tile) => tile.id === 'metric/营收|dimension|nope');
  assert.ok(refusal, 'the requested-but-unknown dimension gets its own tile');
  assert.equal(refusal.kind, 'refusal');
  assert.match(refusal.reason, /no dimension 'nope'/);
  assert.match(refusal.reason, /dim\/渠道/);

  // and an unknown metric is refused rather than dropped
  const unknownMetric = await buildDashboard(factClient(), model, { metric: 'nope', year: '2025' });
  assert.equal(unknownMetric.tiles.length, 1);
  assert.equal(unknownMetric.tiles[0].kind, 'refusal');
  assert.match(unknownMetric.tiles[0].reason, /no metric 'nope'/);
});

test('an attribution accepts every spelling of its metric and dimension, and names the alternatives when it cannot', async () => {
  const canonical = await runAttribution(factClient(), model, { metric: '营收', by: '渠道', from: '2024', to: '2025' });
  const aliased = await runAttribution(factClient(), model, { metric: 'metric/营收', by: 'channel', from: 2024, to: 2025 });
  assert.equal(canonical.executed, true);
  assert.deepEqual(aliased.result, canonical.result, 'the whole attribution, not just the delta');

  const unknownMetric = await runAttribution(factClient(), model, { metric: 'nope', by: '渠道', from: 1, to: 2 });
  assert.equal(unknownMetric.executed, false);
  assert.match(unknownMetric.reason, /no metric 'nope'/);
  assert.match(unknownMetric.reason, /metric\/营收/);
  const unknownDimension = await runAttribution(factClient(), model, { metric: '营收', by: 'nope', from: 1, to: 2 });
  assert.match(unknownDimension.reason, /no dimension 'nope'/);
  assert.match(unknownDimension.reason, /dim\/渠道/);
});
