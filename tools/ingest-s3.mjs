#!/usr/bin/env node
// Ingest Markdown/text/HTML objects from an S3 bucket through the agent API.
//
//   S3_BUCKET=my-docs AWS_REGION=eu-west-1 node tools/ingest-s3.mjs \
//     --prefix handbooks/ --graph handbook-demo --rule-pack incident --apply
import { readFileSync } from 'node:fs';
import { parseCommon, postIngest, printIngestResult } from './connectors/common.mjs';
import { s3Documents } from './connectors/s3.mjs';

const usage = () => {
  console.log(`Usage: node tools/ingest-s3.mjs [options]

Connection (or environment):
  --bucket <name>        S3 bucket (S3_BUCKET)
  --prefix <key>         key prefix (default: whole bucket)
  --region <region>      AWS region (AWS_REGION, default us-east-1)
  --endpoint <url>       path-style endpoint for MinIO/LocalStack (optional)
  --access-key <key>     AWS_ACCESS_KEY_ID
  --secret-key <key>     AWS_SECRET_ACCESS_KEY
  --session-token <t>    AWS_SESSION_TOKEN
  --limit <n>            objects to read (default 100)

Ingestion:
  --graph <name>         graph to build/upsert (default s3-docs)
  --rule-pack <name>     sanguo (default), incident, or a pack in the rules directory
  --dictionary <file>    JSON object mapping entity names to types
  --aliases <file>       JSON object mapping alias -> canonical name
  --gold <file>          JSON array of "head|relation|tail" triples
  --apply                write; without it the ingestion is a dry run
  --api <url>            service URL (default PHOENIXGRAPH_API or http://localhost:8081)
  --api-key <key>        API key (default PHOENIXGRAPH_API_KEY)`);
};

const args = process.argv.slice(2);
if (args.includes('--help') || args.includes('-h')) {
  usage();
  process.exit(0);
}
const connection = {
  bucket: process.env.S3_BUCKET || '',
  prefix: '',
  region: process.env.AWS_REGION || 'us-east-1',
  endpoint: process.env.S3_ENDPOINT || '',
  accessKey: process.env.AWS_ACCESS_KEY_ID || '',
  secretKey: process.env.AWS_SECRET_ACCESS_KEY || '',
  sessionToken: process.env.AWS_SESSION_TOKEN || '',
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
  if (arg === '--bucket') connection.bucket = value();
  else if (arg === '--prefix') connection.prefix = value();
  else if (arg === '--region') connection.region = value();
  else if (arg === '--endpoint') connection.endpoint = value();
  else if (arg === '--access-key') connection.accessKey = value();
  else if (arg === '--secret-key') connection.secretKey = value();
  else if (arg === '--session-token') connection.sessionToken = value();
  else if (arg === '--limit') connection.limit = Number(value());
  else rest.push(arg);
}

try {
  const { options } = parseCommon(rest, { graph: 's3-docs' });
  const read = (path, fallback) => (path ? JSON.parse(readFileSync(path, 'utf8')) : fallback);
  const dictionary = read(options.dictionary, {});
  const aliases = read(options.aliases, {});
  const gold = read(options.gold, []);
  const { documents, skipped } = await s3Documents(connection);
  if (documents.length === 0) {
    console.error('S3 returned no readable text objects.');
    for (const item of skipped) console.error(`  skipped ${item.key}: ${item.reason}`);
    process.exit(1);
  }
  const result = await postIngest({
    ...options,
    description: `S3 s3://${connection.bucket}/${connection.prefix} (${documents.length} objects)`,
    documents,
    dictionary,
    aliases,
    gold,
  });
  printIngestResult(result, {
    target: `s3://${connection.bucket}/${connection.prefix}`,
    graph: options.graph,
    rulePack: options.rulePack,
    apply: options.apply,
  });
  for (const item of skipped) console.log(`  skipped ${item.key}: ${item.reason}`);
} catch (error) {
  console.error(error.message);
  if (error.payload?.hint) console.error(`  ${error.payload.hint}`);
  process.exit(1);
}
