import test from 'node:test';
import assert from 'node:assert/strict';
import {
  instantMs, windowOf, isValidAt, versionId, baseId, isVersion, resolveAsOf, windowText,
} from '../src/validity.mjs';
import { applyEdits } from '../src/edits.mjs';

test('an instant is read from a date, an ISO string, seconds or millis, and an empty value means open-ended', () => {
  const iso = '2026-10-15T00:00:00Z';
  assert.equal(instantMs(iso), Date.parse(iso));
  assert.equal(instantMs(new Date(iso)), Date.parse(iso));
  assert.equal(instantMs(Date.parse(iso)), Date.parse(iso));
  assert.equal(instantMs(Date.parse(iso) / 1000), Date.parse(iso), 'seconds are recognised by magnitude');
  assert.equal(instantMs(String(Date.parse(iso))), Date.parse(iso));
  for (const open of [null, undefined, '', ' ']) assert.equal(instantMs(open), null);
});

test('a window is half open, and an element without one holds at every instant', () => {
  const window = { validFrom: '2024-01-01T00:00:00Z', validTo: '2026-10-15T00:00:00Z' };
  assert.equal(isValidAt(window, Date.parse('2024-01-01T00:00:00Z')), true, 'the start is inclusive');
  assert.equal(isValidAt(window, Date.parse('2026-10-14T23:59:59Z')), true);
  assert.equal(isValidAt(window, Date.parse('2026-10-15T00:00:00Z')), false, 'the end is exclusive');
  assert.equal(isValidAt({}, Date.parse('1999-01-01T00:00:00Z')), true);
  assert.equal(isValidAt(window, null), true, 'no instant filters nothing');
  assert.deepEqual(windowOf({ validFrom: '2024-01-01T00:00:00Z' }), { from: Date.parse('2024-01-01T00:00:00Z'), to: null });
  assert.equal(windowText({}), 'always');
  assert.match(windowText(window), /^2024-01-01T00:00:00Z -> 2026-10-15T00:00:00Z$/);
});

test('a version id names the base id and the instant it started, and can be told apart', () => {
  const id = versionId('metric/营收', '2024-01-01T00:00:00Z');
  assert.equal(id, 'metric/营收#v2024-01-01T00:00:00.000Z');
  assert.equal(baseId(id), 'metric/营收');
  assert.equal(isVersion(id), true);
  assert.equal(isVersion('metric/营收'), false);
});

test('the model as of an instant has one node per definition and the relations re-pointed at it', () => {
  const model = {
    nodes: [
      { id: 'metric/营收', entityType: 'Metric', properties: { name: '营收', expression: 'SUM(net_revenue)', validFrom: '2026-10-15T00:00:00Z' } },
      { id: 'metric/营收#v2024-01-01T00:00:00.000Z', entityType: 'Metric', properties: { name: '营收', expression: 'SUM(revenue)', validFrom: '2024-01-01T00:00:00Z', validTo: '2026-10-15T00:00:00Z' } },
      { id: 'dim/渠道', entityType: 'Dimension', properties: { name: '渠道', graphProperty: 'channel' } },
      { id: 'metric/客单价', entityType: 'Metric', properties: { name: '客单价', validFrom: '2024-01-01T00:00:00Z', validTo: '2025-01-01T00:00:00Z' } },
    ],
    edges: [
      { id: 'metric/营收|groupedBy|dim/渠道', head: 'metric/营收', relation: 'groupedBy', tail: 'dim/渠道', properties: {} },
      { id: 'metric/营收#v2024-01-01T00:00:00.000Z|groupedBy|dim/渠道', head: 'metric/营收#v2024-01-01T00:00:00.000Z', relation: 'groupedBy', tail: 'dim/渠道', properties: {} },
      { id: 'metric/客单价|groupedBy|dim/渠道', head: 'metric/客单价', relation: 'groupedBy', tail: 'dim/渠道', properties: {} },
    ],
  };
  const early = resolveAsOf(model, '2026-01-01T00:00:00Z');
  assert.deepEqual(early.nodes.map((node) => node.id).sort(), ['dim/渠道', 'metric/营收']);
  const revenue = early.nodes.find((node) => node.id === 'metric/营收');
  assert.equal(revenue.properties.expression, 'SUM(revenue)', 'the version valid then answers');
  assert.equal(revenue.properties.definitionId, 'metric/营收#v2024-01-01T00:00:00.000Z');
  assert.deepEqual(early.edges.map((edge) => edge.id), ['metric/营收|groupedBy|dim/渠道'], 'the version edge is re-pointed, not duplicated');
  assert.equal(early.edges[0].head, 'metric/营收');

  const late = resolveAsOf(model, '2026-11-01T00:00:00Z');
  assert.equal(late.nodes.find((node) => node.id === 'metric/营收').properties.expression, 'SUM(net_revenue)');
  assert.equal(late.nodes.some((node) => node.id === 'metric/客单价'), false, 'a retired definition is gone from the live view');
  assert.equal(resolveAsOf(model, null), model, 'no instant is the model itself');
});

