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
import { rank } from '../../tools/triage.mjs';

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

/** The seeds, deduplicated by name and in the order find-entities ranked them. */
const orderedSeeds = (named) => {
  const seen = new Set();
  return named.filter((entity) => (seen.has(entity.name) ? false : seen.add(entity.name)));
};

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
  const seeds = orderedSeeds(named);

  // 2. what the scoring sees: a metric names the component it observes, so a spike names a suspect ---------
  // 3. the ranking: the weights live in tools/triage.mjs - one implementation for this case, the MCP server and
  // the pi extension, so the three cannot quietly disagree about what "most likely" means.
  const result = rank({ relations, seeds: named, types: typeOf });

  log('');
  log('2. The components those metrics watch (a metric names its component, so a spike names a suspect)');
  const observed = Object.entries(result.observed);
  if (!observed.length) log('   none: the symptom names no metric, so nothing anchors the search to a component.');
  for (const [component, metrics] of observed) log(`   ${component}: ${metrics.join(', ')}`);

  log('');
  log('3. Candidate causes the graph supports, ranked');
  for (const [index, candidate] of result.ranked.entries()) {
    log(`   #${index + 1} ${candidate.cause} -> ${candidate.effect}   score ${candidate.score}`);
    for (const reason of candidate.reasons) log(`       ${reason}`);
    for (const edge of candidate.edges) log(`       ${show(edge)}   ${quote(edge)}`);
  }

  // 4. the verdict, with what would overturn it -----------------------------------------------------------
  const { verdict } = result;
  log('');
  log('4. Most likely cause');
  log(`   ${verdict.cause} (score ${verdict.score}), through ${verdict.effect}`);
  if (verdict.runnerUp) {
    const runnerUp = result.ranked[1];
    const reason = runnerUp && runnerUp.reasons.length ? ` - ${runnerUp.reasons[0]}` : '';
    log(`   Runner-up: ${verdict.runnerUp.cause} -> ${verdict.runnerUp.effect} (score ${verdict.runnerUp.score})${reason}.`);
  }
  if (verdict.unexplainedComponents.length) {
    log(`   It does not touch ${verdict.unexplainedComponents.join(', ')}: if that is where the errors really are, look at #2.`);
  }

  log('');
  log('5. What would overturn this, and what the graph cannot say');
  for (const check of verdict.checks) log(`   - ${check}`);
  log(`   The corpus has no approval, ownership or rollback record for ${verdict.cause} unless one was ingested:`);
  log('   ask "who approved it" and the tools return no name match - a refusal, not a guess.');
  log('');
  log(`   evidence: ${result.ranked.length} causal edges, ${relations.filter((edge) => edge.relation === 'affects').length} `
    + `affects edges, ${entities.length} entities; every sentence above is in the artifact`
    + ` (${GRAPH}.agent.json) with its chunk.`);

  finish({
    graph: GRAPH,
    symptom: SYMPTOM,
    ...(asOf === undefined ? {} : { asOf }),
    seeds: seeds.map((entity) => ({ name: entity.name, type: entity.type ?? typeOf.get(entity.name) ?? null })),
    observed: result.observed,
    ranked: result.ranked,
    verdict: result.verdict,
  });
}

main().catch((error) => {
  console.error(`\n${error.message}\n`);
  console.error('Is the service running? tools/start-phoenixgraph.sh --no-build');
  process.exit(1);
});
