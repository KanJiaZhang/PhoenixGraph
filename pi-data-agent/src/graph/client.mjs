/**
 * The PhoenixGraph HTTP client the data agent uses.
 *
 * It knows four surfaces: the graph schema/write API (to persist the metadata layer), the query API (to
 * execute a traversal on a fact graph), the semantic API (the model/SQL/reference queries), and the agent
 * API (ingested context and search). Everything that is a policy decision - what a metric means, what may
 * execute - stays above this file.
 */
import { propertyIndexes } from './schema.mjs';
import { gremlinString, slug } from '../util.mjs';

export class PhoenixError extends Error {
  constructor(message, { status = null, body = null, path = null } = {}) {
    super(message);
    this.name = 'PhoenixError';
    this.status = status;
    this.body = body;
    this.path = path;
  }
}

export class PhoenixClient {
  constructor(options = {}) {
    this.endpoint = String(options.endpoint || process.env.PHOENIXGRAPH_API || 'http://localhost:8081').replace(/\/+$/, '');
    this.apiKey = options.apiKey || process.env.PHOENIXGRAPH_API_KEY || '';
    this.fetchImpl = options.fetchImpl || globalThis.fetch;
    this.timeout = options.timeout ?? 30_000;
    if (typeof this.fetchImpl !== 'function') throw new Error('No fetch implementation (Node 18+ is required)');
  }

  headers(extra = {}) {
    return {
      'content-type': 'application/json; charset=utf-8',
      ...(this.apiKey ? { authorization: `Bearer ${this.apiKey}` } : {}),
      ...extra,
    };
  }

  async request(method, path, body, options = {}) {
    const response = await this.fetchImpl(`${this.endpoint}${path}`, {
      method,
      headers: this.headers(options.headers),
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      signal: AbortSignal.timeout(options.timeout ?? this.timeout),
    });
    const text = await response.text();
    let payload = text;
    try { payload = text ? JSON.parse(text) : null; } catch { /* keep the raw text */ }
    if (response.status === 404 && options.allow404) return null;
    if (!response.ok) {
      const detail = payload && typeof payload === 'object'
        ? (payload.error || payload.message || payload.problem || JSON.stringify(payload).slice(0, 500))
        : String(payload || '').slice(0, 500);
      throw new PhoenixError(`${response.status} ${method} ${path}: ${detail}`, { status: response.status, body: payload, path });
    }
    if (payload && typeof payload === 'object' && typeof payload.error === 'string' && payload.error) {
      throw new PhoenixError(payload.error, { status: response.status, body: payload, path });
    }
    return payload;
  }

  get(path, options) { return this.request('GET', path, undefined, options); }
  post(path, body, options) { return this.request('POST', path, body, options); }
  delete(path, options) { return this.request('DELETE', path, undefined, options); }

  // ---- graphs and schemas -------------------------------------------------------------------------

  graphs() { return this.get('/api/v1/graphs'); }

  /** The stored schema JSON, or null when the graph is not served. */
  schema(graph) { return this.get(`/api/v1/schema/${encodeURIComponent(graph)}`, { allow404: true }); }

  /** The same graph as the builder's draft (the saved-schema endpoint may be a file on the server only). */
  draft(graph) { return this.get(`/api/v1/schema/${encodeURIComponent(graph)}/draft`, { allow404: true }); }

  createSchema(draft) { return this.post('/api/v1/schema', draft); }

  /** Validates, builds stores and registers a new graph; a previously served name needs `replace`. */
  ensureGraph(graph, schemaDraft, options = {}) {
    return this.schema(graph).then(async (existing) => {
      if (existing) return { created: false, schema: existing };
      const saved = await this.createSchema(schemaDraft);
      if (!saved || saved.saved === false) {
        throw new PhoenixError(`Cannot create graph '${graph}': ${(saved?.errors || [saved?.message]).filter(Boolean).join('; ')}`, { body: saved, path: '/api/v1/schema' });
      }
      const stored = await this.schema(graph);
      return { created: true, schema: stored || existing, saved };
    });
  }

  // ---- writes -------------------------------------------------------------------------------------

  /** One batch into a named graph. Vertices first: endpoints resolve against the same batch. */
  async batch(graph, { vertices = [], edges = [] }) {
    if (!vertices.length && !edges.length) return [];
    return this.post(`/api/v1/graphs/${encodeURIComponent(graph)}/batch`, { vertices, edges });
  }

  async upsertVertices(graph, vertices, chunk = 200) {
    const results = [];
    for (let index = 0; index < vertices.length; index += chunk) {
      results.push(...await this.batch(graph, { vertices: vertices.slice(index, index + chunk), edges: [] }));
    }
    return results;
  }