/** A client that records what a write would send, so the edit semantics can be asserted without a service. */
function recordingClient() {
  const calls = { upsertVertices: [], upsertEdges: [], deleteVertex: [], deleteEdge: [] };
  return {
    calls,
    schema: async () => ({
      vertexSchemas: [{ name: 'Node', properties: [{ id: 0, name: 'name' }, { id: 1, name: 'description' }, { id: 2, name: 'expression' }, { id: 3, name: 'validFrom' }, { id: 4, name: 'validTo' }, { id: 5, name: 'definitionId' }, { id: 6, name: 'graphProperty' }, { id: 7, name: 'entityType' }, { id: 8, name: 'nodeIds' }, { id: 9, name: 'edgeIds' }] }],
      edgeSchemas: [{ name: 'Relation', properties: [{ id: 0, name: 'relation' }, { id: 1, name: 'xrelation' }, { id: 2, name: 'relationLabel' }, { id: 3, name: 'relationFamily' }, { id: 4, name: 'validFrom' }, { id: 5, name: 'validTo' }] }],
    }),
    upsertVertices: async (_graph, vertices) => { calls.upsertVertices.push(...vertices); return []; },
    upsertEdges: async (_graph, edges) => { calls.upsertEdges.push(...edges); return []; },
    deleteVertex: async (_graph, id) => { calls.deleteVertex.push(id); return {}; },
    deleteEdge: async (_graph, edge) => { calls.deleteEdge.push(edge); return {}; },
  };
}

test('an edit with an effective instant freezes the old definition and moves the live one', async () => {
  const client = recordingClient();
  const model = {
    graph: 'g',
    nodes: [
      { id: 'model/g', entityType: 'Model', properties: { name: 'model/g', nodeIds: ['model/g', 'metric/营收'], edgeIds: [] } },
      { id: 'metric/营收', entityType: 'Metric', properties: { name: '营收', expression: 'SUM(revenue)', graphProperty: 'revenue', validFrom: '2024-01-01T00:00:00Z' } },
    ],
    edges: [],
  };
  const result = await applyEdits(client, 'g', model, [{
    op: 'update-node', id: 'metric/营收', effectiveFrom: '2026-10-15T00:00:00Z',
    properties: { expression: 'SUM(net_revenue)', graphProperty: 'net_revenue' },
  }], { now: '2026-11-02T00:00:00Z' });

  const version = result.model.nodes.find((node) => node.id === 'metric/营收#v2024-01-01T00:00:00.000Z');
  assert.ok(version, 'the old definition is kept as a version vertex');
  assert.equal(version.properties.expression, 'SUM(revenue)');
  assert.equal(version.properties.validTo, Date.parse('2026-10-15T00:00:00Z'));
  assert.equal(version.properties.definitionId, 'metric/营收');
  const live = result.model.nodes.find((node) => node.id === 'metric/营收');
  assert.equal(live.properties.expression, 'SUM(net_revenue)');
  assert.equal(live.properties.validFrom, Date.parse('2026-10-15T00:00:00Z'));
  assert.equal(live.properties.validTo, undefined);
  assert.ok(result.model.edges.some((edge) => edge.id === 'metric/营收#v2024-01-01T00:00:00.000Z|supersededBy|metric/营收'));
  assert.equal(result.audit.some((record) => record.op === 'supersede'), true);
  // the catalog keeps the version id, or a keyed store could never read the history back
  const catalog = result.model.nodes.find((node) => node.entityType === 'Model').properties.nodeIds;
  assert.ok(catalog.includes('metric/营收#v2024-01-01T00:00:00.000Z'));
  assert.ok(client.calls.upsertVertices.some((vertex) => vertex.id === 'metric/营收#v2024-01-01T00:00:00.000Z'));
  assert.ok(client.calls.upsertEdges.some((edge) => edge.edgeId === 'metric/营收#v2024-01-01T00:00:00.000Z|supersededBy|metric/营收'));
});

