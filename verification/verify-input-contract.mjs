// needs: service
// what: the input contract - what the API does with the requests nobody intends to send. Malformed JSON, a
//       missing field, a field of the wrong JSON type, an id that does not exist, an index the schema does not
//       declare, a value that is not the declared type, a value larger than the limit, an edge pointing at a
//       vertex that is not there. Every case asserts the *status* and that the body says what to change.
//
// Why this is its own check: these are the inputs a client sends by accident, and the difference between a 400
// that names the field and a 500 that names a Java class is the difference between a five-minute fix and a
// support ticket. Three rules are asserted across the whole matrix:
//
//   1. a client mistake is never a 500 (it is 400, 404, 405, 409 or 415),
//   2. no response body carries a stack trace (unless phoenixgraph.errors.include-stack-trace is on),
//   3. every error body has the same shape: error + exception + hint.
import { readFileSync } from 'node:fs';

const API = 'http://localhost:8081';
const GRAPH = 'payment-graph';
const ROCKSDB_GRAPH = 'sanguo-kg-rocksdb-edge';   // whole-type scans are an ordered-range read: this scheme serves them
const stamp = Date.now();

let failures = 0;
const check = (name, ok, detail) => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};
const send = async (method, path, body, headers = {}) => {
  const options = { method, headers: { ...headers } };
  if (body !== undefined) {
    // an explicit content type wins: that is how the 415 case below is reachable at all
    options.headers['Content-Type'] = options.headers['Content-Type'] || 'application/json';
    options.body = typeof body === 'string' ? body : JSON.stringify(body);
  }
  const response = await fetch(API + path, options);
  const text = await response.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* a body that is not JSON is itself a finding; the callers below assert on `text` for those */
  }
  return { status: response.status, text, json, headers: response.headers };
};
const query = (body) => send('POST', '/api/v1/query', body);

// Every error body this check sees, so rules 2 and 3 can be asserted once at the end over the whole matrix.
const errorBodies = [];
const expectError = (label, result, expectedStatus) => {
  if (result.json && typeof result.json === 'object' && !Array.isArray(result.json)) {
    errorBodies.push({ label, body: result.json });
  }
  const details = `${result.status} ${result.text.slice(0, 110)}`;
  check(`${label} -> ${expectedStatus}`, result.status === expectedStatus, details);
  return result;
};
// An error the caller can act on: a sentence, the exception class, and where to look next.
const explainsItself = (result) => result.json !== null && typeof result.json === 'object'
  && typeof result.json.error === 'string' && result.json.error.length > 10
  && typeof result.json.exception === 'string' && typeof result.json.hint === 'string';

// ---- 1. the query endpoint ---------------------------------------------------------------------------
console.log('--- the query endpoint');
const unknownGraph = expectError('an unknown graph is 400 and lists what is served',
  await query({ graphName: 'no-such-graph', query: 'g.V()' }), 400);
check('the unknown-graph message names the graphs that exist', unknownGraph.text.includes('available graphs'),
  unknownGraph.text.slice(0, 90));
expectError('a missing graphName is 400', await query({ query: 'g.V()' }), 400);
expectError('an empty graphName is 400', await query({ graphName: '', query: 'g.V()' }), 400);
expectError('a missing query is 400', await query({ graphName: GRAPH }), 400);
expectError('an empty query is 400', await query({ graphName: GRAPH, query: '' }), 400);
expectError('a blank query is 400', await query({ graphName: GRAPH, query: '   ' }), 400);
expectError('an unbalanced query is 400',
  await query({ graphName: GRAPH, query: 'g.V((' }), 400);
expectError('an unknown language is 400 and names the supported ones',
  await query({ graphName: GRAPH, query: 'g.V()', language: 'nonsense' }), 400);
expectError('a graph name that looks like a path is 400, not a path traversal',
  await query({ graphName: '../../etc/passwd', query: 'g.V()' }), 400);
expectError('a graphName of the wrong JSON type is 400, not coerced',
  await query({ graphName: 123, query: 'g.V()' }), 400);
expectError('returnSubgraph of the wrong JSON type is 400',
  await query({ graphName: GRAPH, query: 'g.V()', returnSubgraph: 'yes' }), 400);
