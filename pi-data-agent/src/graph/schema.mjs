/**
 * The schema the package creates for a data-agent graph.
 *
 * It is the semantic-layer shape PhoenixGraph already reads (`Node` + `Relation`), plus the provenance
 * columns this package promises (source, evidence, confidence, status, review). When the graph already
 * exists, the writer maps onto the properties that exist and reports the rest instead of replacing a
 * schema somebody else authored.
 */

export const VERTEX_TYPE = 'Node';
export const EDGE_TYPE = 'Relation';

const property = (name, type, description, extra = {}) => ({ name, type, description, ...extra });

export const NODE_PROPERTIES = [
  property('name', 'string', 'Node name (table / column / metric / dimension / term / query)', { display: true }),
  property('entityType', 'string', 'Table / Column / Metric / Dimension / Term / Query / Source / Model / Domain'),
  property('description', 'string', 'Business meaning, as written in the source or by a reviewer'),
  property('alias', 'list<string>', 'Synonyms and alternative names'),
  property('dataType', 'string', 'SQL type of a column (DECIMAL(15,2), VARCHAR(64), DATE, ...)'),
  property('columnRole', 'string', 'PK / FK / measure / attribute'),
  property('tableName', 'string', 'The table a column belongs to'),
  property('aggFunction', 'string', 'SUM / COUNT / COUNT_DISTINCT / AVG / RATIO'),
  property('expression', 'string', 'The aggregation expression that defines a metric'),
  property('metricKind', 'string', 'base / derived / ratio'),
  property('unit', 'string', '元 / 个 / % / 天'),
  property('sqlText', 'string', 'The SQL of a reference query'),
  property('graphProperty', 'string', 'The fact-graph property a metric/dimension binds to, or grain=property'),
  property('dataGraph', 'string', 'The graph that holds the fact rows (on the Model node)'),
  property('vertexType', 'string', 'The fact vertex type (on the Model node)'),
  property('owner', 'string', 'Data owner, when the source says so'),
  property('domain', 'string', 'Business domain a table/metric belongs to'),
  property('sourceId', 'string', 'The source document id the fact was read from'),
  property('evidence', 'string', 'JSON: the exact sentence/row and its locator'),
  property('confidence', 'double', '0..1 confidence of the extraction'),
  property('status', 'string', 'accepted / needs_review'),
  property('proposedBy', 'string', 'doc-table / doc-prose / sql-schema / sql-query / reviewer'),
  property('needsReview', 'boolean', 'Set when a definition is incomplete (for example a metric with no expression)'),
  property('builtAt', 'string', 'When the model was built (ISO instant)'),
  property('modelVersion', 'long', 'The model revision'),
  property('nodeIds', 'list<string>', 'Catalog: every node id in this model (Aerospike stores cannot scan a type)'),
  property('edgeIds', 'list<string>', 'Catalog: every relation id in this model'),
];

export const EDGE_PROPERTIES = [
  property('relation', 'string', 'hasColumn / references / computedFrom / usesColumn / groupedBy / dimensionColumn / definedBy / readsTable / contributesTo / belongsTo / describes / sameAs'),
  property('xrelation', 'string', 'The reverse relation name'),
  property('relationLabel', 'string', '中文关系名'),
  property('relationFamily', 'string', '结构 / 血缘 / 关联 / 指标 / 归因 / 来源'),
  property('confidence', 'double', '0..1 confidence of the edge'),
  property('evidence', 'string', 'JSON: the exact sentence/row behind the edge'),
  property('status', 'string', 'accepted / needs_review'),
  property('sourceId', 'string', 'The source document id the edge was read from'),
];

export function schemaDraft(graph, options = {}) {
  const properties = (options.includeExtras === false
    ? NODE_PROPERTIES.filter((item) => !['owner', 'domain', 'sourceId', 'evidence', 'confidence', 'status', 'proposedBy', 'needsReview', 'builtAt', 'modelVersion'].includes(item.name))
    : NODE_PROPERTIES);
  return {
    name: graph,
    description: options.description || 'pi-data-agent: metadata + semantic layer (tables, columns, metrics, dimensions, queries) with evidence',
    idType: 'string',
    storage: {
      type: options.storage || 'Aerospike',
      path: graph,
      regionCount: 64,
      maxVersions: 1,
      cacheEdges: true,
    },
    vertices: [{
      name: VERTEX_TYPE,
      description: 'A data-agent node: Table / Column / Metric / Dimension / Term / Query / Source / Model / Domain',
      display: 'name',
      properties,
    }],
    edges: [{
      name: EDGE_TYPE,
      description: 'A data-agent relation with its evidence',
      direction: 'DIRECTED',
      source: VERTEX_TYPE,
      target: VERTEX_TYPE,
      properties: EDGE_PROPERTIES,
    }],
  };
}