test('an effective instant that is not later than the current one is a correction, not a new version', async () => {
  const model = {
    graph: 'g',
    nodes: [
      { id: 'model/g', entityType: 'Model', properties: { name: 'model/g', nodeIds: ['model/g', 'metric/营收'], edgeIds: [] } },
      { id: 'metric/营收', entityType: 'Metric', properties: { name: '营收', validFrom: '2026-10-15T00:00:00Z' } },
    ],
    edges: [],
  };
  const result = await applyEdits(recordingClient(), 'g', model, [{
    op: 'update-node', id: 'metric/营收', effectiveFrom: '2026-10-15T00:00:00Z', properties: { description: 'typo fixed' },
  }]);
  assert.equal(result.model.nodes.some((node) => isVersion(node.id)), false);
  assert.equal(result.model.nodes.find((node) => node.id === 'metric/营收').properties.description, 'typo fixed');
});

test('retire-node closes the window instead of removing the definition, and needs confirmation', async () => {
  const model = {
    graph: 'g',
    nodes: [
      { id: 'model/g', entityType: 'Model', properties: { name: 'model/g', nodeIds: ['model/g', 'term/退款'], edgeIds: [] } },
      { id: 'term/退款', entityType: 'Term', properties: { name: '退款', validFrom: '2024-01-01T00:00:00Z' } },
    ],
    edges: [],
  };
  await assert.rejects(
    () => applyEdits(recordingClient(), 'g', model, [{ op: 'retire-node', id: 'term/退款', effectiveTo: '2026-10-15T00:00:00Z' }]),
    /confirm:true/,
  );
  const result = await applyEdits(recordingClient(), 'g', model, [
    { op: 'retire-node', id: 'term/退款', confirm: true, effectiveTo: '2026-10-15T00:00:00Z' },
  ]);
  const retired = result.model.nodes.find((node) => node.id === 'term/退款');
  assert.equal(retired.properties.validTo, Date.parse('2026-10-15T00:00:00Z'));
  assert.ok(result.model.nodes.some((node) => node.id === 'term/退款'), 'the node stays: history must stay readable');
  assert.equal(resolveAsOf(result.model, '2026-11-01T00:00:00Z').nodes.some((node) => node.id === 'term/退款'), false);
  assert.equal(resolveAsOf(result.model, '2025-06-01T00:00:00Z').nodes.some((node) => node.id === 'term/退款'), true);
});

test('a hard delete removes the id from the catalog, a retire keeps it', async () => {
  const model = {
    graph: 'g',
    nodes: [
      { id: 'model/g', entityType: 'Model', properties: { name: 'model/g', nodeIds: ['model/g', 'term/退款', 'term/成交'], edgeIds: [] } },
      { id: 'term/退款', entityType: 'Term', properties: { name: '退款' } },
      { id: 'term/成交', entityType: 'Term', properties: { name: '成交' } },
    ],
    edges: [],
    catalog: { nodeIds: ['model/g', 'term/退款', 'term/成交'], edgeIds: [] },
  };
  const deleted = await applyEdits(recordingClient(), 'g', model, [{ op: 'delete-node', id: 'term/退款', confirm: true }]);
  const ids = deleted.model.nodes.find((node) => node.entityType === 'Model').properties.nodeIds;
  assert.equal(ids.includes('term/退款'), false);
  assert.equal(ids.includes('term/成交'), true);
});