  async upsertEdges(graph, edges, chunk = 200) {
    const results = [];
    for (let index = 0; index < edges.length; index += chunk) {
      results.push(...await this.batch(graph, { vertices: [], edges: edges.slice(index, index + chunk) }));
    }
    return results;
  }

  async deleteVertex(graph, id) {
    // the body form, because an id such as metric/revenue contains a slash the path form cannot carry
    return this.post(`/api/v1/graphs/${encodeURIComponent(graph)}/vertices/delete`, { id });
  }

  async deleteEdge(graph, edge) {
    return this.post(`/api/v1/graphs/${encodeURIComponent(graph)}/edges/delete`, {
      edgeId: edge.edgeId, edgeSchemaName: edge.edgeSchemaName || 'Relation', source: edge.source, destination: edge.destination,
    });
  }

  // ---- queries ------------------------------------------------------------------------------------

  /** A Gremlin traversal against a named graph; returns the rows (not the HTTP envelope). */
  async query(graph, gremlin, options = {}) {
    const answer = await this.post('/api/v1/query', {
      graphName: graph, query: gremlin, includeResult: true, returnSubgraph: false,
    }, options);
    if (Array.isArray(answer)) return answer;
    if (answer && Array.isArray(answer.result)) return answer.result;
    if (answer && answer.error) throw new PhoenixError(String(answer.error), { body: answer });
    return answer == null ? [] : [answer];
  }

  /** The catalog node of a model graph. */
  async catalog(graph) {
    const id = `model/${slug(graph)}`;
    const rows = await this.query(graph, `g.V(${gremlinString(id)},'Node').elementMap()`);
    return rows[0] || null;
  }

  /** Reads named Node vertices in batches; the only read path a keyed store offers. */
  async nodesByIds(graph, ids) {
    const rows = [];
    for (let index = 0; index < ids.length; index += 50) {
      const chunk = ids.slice(index, index + 50);
      const query = `g.V(${chunk.map(gremlinString).join(',')},'Node').elementMap()`;
      rows.push(...await this.query(graph, query));
    }
    return rows;
  }

  /** Reads the outgoing Relation edges of named Node vertices in batches. */
  async edgesFromHeads(graph, ids) {
    const rows = [];
    for (let index = 0; index < ids.length; index += 20) {
      const chunk = ids.slice(index, index + 20);
      const query = `g.V(${chunk.map(gremlinString).join(',')},'Node').outE('Relation').elementMap()`;
      rows.push(...await this.query(graph, query));
    }
    return rows;
  }

  /**
   * The `Node` vertices and `Relation` edges of a data-agent graph, in the builder's shape.
   *
   * Two read paths, because the storage schemes differ: a RocksDB-edge graph scans a type, while an
   * Aerospike graph cannot - it answers by id. The Model node carries a catalog (nodeIds/edgeIds), so the
   * keyed path reads the catalog and then the named ids. An Aerospike graph without a catalog is refused
   * with the reason instead of silently looking empty.
   */
  async readModel(graph) {
    let nodeRows;
    let edgeRows;
    let viaCatalog = false;
    // Catalog first: a keyed store may answer a whole-type scan with ids but no properties (Aerospike), and
    // treating that as a model would silently produce 34 blank nodes. The catalog is written by this package.
    let catalog = null;
    try { catalog = await this.catalog(graph); } catch { /* a graph without a catalog is read the old way */ }
    if (catalog && Array.isArray(catalog.nodeIds) && catalog.nodeIds.length) {
      const ids = catalog.nodeIds.map(String);
      nodeRows = await this.nodesByIds(graph, ids);
      edgeRows = await this.edgesFromHeads(graph, ids);
      viaCatalog = true;
    } else {
      nodeRows = await this.query(graph, "g.V('Node').elementMap()");
      edgeRows = await this.query(graph, "g.V('Node').outE('Relation').elementMap()");
      // A keyed store can answer a whole-type scan with only ids and labels; that is not a model. Refuse it
      // instead of returning blank nodes that look like an empty graph.
      if (nodeRows.length && nodeRows.every((row) => !row.entityType && Object.keys(row).length <= 2)) {
        throw new PhoenixError(`Graph '${graph}' answered a type scan without properties and has no Model catalog; ` +
          'build it with pi-data-agent first (the catalog is on the Model node).', { body: nodeRows.slice(0, 3) });
      }
    }
    const nodes = nodeRows.map((row) => {
      const properties = { ...(row || {}) };
      delete properties.id;
      delete properties.label;
      return { id: String(row.id), entityType: String(properties.entityType || row.entityType || 'Node'), properties };
    });
    const edges = [];
    const seenEdges = new Set();
    for (const row of edgeRows) {
      const id = String(row.id || '');
      const parts = id.split('|');
      const relation = String(row.relation || (parts.length === 3 ? parts[1] : ''));
      const head = parts.length === 3 ? parts[0] : (row.source ? String(row.source) : null);
      const tail = parts.length === 3 ? parts[2] : (row.target ? String(row.target) : null);
      if (!head || !tail || !relation || seenEdges.has(id)) continue;
      seenEdges.add(id);
      const properties = { ...(row || {}) };
      delete properties.id;
      delete properties.label;
      delete properties.source;
      delete properties.target;
      edges.push({ id, head, relation, tail, properties });
    }
    return { nodes, edges, viaCatalog };
  }

