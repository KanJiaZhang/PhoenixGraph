#!/usr/bin/env node
// The triage ranking, as a library: one implementation for the example, the MCP server and the pi extension.
//
//   rank({ relations, entities, seeds, asOf })   pure: the score arithmetic over an artifact, no I/O
//   triage({ endpoint, graph, symptom, asOf })   the two calls that feed it (find-entities, then the artifact)
//   render(result)                               the report a model or a human reads
//
// The point of the split: the ranking is a *judgement* (weights, tie-breaks, what counts as a precedent), so it
// lives in one file that can be read and argued with. Three copies of it - an example, a server, an extension -
// would be three answers that agree only until someone edits one.
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const INFRA = new Set(['affects', 'depends_on', 'uses', 'deployed_on', 'part_of']);

/** Whether a fact holds at an instant; a fact with no window holds always. */
export function validAt(fact, asOf) {
  if (asOf == null) return true;
  const from = fact.validFrom == null ? null : Number(fact.validFrom);
  const to = fact.validTo == null ? null : Number(fact.validTo);
  return (from === null || asOf >= from) && (to === null || asOf < to);
}

/** Shortest infrastructure path from `from` to any of `targets`, at most `maxHops` hops. */
function reachable(neighbours, from, targets, maxHops) {
  const found = new Map();
  const seen = new Set([from]);
  let frontier = [{ node: from, hops: 0, path: [] }];
  while (frontier.length) {
    const next = [];
    for (const { node, hops, path } of frontier) {
      if (targets.has(node) && node !== from) found.set(node, { hops, path });
      if (hops === maxHops) continue;
      for (const edge of neighbours.get(node) || []) {
        if (seen.has(edge.to)) continue;
        seen.add(edge.to);
        next.push({ node: edge.to, hops: hops + 1, path: [...path, edge] });
      }
    }
    frontier = next;
  }
  return found;
}

function adjacency(relations) {
  const neighbours = new Map();
  for (const relation of relations) {
    if (!INFRA.has(relation.relation)) continue;
    if (!neighbours.has(relation.head)) neighbours.set(relation.head, []);
    if (!neighbours.has(relation.tail)) neighbours.set(relation.tail, []);
    neighbours.get(relation.head).push({ to: relation.tail, relation });
    neighbours.get(relation.tail).push({ to: relation.head, relation, reversed: true });
  }
  return neighbours;
}

const deployNumber = (name) => {
  const match = /deploy-(\d+)/.exec(String(name || ''));
  return match ? Number(match[1]) : null;
};

/**
 * Ranks the causes a symptom supports.
 *
 * @param {object} input
 * @param {Array} input.relations the artifact's relations (already filtered to the instant, optionally)
 * @param {Array<string|{name:string}>} input.seeds the entities the symptom names
 * @param {Map|object} [input.types] an entity name to type map (a Metric seed names a component)
 * @returns {{ seeds: string[], observed: object, ranked: Array, verdict: object|null, refusal?: string }}
 */