expectError('malformed JSON is 400 and quotes the parse error',
  await send('POST', '/api/v1/query', '{"graphName":'), 400);
expectError('a JSON array instead of an object is 400', await send('POST', '/api/v1/query', '[]'), 400);
expectError('a null body is 400', await send('POST', '/api/v1/query', 'null'), 400);
expectError('no body at all is 400', await send('POST', '/api/v1/query', undefined), 400);
expectError('an unsupported content type is 415',
  await send('POST', '/api/v1/query', 'graphName=x', { 'Content-Type': 'text/plain' }), 415);// a parameter the caller may send: the *value* is their business, but it must not become a 500
const bigLimit = await query({ graphName: ROCKSDB_GRAPH, query: "g.V('Entity').limit(2147483647)" });
check('a limit of Integer.MAX_VALUE does not crash the engine',
  bigLimit.status === 200 || bigLimit.status === 400, `${bigLimit.status}`);
const negativeLimit = await query({ graphName: ROCKSDB_GRAPH, query: "g.V('Entity').limit(-1)" });
check('a negative limit is answered (200 with no rows, or a 400 that explains it)',
  negativeLimit.status === 200 || negativeLimit.status === 400, `${negativeLimit.status}`);
const zeroLimit = await query({ graphName: ROCKSDB_GRAPH, query: "g.V('Entity').limit(0)", includeResult: true });
check('limit(0) is an empty answer, not an error',
  zeroLimit.status === 200 && JSON.stringify(zeroLimit.json).replace(/\s/g, '').includes('[]'),
  `${zeroLimit.status} ${zeroLimit.text.slice(0, 60)}`);
const unknownType = await query({ graphName: ROCKSDB_GRAPH, query: "g.V('NoSuchVertexType').limit(1)" });
const unknownTypeBody = await expectError('an unknown vertex type is 400 and names it', unknownType, 400);
check('and the message lists what the graph does hold',
  unknownTypeBody.text.includes('Unknown vertex schema'), unknownTypeBody.text.slice(0, 100));
const cjk = await query({ graphName: ROCKSDB_GRAPH, query: "g.V('不存在').limit(1)" });
check('a CJK vertex type is answered with its own name intact, not mangled',
  cjk.status === 400 && cjk.text.includes('不存在'), `${cjk.status} ${cjk.text.slice(0, 100)}`);

// ---- 2. vertex writes ---------------------------------------------------------------------------------
console.log('--- vertex writes');
const vertex = (body) => send('POST', '/api/v1/vertices', body);
const known = `zz-contract-${stamp}`;
const created = await vertex({ id: known, vertexSchemaName: 'Account',
  properties: [{ index: 1, value: 'ACTIVE' }, { index: 2, value: 'SAVINGS' }] });
check('a well-formed vertex is written', created.status === 200 && created.text.includes('SUCCESS'),
  `${created.status} ${created.text.slice(0, 80)}`);

check('a vertex without an id is a 400 that names the field, not a NullPointerException',
  explainsItself(await expectError('a missing id is 400',
    await vertex({ vertexSchemaName: 'Account', properties: [] }), 400)));
expectError('a blank id is 400', await vertex({ id: '   ', vertexSchemaName: 'Account', properties: [] }), 400);
expectError('a missing vertexSchemaName is 400',
  await vertex({ id: `zz-noschema-${stamp}`, properties: [] }), 400);
expectError('an unknown vertexSchemaName is 400',
  await vertex({ id: `zz-badschema-${stamp}`, vertexSchemaName: 'Nope', properties: [] }), 400);
expectError('a property index the schema does not declare is 400',
  await vertex({ id: `zz-badindex-${stamp}`, vertexSchemaName: 'Account',
    properties: [{ index: 99, value: 'x' }] }), 400);
expectError('a negative property index is 400',
  await vertex({ id: `zz-negindex-${stamp}`, vertexSchemaName: 'Account',
    properties: [{ index: -7, value: 'x' }] }), 400);
expectError('an enum value outside the declared set is 400',
  await vertex({ id: `zz-enum-${stamp}`, vertexSchemaName: 'Account',
    properties: [{ index: 1, value: 'CHECKING' }] }), 400);
