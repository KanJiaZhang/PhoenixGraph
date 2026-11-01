/**
 * Observations -> the semantic/metadata graph.
 *
 * The output is data in the shape PhoenixGraph's semantic layer already reads: `Node` vertices with
 * `entityType` and `Relation` edges with `relation` (`hasColumn`, `references`, `computedFrom`, `usesColumn`,
 * `groupedBy`, `dimensionColumn`, `definedBy`, `readsTable`, `contributesTo`, `belongsTo`, `describes`).
 * The one deployment fact the model cannot know - which fact graph and vertex type carry the rows - is
 * written on a `Model` node, so a graph built here is self-describing.
 *
 * Two rules decide whether a metric or dimension can execute:
 *   1. a metric needs an `expression` (otherwise it is a term needing review, never a made-up formula);
 *   2. it needs a `graphProperty` that the fact vertex type actually declares. A missing binding is a
 *      warning and a refusal at execution time, never a substring guess.
 */
import { quote, slug, shortId, tableOfColumn, unique, isDateType } from '../util.mjs';
import { parseSqlQueries } from '../readers/sql.mjs';

const RELATION_LABELS = {
  hasColumn: ['columnOf', '包含字段', '结构'],
  references: ['referencedBy', '外键引用', '结构'],
  computedFrom: ['computedInto', '取自表', '血缘'],
  usesColumn: ['usedBy', '使用字段', '血缘'],
  groupedBy: ['groupedByReverse', '可切分维度', '指标'],
  dimensionColumn: ['dimensionOf', '维度字段', '维度'],
  definedBy: ['definesMetric', '定义查询', '指标'],
  readsTable: ['readBy', '读取表', '血缘'],
  contributesTo: ['contributesFrom', '贡献于', '归因'],
  belongsTo: ['containsMetric', '属于', '主题'],
  describes: ['describedBy', '描述', '来源'],
  sameAs: ['sameAs', '同义', '语义'],
};

export function relationProps(relation) {
  const [xrelation, relationLabel, relationFamily] = RELATION_LABELS[relation] || ['relatedTo', relation, '其他'];
  return { relation, xrelation, relationLabel, relationFamily };
}

function evidenceJson(items) {
  if (!items || items.length === 0) return null;
  return JSON.stringify(items.slice(0, 8).map((item) => ({
    sourceId: item.sourceId || null,
    quote: item.evidence || item.quote || null,
    locator: item.locator || null,
    reason: item.reason || null,
  })));
}

function mergeById(observations) {
  const merged = new Map();
  for (const observation of observations) {
    if (!observation || !observation.id) continue;
    const existing = merged.get(observation.id);
    if (!existing) {
      merged.set(observation.id, {
        ...observation,
        evidenceItems: [observation],
        synonyms: unique(observation.synonyms || []),
      });
      continue;
    }
    const next = { ...existing };
    // explicit structured facts win over prose; a later explicit definition wins over an empty one
    for (const [key, value] of Object.entries(observation)) {
      if (value == null || value === '' || (Array.isArray(value) && value.length === 0)) continue;
      if (next[key] == null || next[key] === '' || (Array.isArray(next[key]) && next[key].length === 0)) next[key] = value;
      else if (key === 'expression' && !next.expression && value) next.expression = value;
      else if (key === 'description' && !next.description) next.description = value;
      else if (key === 'graphProperty' && !next.graphProperty) next.graphProperty = value;
      else if (key === 'dataType' && !next.dataType) next.dataType = value;
    }
    next.evidenceItems = [...(existing.evidenceItems || []), observation].slice(0, 8);
    next.synonyms = unique([...(existing.synonyms || []), ...(observation.synonyms || [])]);
    next.confidence = Math.max(existing.confidence ?? 0, observation.confidence ?? 0);
    next.tables = unique([...(existing.tables || []), ...(observation.tables || [])]);
    next.columns = unique([...(existing.columns || []), ...(observation.columns || [])]);
    if (observation.evidence && observation.confidence >= (existing.confidence ?? 0)) {
      next.evidence = observation.evidence;
      next.sourceId = observation.sourceId;
      next.locator = observation.locator;
      next.reason = observation.reason;
    }
    merged.set(observation.id, next);
  }
  return [...merged.values()];
}

