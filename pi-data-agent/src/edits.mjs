/**
 * Reviewer edits: add, change and remove metadata, entities and relations.
 *
 * An edit is validated against the same rules the extractor follows, then written through the same batch
 * API. Nothing is overwritten wholesale: a node edit merges the named properties, every edit carries an
 * audit record (before/after, who, when), and a delete is refused unless it says `confirm: true` and either
 * has no edges or asks to cascade. That is the difference between "the user can fix the model" and "one
 * typo can remove a metric the dashboard depends on".
 */
import { unique } from './util.mjs';
import { relationProps } from './extract/model.mjs';
import { nodeRequests, edgeRequests } from './graph/schema.mjs';

export const NODE_TYPES = ['Table', 'Column', 'Metric', 'Dimension', 'Term', 'Query', 'Source', 'Model', 'Domain'];
export const RELATIONS = ['hasColumn', 'references', 'computedFrom', 'usesColumn', 'groupedBy', 'dimensionColumn', 'definedBy', 'readsTable', 'contributesTo', 'belongsTo', 'describes', 'sameAs'];

const PREFIX_FOR_TYPE = {
  Table: 'table/', Column: 'column/', Metric: 'metric/', Dimension: 'dim/', Term: 'term/',
  Query: 'query/', Source: 'source/', Model: 'model/', Domain: 'domain/',
};

const EDITABLE = new Set([
  'name', 'description', 'alias', 'dataType', 'columnRole', 'tableName', 'aggFunction', 'expression',
  'metricKind', 'unit', 'sqlText', 'graphProperty', 'owner', 'domain', 'dataGraph', 'vertexType',
  'evidence', 'confidence', 'status', 'proposedBy', 'needsReview', 'sourceId', 'tags',
]);

const RELATION_TYPES = {
  hasColumn: ['Table', 'Column'],
  references: ['Column', 'Column'],
  computedFrom: ['Metric', 'Table'],
  usesColumn: ['Metric', 'Column'],
  groupedBy: ['Metric', 'Dimension'],
  dimensionColumn: ['Dimension', 'Column'],
  definedBy: ['Metric', 'Query'],
  readsTable: ['Query', 'Table'],
  contributesTo: ['Metric', 'Metric'],
  describes: ['Source', null],
  sameAs: [null, null],
  belongsTo: [null, 'Domain'],
};

function nodeOf(model, id) {
  return (model.nodes || []).find((node) => node.id === id) || null;
}

function typeOfId(id) {
  const prefix = String(id).split('/')[0];
  return Object.entries(PREFIX_FOR_TYPE).find(([, value]) => value === `${prefix}/`)?.[0] || null;
}

function edgeId(head, relation, tail) {
  return `${head}|${relation}|${tail}`;
}

function mergeNode(node, properties) {
  const merged = { ...node, properties: { ...(node.properties || {}) } };
  for (const [key, value] of Object.entries(properties || {})) {
    if (!EDITABLE.has(key)) continue;
    if (value == null || value === '') delete merged.properties[key];
    else if (key === 'alias') merged.properties.alias = unique(Array.isArray(value) ? value : [value]);
    else merged.properties[key] = value;
  }
  merged.properties.proposedBy = merged.properties.proposedBy || 'reviewer';
  merged.properties.status = merged.properties.status || 'accepted';
  merged.properties.confidence = merged.properties.confidence ?? 1;
  return merged;
}

function validateNode(operation) {
  const node = operation.node || operation;
  if (!node?.id) throw new Error('an upsert-node needs a node.id');
  const type = node.entityType || typeOfId(node.id);
  if (!NODE_TYPES.includes(type)) throw new Error(`unknown entityType '${type}' (use ${NODE_TYPES.join(', ')})`);
  const prefix = PREFIX_FOR_TYPE[type];
  if (!node.id.startsWith(prefix)) throw new Error(`${type} ids must start with '${prefix}', got '${node.id}'`);
  if (node.properties?.id || node.properties?.entityType) throw new Error('id and entityType are part of the node, not properties');
  return { ...node, entityType: type };
}

function validateEdge(edge, model, added) {
  if (!edge?.head || !edge?.tail || !edge?.relation) throw new Error('an edge needs head, relation and tail');
  if (!RELATIONS.includes(edge.relation)) throw new Error(`unknown relation '${edge.relation}' (use ${RELATIONS.join(', ')})`);
  const head = nodeOf(model, edge.head) || added.get(edge.head);
  const tail = nodeOf(model, edge.tail) || added.get(edge.tail);
  if (!head) throw new Error(`edge head '${edge.head}' is not in the model`);
  if (!tail) throw new Error(`edge tail '${edge.tail}' is not in the model`);
  const [headType, tailType] = RELATION_TYPES[edge.relation];
  if (headType && head.entityType !== headType) throw new Error(`${edge.relation} needs a ${headType} head, got ${head.entityType}`);
  if (tailType && tail.entityType !== tailType) throw new Error(`${edge.relation} needs a ${tailType} tail, got ${tail.entityType}`);
  return { ...edge, head: head.id, tail: tail.id, id: edge.id || edgeId(head.id, edge.relation, tail.id) };
}