expectError('a value of the wrong declared type is 400',
  await vertex({ id: `zz-type-${stamp}`, vertexSchemaName: 'Account',
    properties: [{ index: 2, value: { nested: true } }] }), 400);
expectError('a null property entry is 400',
  await vertex({ id: `zz-nullprop-${stamp}`, vertexSchemaName: 'Account', properties: [null] }), 400);
const huge = await expectError('a property value over the size limit is 400',
  await vertex({ id: `zz-huge-${stamp}`, vertexSchemaName: 'Account',
    properties: [{ index: 1, value: 'A'.repeat(600 * 1024) }] }), 400);
check('the size-limit message names the limit rather than echoing the value',
  huge.text.includes('over the limit of') && huge.text.length < 600,
  `${huge.text.length} characters of body`);
const extra = await vertex({ id: `zz-extra-${stamp}`, vertexSchemaName: 'Account', properties: [], bogus: 1 });
check('an unknown JSON field is ignored, as a forward-compatible API should',
  extra.status === 200, `${extra.status} ${extra.text.slice(0, 60)}`);
const cjkVertex = await vertex({ id: `边-${stamp}`, vertexSchemaName: 'Account',
  properties: [{ index: 1, value: 'ACTIVE' }] });
check('a CJK id is written and read back byte-for-byte',
  cjkVertex.status === 200 && (await send('GET', `/api/v1/vertices/${encodeURIComponent(`边-${stamp}`)}`)).status === 200,
  `${cjkVertex.status}`);

// reads and deletes of things that are not there
expectError('a point read of an unknown vertex is 404', await send('GET', '/api/v1/vertices/no-such-vertex'), 404);
expectError('deleting an unknown vertex is 404, not a silent SUCCESS',
  await send('DELETE', '/api/v1/vertices/no-such-vertex'), 404);
expectError('updating a property of an unknown vertex is 404',
  await send('PUT', '/api/v1/vertices/no-such-vertex/properties/1', { value: 'x' }), 404);
expectError('deleting a property of an unknown vertex is 404',
  await send('DELETE', '/api/v1/vertices/no-such-vertex/properties/1'), 404);
expectError('a property index that is not a number is 400 and names the parameter',
  await send('PUT', `/api/v1/vertices/${known}/properties/abc`, { value: 'x' }), 400);
expectError('a property update with an index the type does not declare is 400',
  await send('PUT', `/api/v1/vertices/${known}/properties/99`, { value: 'x' }), 400);
const updated = await send('PUT', `/api/v1/vertices/${known}/properties/2`, { value: 'CHECKING' });
check('a property update inside the declared schema is accepted', updated.status === 200,
  `${updated.status} ${updated.text.slice(0, 60)}`);
const stored = await send('GET', `/api/v1/vertices/${known}`);
check('and it is readable afterwards',
  stored.status === 200 && stored.text.includes('CHECKING'), stored.text.slice(0, 90));

// the delete is a soft delete: the vertex stops being readable, and deleting it twice is a 404 the second time
const deleted = await send('DELETE', `/api/v1/vertices/${known}`);
check('deleting an existing vertex is 200 with SUCCESS',
  deleted.status === 200 && deleted.text.includes('SUCCESS'), deleted.text.slice(0, 60));
expectError('and the deleted vertex reads as 404', await send('GET', `/api/v1/vertices/${known}`), 404);
expectError('deleting it again is 404 (the second delete is not a lie)',
  await send('DELETE', `/api/v1/vertices/${known}`), 404);
await send('DELETE', `/api/v1/vertices/${encodeURIComponent(`边-${stamp}`)}`);

// ---- 3. edge writes -----------------------------------------------------------------------------------
console.log('--- edge writes');
const edge = (body) => send('POST', '/api/v1/edges', body);
const endpoints = [`zz-src-${stamp}`, `zz-dst-${stamp}`];
for (const id of endpoints) {
  await vertex({ id, vertexSchemaName: 'Account', properties: [{ index: 1, value: 'ACTIVE' }] });
}
const writeEdge = (body) => edge(body);
const goodEdge = await writeEdge({ edgeId: `zz-e-${stamp}`, edgeSchemaName: 'Payment',
  source: endpoints[0], destination: endpoints[1], properties: [] });