/** Property names a fact vertex type declares, from `/api/v1/schema/{dataGraph}`. */
export function factProperties(schema, vertexType) {
  if (!schema) return { vertexType: vertexType || null, properties: new Set(), vertexTypes: [], warnings: [] };
  const vertexSchemas = Array.isArray(schema.vertexSchemas) ? schema.vertexSchemas : [];
  const names = vertexSchemas.map((vertex) => vertex.name);
  let chosen = vertexType;
  const warnings = [];
  if (!chosen && names.length === 1) {
    chosen = names[0];
    warnings.push(`data graph '${schema.name}' has one vertex type (${chosen}); using it as the fact vertex type`);
  }
  if (!chosen) {
    throw new Error(`The data graph '${schema.name}' has vertex types [${names.join(', ')}]; pass vertexType to say which carries the facts`);
  }
  const vertex = vertexSchemas.find((candidate) => candidate.name === chosen);
  if (!vertex) throw new Error(`Data graph '${schema.name}' has no vertex type '${chosen}' (it has ${names.join(', ') || 'none'})`);
  const properties = new Set((vertex.properties || []).map((property) => property.name));
  return { vertexType: chosen, properties, vertexTypes: names, warnings };
}

function parseBinding(text) {
  if (!text) return null;
  const value = String(text).trim();
  if (!value) return null;
  if (value.includes('=')) {
    const byGrain = {};
    for (const part of value.split(',')) {
      const [grain, property] = part.split('=').map((piece) => piece.trim());
      if (grain && property) byGrain[grain.toLowerCase()] = property;
    }
    return Object.keys(byGrain).length ? byGrain : null;
  }
  return { raw: value, [value]: value };
}

function bindingText(binding) {
  if (!binding) return null;
  if (typeof binding === 'string') return binding.trim() || null;
  const keys = Object.keys(binding);
  if (keys.length === 0) return null;
  const meaningful = keys.filter((key) => key !== 'raw');
  if (meaningful.length === 0) return null;
  if (meaningful.length === 1 && meaningful[0] === binding[meaningful[0]]) return binding[meaningful[0]];
  return meaningful.map((key) => `${key}=${binding[key]}`).join(',');
}

function inferMetricBinding(metric, fact) {
  if (!fact || fact.properties.size === 0) return null;
  if (/^\s*count\s*\(\s*\*\s*\)/i.test(String(metric.expression || ''))) return 'count';
  const identifiers = [...String(metric.expression || '').matchAll(/\b[A-Za-z_][\w$]*\b/g)]
    .map((match) => match[0])
    .filter((name) => !/^(?:sum|count|avg|min|max|ratio|distinct|case|when|then|else|end|and|or|not|null|as|cast|integer|decimal|date|coalesce|100)$/i.test(name));
  const hits = unique(identifiers.filter((name) => fact.properties.has(name)));
  if (hits.length === 1) return hits[0];
  const byId = shortId(metric.id);
  if (fact.properties.has(byId)) return byId;
  return null;
}

function inferDimensionBinding(dimension, fact) {
  if (!fact || fact.properties.size === 0) return null;
  const column = String(dimension.column || dimension.name || '').replace(/^.*\./, '');
  if (fact.properties.has(column)) return { [column]: column };
  if (isDateType(dimension.dataType) && fact.properties.has('year') && fact.properties.has('month')) {
    return { year: 'year', month: 'month' };
  }
  return null;
}

function normaliseColumnId(table, column) {
  return table ? `column/${table}.${column}` : `column/${column}`;
}

/** All column references a metric's expression makes, mapped to the table that declares them when known. */
function resolveMetricColumns(metric, columnsByTable, columnsByName) {
  const found = [];
  for (const name of metric.columns || []) {
    if (metric.tables?.length === 1) found.push({ table: metric.tables[0], name });
    else if (columnsByName.has(name)) found.push({ table: columnsByName.get(name), name });
    else if (columnsByTable.has(name)) found.push({ table: columnsByTable.get(name), name });
  }
  return unique(found.map((column) => normaliseColumnId(column.table, column.name)));
}

