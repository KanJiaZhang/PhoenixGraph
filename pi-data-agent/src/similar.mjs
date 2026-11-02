/**
 * Vector similarity over the metadata layer, combined with the graph it lives in.
 *
 * Two retrievals answer two different questions, and the case needs both:
 *
 *   vector  - "which definition do I mean?" A word like 收入 is not an id; the nearest metric/dimension/column
 *             by embedding is a candidate, and the score says how close it was.
 *   graph   - "what is that definition made of?" Once a node is named, its relations are facts: the columns it
 *             computes from, the table it reads, the dimension it can be grouped by, the source that defines it.
 *
 * `similarWithGraph` runs the first and then expands through the second, so the answer is a set of definitions
 * *with their lineage*, which is what a data agent needs before it may quote a number. The vector step never
 * decides alone: a match carries its score, and a match with no relations is reported as an isolated candidate.
 */
import { cosine, embed } from './embed.mjs';
import { quote } from './util.mjs';

const SEARCHABLE = ['Metric', 'Dimension', 'Term', 'Column', 'Table', 'Domain'];

/** The text a node is embedded from: its name, what it is called elsewhere, and what it means. */
export function nodeText(node) {
  const properties = node.properties || {};
  return [
    properties.name,
    ...(Array.isArray(properties.alias) ? properties.alias : []),
    properties.description,
    properties.expression,
    properties.dataType,
    properties.tableName,
    properties.unit,
  ].filter(Boolean).join(' · ');
}

/** The embedding of one node (computed, never stored: the same text always hashes to the same vector). */
export function embedNode(node) {
  return { id: node.id, node, text: nodeText(node), vector: embed(nodeText(node)) };
}

/**
 * The nearest nodes to a piece of text.
 *
 * @returns {{query:string, matches:Array<{id:string,entityType:string,name:string,score:number,text:string}>}}
 */
export function similarNodes(model, text, options = {}) {
  const k = Math.max(1, Math.min(50, Number(options.k) || 5));
  const types = options.entityTypes && options.entityTypes.length ? new Set(options.entityTypes) : null;
  const query = embed(text);
  const scored = [];
  for (const node of model.nodes || []) {
    if (types && !types.has(node.entityType)) continue;
    if (node.entityType === 'Model' || node.entityType === 'Source') continue;
    const document = nodeText(node);
    if (!document) continue;
    const base = cosine(query, embed(document));
    // A name or alias that the question literally contains is worth more than a shared character n-gram:
    // "收入怎么算" must prefer the metric called 收入 over every other definition that shares the character.
    const asked = String(text).toLowerCase();
    const names = [node.properties?.name, ...(Array.isArray(node.properties?.alias) ? node.properties.alias : [])]
      .filter(Boolean).map((name) => String(name).toLowerCase());
    const exact = names.some((name) => asked.includes(name) || name.includes(asked));
    const score = Math.min(1, base + (exact ? 0.25 : 0));
    if (score <= 0) continue;
    scored.push({
      id: node.id,
      entityType: node.entityType,
      name: node.properties?.name || node.id,
      score,
      exact,
      text: quote(document, 200),
    });
  }
  scored.sort((left, right) => right.score - left.score || left.id.localeCompare(right.id));
  return { query: String(text), matches: scored.slice(0, k) };
}

/**
 * The subgraph around some nodes: every Relation edge incident to them, walked `hops` times.
 *
 * @returns {{nodes:Array, edges:Array, missing:string[]}}
 */
