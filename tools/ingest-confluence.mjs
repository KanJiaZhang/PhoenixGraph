#!/usr/bin/env node
// Ingest a Confluence space through the agent API.
//
//   CONFLUENCE_BASE_URL=https://acme.atlassian.net/wiki \
//   CONFLUENCE_SPACE=ENG CONFLUENCE_EMAIL=me@acme.com CONFLUENCE_TOKEN=... \
//   node tools/ingest-confluence.mjs --graph confluence-demo --rule-pack incident --apply
import { readFileSync } from 'node:fs';
import { parseCommon, postIngest, printIngestResult } from './connectors/common.mjs';
import { confluenceDocuments } from './connectors/confluence.mjs';

const usage = () => {
  console.log(`Usage: node tools/ingest-confluence.mjs [options]

Connection (or the environment variables in parentheses):
  --base <url>        wiki root, e.g. https://acme.atlassian.net/wiki (CONFLUENCE_BASE_URL)
  --space <key>       space key (CONFLUENCE_SPACE)
  --email <address>   Cloud account email for Basic auth (CONFLUENCE_EMAIL)
  --token <token>     API token or personal access token (CONFLUENCE_TOKEN)
  --limit <n>         how many pages (default 100)

Ingestion:
  --graph <name>      graph to build/upsert (default confluence-demo)
  --rule-pack <name>  sanguo (default), incident, or a pack in the rules directory
  --dictionary <file> JSON object mapping entity names to types
  --aliases <file>    JSON object mapping alias -> canonical name
  --gold <file>       JSON array of "head|relation|tail" triples
  --apply             write; without it the ingestion is a dry run
  --api <url>         service URL (default PHOENIXGRAPH_API or http://localhost:8081)
  --api-key <key>     API key (default PHOENIXGRAPH_API_KEY)`);
};

const args = process.argv.slice(2);
if (args.includes('--help') || args.includes('-h')) {
  usage();
  process.exit(0);
}
const connection = {
  base: process.env.CONFLUENCE_BASE_URL || '',
  space: process.env.CONFLUENCE_SPACE || '',
  email: process.env.CONFLUENCE_EMAIL || '',
  token: process.env.CONFLUENCE_TOKEN || '',
  limit: 100,
};
const rest = [];
for (let i = 0; i < args.length; i += 1) {
  const arg = args[i];
  const value = () => {
    const next = args[i + 1];
    if (!next || next.startsWith('--')) throw new Error(`${arg} needs a value`);
    i += 1;
    return next;
  };
  if (arg === '--base') connection.base = value();
  else if (arg === '--space') connection.space = value();
  else if (arg === '--email') connection.email = value();
  else if (arg === '--token') connection.token = value();
  else if (arg === '--limit') connection.limit = Number(value());
  else rest.push(arg);
}

try {
  const { options } = parseCommon(rest, { graph: 'confluence-demo' });
  const read = (path, fallback) => (path ? JSON.parse(readFileSync(path, 'utf8')) : fallback);
  const dictionary = read(options.dictionary, {});
  const aliases = read(options.aliases, {});
  const gold = read(options.gold, []);
  const documents = await confluenceDocuments(connection);
  if (documents.length === 0) {
    console.error('Confluence returned no readable pages.');
    process.exit(1);
  }
  const result = await postIngest({
    ...options,
    description: `Confluence space ${connection.space} (${documents.length} pages)`,
    documents,
    dictionary,
    aliases,
    gold,
  });
  printIngestResult(result, {
    target: `${connection.base} · space ${connection.space}`,
    graph: options.graph,
    rulePack: options.rulePack,
    apply: options.apply,
  });
} catch (error) {
  console.error(error.message);
  if (error.payload?.hint) console.error(`  ${error.payload.hint}`);
  process.exit(1);
}