function mergeEquivalent(observations, keyOf, warnings, label) {
  const groups = new Map();
  const order = [];
  for (const observation of observations) {
    const key = keyOf(observation);
    if (!key) {
      order.push(observation);
      continue;
    }
    if (!groups.has(key)) {
      groups.set(key, observation);
      order.push(observation);
      continue;
    }
    const canonical = groups.get(key);
    canonical.synonyms = unique([...(canonical.synonyms || []), observation.name, shortId(observation.id), ...(observation.synonyms || [])]);
    canonical.tables = unique([...(canonical.tables || []), ...(observation.tables || [])]);
    canonical.columns = unique([...(canonical.columns || []), ...(observation.columns || [])]);
    canonical.evidenceItems = [...(canonical.evidenceItems || []), ...(observation.evidenceItems || [])].slice(0, 8);
    canonical.confidence = Math.max(canonical.confidence ?? 0, observation.confidence ?? 0);
    for (const key2 of ['unit', 'description', 'dataType', 'queryId', 'graphProperty', 'table', 'column', 'expression', 'aggFunction', 'sqlText']) {
      if (canonical[key2] == null && observation[key2] != null) canonical[key2] = observation[key2];
    }
    warnings.push(`${label} ${observation.id} and ${canonical.id} carry the same definition/binding; kept ${canonical.id} and recorded the other as an alias`);
  }
  return order;
}

/**
 * @param {{graph:string, dataGraph?:string, vertexType?:string, documents:Array, observations:object,
 *          factSchema?:object, bindings?:object, overrides?:object, now?:Date|string}} options
 */