export function rank({ relations = [], seeds = [], types = {} }) {
  const seedNames = seeds.map((seed) => (typeof seed === 'string' ? seed : seed.name));
  const typeOf = (name) => (typeof types.get === 'function' ? types.get(name) : types[name]);

  const effectsOf = new Map(); // kept for callers that ask what a cause did
  for (const relation of relations) {
    if (relation.relation !== 'caused') continue;
    if (!effectsOf.has(relation.head)) effectsOf.set(relation.head, []);
    effectsOf.get(relation.head).push(relation.tail);
  }

  // Which components the named metrics watch: a metric affects the component it observes, so a spike names a
  // suspect rather than a number.
  const observed = {};
  for (const relation of relations) {
    if (relation.relation !== 'affects') continue;
    if (!seedNames.includes(relation.head) || typeOf(relation.head) !== 'Metric') continue;
    (observed[relation.tail] ||= []).push(relation.head);
  }
  const observedComponents = new Set(Object.keys(observed));
  const observedMetrics = new Set(seedNames.filter((name) => typeOf(name) === 'Metric'));

  const effectTargets = new Map();
  for (const relation of relations) {
    if (relation.relation !== 'affects') continue;
    if (!effectTargets.has(relation.head)) effectTargets.set(relation.head, []);
    effectTargets.get(relation.head).push(relation.tail);
  }

  const causal = relations.filter((relation) => relation.relation === 'caused');
  if (!causal.length) {
    return { seeds: seedNames, observed, ranked: [], verdict: null, refusal: 'the corpus has no causal edge to rank' };
  }
  const neighbours = adjacency(relations);
  const deploys = causal.map((relation) => deployNumber(relation.head)).filter((number) => number !== null);
  const newest = deploys.length ? Math.max(...deploys) : null;

  const ranked = causal.map((relation) => {
    const cause = relation.head;
    const effect = relation.tail;
    const reasons = [];
    let score = 0;

    const targets = effectTargets.get(effect) || [];
    const onComponent = targets.filter((target) => observedComponents.has(target));
    const onMetric = targets.filter((target) => observedMetrics.has(target));
    if (onComponent.length) {
      score += 6;
      reasons.push(`the failure mode acts on ${[...new Set(onComponent)].join(', ')}, which the symptom's metrics observe (+6)`);
    } else if (onMetric.length) {
      score += 4;
      reasons.push(`the failure mode moves ${[...new Set(onMetric)].join(', ')} (+4)`);
    } else if (targets.length) {
      score -= 3;
      reasons.push(`the failure mode acts on ${[...new Set(targets)].join(', ')}, which shows no signal here (-3)`);
    }

    const touched = relations.filter((other) => other.relation === 'affects' && other.head === cause
      && observedComponents.has(other.tail)).map((other) => other.tail);
    if (touched.length) {
      score += Math.min(6, 3 * touched.length);
      reasons.push(`a change window that touches ${[...new Set(touched)].join(' and ')} (+${Math.min(6, 3 * touched.length)})`);
    }

    const reached = reachable(neighbours, cause, observedComponents, 2);
    if (reached.size) {
      score += Math.min(4, 2 * reached.size);
      reasons.push(`reaches ${[...reached.keys()].join(' and ')} within ${Math.max(...[...reached.values()].map((r) => r.hops))} hop(s) (+${Math.min(4, 2 * reached.size)})`);
    }

    // A precedent is a documented incident that failed the same way on the same component: the same shape of
    // failure on the same thing. The incident number comes from the graph (`INC-1042 reports ...`), so the claim
    // is checkable; an effect with no incident behind it is not a precedent, it is another guess.
    const myTargets = new Set(relations.filter((edge) => edge.relation === 'affects' && edge.head === effect
      && observedComponents.has(edge.tail)).map((edge) => edge.tail));
    const precedents = [];
    for (const other of causal) {
      if (other === relation) continue;
      const shared = relations.filter((edge) => edge.relation === 'affects' && edge.head === other.tail
        && myTargets.has(edge.tail));
      if (!shared.length) continue;
      const incidents = relations.filter((edge) => edge.relation === 'reported' && edge.tail === other.tail)
        .map((edge) => edge.head);
      if (!incidents.length) continue;
      precedents.push({ cause: other.head, effect: other.tail, component: shared[0].tail, incidents });
    }
    if (precedents.length) {
      score += 2;
      const first = precedents[0];
      reasons.push(`${first.incidents.join('/')} (${first.cause} -> ${first.effect}) already failed on ${first.component} (+2)`);
    }

    const number = deployNumber(cause);
    if (number !== null && number === newest) {
      score += 2;
      reasons.push('the newest change in the corpus (+2)');
    } else if (number !== null && number === newest - 1) {
      score += 1;
      reasons.push('the second-newest change in the corpus (+1)');
    }

    return {
      cause,
      effect,
      score,
      reasons,
      precedents,
      reaches: [...reached.keys()],
      hops: reached.size ? Math.max(...[...reached.values()].map((entry) => entry.hops)) : 0,
      relation,
      edges: [relation, ...relations.filter((edge) => edge.relation === 'affects' && edge.head === effect)],
    };
  }).sort((a, b) => b.score - a.score || (deployNumber(b.cause) ?? 0) - (deployNumber(a.cause) ?? 0));

  const [best, second] = ranked;
  const unexplained = [...observedComponents].filter((component) => !best.reaches.includes(component));
  const checks = [];
  if (relations.some((edge) => edge.relation === 'affects' && edge.head === best.cause && observedComponents.has(edge.tail))) {
    checks.push(`${best.cause} did not actually ship the change it claims (check the release diff, not the ticket)`);
  }
  if (observedComponents.size) {
    checks.push(`the ${[...observedComponents].join(' / ')} signal started before ${best.cause} landed (order the metrics by minute)`);
  }
  if (second) {
    checks.push(`the errors concentrate on a code path that never touches ${best.effect} (then #2 explains them better)`);
  }

  return {
    seeds: seedNames,
    observed,
    ranked,
    verdict: {
      cause: best.cause,
      effect: best.effect,
      score: best.score,
      runnerUp: second ? { cause: second.cause, effect: second.effect, score: second.score } : null,
      unexplainedComponents: unexplained,
      checks,
    },
  };
}

/**
 * The two calls behind the ranking: which entities the symptom names (name matches only - a vector neighbour is
 * a guess), then the artifact, filtered to the instant.
 */
