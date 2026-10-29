#!/usr/bin/env node
// Incident triage over a PhoenixGraph graph: given a fault symptom, rank the causes the graph can support.
//
//   node examples/incident-triage/triage.mjs                    # the symptom in symptom.txt
//   node examples/incident-triage/triage.mjs --symptom "..."     # your own symptom
//   node examples/incident-triage/triage.mjs --reingest          # rebuild the graph from documents/
//
// What it does, and what it deliberately does not:
//
//   * `find_entities` (the agent tool) decides which entities the symptom *names*. That is the same call an
//     agent makes, and it is why a symptom that names nothing gets a refusal instead of a guess.
//   * A metric names the component it watches (`redis-cpu-percent affects redis-cache`), so the metrics in the
//     symptom turn into the set of *observed components*. A cause is interesting when it reaches one.
//   * The ranking is arithmetic over the graph, printed line by line, with the sentence behind every edge.
//     There is no model here and no hidden weight: you can disagree with a weight and re-run it.
//   * The answer is a hypothesis with its evidence, the runner-up, and the checks that would flip it. A graph
//     cannot know that a deploy was innocent, so it does not claim to.
import { readFileSync, unlinkSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DEFAULT_GRAPH, dictionary, documents } from './corpus.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '../..');
const API = (process.env.PHOENIXGRAPH_API || 'http://localhost:8081').replace(/\/+$/, '');
const GRAPH = process.env.PHOENIXGRAPH_TRIAGE_GRAPH || DEFAULT_GRAPH;
const args = process.argv.slice(2);
const flag = (name) => args.includes(`--${name}`);
const value = (name) => {
  const index = args.indexOf(`--${name}`);
  return index < 0 ? undefined : args[index + 1];
};

const AS_OF = value('as-of') || process.env.PHOENIXGRAPH_AS_OF;

/** Whether a fact holds at an instant; a fact with no window holds always. */
const validAt = (fact, instant) => {
  if (!instant) return true;
  const from = fact.validFrom === undefined || fact.validFrom === null ? null : Number(fact.validFrom);
  const to = fact.validTo === undefined || fact.validTo === null ? null : Number(fact.validTo);
  return (from === null || instant >= from) && (to === null || instant < to);
};

const instantOf = (written) => {
  if (!written) return undefined;
  const text = String(written).trim();
  if (/^\d+$/.test(text)) return Number(text);
  const iso = text.length === 10 ? text + 'T00:00:00Z' : text;
  const millis = Date.parse(iso);
  if (Number.isNaN(millis)) throw new Error(`--as-of is not a date: ${written}`);
  return millis;
};

const SYMPTOM = value('symptom')
  || process.env.PHOENIXGRAPH_SYMPTOM
  || readFileSync(join(HERE, 'symptom.txt'), 'utf8').replace(/\s+/g, ' ').trim();

// Two ways to read this program: the report (what a human on call wants) and --json (what a check asserts on).
// The prose is collected rather than printed so both come out of the same computation - a verification script
// that re-implemented the ranking would verify its own copy of it.
const JSON_MODE = flag('json');
const prose = [];
const log = (...parts) => prose.push(parts.join(' '));
const finish = (report) => {
  if (JSON_MODE) globalThis.console.log(JSON.stringify(report, null, 2));
  else globalThis.console.log(prose.join('\n'));
  process.exit(0);
};