export function buildModel(options) {
  const graph = options.graph || 'data-agent';
  const now = options.now ?? new Date();
  const warnings = [...(options.observations?.warnings || [])];
  const facts = options.factSchema ? factProperties(options.factSchema, options.vertexType) : { vertexType: options.vertexType || null, properties: new Set(), vertexTypes: [], warnings: [] };
  warnings.push(...facts.warnings);
  const dataGraph = options.dataGraph || options.factSchema?.name || null;
  const vertexType = facts.vertexType || options.vertexType || null;
  const bindings = options.bindings || {};

  // bind before de-duplicating: two sources that define the same metric in Chinese and English are the same
  // metric when their expression and the fact property agree, and the merge is recorded as a warning.
  const rawMetrics = (options.observations?.metrics || []).map((metric) => ({
    ...metric,
    graphProperty: bindings[metric.id] ?? metric.graphProperty ?? (options.inferBindings === false ? null : inferMetricBinding(metric, facts)),
  }));
  const rawDimensions = (options.observations?.dimensions || []).map((dimension) => ({
    ...dimension,
    graphProperty: bindings[dimension.id] ?? dimension.graphProperty ?? (options.inferBindings === false ? null : inferDimensionBinding(dimension, facts)),
  }));
  const metrics = options.mergeDuplicates === false ? mergeById(rawMetrics)
    : mergeEquivalent(mergeById(rawMetrics),
      (metric) => (metric.expression && metric.graphProperty ? `${String(metric.expression).replace(/\s+/g, ' ').toLowerCase()}|${metric.graphProperty}` : null), warnings, 'metric');
  const dimensions = options.mergeDuplicates === false ? mergeById(rawDimensions)
    : mergeEquivalent(mergeById(rawDimensions),
      (dimension) => (dimension.column && dimension.graphProperty
        ? `${String(dimension.column).replace(/^.*\./, '')}|${bindingText(dimension.graphProperty)}`
        : null), warnings, 'dimension');
  const terms = mergeById(options.observations?.terms || []);
  const tables = mergeById((options.observations?.tables || []).map((table) => ({
    ...table, id: table.id || `table/${slug(table.name)}`,
  })));
  const queryObservations = (options.observations?.queries || []).map((query) => ({ ...query, id: query.id || `query/${slug(query.name)}` }));
  const queries = mergeById(queryObservations);

  const columnMap = new Map();
  const knownTableByName = new Map();
  for (const column of options.observations?.columns || []) {
    if (column.table && !knownTableByName.has(column.name)) knownTableByName.set(column.name, column.table);
  }
  for (const column of options.observations?.columns || []) {
    const table = column.table || knownTableByName.get(column.name) || null;
    const id = normaliseColumnId(table, column.name);
    const existing = columnMap.get(id);
    if (existing) {
      existing.description = existing.description || column.description;
      existing.dataType = existing.dataType || column.dataType;
      existing.columnRole = existing.columnRole || column.columnRole;
      existing.evidenceItems.push(column);
      existing.confidence = Math.max(existing.confidence ?? 0, column.confidence ?? 0);
    } else {
      columnMap.set(id, {
        ...column, id, tableName: table, evidenceItems: [column], confidence: column.confidence ?? 0.8,
      });
    }
  }
  const columnsByTable = new Map();
  const columnsByName = new Map();
  for (const column of columnMap.values()) {
    if (column.tableName) columnsByTable.set(column.name, column.tableName);
    if (!columnsByName.has(column.name)) columnsByName.set(column.name, column.tableName);
  }
  for (const metric of metrics) {
    for (const columnId of resolveMetricColumns(metric, columnsByTable, columnsByName)) {
      if (columnMap.has(columnId)) continue;
      const name = columnId.replace(/^column\//, '').split('.').pop();
      const table = tableOfColumn(columnId) || null;
      columnMap.set(columnId, {
        id: columnId, name, tableName: table, dataType: null, columnRole: 'measure',
        evidenceItems: metric.evidenceItems || [], confidence: 0.5, sourceId: metric.sourceId, evidence: metric.evidence,
      });
    }
    // a metric whose expression reads one table's columns can name that table without the author saying so
    if (!metric.tables?.length) {
      const inferred = unique(resolveMetricColumns(metric, columnsByTable, columnsByName).map((columnId) => tableOfColumn(columnId)).filter(Boolean));
      if (inferred.length === 1) metric.tables = inferred;
    }
  }
  // a metric with no column at all (COUNT(*)) still belongs to the one table the rest of the model declares
  const tableNamesWithColumns = unique([...columnMap.values()].map((column) => column.tableName).filter(Boolean));
  if (tableNamesWithColumns.length === 1) {
    for (const metric of metrics) if (!metric.tables?.length) metric.tables = [...tableNamesWithColumns];
  }
  // An expression can name an identifier no column in the model declares. Do not invent a column: keep the
  // expression as written for the SQL export and put the gap in the review list.
  for (const metric of metrics) {
    const unresolved = (metric.columns || []).filter((name) => !columnsByName.has(name)
      && !tableNamesWithColumns.includes(name)
      && !(metric.tables || []).includes(name));
    if (unresolved.length) {
      warnings.push(`${metric.id} reads ${unresolved.join(', ')}, which no column in this model declares; the SQL keeps the expression as written and the lineage is incomplete`);
    }
  }

  // tables named by a metric but never declared get a node: the lineage needs somewhere to point
  for (const table of tables) {
    if (table.name && !tables.some((candidate) => candidate.name === table.name && candidate.id !== table.id)) {
      table.id = `table/${slug(table.name)}`;
    }
  }
  for (const metric of metrics) {
    for (const tableName of metric.tables || []) {
      if (!tables.some((table) => table.name === tableName)) {
        tables.push({
          id: `table/${slug(tableName)}`, name: tableName, description: null, sourceId: metric.sourceId,
          evidence: metric.evidence, confidence: 0.5, evidenceItems: [], proposedBy: 'lineage',
        });
      }
    }
  }

  // ---- bindings -------------------------------------------------------------------------------------
  const unboundMetrics = [];
  const unboundDimensions = [];
  for (const metric of metrics) {
    if (!metric.expression) {
      metric.needsReview = true;
      metric.status = 'needs_review';
      warnings.push(`${metric.id} has no expression; it is in the model as a term needing review, not as a metric that can answer`);
    }
    metric.graphProperty = bindings[metric.id] ?? metric.graphProperty ?? (options.inferBindings === false ? null : inferMetricBinding(metric, facts));
    if (!metric.graphProperty) unboundMetrics.push(metric.id);
    metric.metricKind = metric.metricKind || (/metric\//.test(String(metric.expression || '')) ? 'derived'
      : /\/\s*(?:SUM|COUNT|AVG)/i.test(String(metric.expression || '')) ? 'ratio' : 'base');
    metric.status = metric.status || (metric.expression ? 'accepted' : 'needs_review');
  }
  for (const dimension of dimensions) {
    const explicit = bindings[dimension.id];
    dimension.graphProperty = explicit ?? dimension.graphProperty ?? (options.inferBindings === false ? null : inferDimensionBinding(dimension, facts));
    if (!dimension.graphProperty) unboundDimensions.push(dimension.id);
    if (dimension.column && !dimension.table) {
      dimension.table = columnsByName.get(String(dimension.column).replace(/^.*\./, '')) || null;
    }
    const columnId = normaliseColumnId(dimension.table, dimension.column || dimension.name);
    if (dimension.column && !columnMap.has(columnId)) {
      columnMap.set(columnId, {
        id: columnId, name: dimension.column, tableName: dimension.table, dataType: dimension.dataType,
        columnRole: isDateType(dimension.dataType) ? 'attribute' : 'attribute',
        evidenceItems: dimension.evidenceItems || [], confidence: dimension.confidence ?? 0.7,
        sourceId: dimension.sourceId, evidence: dimension.evidence,
      });
    }
  }

  // ---- nodes ----------------------------------------------------------------------------------------
  const nodes = new Map();
  const putNode = (source) => {
    if (!source?.id) return;
    if (!nodes.has(source.id)) {
      nodes.set(source.id, {
        id: source.id,
        entityType: source.entityType,
        properties: {},
        evidenceItems: source.evidenceItems || [],
      });
    }
    const target = nodes.get(source.id);
    const { id, entityType, evidenceItems, ...properties } = source;
    for (const [key, value] of Object.entries(properties)) {
      if (value == null || value === '' || (Array.isArray(value) && value.length === 0)) continue;
      if (target.properties[key] == null) target.properties[key] = value;
      else if (key === 'alias') target.properties[key] = unique([...(target.properties[key] || []), ...(Array.isArray(value) ? value : [value])]);
      else if (key === 'description' && !target.properties[key]) target.properties[key] = value;
    }
    target.entityType = entityType || target.entityType;
  };

  putNode({ id: `model/${slug(graph)}`, entityType: 'Model', name: `model/${slug(graph)}`, description: `Semantic/metadata layer built by pi-data-agent from ${options.documents?.length ?? 0} source(s)`, dataGraph, vertexType, modelVersion: 1, builtAt: new Date(now).toISOString(), proposedBy: 'pi-data-agent' });

  for (const document of options.documents || []) {
    putNode({
      id: `source/${document.id}`, entityType: 'Source', name: document.title || document.name,
      description: document.url || document.path || document.name, sourceId: document.id,
      evidence: quote(`${document.kind}: ${document.title || document.name}`), proposedBy: 'pi-data-agent',
    });
  }

  for (const table of tables) {
    putNode({
      id: table.id || `table/${slug(table.name)}`, entityType: 'Table', name: table.name,
      description: table.description, domain: table.domain, sourceId: table.sourceId,
      evidence: evidenceJson(table.evidenceItems), confidence: table.confidence, proposedBy: table.proposedBy,
      status: table.confidence && table.confidence < 0.6 ? 'needs_review' : 'accepted',
    });
  }
  for (const column of columnMap.values()) {
    putNode({
      id: column.id, entityType: 'Column', name: column.name, tableName: column.tableName,
      dataType: column.dataType, columnRole: column.columnRole, description: column.description,
      sourceId: column.sourceId, evidence: evidenceJson(column.evidenceItems), confidence: column.confidence,
      proposedBy: column.proposedBy || 'extracted', status: column.confidence && column.confidence < 0.6 ? 'needs_review' : 'accepted',
    });
  }
  for (const metric of metrics) {
    putNode({
      id: metric.id, entityType: 'Metric', name: metric.name, description: metric.description,
      expression: metric.expression, aggFunction: metric.aggFunction, unit: metric.unit,
      metricKind: metric.metricKind, alias: metric.synonyms, graphProperty: metric.graphProperty,
      owner: metric.owner, sourceId: metric.sourceId, evidence: evidenceJson(metric.evidenceItems),
      confidence: metric.confidence, proposedBy: metric.proposedBy || 'extracted', status: metric.status,
      needsReview: metric.needsReview === true ? true : undefined,
    });
  }
  for (const dimension of dimensions) {
    putNode({
      id: dimension.id, entityType: 'Dimension', name: dimension.name, description: dimension.description,
      alias: dimension.synonyms, dataType: dimension.dataType, graphProperty: bindingText(dimension.graphProperty),
      sourceId: dimension.sourceId, evidence: evidenceJson(dimension.evidenceItems), confidence: dimension.confidence,
      proposedBy: dimension.proposedBy || 'extracted', status: dimension.confidence && dimension.confidence < 0.6 ? 'needs_review' : 'accepted',
    });
  }
  for (const term of terms) {
    putNode({
      id: term.id, entityType: 'Term', name: term.name, description: term.description, alias: term.synonyms,
      sourceId: term.sourceId, evidence: evidenceJson(term.evidenceItems), confidence: term.confidence,
      proposedBy: term.proposedBy || 'extracted', status: 'accepted',
    });
  }
  for (const query of queries) {
    putNode({
      id: query.id, entityType: 'Query', name: query.name, description: query.description, sqlText: query.sqlText,
      sourceId: query.sourceId, evidence: evidenceJson(query.evidenceItems), confidence: query.confidence,
      proposedBy: query.proposedBy || 'extracted', status: 'accepted',
    });
  }
  // domains mentioned on a table/metric become real nodes and edges
  for (const node of [...nodes.values()]) {
    const domain = node.properties.domain;
    if (!domain) continue;
    const id = `domain/${slug(domain)}`;
    putNode({ id, entityType: 'Domain', name: domain, description: `${domain} 业务域`, proposedBy: 'pi-data-agent' });
  }

  // ---- edges ----------------------------------------------------------------------------------------
  const edges = new Map();
  const putEdge = (head, relation, tail, extra = {}) => {
    if (!head || !tail || !nodes.has(head) || !nodes.has(tail)) return;
    if (head === tail) return;
    const id = `${head}|${relation}|${tail}`;
    const existing = edges.get(id);
    if (!existing) {
      edges.set(id, {
        id, head, relation, tail,
        properties: {
          ...relationProps(relation),
          confidence: extra.confidence ?? 0.9,
          evidence: extra.evidence ?? null,
          status: extra.status || 'accepted',
          sourceId: extra.sourceId || null,
        },
      });
      return;
    }
    existing.properties.confidence = Math.max(existing.properties.confidence ?? 0, extra.confidence ?? 0);
    if (!existing.properties.evidence && extra.evidence) existing.properties.evidence = extra.evidence;
  };

  for (const column of columnMap.values()) {
    if (!column.tableName) continue;
    const tableId = `table/${slug(column.tableName)}`;
    if (!nodes.has(tableId)) {
      putNode({ id: tableId, entityType: 'Table', name: column.tableName, description: null, proposedBy: 'lineage' });
    }
    putEdge(tableId, 'hasColumn', column.id, { evidence: evidenceJson(column.evidenceItems), sourceId: column.sourceId, confidence: column.confidence });
  }
  for (const key of options.observations?.foreignKeys || []) {
    const head = normaliseColumnId(key.table, key.column);
    const tail = normaliseColumnId(key.referencesTable, key.referencesColumn);
    if (!key.referencesColumn) continue;
    if (!nodes.has(head)) putNode({ id: head, entityType: 'Column', name: key.column, tableName: key.table, proposedBy: 'sql-schema' });
    if (!nodes.has(tail)) putNode({ id: tail, entityType: 'Column', name: key.referencesColumn, tableName: key.referencesTable, proposedBy: 'sql-schema' });
    putEdge(head, 'references', tail, { evidence: key.evidence, sourceId: key.sourceId, confidence: key.confidence ?? 0.9 });
  }
  for (const metric of metrics) {
    for (const tableName of metric.tables || []) putEdge(metric.id, 'computedFrom', `table/${slug(tableName)}`, { evidence: metric.evidence, sourceId: metric.sourceId, confidence: metric.confidence });
    for (const columnId of resolveMetricColumns(metric, columnsByTable, columnsByName)) {
      putEdge(metric.id, 'usesColumn', columnId, { evidence: metric.evidence, sourceId: metric.sourceId, confidence: metric.confidence });
    }
    for (const dimensionId of metric.groupedBy || []) {
      // observations may name dimensions directly; resolved below by name when not prefixed
    }
    const derived = [...String(metric.expression || '').matchAll(/metric\/([A-Za-z0-9_\u4e00-\u9fff]+)/g)]
      .map((match) => `metric/${match[1]}`);
    for (const base of unique(derived)) {
      if (base !== metric.id) putEdge(base, 'contributesTo', metric.id, { evidence: metric.evidence, sourceId: metric.sourceId, confidence: 0.8 });
    }
    if (metric.queryId) putEdge(metric.id, 'definedBy', metric.queryId, { evidence: metric.evidence, sourceId: metric.sourceId });
  }
  for (const dimension of dimensions) {
    const columnId = normaliseColumnId(dimension.table, dimension.column || dimension.name);
    putEdge(dimension.id, 'dimensionColumn', columnId, { evidence: dimension.evidence, sourceId: dimension.sourceId, confidence: dimension.confidence });
  }
  for (const query of queries) {
    for (const tableName of query.tables || []) putEdge(query.id, 'readsTable', `table/${slug(tableName)}`, { evidence: query.evidence, sourceId: query.sourceId, confidence: query.confidence });
    for (const metricId of query.metrics || []) putEdge(metricId, 'definedBy', query.id, { evidence: query.evidence, sourceId: query.sourceId, confidence: 0.75 });
  }

  // a dimension can slice a metric when both are bound and the fact graph has both properties; the model
  // records the pairing the interpreter reads, and refuses the pair when the binding is missing
  for (const metric of metrics) {
    if (!metric.graphProperty) continue;
    for (const dimension of dimensions) {
      if (!dimension.graphProperty) continue;
      if (dimension.queryId && metric.queryId && dimension.queryId !== metric.queryId) continue;
      putEdge(metric.id, 'groupedBy', dimension.id, { evidence: metric.evidence, sourceId: metric.sourceId, confidence: 0.7 });
    }
  }
  for (const node of nodes.values()) {
    const sourceId = node.properties.sourceId;
    if (!sourceId) continue;
    const sourceNode = `source/${sourceId}`;
    if (!nodes.has(sourceNode)) continue;
    putEdge(sourceNode, 'describes', node.id, { evidence: node.properties.evidence, sourceId, confidence: node.properties.confidence ?? 0.7 });
  }
  for (const node of nodes.values()) {
    const domain = node.properties.domain;
    if (!domain) continue;
    putEdge(node.id, 'belongsTo', `domain/${slug(domain)}`, { evidence: node.properties.evidence, sourceId: node.properties.sourceId });
  }

  const nodeList = [...nodes.values()].sort((a, b) => a.id.localeCompare(b.id));
  const edgeList = [...edges.values()].sort((a, b) => a.id.localeCompare(b.id));
  // The catalog makes the model readable on a keyed store (Aerospike cannot scan a whole type): the Model
  // node lists every id the package wrote, and the client reads them back by id.
  const modelNode = nodes.get(`model/${slug(graph)}`);
  if (modelNode) {
    modelNode.properties.nodeIds = nodeList.map((node) => node.id);
    modelNode.properties.edgeIds = edgeList.map((edge) => edge.id);
  }
  const byType = {};
  for (const node of nodeList) byType[node.entityType] = (byType[node.entityType] || 0) + 1;
  for (const id of unboundMetrics) warnings.push(`${id} has no graphProperty on fact graph '${dataGraph || '(none)'}'${vertexType ? ` / ${vertexType}` : ''}: SQL is available, execution is refused`);
  for (const id of unboundDimensions) warnings.push(`${id} has no graphProperty on fact graph '${dataGraph || '(none)'}'${vertexType ? ` / ${vertexType}` : ''}: SQL is available, execution is refused`);
  for (const metric of metrics) {
    if (!metric.tables?.length) warnings.push(`${metric.id} names no source table; its lineage is the columns it uses`);
  }

  return {
    graph,
    dataGraph,
    vertexType,
    modelVersion: 1,
    builtAt: new Date(now).toISOString(),
    nodes: nodeList.map((node) => ({ id: node.id, entityType: node.entityType, properties: node.properties })),
    edges: edgeList.map((edge) => ({ id: edge.id, head: edge.head, relation: edge.relation, tail: edge.tail, properties: edge.properties })),
    sources: (options.documents || []).map((document) => ({ id: document.id, name: document.name, title: document.title, kind: document.kind, path: document.path, url: document.url })),
    stats: {
      nodes: nodeList.length,
      edges: edgeList.length,
      byType,
      metrics: metrics.length,
      dimensions: dimensions.length,
      tables: tables.length,
      columns: columnMap.size,
      queries: queries.length,
      evidence: nodeList.reduce((sum, node) => sum + (node.properties.evidence ? 1 : 0), 0),
    },
    binding: {
      dataGraph,
      vertexType,
      unboundMetrics: unique(unboundMetrics),
      unboundDimensions: unique(unboundDimensions),
      factProperties: [...facts.properties].sort(),
    },
    warnings: unique(warnings),
    metrics,
    dimensions,
  };
}

/** The model from a graph read (`Node` vertices + `Relation` edges) in the builder's shape. */
export function modelFromGraph(graph, nodes, edges) {
  const modelNode = nodes.find((node) => node.entityType === 'Model');
  return {
    graph,
    dataGraph: modelNode?.properties?.dataGraph || null,
    vertexType: modelNode?.properties?.vertexType || null,
    nodes: nodes.map((node) => ({ id: node.id, entityType: node.entityType, properties: node.properties })),
    edges: edges.map((edge) => ({ id: edge.id, head: edge.head, relation: edge.relation, tail: edge.tail, properties: edge.properties })),
    stats: {
      nodes: nodes.length,
      edges: edges.length,
      byType: nodes.reduce((acc, node) => { acc[node.entityType] = (acc[node.entityType] || 0) + 1; return acc; }, {}),
      metrics: nodes.filter((node) => node.entityType === 'Metric').length,
      dimensions: nodes.filter((node) => node.entityType === 'Dimension').length,
      tables: nodes.filter((node) => node.entityType === 'Table').length,
      columns: nodes.filter((node) => node.entityType === 'Column').length,
      queries: nodes.filter((node) => node.entityType === 'Query').length,
    },
    warnings: [],
  };
}

/** SQL that the server's semantic renderer can produce for one metric (a client-side fallback). */
export function metricSql(model, metricId, dimensionId = null, grain = 'raw') {
  const metric = model.nodes.find((node) => node.id === metricId);
  if (!metric?.properties?.expression) return null;
  const computed = (model.edges || []).find((edge) => edge.head === metricId && edge.relation === 'computedFrom');
  const usedColumns = (model.edges || []).filter((edge) => edge.head === metricId && edge.relation === 'usesColumn').map((edge) => edge.tail);
  const dimensionEdge = dimensionId ? (model.edges || []).find((edge) => edge.head === dimensionId && edge.relation === 'dimensionColumn') : null;
  const table = computed?.tail?.replace(/^table\//, '')
    || (dimensionEdge?.tail ? tableOfColumn(dimensionEdge.tail) : null)
    || (usedColumns[0] ? tableOfColumn(usedColumns[0]) : null);
  let expression = metric.properties.expression;
  const columns = usedColumns.map((edge) => edge.replace(/^column\//, ''));
  for (const column of columns) {
    const name = column.split('.').pop();
    expression = expression.replace(new RegExp(`\\b${name}\\b`, 'g'), column);
  }
  const select = [];
  let groupBy = null;
  if (dimensionId) {
    const dimension = model.nodes.find((node) => node.id === dimensionId);
    const column = dimensionEdge?.tail?.replace(/^column\//, '') || dimension?.properties?.name;
    if (column) {
      const normalGrain = String(grain || 'raw').toLowerCase();
      const rendered = normalGrain === 'year' ? `EXTRACT(YEAR FROM ${column})`
        : normalGrain === 'quarter' ? `EXTRACT(QUARTER FROM ${column})`
          : normalGrain === 'month' ? `DATE_TRUNC('month', ${column})`
            : column;
      const alias = normalGrain === 'raw' ? shortId(dimensionId) : `${shortId(dimensionId)}_${normalGrain}`;
      select.push(`${rendered} AS ${alias}`);
      groupBy = rendered;
    }
  }
  select.push(`${expression} AS ${shortId(metricId)}`);
  const from = table ? ` FROM ${table}` : '';
  return `SELECT ${select.join(', ')}${from}${groupBy ? ` GROUP BY ${groupBy}` : ''};`;
}