export async function triage({ endpoint, graph, symptom, k = 20, asOf = null, fetchImpl = fetch }) {
  const base = String(endpoint || 'http://localhost:8081').replace(/\/+$/, '');
  const post = async (path, body) => {
    const response = await fetchImpl(`${base}${path}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json; charset=utf-8' },
      body: JSON.stringify(body),
    });
    const text = await response.text();
    if (!response.ok) throw new Error(`${path} -> ${response.status}: ${text.slice(0, 200)}`);
    return text ? JSON.parse(text) : null;
  };
  // the API takes an ISO instant (its DTO field is a string); a client that computed millis sends the same moment
  const found = await post('/api/v1/agent/find-entities',
    { graph, question: symptom, k, asOf: asOf == null ? undefined : new Date(asOf).toISOString() });
  const seeds = (found.entities || []).filter((entity) => entity.via === 'name');
  if (!seeds.length) {
    return { graph, symptom, asOf, refusal: 'no entity in the graph is named by this symptom', seeds: [], observed: {}, ranked: [], verdict: null };
  }
  const indexResponse = await fetchImpl(`${base}/api/v1/agent/index/${encodeURIComponent(graph)}`);
  if (!indexResponse.ok) throw new Error(`the artifact of '${graph}' is not available (${indexResponse.status})`);
  const artifact = await indexResponse.json();
  const relations = (artifact.relations || []).filter((relation) => validAt(relation, asOf));
  const types = new Map((artifact.entities || []).map((entity) => [entity.name, entity.type]));
  for (const seed of seeds) if (seed.type) types.set(seed.name, seed.type);
  return { graph, symptom, asOf, ...rank({ relations, seeds, types }) };
}

/** The report: the ranking with the sentence behind each edge, the verdict, and what would overturn it. */
export function render(result) {
  const lines = [];
  const stamp = result.asOf ? ` (as of ${new Date(result.asOf).toISOString().slice(0, 16).replace('T', ' ')} UTC)` : '';
  lines.push(`triage for: ${result.symptom}${stamp}`);
  if (result.refusal) {
    lines.push(`refused: ${result.refusal}. Do not name a cause; ask for the component to be ingested first.`);
    return lines.join('\n');
  }
  const observed = Object.entries(result.observed || {});
  if (observed.length) {
    lines.push(`observed components: ${observed.map(([component, metrics]) => `${component} (${metrics.join(', ')})`).join('; ')}`);
  }
  for (const [index, candidate] of result.ranked.entries()) {
    lines.push(`${index + 1}. ${candidate.cause} -> ${candidate.effect}   score ${candidate.score}`);
    for (const reason of candidate.reasons) lines.push(`   - ${reason}`);
    const sentence = candidate.relation && candidate.relation.sentence
      ? `  "${String(candidate.relation.sentence).trim()}"` : '';
    lines.push(`   ${candidate.cause} -${candidate.relation?.relation || 'caused'}-> ${candidate.effect}${sentence}`);
  }
  if (result.verdict) {
    lines.push(`most likely: ${result.verdict.cause} (score ${result.verdict.score}) through ${result.verdict.effect}`);
    if (result.verdict.runnerUp) {
      lines.push(`runner-up: ${result.verdict.runnerUp.cause} (score ${result.verdict.runnerUp.score})`);
    }
    if (result.verdict.checks?.length) {
      lines.push('what would overturn it:');
      for (const check of result.verdict.checks) lines.push(`   - ${check}`);
    }
  }
  lines.push('quote the sentences and say which cause you mean; if the ranking is thin, say so.');
  return lines.join('\n');
}

// ---- CLI: the same result a human can read -------------------------------------------------------------------
// Only when this file *is* the entry point: examples/incident-triage/triage.mjs imports it, and that path also
// ends with triage.mjs (a suffix test would have run this CLI inside the example).
const invokedDirectly = process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url;
if (invokedDirectly) {
  const argv = process.argv.slice(2);
  const value = (name) => {
    const index = argv.indexOf(`--${name}`);
    return index < 0 ? undefined : argv[index + 1];
  };
  const graph = value('graph') || process.env.PHOENIXGRAPH_TRIAGE_GRAPH || 'incident-triage';
  const symptom = value('symptom');
  if (!symptom) {
    console.error('usage: node tools/triage.mjs --symptom "..." [--graph incident-triage] [--as-of 2026-10-21]');
    process.exit(2);
  }
  const asOf = value('as-of') ? Date.parse(value('as-of').length === 10 ? `${value('as-of')}T00:00:00Z` : value('as-of')) : null;
  const result = await triage({ endpoint: process.env.PHOENIXGRAPH_API, graph, symptom, asOf });
  console.log(argv.includes('--json') ? JSON.stringify(result, null, 2) : render(result));
}
