import test from 'node:test';
import assert from 'node:assert/strict';
import { PhoenixClient, writeModel } from '../src/graph/client.mjs';
import { NODE_PROPERTIES, EDGE_PROPERTIES, nodeRequests, edgeRequests } from '../src/graph/schema.mjs';

const schema = {
  name: 'g',
  vertexSchemas: [{
    name: 'Node',
    properties: NODE_PROPERTIES.map((property, index) => ({ id: index + 1, name: property.name, type: property.type })),
  }],
  edgeSchemas: [{
    name: 'Relation',
    properties: EDGE_PROPERTIES.map((property, index) => ({ id: index + 1, name: property.name, type: property.type })),
  }],
};

test('nodeRequests map names to schema indexes and include entityType', () => {
  const batch = nodeRequests([
    { id: 'metric/m', entityType: 'Metric', properties: { name: 'm', alias: ['营收'], confidence: 0.9, expression: 'SUM(a)' } },
  ], schema);
  const request = batch.vertices[0];
  assert.equal(request.id, 'metric/m');
  assert.equal(request.vertexSchemaName, 'Node');
  const byIndex = new Map(request.properties.map((property) => [property.index, property.value]));
  const indexOf = (name) => schema.vertexSchemas[0].properties.find((property) => property.name === name).id;
  assert.equal(byIndex.get(indexOf('entityType')), 'Metric');
  assert.equal(byIndex.get(indexOf('expression')), 'SUM(a)');
  assert.deepEqual(byIndex.get(indexOf('alias')), ['营收']);
  assert.equal(byIndex.get(indexOf('confidence')), 0.9);
  assert.deepEqual(batch.dropped, []);
});

test('edgeRequests use head|relation|tail ids and the declared properties', () => {
  const batch = edgeRequests([{
    head: 'metric/m', relation: 'computedFrom', tail: 'table/t',
    properties: { relation: 'computedFrom', xrelation: 'x', confidence: 0.8 },
  }], schema);
  assert.equal(batch.edges[0].edgeId, 'metric/m|computedFrom|table/t');
  assert.equal(batch.edges[0].source, 'metric/m');
  assert.equal(batch.edges[0].destination, 'table/t');
  assert.ok(batch.edges[0].properties.length >= 3);
});

test('writeModel creates a graph schema when absent and writes vertices before edges', async () => {
  const calls = { created: null, vertices: [], edges: [] };
  const client = {
    async schema() { return schema; },
    async upsertVertices(_graph, vertices) { calls.vertices.push(...vertices); },
    async upsertEdges(_graph, edges) { calls.edges.push(...edges); },
  };
  const model = {
    graph: 'g',
    nodes: [{ id: 'metric/m', entityType: 'Metric', properties: { name: 'm', expression: 'SUM(a)' } }],
    edges: [{ id: 'metric/m|definedBy|query/q', head: 'metric/m', relation: 'definedBy', tail: 'query/q', properties: { relation: 'definedBy' } }],
  };
  const result = await writeModel(client, model, { graph: 'g' });
  assert.equal(result.vertices, 1);
  assert.equal(result.edges, 1);
  assert.equal(calls.vertices[0].properties.some((property) => property.value === 'Metric'), true);
  const missing = await writeModel({ ...client, schema: async () => null, ensureGraph: async () => ({ created: true, schema }) }, model, { graph: 'g' });
  assert.equal(missing.created, true);
});

test('writeModel refuses a RocksDB graph before writing anything', async () => {
  let wrote = false;
  const client = {
    async schema() { return schema; },
    async graphs() { return [{ name: 'g', storage: 'RocksDB-Edge' }]; },
    async upsertVertices() { wrote = true; },
    async upsertEdges() { wrote = true; },
  };
  await assert.rejects(
    () => writeModel(client, { graph: 'g', nodes: [{ id: 'metric/m', entityType: 'Metric', properties: { name: 'm' } }], edges: [] }),
    /has no HTTP write path/,
  );
  assert.equal(wrote, false);
});

test('readModel prefers the Model catalog and reads nodes/edges by id', async () => {
  const requests = [];
  const client = new PhoenixClient({
    endpoint: 'http://graph.test',
    apiKey: 'secret',
    fetchImpl: async (url, init) => {
      const body = init?.body ? JSON.parse(init.body) : null;
      requests.push({ url, body, authorization: init?.headers?.authorization });
      const query = body?.query || '';
      let result = [];
      if (query.includes("g.V('model/g','Node')")) {
        result = [{ id: 'model/g', label: 'Node', entityType: 'Model', nodeIds: ['model/g', 'metric/m', 'table/t'], edgeIds: ['metric/m|computedFrom|table/t'] }];
      } else if (query.includes('outE')) {
        result = [{ id: 'metric/m|computedFrom|table/t', label: 'Relation', relation: 'computedFrom', xrelation: 'x' }];
      } else if (query.includes('g.V(')) {
        result = [
          { id: 'model/g', label: 'Node', entityType: 'Model', nodeIds: ['model/g', 'metric/m', 'table/t'] },
          { id: 'metric/m', label: 'Node', entityType: 'Metric', name: 'm', expression: 'SUM(a)' },
          { id: 'table/t', label: 'Node', entityType: 'Table', name: 't' },
        ];
      }
      return new Response(JSON.stringify({ result }), { status: 200, headers: { 'content-type': 'application/json' } });
    },
  });
  const read = await client.readModel('g');
  assert.equal(read.viaCatalog, true);
  assert.equal(read.nodes.length, 3);
  assert.equal(read.nodes.find((node) => node.id === 'metric/m').entityType, 'Metric');
  assert.equal(read.edges.length, 1);
  assert.equal(read.edges[0].head, 'metric/m');
  assert.ok(requests.every((request) => request.authorization === 'Bearer secret'));
});

test('a 404 from the schema endpoint is null, and another error carries status/body', async () => {
  const missing = new PhoenixClient({
    endpoint: 'http://graph.test',
    fetchImpl: async () => new Response('not found', { status: 404 }),
  });
  assert.equal(await missing.schema('nope'), null);
  const broken = new PhoenixClient({
    endpoint: 'http://graph.test',
    fetchImpl: async () => new Response(JSON.stringify({ error: 'boom' }), { status: 500 }),
  });
  await assert.rejects(() => broken.query('g', "g.V('Node')"), /500 POST \/api\/v1\/query: boom/);
});