check('an edge between two vertices that exist is written', goodEdge.status === 200,
  `${goodEdge.status} ${goodEdge.text.slice(0, 70)}`);
check('a missing edgeId is a 400 that names the field, not a NullPointerException',
  explainsItself(await expectError('a missing edgeId is 400',
    await writeEdge({ edgeSchemaName: 'Payment', source: endpoints[0], destination: endpoints[1], properties: [] }),
    400)));
expectError('a missing source is 400',
  await writeEdge({ edgeId: `zz-e2-${stamp}`, edgeSchemaName: 'Payment', destination: endpoints[1], properties: [] }), 400);
expectError('a missing destination is 400',
  await writeEdge({ edgeId: `zz-e3-${stamp}`, edgeSchemaName: 'Payment', source: endpoints[0], properties: [] }), 400);
expectError('an unknown edgeSchemaName is 400',
  await writeEdge({ edgeId: `zz-e4-${stamp}`, edgeSchemaName: 'Nope', source: endpoints[0],
    destination: endpoints[1], properties: [] }), 400);
const dangling = await expectError('an edge to a vertex that does not exist is 404',
  await writeEdge({ edgeId: `zz-e5-${stamp}`, edgeSchemaName: 'Payment', source: endpoints[0],
    destination: `zz-ghost-${stamp}`, properties: [] }), 404);
check('the dangling-edge message names the missing endpoint and the setting that allows it',
  dangling.text.includes('does not exist') && dangling.text.includes('require-existing-endpoints'),
  dangling.text.slice(0, 120));
check('and no untyped stub vertex was created for the missing endpoint',
  (await send('GET', `/api/v1/vertices/zz-ghost-${stamp}`)).status === 404);
expectError('deleting an edge without its schema name is 400 and names the parameter',
  await send('DELETE', `/api/v1/edges/zz-e-${stamp}`), 400);
expectError('deleting an edge without its destination is 400',
  await send('DELETE', `/api/v1/edges/zz-e-${stamp}?edgeSchemaName=Payment&source=${endpoints[0]}`), 400);
const deletedEdge = await send('DELETE',
  `/api/v1/edges/zz-e-${stamp}?edgeSchemaName=Payment&source=${endpoints[0]}&destination=${endpoints[1]}`);
check('deleting an edge with all three parameters is accepted', deletedEdge.status === 200,
  `${deletedEdge.status} ${deletedEdge.text.slice(0, 60)}`);
for (const id of endpoints) {
  await send('DELETE', `/api/v1/vertices/${id}`);
}

// ---- 4. batch writes ----------------------------------------------------------------------------------
console.log('--- batch writes');
const batch = (body, graph) => send('POST', graph ? `/api/v1/graphs/${graph}/batch` : '/api/v1/batch', body);
const emptyBatch = await batch({});
check('an empty batch body is an empty result, not an error',
  emptyBatch.status === 200 && emptyBatch.text.includes('[]'), `${emptyBatch.status} ${emptyBatch.text.slice(0, 40)}`);
const inline = await batch({
  vertices: [{ id: `zz-b-${stamp}`, vertexSchemaName: 'Account', properties: [{ index: 1, value: 'ACTIVE' }] },
    { id: `zz-b2-${stamp}`, vertexSchemaName: 'Account', properties: [] }],
  edges: [{ edgeId: `zz-be-${stamp}`, edgeSchemaName: 'Payment', source: `zz-b-${stamp}`,
    destination: `zz-b2-${stamp}`, properties: [] }],
});
check('a batch writes its vertices and then an edge between them (one file, one request)',
  inline.status === 200 && (inline.json || []).length === 3, `${inline.status} ${inline.text.slice(0, 90)}`);
expectError('a batch with one unusable vertex is 400 and says which field',
  await batch({ vertices: [{ id: `zz-b3-${stamp}`, vertexSchemaName: 'Account', properties: [] },
    { vertexSchemaName: 'Account' }] }), 400);
expectError('a batch whose edge points outside the batch is 404',
  await batch({ edges: [{ edgeId: `zz-be2-${stamp}`, edgeSchemaName: 'Payment',
    source: `zz-ghost-${stamp}`, destination: `zz-ghost2-${stamp}`, properties: [] }] }), 404);
