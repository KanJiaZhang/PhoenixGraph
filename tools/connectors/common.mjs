// Shared helpers for the document connectors. No dependencies: Node 18+ only.

export const stripHtml = (html) => String(html || '')
  .replace(/<script[\s\S]*?<\/script>/gi, ' ')
  .replace(/<style[\s\S]*?<\/style>/gi, ' ')
  .replace(/<\/(p|div|li|h[1-6]|tr)>/gi, '\n')
  .replace(/<br\s*\/?>/gi, '\n')
  .replace(/<[^>]+>/g, ' ')
  .replace(/&nbsp;/gi, ' ')
  .replace(/&amp;/gi, '&')
  .replace(/&lt;/gi, '<')
  .replace(/&gt;/gi, '>')
  .replace(/&quot;/gi, '"')
  .replace(/&#39;/gi, "'")
  .replace(/[ \t]+/g, ' ')
  .replace(/\n{3,}/g, '\n\n')
  .trim();

export const titleOf = (text, fallback) => {
  const heading = /^#\s+(.+)$/m.exec(text || '');
  return heading ? heading[1].trim() : fallback;
};

export const percent = (value) => (value === undefined || value === null
  ? 'n/a'
  : `${(Number(value) * 100).toFixed(0)}%`);

const apiOf = (value) => (value || process.env.PHOENIXGRAPH_API || 'http://localhost:8081').replace(/\/+$/, '');

export async function postIngest({
  api,
  apiKey,
  graph,
  rulePack = 'sanguo',
  description,
  documents,
  dictionary = {},
  aliases = {},
  gold = [],
  apply = false,
}) {
  const headers = { Accept: 'application/json', 'Content-Type': 'application/json; charset=utf-8' };
  const key = apiKey || process.env.PHOENIXGRAPH_API_KEY || '';
  if (key) headers.Authorization = `Bearer ${key}`;
  const response = await fetch(`${apiOf(api)}/api/v1/agent/ingest`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      graph,
      description: description || `Ingested from ${documents.length} connected document(s)`,
      documents,
      dictionary,
      aliases,
      gold,
      apply,
      rulePack,
    }),
  });
  const text = await response.text();
  let payload;
  try {
    payload = text ? JSON.parse(text) : {};
  } catch {
    throw new Error(`The service answered ${response.status} with a non-JSON body: ${text.slice(0, 300)}`);
  }
  if (!response.ok) {
    const error = new Error(`Ingestion failed (${response.status}): ${payload.error || text}`);
    error.payload = payload;
    throw error;
  }
  return payload;
}

export function printIngestResult(result, { target, graph, rulePack, apply }) {
  const quality = result.quality || {};
  console.log('PhoenixGraph document ingestion');
  if (target) console.log(`  source:    ${target}`);
  console.log(`  graph:     ${graph}`);
  console.log(`  rule pack: ${rulePack}`);
  console.log(`  mode:      ${apply ? 'write' : 'dry run (nothing is written)'}`);
  console.log('\nExtraction');
  console.log(`  entities:  ${result.entities}`);
  console.log(`  relations: ${result.relations}`);
  console.log(`  provenance ${percent(quality.provenanceRate)} · type agreement ${percent(quality.typeAgreement)} · linked ${percent(quality.entityLinkRate)}`);
  if (quality.mergedEntities?.length) console.log(`  merged:    ${quality.mergedEntities.map((item) => `${item.canonical} <- ${item.aliases.join(', ')}`).join('; ')}`);
  if (quality.mergeCandidates?.length) console.log(`  candidates: ${quality.mergeCandidates.map((item) => `${item.left} ~ ${item.right} (${item.reason})`).join('; ')}`);
  if (quality.gold) {
    console.log(`  against gold: precision ${percent(quality.gold.precision)}, recall ${percent(quality.gold.recall)}`);
  }
  if (result.applied) {
    console.log('\nWritten');
    console.log(`  vertices: ${result.vertices}`);
    console.log(`  edges:    ${result.edges}`);
    console.log(`  artifact: ${result.indexFile || '(none)'}`);
  } else {
    console.log('\nDry run: nothing was written. Re-run with --apply to build the graph.');
  }
  for (const note of result.notes || []) console.log(`  note: ${note}`);
}

export function parseCommon(argv, defaults = {}) {
  const options = {
    graph: defaults.graph || 'docs-demo',
    rulePack: defaults.rulePack || 'sanguo',
    dictionary: defaults.dictionary || null,
    aliases: defaults.aliases || null,
    gold: defaults.gold || null,
    apply: false,
    api: process.env.PHOENIXGRAPH_API || 'http://localhost:8081',
    apiKey: process.env.PHOENIXGRAPH_API_KEY || '',
  };
  const rest = [];
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const value = () => {
      const next = argv[i + 1];
      if (!next || next.startsWith('--')) throw new Error(`${arg} needs a value`);
      i += 1;
      return next;
    };
    if (arg === '--graph') options.graph = value();
    else if (arg === '--rule-pack') options.rulePack = value();
    else if (arg === '--dictionary') options.dictionary = value();
    else if (arg === '--aliases') options.aliases = value();
    else if (arg === '--gold') options.gold = value();
    else if (arg === '--api') options.api = value().replace(/\/+$/, '');
    else if (arg === '--api-key') options.apiKey = value();
    else if (arg === '--apply') options.apply = true;
    else if (arg === '--dry-run') options.apply = false;
    else rest.push(arg);
  }
  return { options, rest };
}