// ---- plumbing -------------------------------------------------------------------------------------------
const call = async (method, path, body) => {
  const response = await fetch(API + path, {
    method,
    headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json; charset=utf-8' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let payload = text;
  try { payload = text ? JSON.parse(text) : null; } catch { /* keep the raw text */ }
  if (!response.ok) {
    const detail = payload && typeof payload === 'object' ? (payload.error || payload.message) : text;
    throw new Error(`${method} ${path} -> ${response.status}: ${detail || 'no body'}`);
  }
  return payload;
};

async function ensureGraph() {
  const documentsToIngest = documents();
  const gazetteer = dictionary();
  if (flag('reingest')) {
    // A rebuild starts from nothing. An artifact left on disk is merged into the new index - chunk ids and all -
    // and those chunks are not in the graph the ingest is about to write, which fails the mention edges. Removing
    // it is what "rebuild" has to mean; the corpus is the source, the artifact is output.
    try { unlinkSync(join(REPO, `${GRAPH}.agent.json`)); } catch { /* there was nothing to remove */ }
  }
  if (!flag('reingest')) {
    try {
      const known = await call('GET', `/api/v1/agent/index/${GRAPH}`);
      // an artifact left over from a smaller corpus would silently answer half the question
      if ((known.relations || []).length >= 40) return { artifact: known, ingested: false };
    } catch { /* not ingested yet: fall through */ }
  }
  const result = await call('POST', '/api/v1/agent/ingest', {
    graph: GRAPH, apply: true, rulePack: 'incident', dictionary: gazetteer,
    description: 'Ops corpus: RCA documents, topology, hosts, metrics and change windows, for incident triage.',
    documents: documentsToIngest,
  });
  if (!result.applied) throw new Error(`ingestion did not write: ${(result.notes || []).join('; ')}`);
  return { artifact: await call('GET', `/api/v1/agent/index/${GRAPH}`), ingested: true };
}

// ---- the graph as a plain edge list ----------------------------------------------------------------------
const INFRA = new Set(['affects', 'depends_on', 'uses', 'deployed_on', 'part_of']);

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

const show = (relation) => `${relation.head} -${relation.relation}-> ${relation.tail}`;
const quote = (relation) => `"${(relation.sentence || '').trim()}"`;

// ---- the triage ------------------------------------------------------------------------------------------
async function main() {
  const { artifact, ingested } = await ensureGraph();
  // The instant this run answers as of: the artifact is filtered here exactly as the server filters it, so the
  // ranking and the evidence cannot disagree about what existed.
  const asOf = instantOf(AS_OF);
  const all = artifact.relations || [];
  const relations = asOf === undefined ? all : all.filter((relation) => validAt(relation, asOf));
  const entities = asOf === undefined ? artifact.entities || []
    : (artifact.entities || []).filter((entity) => validAt(entity, asOf));
  const typeOf = new Map(entities.map((entity) => [entity.name, entity.type]));

  log('PhoenixGraph incident triage');
  log(`  api:      ${API}`);
  log(`  graph:    ${GRAPH} (${entities.length} entities, ${relations.length} relations,`
    + ` provenanceRate ${artifact.quality?.provenanceRate ?? '?'})${ingested ? ' - ingested from documents/ just now' : ''}`
    + (asOf === undefined ? '' : ` as of ${new Date(asOf).toISOString().slice(0, 16).replace('T', ' ')} UTC`));
  const windowed = all.filter((relation) => relation.validFrom != null || relation.validTo != null).length;
  log(`            ${windowed} of ${all.length} relations carry a validity window; the rest hold always`);
  log(`  symptom:  ${SYMPTOM}`);
  log('');

  // 1. what the symptom names -----------------------------------------------------------------------------
  const found = await call('POST', '/api/v1/agent/find-entities',
    { graph: GRAPH, question: SYMPTOM, k: 20, asOf: AS_OF });
  const named = (found.entities || []).filter((entity) => entity.via === 'name');
  log('1. What the symptom names (find_entities, via=name; vector-only matches are not used as seeds)');
  if (named.length === 0) {
    log('   nothing: no entity in the graph is named by this symptom.');
    log('');
    log('   Refusing to name a cause. This is the same rule the tools apply to an agent: no name match and');
    log('   no relation is a finding, not a prompt to guess. Add the component to the corpus first.');
    finish({ graph: GRAPH, symptom: SYMPTOM, refusal: 'no entity in the graph is named by this symptom', seeds: [], observed: {}, ranked: [] });
  }
  for (const entity of named) log(`   ${entity.name} (${entity.type ?? typeOf.get(entity.name) ?? '?'})`);
  const seeds = new Set(named.map((entity) => entity.name));

  // 2. which components the named metrics watch -----------------------------------------------------------
  const metricSeeds = named.filter((entity) => (entity.type ?? typeOf.get(entity.name)) === 'Metric');
  const observed = new Map(); // component -> metrics that watch it
  for (const relation of relations) {
    if (relation.relation !== 'affects' || !metricSeeds.some((metric) => metric.name === relation.head)) continue;
    if (!observed.has(relation.tail)) observed.set(relation.tail, []);
    observed.get(relation.tail).push(relation.head);
  }
  const observedComponents = new Set(observed.keys());
  log('');
  log('2. The components those metrics watch (a metric names its component, so a spike names a suspect)');
  if (observedComponents.size === 0) log('   none: the symptom names no metric, so nothing anchors the search to a component.');
  for (const [component, metrics] of observed) {
    log(`   ${component}: ${metrics.join(', ')}`);
  }

  // 3. candidate causes, scored ---------------------------------------------------------------------------
  const neighbours = adjacency(relations);
  const causal = relations.filter((relation) => relation.relation === 'caused');
  const deployNumber = (name) => {
    const match = /deploy-(\d+)/.exec(name);
    return match ? Number(match[1]) : null;
  };
  const deploys = causal.map((relation) => deployNumber(relation.head)).filter((number) => number !== null);
  const newest = deploys.length ? Math.max(...deploys) : null;

  const effectsOf = new Map();
  for (const relation of causal) if (!effectsOf.has(relation.tail)) effectsOf.set(relation.tail, []);
  for (const relation of causal) effectsOf.get(relation.tail).push(relation.head);

  // The metrics the symptom names, as opposed to the components they watch: a failure mode that moved
  // checkout-p99-latency is a candidate even though the *component* in the symptom is the checkout service.
  const observedMetrics = new Set(metricSeeds.map((metric) => metric.name));
  const effectTargets = new Map();
  for (const relation of relations) {
    if (relation.relation !== 'affects') continue;
    if (!effectTargets.has(relation.head)) effectTargets.set(relation.head, []);
    effectTargets.get(relation.head).push(relation.tail);
  }

  const candidates = causal.map((relation) => {
    const cause = relation.head;
    const effect = relation.tail;
    const reasons = [];
    let score = 0;

    // Does the failure mode act on the thing that is broken, on a metric that moved, or on something the
    // symptom says is fine? That is the question that separates the cache story from the deploy story when both
    // touch the checkout service.
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

    // A precedent is a *documented* incident that failed the same way on the same component: the same shape of
    // failure on the same thing, which is what an on-call engineer means by "we have seen this before". The
    // incident number comes from the graph (`INC-1042 reports cache-eviction-storm`), so the claim is checkable;
    // an effect with no incident behind it is not a precedent, it is another guess.
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

    return { relation, cause, effect, score, reasons, reached, precedents };
  }).sort((a, b) => b.score - a.score || (deployNumber(b.cause) ?? 0) - (deployNumber(a.cause) ?? 0));

  log('');
  log('3. Candidate causes the graph supports, ranked');
  if (!candidates.length) {
    log('   none: the corpus has no causal edge at all, so there is nothing to rank.');
    finish({ graph: GRAPH, symptom: SYMPTOM, refusal: 'the corpus has no causal edge to rank', seeds: [...seeds], observed: Object.fromEntries(observed), ranked: [] });
  }
  for (const [index, candidate] of candidates.entries()) {
    log(`   #${index + 1} ${candidate.cause} -> ${candidate.effect}   score ${candidate.score}`);
    for (const reason of candidate.reasons) log(`       ${reason}`);
    log(`       ${show(candidate.relation)}   ${quote(candidate.relation)}`);
    const support = relations.filter((other) => other.relation === 'affects' && other.head === candidate.effect);
    for (const edge of support) log(`       ${show(edge)}   ${quote(edge)}`);
  }

  // 4. the verdict, with what would overturn it -----------------------------------------------------------
  const [best, second] = candidates;
  log('');
  log('4. Most likely cause');
  log(`   ${best.cause} (score ${best.score}), through ${best.effect}`);
  const withWindow = candidates.filter((candidate) => relations.some((edge) => edge.relation === 'affects'
    && edge.head === candidate.cause && observedComponents.has(edge.tail)));
  const explanation = relations.filter((other) => other.relation === 'affects' && other.head === best.effect
    && observedComponents.has(other.tail)).map((other) => other.tail);
  if (explanation.length && withWindow.length === 1) {
    log(`   It is the only candidate with a change window that touches ${[...new Set(explanation)].join(' and ')},`
      + ' which is where the symptom\'s own metrics point.');
  }
  if (best.precedents.length) {
    const first = best.precedents[0];
    log(`   The shape is not new: ${first.incidents.join('/') || first.cause} was ${first.effect} on ${first.component}.`
      + ' That is the argument for treating it as the same class of failure first.');
  }
  const unexplained = [...observedComponents].filter((component) => !best.reached.has(component));
  if (unexplained.length) log(`   It does not touch ${unexplained.join(', ')}: if that is where the errors really are, look at #2.`);
  if (second) {
    log(`   Runner-up: ${second.cause} -> ${second.effect} (score ${second.score})`
      + `${second.reasons.length ? ` - ${second.reasons[0]}` : ''}.`);
  }

  log('');
  log('5. What would overturn this, and what the graph cannot say');
  const checks = [];
  const changeWindow = relations.filter((other) => other.relation === 'affects' && other.head === best.cause
    && observedComponents.has(other.tail));
  if (changeWindow.length) checks.push(`${best.cause} did not actually ship the change it claims (check the release diff, not the ticket)`);
  checks.push(`the ${[...observedComponents].join(' / ')} signal started before ${best.cause} landed (order the metrics by minute)`);
  if (second) checks.push(`the errors concentrate on a code path that never touches ${best.effect} (then #2 explains them better)`);
  for (const check of checks) log(`   - ${check}`);
  log(`   The corpus has no approval, ownership or rollback record for ${best.cause} unless one was ingested:`);
  log('   ask "who approved it" and the tools return no name match - a refusal, not a guess.');
  log('');
  log(`   evidence: ${candidates.length} causal edges, ${relations.filter((edge) => edge.relation === 'affects').length} `
    + `affects edges, ${entities.length} entities; every sentence above is in the artifact`
    + ` (${GRAPH}.agent.json) with its chunk.`);

  finish({
    graph: GRAPH,
    symptom: SYMPTOM,
    seeds: named.map((entity) => ({ name: entity.name, type: entity.type ?? typeOf.get(entity.name) ?? null })),
    observed: Object.fromEntries(observed),
    ranked: candidates.map((candidate) => ({
      cause: candidate.cause,
      effect: candidate.effect,
      score: candidate.score,
      reasons: candidate.reasons,
      edges: [candidate.relation, ...relations.filter((edge) => edge.relation === 'affects' && edge.head === candidate.effect)]
        .map((edge) => ({ head: edge.head, relation: edge.relation, tail: edge.tail, sentence: edge.sentence, cite: edge.cite })),
      reaches: [...candidate.reached.keys()],
      precedents: candidate.precedents,
    })),
    verdict: {
      cause: best.cause,
      effect: best.effect,
      score: best.score,
      runnerUp: second ? { cause: second.cause, effect: second.effect, score: second.score } : null,
      unexplainedComponents: [...observedComponents].filter((component) => !best.reached.has(component)),
      checks,
    },
  });
}

main().catch((error) => {
  console.error(`\n${error.message}\n`);
  console.error('Is the service running? tools/start-phoenixgraph.sh --no-build');
  process.exit(1);
});