/**
 * Applies operations to an in-memory model and, unless `dryRun`, to the graph.
 *
 * @param {object} client PhoenixClient
 * @param {string} graph
 * @param {object} model the model read from the graph
 * @param {Array<object>} operations
 * @param {{dryRun?:boolean, allowDelete?:boolean, now?:Date|string}} options
 */
export async function applyEdits(client, graph, model, operations, options = {}) {
  const next = {
    ...model,
    nodes: (model.nodes || []).map((node) => ({ ...node, properties: { ...(node.properties || {}) } })),
    edges: (model.edges || []).map((edge) => ({ ...edge, properties: { ...(edge.properties || {}) } })),
  };
  const audit = [];
  const warnings = [...(model.warnings || [])];
  const added = new Map();
  const changedNodes = new Map();
  const changedEdges = new Map();
  const deletedNodes = [];
  const deletedEdges = [];

  for (const operation of operations || []) {
    if (!operation || !operation.op) throw new Error('every edit needs an op');
    const before = null;
    switch (operation.op) {
      case 'upsert-node': {
        const node = validateNode(operation);
        const existing = nodeOf(next, node.id) || added.get(node.id) || null;
        const merged = mergeNode(existing || { id: node.id, entityType: node.entityType, properties: {} }, node.properties || {});
        if (existing) {
          const index = next.nodes.findIndex((candidate) => candidate.id === node.id);
          next.nodes[index] = merged;
        } else {
          next.nodes.push(merged);
          added.set(node.id, merged);
        }
        changedNodes.set(node.id, merged);
        audit.push({ op: operation.op, id: node.id, before: existing, after: merged });
        break;
      }
      case 'update-node': {
        const existing = nodeOf(next, operation.id) || added.get(operation.id);
        if (!existing) throw new Error(`update-node: '${operation.id}' is not in the model (use upsert-node to add it)`);
        const merged = mergeNode(existing, operation.properties || {});
        const index = next.nodes.findIndex((candidate) => candidate.id === operation.id);
        next.nodes[index] = merged;
        changedNodes.set(operation.id, merged);
        audit.push({ op: operation.op, id: operation.id, before: existing, after: merged });
        break;
      }
      case 'set-binding': {
        const existing = nodeOf(next, operation.id) || added.get(operation.id);
        if (!existing) throw new Error(`set-binding: '${operation.id}' is not in the model`);
        const value = String(operation.graphProperty || '').trim();
        if (!value) throw new Error('set-binding needs graphProperty');
        if (!/^[A-Za-z_][\w$]*(?:\s*=\s*[A-Za-z_][\w$]*)?(?:\s*,\s*[A-Za-z_][\w$]*\s*=\s*[A-Za-z_][\w$]*)*$/.test(value)) {
          throw new Error(`graphProperty '${value}' is not a property name or a grain=property list`);
        }
        const merged = mergeNode(existing, { graphProperty: value });
        next.nodes[next.nodes.findIndex((candidate) => candidate.id === operation.id)] = merged;
        changedNodes.set(operation.id, merged);
        audit.push({ op: operation.op, id: operation.id, before: existing, after: merged });
        break;
      }
      case 'add-alias': {
        const existing = nodeOf(next, operation.id) || added.get(operation.id);
        if (!existing) throw new Error(`add-alias: '${operation.id}' is not in the model`);
        const aliases = unique([...(existing.properties?.alias || []), ...(Array.isArray(operation.alias) ? operation.alias : [operation.alias])]);
        const merged = mergeNode(existing, { alias: aliases });
        next.nodes[next.nodes.findIndex((candidate) => candidate.id === operation.id)] = merged;
        changedNodes.set(operation.id, merged);
        audit.push({ op: operation.op, id: operation.id, before: existing, after: merged });
        break;
      }
      case 'upsert-edge': {
        const edge = validateEdge(operation.edge || operation, next, added);
        const existing = next.edges.find((candidate) => candidate.id === edge.id);
        const merged = {
          id: edge.id, head: edge.head, relation: edge.relation, tail: edge.tail,
          properties: { ...relationProps(edge.relation), confidence: 1, status: 'accepted', proposedBy: 'reviewer', ...(existing?.properties || {}), ...(edge.properties || {}) },
        };
        if (existing) next.edges[next.edges.findIndex((candidate) => candidate.id === edge.id)] = merged;
        else next.edges.push(merged);
        changedEdges.set(edge.id, merged);
        audit.push({ op: operation.op, id: edge.id, before: existing || null, after: merged });
        break;
      }
      case 'update-edge': {
        const edge = validateEdge(operation.edge || operation, next, added);
        const existing = next.edges.find((candidate) => candidate.id === edge.id);
        if (!existing) throw new Error(`update-edge: '${edge.id}' is not in the model`);
        const merged = { ...existing, properties: { ...existing.properties, ...(edge.properties || {}) } };
        next.edges[next.edges.findIndex((candidate) => candidate.id === edge.id)] = merged;
        changedEdges.set(edge.id, merged);
        audit.push({ op: operation.op, id: edge.id, before: existing, after: merged });
        break;
      }
      case 'delete-edge': {
        if (!options.allowDelete && operation.confirm !== true) throw new Error('delete-edge needs confirm:true');
        const edge = validateEdge(operation.edge || operation, next, added);
        const index = next.edges.findIndex((candidate) => candidate.id === edge.id);
        if (index < 0) throw new Error(`delete-edge: '${edge.id}' is not in the model`);
        const existing = next.edges[index];
        next.edges.splice(index, 1);
        deletedEdges.push(existing);
        audit.push({ op: operation.op, id: edge.id, before: existing, after: null });
        break;
      }
      case 'delete-node': {
        if (!options.allowDelete && operation.confirm !== true) throw new Error('delete-node needs confirm:true');
        const existing = nodeOf(next, operation.id) || added.get(operation.id);
        if (!existing) throw new Error(`delete-node: '${operation.id}' is not in the model`);
        if (existing.entityType === 'Model') throw new Error('the Model node is the model; edit its properties instead of deleting it');
        const incident = next.edges.filter((edge) => edge.head === operation.id || edge.tail === operation.id);
        if (incident.length && operation.cascade !== true) {
          throw new Error(`delete-node: '${operation.id}' has ${incident.length} edge(s); pass cascade:true to delete them too`);
        }
        if (operation.cascade === true) {
          for (const edge of incident) {
            next.edges.splice(next.edges.findIndex((candidate) => candidate.id === edge.id), 1);
            deletedEdges.push(edge);
            audit.push({ op: 'delete-edge', id: edge.id, before: edge, after: null, cascadeOf: operation.id });
          }
        }
        next.nodes.splice(next.nodes.findIndex((candidate) => candidate.id === operation.id), 1);
        deletedNodes.push(existing);
        audit.push({ op: operation.op, id: operation.id, before: existing, after: null });
        break;
      }
      default:
        throw new Error(`unknown edit op '${operation.op}' (use upsert-node, update-node, set-binding, add-alias, upsert-edge, update-edge, delete-node, delete-edge)`);
    }
  }

  // The catalog is part of the model, not a side file: every edit refreshes it so a keyed store can read
  // the model back before and after the write.
  const modelNode = next.nodes.find((node) => node.entityType === 'Model');
  if (modelNode) {
    modelNode.properties = {
      ...modelNode.properties,
      nodeIds: next.nodes.map((node) => node.id),
      edgeIds: next.edges.map((edge) => edge.id),
      builtAt: modelNode.properties.builtAt || new Date(options.now ?? Date.now()).toISOString(),
    };
    changedNodes.set(modelNode.id, modelNode);
  }

  // validate after the whole batch: the model must still answer for its binding
  if (changedNodes.size || deletedNodes.length) {
    for (const node of next.nodes) {
      if (node.entityType === 'Metric' && !node.properties?.expression) {
        warnings.push(`${node.id} has no expression after this edit: it cannot answer until one is set`);
      }
    }
  }

  let written = { vertices: 0, edges: 0 };
  if (options.dryRun !== true && (changedNodes.size || changedEdges.size || deletedNodes.length || deletedEdges.length)) {
    const schema = await client.schema(graph);
    if (!schema) throw new Error(`graph '${graph}' has no schema`);
    const vertexBatch = nodeRequests([...changedNodes.values()], schema);
    const edgeBatch = edgeRequests([...changedEdges.values()], schema);
    warnings.push(...vertexBatch.warnings, ...edgeBatch.warnings);
    if (vertexBatch.vertices.length) await client.upsertVertices(graph, vertexBatch.vertices);
    if (edgeBatch.edges.length) await client.upsertEdges(graph, edgeBatch.edges);
    for (const edge of deletedEdges) {
      await client.deleteEdge(graph, { edgeId: edge.id, edgeSchemaName: 'Relation', source: edge.head, destination: edge.tail });
    }
    for (const node of deletedNodes) await client.deleteVertex(graph, node.id);
    written = { vertices: vertexBatch.vertices.length, edges: edgeBatch.edges.length, deletedVertices: deletedNodes.length, deletedEdges: deletedEdges.length };
  }

  return {
    ok: true,
    dryRun: options.dryRun === true,
    model: next,
    audit,
    written,
    warnings: unique(warnings),
    summary: { operations: (operations || []).length, changedNodes: changedNodes.size, changedEdges: changedEdges.size, deletedNodes: deletedNodes.length, deletedEdges: deletedEdges.length },
  };
}
