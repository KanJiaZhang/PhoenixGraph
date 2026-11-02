/**
 * Execution of a semantic request on the fact graph.
 *
 * The SQL renderer (the semantic API) is the export; this is the answer. It builds the same traversal
 * shape the server's `SemanticRunner` builds - `g.V(<type>).has(<date>, <year>).group().by(<property>)
 * .by(values(<measure>).sum())` - and refuses the cases where it would have to guess: no fact binding, no
 * property for the grain, a measure that is not bound, no date property for a year filter.
 */
import { gremlinString, gremlinNumber, round, isDateType } from './util.mjs';
import { metricSql } from './extract/model.mjs';

function modelNode(model) {
  return (model.nodes || []).find((node) => node.entityType === 'Model') || null;
}

/** Which fact graph/vertex type answers this model, from the Model node unless the caller overrides it. */
export function bindingOf(model, options = {}) {
  const node = modelNode(model)?.properties || {};
  const dataGraph = options.dataGraph || model.dataGraph || node.dataGraph || null;
  const vertexType = options.vertexType || model.vertexType || node.vertexType || null;
  // A keyed fact graph (Aerospike) cannot scan a whole vertex type, so the model can carry the fact ids it was
  // bound to. Then a semantic query is `g.V(ids...)` - still one traversal, just addressed the way the store
  // answers: by id.
  const factIds = options.factIds || model.factIds || node.factIds || null;
  return { dataGraph, vertexType, factIds: Array.isArray(factIds) ? factIds.map(String) : null };
}

export function metricNode(model, id) {
  if (!id) return null;
  const wanted = id.includes('/') ? id : `metric/${id}`;
  return (model.nodes || []).find((node) => node.id === wanted) || null;
}

export function dimensionNode(model, id) {
  if (!id) return null;
  const wanted = id.includes('/') ? id : `dim/${id}`;
  return (model.nodes || []).find((node) => node.id === wanted) || null;
}

function parseBindings(text) {
  const value = String(text ?? '').trim();
  if (!value) return {};
  if (!value.includes('=')) return { raw: value };
  const out = {};
  for (const part of value.split(',')) {
    const [grain, property] = part.split('=').map((piece) => piece.trim());
    if (grain && property) out[grain.toLowerCase()] = property;
  }
  return out;
}

export function dimensionProperty(dimension, grain = 'raw') {
  if (!dimension) return null;
  const byGrain = parseBindings(dimension.properties?.graphProperty);
  if (Object.keys(byGrain).length === 0) return null;
  const wanted = String(grain || 'raw').toLowerCase();
  if (byGrain[wanted]) return byGrain[wanted];
  if (byGrain.raw) return byGrain.raw;
  // a single binding without a grain applies to every grain; a grain-keyed map falls back to raw
  const keys = Object.keys(byGrain).filter((key) => key !== 'raw');
  return keys.length === 1 ? byGrain[keys[0]] : null;
}

export function dateDimension(model) {
  return (model.nodes || []).find((node) => node.entityType === 'Dimension'
    && /(?:^|,)year=/.test(String(node.properties?.graphProperty || '')));
}

export function yearProperty(model) {
  const dimension = dateDimension(model);
  return dimension ? parseBindings(dimension.properties.graphProperty).year || null : null;
}

function measureOf(metric) {
  const binding = String(metric.properties?.graphProperty || '').trim();
  if (!binding) return null;
  if (binding === 'count' || /^\s*count\s*\(\s*\*\s*\)/i.test(String(metric.properties?.expression || ''))) return 'count';
  return binding;
}

function gremlinValue(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return gremlinNumber(value);
  if (typeof value === 'boolean') return String(value);
  return gremlinString(value);
}

/**
 * One semantic query. Returns rows, the total and the traversal; a refusal is `{ executed:false, reason }`
 * with the SQL when the model can still express it.
 */
