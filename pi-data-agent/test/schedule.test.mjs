import test from 'node:test';
import assert from 'node:assert/strict';
import { execute } from '../src/execute.mjs';
import { runSchedule, renderSchedule, previousOf } from '../src/schedule.mjs';

/** A model bound to a keyed fact graph: the ids are what a type scan cannot do. */
const keyedModel = {
  graph: 'sales-model',
  dataGraph: 'sales-facts',
  vertexType: 'SalesDay',
  nodes: [
    { id: 'model/sales-model', entityType: 'Model', properties: { dataGraph: 'sales-facts', vertexType: 'SalesDay', factIds: ['sd-1', 'sd-2'] } },
    { id: 'metric/营收', entityType: 'Metric', properties: { name: '营收', expression: 'SUM(revenue)', graphProperty: 'revenue' } },
    { id: 'dim/日期', entityType: 'Dimension', properties: { name: '日期', graphProperty: 'year=year,month=month', dataType: 'DATE' } },
    { id: 'dim/渠道', entityType: 'Dimension', properties: { name: '渠道', graphProperty: 'channel' } },
  ],
  edges: [],
};

test('a keyed fact graph is read by id, and the type name closes the start step', async () => {
  const queries = [];
  const client = { query: async (_graph, gremlin) => { queries.push(gremlin); return [1234]; } };
  const result = await execute(client, keyedModel, { metric: 'metric/营收', year: 2025 });
  assert.equal(result.executed, true);
  assert.equal(result.total, 1234);
  assert.equal(queries[0].startsWith("g.V('sd-1','sd-2','SalesDay')"), true);
  assert.match(queries[0], /\.has\('year', 2025\)/);
  assert.match(queries[0], /values\('revenue'\)\.sum\(\)/);
});

test('without fact ids the same model starts from the vertex type (a scannable store)', async () => {
  const queries = [];
  const client = { query: async (_graph, gremlin) => { queries.push(gremlin); return [7]; } };
  const scanModel = { ...keyedModel, nodes: keyedModel.nodes.map((node) => (node.entityType === 'Model' ? { ...node, properties: { dataGraph: 'facts', vertexType: 'SalesDay' } } : node)) };
  await execute(client, scanModel, { metric: 'metric/营收' });
  assert.equal(queries[0].startsWith("g.V('SalesDay')"), true);
});

test('the schedule runs one cycle per period, waits between them and records what each produced', async () => {
  const sleeps = [];
  const order = [];
  const schedule = await runSchedule({
    graph: 'sales-model',
    periods: ['2024', '2025', '2026'],
    everyMs: 1000,
    sleep: async (ms) => { sleeps.push(ms); },
    dashboardFor: async (options) => {
      order.push(`dashboard:${options.year}`);
      return {
        dashboard: {
          executed: 3, refused: 1, tiles: [
            { kind: 'total', metric: 'metric/营收' },
            { kind: 'breakdown', dimension: 'dim/渠道' },
            { kind: 'refusal', metric: 'metric/客单价' },
            { kind: 'total', metric: 'metric/订单数' },
          ],
        },
        files: { html: `/tmp/dash-${options.year}.html`, json: `/tmp/dash-${options.year}.json` },
      };
    },
    attributeFor: async (options) => {
      order.push(`attribute:${options.from}->${options.to}`);
      return { result: { executed: true, attribution: { totalBefore: 100, totalAfter: 150, delta: 50, topMover: { value: 'APP', delta: 30 } } }, files: null };
    },
  });
  assert.equal(schedule.cycles, 3);
  assert.equal(schedule.executed, 9);
  assert.equal(schedule.refused, 3);
  assert.deepEqual(sleeps, [1000, 1000], 'no sleep before the first cycle');
  assert.deepEqual(order, [
    'dashboard:2024', 'attribute:2023->2024',
    'dashboard:2025', 'attribute:2024->2025',
    'dashboard:2026', 'attribute:2025->2026',
  ]);
  assert.equal(schedule.runs[1].attribution.delta, 50);
  assert.equal(schedule.runs[2].dashboard.files.html, '/tmp/dash-2026.html');
  const text = renderSchedule(schedule);
  assert.match(text, /2025 \| 3\/1 tiles \| 2024 -> 2025: delta 50/);
  assert.match(text, /total: 9 executed, 3 refused/);
});

test('a refused attribution is recorded as a refusal, not as a zero', async () => {
  const schedule = await runSchedule({
    graph: 'g', periods: ['2025'], sleep: async () => {},
    dashboardFor: async () => ({ dashboard: { executed: 1, refused: 0, tiles: [{ kind: 'total', metric: 'metric/m' }, { kind: 'breakdown', dimension: 'dim/d' }] }, files: null }),
    attributeFor: async () => ({ result: { executed: false, reason: 'dim/d is not bound on the fact graph' }, files: null }),
  });
  assert.equal(schedule.runs[0].attribution.executed, false);
  assert.match(renderSchedule(schedule), /refused: dim\/d is not bound/);
});

test('a schedule without periods is refused, and a period knows its predecessor', async () => {
  await assert.rejects(() => runSchedule({ graph: 'g', periods: [] }), /at least one period/);
  assert.equal(previousOf('2025'), '2024');
  assert.equal(previousOf('2025-10'), null, 'a non-year period has no arithmetic predecessor');
});