export function neighbourhood(model, ids, options = {}) {
  const hops = Math.max(0, Math.min(3, options.hops == null ? 1 : Number(options.hops)));
  const relations = options.relations && options.relations.length ? new Set(options.relations) : null;
  const nodes = new Map((model.nodes || []).map((node) => [node.id, node]));
  const found = new Set();
  const missing = [];
  for (const id of ids) {
    if (nodes.has(id)) found.add(id);
    else missing.push(id);
  }
  const edges = [];
  const edgeIds = new Set();
  let frontier = [...found];
  // hop 0 is the direct neighbourhood, so `hops: 1` means one expansion; `hops: 0` means the nodes themselves
  for (let hop = 0; hop < hops && frontier.length; hop += 1) {
    const next = [];
    for (const edge of model.edges || []) {
      if (relations && !relations.has(edge.relation)) continue;
      const touchesHead = frontier.includes(edge.head);
      const touchesTail = frontier.includes(edge.tail);
      if (!touchesHead && !touchesTail) continue;
      if (!edgeIds.has(edge.id)) { edgeIds.add(edge.id); edges.push(edge); }
      for (const id of [edge.head, edge.tail]) {
        if (!found.has(id) && nodes.has(id)) { found.add(id); next.push(id); }
      }
    }
    frontier = next;
  }
  return { nodes: [...found].map((id) => nodes.get(id)), edges, missing };
}

/**
 * The vector match and its graph neighbourhood, which is the shape a data agent answers from: "you probably
 * mean 营收 (score 0.41); it is defined as SUM(revenue) over table/marketing, grouped by dim/渠道 and dim/地区,
 * and the definition is source/metrics.md".
 */
export function similarWithGraph(model, text, options = {}) {
  const asked = similarNodes(model, text, options);
  const wanted = options.expand === false ? [] : asked.matches.map((match) => match.id);
  const subgraph = neighbourhood(model, wanted, { hops: options.hops == null ? 1 : options.hops, relations: options.relations });
  const byId = new Map((model.nodes || []).map((node) => [node.id, node]));
  const matches = asked.matches.map((match) => {
    const incident = subgraph.edges.filter((edge) => edge.head === match.id || edge.tail === match.id);
    return {
      ...match,
      relations: incident.map((edge) => {
        const outgoing = edge.head === match.id;
        const otherId = outgoing ? edge.tail : edge.head;
        return {
          relation: edge.relation,
          direction: outgoing ? 'out' : 'in',
          other: otherId,
          otherName: byId.get(otherId)?.properties?.name || otherId,
          otherType: byId.get(otherId)?.entityType || null,
          evidence: edge.properties?.evidence || null,
        };
      }),
    };
  });
  return {
    ok: true,
    query: asked.query,
    model: model.graph,
    asOf: model.asOf || null,
    matches,
    nodes: subgraph.nodes.map((node) => ({
      id: node.id, entityType: node.entityType, name: node.properties?.name || node.id,
      description: quote(node.properties?.description, 160),
    })),
    edges: subgraph.edges.map((edge) => ({ id: edge.id, head: edge.head, relation: edge.relation, tail: edge.tail })),
    isolated: matches.filter((match) => !match.relations.length).map((match) => match.id),
  };
}

/** The answer as the model sees it. */
export function renderSimilar(bundle) {
  const lines = [];
  lines.push(`query: ${bundle.query}  (model ${bundle.model}${bundle.asOf ? `, as of ${bundle.asOf}` : ''})`);
  if (!bundle.matches.length) {
    lines.push('no node in the model is close to this text - a missing definition is a finding, not a gap to fill');
    return lines.join('\n');
  }
  for (const match of bundle.matches) {
    lines.push(`${match.score.toFixed(3)}  ${match.id}  ${match.name}`);
    lines.push(`       ${match.text}`);
    for (const relation of match.relations.slice(0, 8)) {
      lines.push(`       ${relation.direction === 'out' ? '->' : '<-'} ${relation.relation} ${relation.other} (${relation.otherType || '?'})`);
    }
    if (!match.relations.length) lines.push('       (no relations: an isolated candidate, so nothing behind it can be quoted)');
  }
  if (bundle.isolated.length) lines.push(`isolated: ${bundle.isolated.join(', ')}`);
  return lines.join('\n');
}

export { SEARCHABLE };
