#!/usr/bin/env node
// Ingest pages the Notion integration can see through the agent API.
//
//   NOTION_TOKEN=secret_... node tools/ingest-notion.mjs \
//     --graph notion-demo --rule-pack incident --apply
import { readFileSync } from 'node:fs';
import { parseCommon, postIngest, printIngestResult } from './connectors/common.mjs';
import { notionDocuments } from './connectors/notion.mjs';

const usage = () => {
  console.log(`Usage: node tools/ingest-notion.mjs [options]

Connection (or the environment variable in parentheses):
  --token <token>     integration token (NOTION_TOKEN)
  --query <text>      search text (default: every page the integration can see)
  --limit <n>         how many pages (default 100)

Ingestion:
  --graph <name>      graph to build/upsert (default notion-demo)
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
  token: process.env.NOTION_TOKEN || '',
  query: '',
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
  if (arg === '--token') connection.token = value();
  else if (arg === '--query') connection.query = value();
  else if (arg === '--limit') connection.limit = Number(value());
  else rest.push(arg);
}

try {
  const { options } = parseCommon(rest, { graph: 'notion-demo' });
  const read = (path, fallback) => (path ? JSON.parse(readFileSync(path, 'utf8')) : fallback);
  const dictionary = read(options.dictionary, {});
  const aliases = read(options.aliases, {});
  const gold = read(options.gold, []);
  const documents = await notionDocuments(connection);
  if (documents.length === 0) {
    console.error('Notion returned no readable pages.');
    process.exit(1);
  }
  const result = await postIngest({
    ...options,
    description: `Notion pages (${documents.length})${connection.query ? ` matching "${connection.query}"` : ''}`,
    documents,
    dictionary,
    aliases,
    gold,
  });
  printIngestResult(result, {
    target: connection.query ? `Notion search "${connection.query}"` : 'Notion pages',
    graph: options.graph,
    rulePack: options.rulePack,
    apply: options.apply,
  });
} catch (error) {
  console.error(error.message);
  if (error.payload?.hint) console.error(`  ${error.payload.hint}`);
  process.exit(1);
}