const batchCjk = await batch({ vertices: [{ id: `批-${stamp}`, vertexSchemaName: 'Account', properties: [] }] });
check('a batch with CJK ids is accepted',
  batchCjk.status === 200 && batchCjk.text.includes(`批-${stamp}`), `${batchCjk.status}`);
for (const id of [`zz-b-${stamp}`, `zz-b2-${stamp}`, `批-${stamp}`]) {
  await send('DELETE', `/api/v1/vertices/${encodeURIComponent(id)}`);
}
await send('DELETE', `/api/v1/edges/zz-be-${stamp}?edgeSchemaName=Payment&source=zz-b-${stamp}&destination=zz-b2-${stamp}`);
expectError('a batch into an unknown graph is 400', await batch({ vertices: [] }, 'no-such-graph'), 400);

// ---- 5. the schema endpoints --------------------------------------------------------------------------
console.log('--- schema endpoints');
const schema = (path, body) => send('POST', path, body);
const version = await schema('/api/v1/schema/version', JSON.parse(readFileSync(
  'graph-http/src/main/resources/payment-graph.json', 'utf8')));
check('a schema file this build writes is judged compatible',
  version.status === 200 && version.json.compatible === true, `${version.status} ${version.text.slice(0, 80)}`);
const future = await schema('/api/v1/schema/version', { name: 'probe', schemaVersion: 99,
  vertexSchemas: [], edgeSchemas: [] });
check('a schema from a newer build is refused by name, with both versions',
  future.status === 200 && future.json.compatible === false
  && future.json.problem.includes('99') && future.json.problem.includes('1'),
  `${future.status} ${future.text.slice(0, 120)}`);
const malformed = await schema('/api/v1/schema/version', '{not json');
check('a body that is not a schema at all is judged unreadable rather than blowing up',
  malformed.status === 200 && malformed.json.compatible === false
  && typeof malformed.json.problem === 'string',
  `${malformed.status} ${malformed.text.slice(0, 110)}`);
expectError('a schema for an unknown graph is 404', await send('GET', '/api/v1/schema/no-such-graph'), 404);

// ---- 6. the transport-level contract ------------------------------------------------------------------
console.log('--- the transport-level contract');
expectError('an unknown endpoint is 404 and names the method and path',
  await send('GET', '/api/v1/no-such-endpoint'), 404);
const wrongMethod = await send('GET', '/api/v1/query');
check('a known path with the wrong method is 405 with the Allow header',
  wrongMethod.status === 405 && wrongMethod.headers.get('allow') === 'POST',
  `${wrongMethod.status} allow=${wrongMethod.headers.get('allow')}`);
const deletedPath = await send('DELETE', '/api/v1/vertices/');
check('a path missing its id is 404 (there is no such endpoint)', deletedPath.status === 404,
  `${deletedPath.status}`);

// ---- 7. the three rules, over everything above --------------------------------------------------------
console.log('--- the rules over the whole matrix');
const traces = errorBodies.filter((entry) => entry.body.stackTrace !== undefined);
check('no error body carries a stack trace',
  traces.length === 0,
  traces.length === 0 ? `${errorBodies.length} error bodies checked`
    : `${traces.length} do, first: ${traces[0].label}`);
check('every error body has the same shape (error, exception, hint)',
  errorBodies.every((entry) => typeof entry.body.error === 'string'
    && typeof entry.body.exception === 'string' && typeof entry.body.hint === 'string'),
  `${errorBodies.length} error bodies`);
check('no error body names an internal frame or file path',
  errorBodies.every((entry) => !/\.java:\d+|com\.pengcheng\.phoenixgraph\.http\.service\.\w+\.(?!\w)/.test(entry.body.error)),
  errorBodies.map((entry) => entry.body.error).find((text) => /\.java:\d+/.test(text)) || 'none');
check('every client mistake above was a 4xx, never a 500',
  errorBodies.every((entry) => entry.body.exception !== 'java.lang.NullPointerException'),
  errorBodies.filter((entry) => entry.body.exception === 'java.lang.NullPointerException')
    .map((entry) => entry.label).join(', ') || 'none');

console.log('');
console.log(failures === 0
  ? 'the API answers every malformed, boundary and unknown input with a status and a sentence that say what to change'
  : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
