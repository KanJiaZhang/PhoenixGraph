import test from 'node:test';
import assert from 'node:assert/strict';
import { applyEdits } from '../src/edits.mjs';

function baseModel() {
  return {
    graph: 'g',
    dataGraph: 'facts',
    vertexType: 'Fact',
    nodes: [
      { id: 'model/g', entityType: 'Model', properties: { dataGraph: 'facts', vertexType: 'Fact', nodeIds: [], edgeIds: [] } },
      { id: 'table/t', entityType: 'Table', properties: { name: 't' } },
      { id: 'column/t.a', entityType: 'Column', properties: { name: 'a', tableName: 't', dataType: 'DECIMAL' } },
      { id: 'metric/m', entityType: 'Metric', properties: { name: 'm', expression: 'SUM(a)', graphProperty: null } },
      { id: 'dim/d', entityType: 'Dimension', properties: { name: 'd', graphProperty: 'd' } },
    ],
    edges: [
      { id: 'table/t|hasColumn|column/t.a', head: 'table/t', relation: 'hasColumn', tail: 'column/t.a', properties: {} },
      { id: 'metric/m|usesColumn|column/t.a', head: 'metric/m', relation: 'usesColumn', tail: 'column/t.a', properties: {} },
    ],
    warnings: [],
  };
}

const schema = {
  vertexSchemas: [{ name: 'Node', properties: [
    'name', 'entityType', 'description', 'alias', 'dataType', 'columnRole', 'tableName', 'aggFunction',
    'expression', 'metricKind', 'unit', 'sqlText', 'graphProperty', 'dataGraph', 'vertexType', 'owner',
    'domain', 'sourceId', 'evidence', 'confidence', 'status', 'proposedBy', 'needsReview', 'builtAt',
    'modelVersion', 'nodeIds', 'edgeIds',
  ].map((name, index) => ({ id: index + 1, name })) }],
  edgeSchemas: [{ name: 'Relation', properties: ['relation', 'xrelation', 'relationLabel', 'relationFamily', 'confidence', 'evidence', 'status', 'sourceId'].map((name, index) => ({ id: index + 1, name })) }],
};

function fakeClient() {
  const calls = { vertices: [], edges: [], deletedVertices: [], deletedEdges: [] };
  return {
    calls,
    async schema() { return schema; },
    async upsertVertices(_graph, vertices) { calls.vertices.push(...vertices); return []; },
    async upsertEdges(_graph, edges) { calls.edges.push(...edges); return []; },
    async deleteVertex(_graph, id) { calls.deletedVertices.push(id); return {}; },
    async deleteEdge(_graph, edge) { calls.deletedEdges.push(edge); return {}; },
  };
}

test('update, binding and alias edits apply in memory and refresh the catalog', async () => {
  const client = fakeClient();
  const result = await applyEdits(client, 'g', baseModel(), [
    { op: 'update-node', id: 'metric/m', properties: { description: 'revised', unit: '元' } },
    { op: 'set-binding', id: 'metric/m', graphProperty: 'm' },
    { op: 'add-alias', id: 'metric/m', alias: ['收入', '营收'] },
    { op: 'upsert-node', node: { id: 'term/new', entityType: 'Term', properties: { name: 'new', description: 'term' } } },
    { op: 'upsert-edge', edge: { head: 'term/new', relation: 'sameAs', tail: 'metric/m' } },
  ], { now: '2026-10-30T00:00:00.000Z' });
  assert.equal(result.ok, true);
  const metric = result.model.nodes.find((node) => node.id === 'metric/m');
  assert.equal(metric.properties.description, 'revised');
  assert.equal(metric.properties.graphProperty, 'm');
  assert.deepEqual(metric.properties.alias, ['收入', '营收']);
  assert.ok(result.model.nodes.some((node) => node.id === 'term/new'));
  assert.ok(result.model.edges.some((edge) => edge.id === 'term/new|sameAs|metric/m'));
  const modelNode = result.model.nodes.find((node) => node.entityType === 'Model');
  assert.ok(modelNode.properties.nodeIds.includes('term/new'));
  assert.ok(modelNode.properties.edgeIds.includes('term/new|sameAs|metric/m'));
  assert.equal(client.calls.vertices.length, 3); // metric, term, and the refreshed Model catalog
  assert.equal(client.calls.edges.length, 1);
  assert.ok(result.audit.length >= 5);
});

test('dry run validates but writes nothing', async () => {
  const client = fakeClient();
  const result = await applyEdits(client, 'g', baseModel(), [
    { op: 'update-node', id: 'metric/m', properties: { description: 'x' } },
  ], { dryRun: true });
  assert.equal(result.dryRun, true);
  assert.equal(client.calls.vertices.length, 0);
  assert.equal(client.calls.edges.length, 0);
});

test('a relation must exist and its endpoint types must match', async () => {
  await assert.rejects(
    () => applyEdits(fakeClient(), 'g', baseModel(), [{ op: 'upsert-edge', edge: { head: 'metric/m', relation: 'hasColumn', tail: 'column/t.a' } }]),
    /hasColumn needs a Table head/,
  );
  await assert.rejects(
    () => applyEdits(fakeClient(), 'g', baseModel(), [{ op: 'upsert-edge', edge: { head: 'metric/m', relation: 'invented', tail: 'dim/d' } }]),
    /unknown relation/,
  );
});

test('deletes need confirmation, and a node with edges needs cascade', async () => {
  await assert.rejects(
    () => applyEdits(fakeClient(), 'g', baseModel(), [{ op: 'delete-node', id: 'metric/m' }]),
    /needs confirm:true/,
  );
  await assert.rejects(
    () => applyEdits(fakeClient(), 'g', baseModel(), [{ op: 'delete-node', id: 'metric/m', confirm: true }]),
    /has 1 edge/,
  );
  const result = await applyEdits(fakeClient(), 'g', baseModel(), [{ op: 'delete-node', id: 'metric/m', confirm: true, cascade: true }]);
  assert.equal(result.model.nodes.some((node) => node.id === 'metric/m'), false);
  assert.equal(result.model.edges.some((edge) => edge.head === 'metric/m' || edge.tail === 'metric/m'), false);
  assert.equal(result.written.deletedVertices, 1);
  assert.equal(result.written.deletedEdges, 1);
});

test('the Model node cannot be deleted and unknown properties are ignored', async () => {
  await assert.rejects(
    () => applyEdits(fakeClient(), 'g', baseModel(), [{ op: 'delete-node', id: 'model/g', confirm: true }]),
    /edit its properties instead of deleting it/,
  );
  const result = await applyEdits(fakeClient(), 'g', baseModel(), [
    { op: 'update-node', id: 'metric/m', properties: { invented: 'x', description: 'ok' } },
  ], { dryRun: true });
  const metric = result.model.nodes.find((node) => node.id === 'metric/m');
  assert.equal(metric.properties.invented, undefined);
  assert.equal(metric.properties.description, 'ok');
});