/** name -> property index for the types in a stored schema JSON. */
export function propertyIndexes(schemaJson) {
  const result = { vertices: new Map(), edges: new Map(), vertexNames: [], edgeNames: [] };
  for (const vertex of schemaJson?.vertexSchemas || []) {
    result.vertexNames.push(vertex.name);
    result.vertices.set(vertex.name, new Map((vertex.properties || []).map((propertySchema) => [propertySchema.name, propertySchema.id])));
  }
  for (const edge of schemaJson?.edgeSchemas || []) {
    result.edgeNames.push(edge.name);
    result.edges.set(edge.name, new Map((edge.properties || []).map((propertySchema) => [propertySchema.name, propertySchema.id])));
  }
  return result;
}

/** The property names a stored schema does not declare, for a report; never throws. */
export function missingProperties(schemaJson, vertexType = VERTEX_TYPE, edgeType = EDGE_TYPE) {
  const indexes = propertyIndexes(schemaJson);
  const vertex = indexes.vertices.get(vertexType);
  const edge = indexes.edges.get(edgeType);
  return {
    vertices: vertex ? NODE_PROPERTIES.filter((item) => !vertex.has(item.name)).map((item) => item.name) : NODE_PROPERTIES.map((item) => item.name),
    edges: edge ? EDGE_PROPERTIES.filter((item) => !edge.has(item.name)).map((item) => item.name) : EDGE_PROPERTIES.map((item) => item.name),
    hasVertexType: Boolean(vertex),
    hasEdgeType: Boolean(edge),
  };
}

function coerce(value, type, warnings, name) {
  if (value == null) return value;
  if (type === 'list<string>') {
    if (Array.isArray(value)) return value.map(String);
    if (typeof value === 'string' && value.includes(',')) return value.split(',').map((part) => part.trim()).filter(Boolean);
    return [String(value)];
  }
  if (type === 'boolean') return Boolean(value);
  if (type === 'double' || type === 'float') {
    const number = Number(value);
    return Number.isFinite(number) ? number : (warnings.push(`${name}: '${value}' is not a number; property dropped`), undefined);
  }
  if (type === 'long' || type === 'int' || type === 'integer') {
    const number = Number(value);
    return Number.isFinite(number) ? Math.trunc(number) : (warnings.push(`${name}: '${value}' is not an integer; property dropped`), undefined);
  }
  return typeof value === 'string' ? value : String(value);
}

/** VertexRequest bodies for the batch API, with only the properties the graph declares. */
export function nodeRequests(nodes, schemaJson, options = {}) {
  const indexes = propertyIndexes(schemaJson);
  const map = indexes.vertices.get(VERTEX_TYPE) || new Map();
  const types = new Map(NODE_PROPERTIES.map((item) => [item.name, item.type]));
  const warnings = [];
  const dropped = new Set();
  const vertices = [];
  for (const node of nodes) {
    const properties = [];
    const allProperties = { ...(node.properties || {}), entityType: node.entityType || node.properties?.entityType };
    for (const [name, value] of Object.entries(allProperties)) {
      if (value == null || value === '') continue;
      const index = map.get(name);
      if (index == null) { dropped.add(name); continue; }
      const coerced = coerce(value, types.get(name) || 'string', warnings, `${node.id}.${name}`);
      if (coerced !== undefined) properties.push({ index, value: coerced });
    }
    vertices.push({ id: node.id, vertexSchemaName: VERTEX_TYPE, properties });
  }
  return { vertices, warnings, dropped: [...dropped] };
}

/** EdgeRequest bodies for the batch API. Edge ids are `head|relation|tail`. */
export function edgeRequests(edges, schemaJson) {
  const indexes = propertyIndexes(schemaJson);
  const map = indexes.edges.get(EDGE_TYPE) || new Map();
  const types = new Map(EDGE_PROPERTIES.map((item) => [item.name, item.type]));
  const warnings = [];
  const dropped = new Set();
  const requests = [];
  for (const edge of edges) {
    const properties = [];
    for (const [name, value] of Object.entries(edge.properties || {})) {
      if (value == null || value === '') continue;
      const index = map.get(name);
      if (index == null) { dropped.add(name); continue; }
      const coerced = coerce(value, types.get(name) || 'string', warnings, `${edge.id}.${name}`);
      if (coerced !== undefined) properties.push({ index, value: coerced });
    }
    requests.push({
      edgeId: edge.id || `${edge.head}|${edge.relation}|${edge.tail}`,
      edgeSchemaName: EDGE_TYPE,
      source: edge.head,
      destination: edge.tail,
      properties,
    });
  }
  return { edges: requests, warnings, dropped: [...dropped] };
}