  /** One sample fact vertex, used to explain what a graph actually contains. */
  async sample(graph, vertexType, limit = 1) {
    return this.query(graph, `g.V('${String(vertexType).replace(/'/g, "\\'")}').limit(${Math.max(1, Math.min(20, limit))}).elementMap()`);
  }

  /** The properties of a fact vertex type, from the graph's stored schema. */
  async vertexProperties(graph, vertexType) {
    const schema = await this.schema(graph);
    if (!schema) return null;
    const vertex = (schema.vertexSchemas || []).find((candidate) => candidate.name === vertexType);
    if (!vertex) return null;
    return { schema, properties: (vertex.properties || []).map((property) => property.name) };
  }

  // ---- semantic API -------------------------------------------------------------------------------

  async semanticModel(graph) { return this.get(`/api/v1/semantic/${encodeURIComponent(graph)}/model`); }

  async translate(graph, request) { return this.post(`/api/v1/semantic/${encodeURIComponent(graph)}/translate`, request); }

  async ask(graph, request) { return this.post(`/api/v1/semantic/${encodeURIComponent(graph)}/ask`, request); }

  async analyze(graph, request) { return this.post(`/api/v1/analysis/${encodeURIComponent(graph)}/analyze`, request); }

  async attribute(graph, request) { return this.post(`/api/v1/analysis/${encodeURIComponent(graph)}/attribute`, request); }

  // ---- agent API ----------------------------------------------------------------------------------

  async search(graph, question, k = 5) { return this.post('/api/v1/agent/search', { graph, question, k }); }

  async context(graph, question, k = 5, hops = 1) { return this.post('/api/v1/agent/context', { graph, question, k, hops }); }
}

/**
 * Writes a model into a graph, creating the schema when the graph is new, and maps every property onto
 * the schema's indices. Returns what was written and what the graph could not store.
 */
export async function writeModel(client, model, options = {}) {
  const graph = options.graph || model.graph;
  let schema = await client.schema(graph);
  let created = false;
  if (!schema) {
    const draft = options.schemaDraft || (await import('./schema.mjs')).schemaDraft(graph, { storage: options.storage });
    const ensured = await client.ensureGraph(graph, draft, options);
    schema = ensured.schema;
    created = Boolean(ensured.created);
  }
  const { nodeRequests, edgeRequests, missingProperties } = await import('./schema.mjs');
  if (options.allowUnwritable !== true) {
    try {
      const served = await client.graphs();
      const info = (served || []).find((candidate) => candidate.name === graph);
      if (info && /RocksDB/i.test(String(info.storage || ''))) {
        throw new PhoenixError(`Graph '${graph}' uses ${info.storage}; this service has no HTTP write path for it. ` +
          'Build the model as an Aerospike graph (the default), or write a RocksDB model with a loader and the same Node/Relation schema.',
        { body: info });
      }
    } catch (error) {
      if (error instanceof PhoenixError) throw error;
      // a service that does not list graphs is not a reason to refuse the write; the batch will answer
    }
  }
  const vertexBatch = nodeRequests(model.nodes || [], schema);
  const edgeBatch = edgeRequests(model.edges || [], schema);
  const warnings = [...vertexBatch.warnings, ...edgeBatch.warnings];
  const dropped = [...new Set([...vertexBatch.dropped, ...edgeBatch.dropped])];
  const missing = missingProperties(schema);
  if (missing.vertices.length) warnings.push(`schema '${graph}' does not declare vertex properties: ${missing.vertices.join(', ')} (dropped)`);
  if (missing.edges.length) warnings.push(`schema '${graph}' does not declare edge properties: ${missing.edges.join(', ')} (dropped)`);

  if (options.dryRun !== true) {
    await client.upsertVertices(graph, vertexBatch.vertices);
    await client.upsertEdges(graph, edgeBatch.vertices || edgeBatch.edges);
  }

  // the schema JSON endpoint can be absent for a classpath schema; report the property indexes actually used
  const indexes = schema ? propertyIndexes(schema) : { vertexNames: [], edgeNames: [] };
  return {
    graph, created, schema: { vertexTypes: indexes.vertexNames, edgeTypes: indexes.edgeNames },
    vertices: vertexBatch.vertices.length,
    edges: (edgeBatch.edges || []).length,
    dropped, warnings,
  };
}