export async function execute(client, model, request = {}) {
  const binding = bindingOf(model, request);
  const metric = metricNode(model, request.metric || request.metricId);
  if (!metric) return { executed: false, reason: `no metric '${request.metric || request.metricId}' in this model` };
  const dimensionIdForSql = request.dimension ? (request.dimension.includes('/') ? request.dimension : `dim/${request.dimension}`) : null;
  const sql = request.sql ?? metricSql(model, metric.id, dimensionIdForSql, request.grain || 'raw');
  if (!binding.dataGraph || !binding.vertexType) {
    return { executed: false, reason: 'the model does not name the fact graph/vertex type that holds the rows (set dataGraph and vertexType on the Model node or pass them)', sql };
  }
  const measure = measureOf(metric);
  if (!measure) {
    return { executed: false, reason: `${metric.id} has no graphProperty on the fact graph: it cannot be executed here; the SQL is the export`, sql };
  }
  const dimension = request.dimension ? dimensionNode(model, request.dimension) : null;
  if (request.dimension && !dimension) return { executed: false, reason: `no dimension '${request.dimension}' in this model`, sql };
  const dimensionProp = dimension ? dimensionProperty(dimension, request.grain || 'raw') : null;
  if (dimension && !dimensionProp) {
    return { executed: false, reason: `${dimension.id} has no graphProperty at grain '${request.grain || 'raw'}' on the fact graph; the SQL is the export`, sql };
  }

  // the engine reads a start step as `g.V(id, id, ..., 'VertexType')`: the last argument names the schema
  const traversal = [binding.factIds?.length
    ? `g.V(${[...binding.factIds, binding.vertexType].map(gremlinString).join(',')})`
    : `g.V(${gremlinString(binding.vertexType)})`];
  const warnings = [];
  if (request.year != null && String(request.year).trim() !== '') {
    const property = yearProperty(model);
    if (!property) {
      return { executed: false, reason: `the model has no date dimension with a year binding, so a year filter cannot be applied; the SQL is the export`, sql };
    }
    traversal.push(`.has(${gremlinString(property)}, ${gremlinNumber(request.year)})`);
  }
  for (const filter of request.filters || []) {
    if (!filter || filter.property == null || filter.value == null) continue;
    traversal.push(`.has(${gremlinString(filter.property)}, ${gremlinValue(filter.value)})`);
  }
  const reduction = measure === 'count' ? 'count()' : `values(${gremlinString(measure)}).sum()`;
  if (dimension) traversal.push(`.group().by(${gremlinString(dimensionProp)}).by(${reduction})`);
  else traversal.push(`.${reduction}`);
  const gremlin = traversal.join('');

  let answer;
  try {
    answer = await client.query(binding.dataGraph, gremlin);
  } catch (error) {
    return { executed: false, reason: `the fact graph did not answer: ${error.message}`, gremlin, sql };
  }

  const rows = [];
  let total = 0;
  if (dimension) {
    const grouped = answer.length && answer[0] && typeof answer[0] === 'object' && !Array.isArray(answer[0]) ? answer[0] : {};
    for (const [value, measureValue] of Object.entries(grouped)) {
      const number = Number(measureValue?.value ?? measureValue);
      if (!Number.isFinite(number)) continue;
      rows.push({ value, measure: number });
      total += number;
    }
    rows.sort((left, right) => right.measure - left.measure);
  } else {
    const scalar = Number(answer?.[0]?.value ?? answer?.[0] ?? 0);
    total = Number.isFinite(scalar) ? scalar : 0;
    rows.push({ value: 'total', measure: total });
  }
  if (rows.length === 0) {
    warnings.push(`no rows for ${binding.dataGraph}/${binding.vertexType}${request.year != null ? ` in ${request.year}` : ''}: the data does not cover the filter, so the zero is "no data", not a measured zero`);
  }
  const dateBound = /(?:^|,)year=/.test(String(dimension?.properties?.graphProperty || '')) || isDateType(dimension?.properties?.dataType);
  if (dimension && request.grain && request.grain !== 'raw' && !dateBound) {
    warnings.push(`grain '${request.grain}' does not apply to ${dimension.id}: it is not a date dimension, so values are grouped unchanged`);
  }
  return {
    executed: true,
    dataGraph: binding.dataGraph,
    vertexType: binding.vertexType,
    metric: metric.id,
    dimension: dimension?.id || null,
    grain: request.grain || 'raw',
    measure,
    rows,
    total: round(total, 4),
    gremlin,
    warnings,
    sql,
  };
}
