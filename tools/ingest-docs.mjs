#!/usr/bin/env node
// Ingest a folder of documents through the agent API.
//
//   node tools/ingest-docs.mjs examples/business-demo/documents \
//     --graph incident-demo --rule-pack incident \
//     --dictionary examples/business-demo/dictionary.json --apply
//
// Defaults to a dry run: nothing is written until --apply. Text, Markdown and HTML need no dependency;
// PDF uses `pdftotext` when it is installed and is skipped with a warning otherwise.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { basename, extname, join, relative, resolve } from 'node:path';
import { extractPdfText } from './connectors/pdf.mjs';

const API = (process.env.PHOENIXGRAPH_API || 'http://localhost:8081').replace(/\/+$/, '');
const API_KEY = process.env.PHOENIXGRAPH_API_KEY || '';

const usage = () => {
  console.log(`Usage: node tools/ingest-docs.mjs <file-or-directory> [options]

Options:
  --graph <name>          graph to build/upsert (default: docs-demo)
  --rule-pack <name>      extraction vocabulary: sanguo (default), incident
  --dictionary <file>     JSON object mapping entity names to types
  --aliases <file>        JSON object mapping alias -> canonical name
  --gold <file>           JSON array of "head|relation|tail" triples for the quality report
  --apply                 write the graph; without it the ingestion is a dry run
  --api <url>             service URL (default: PHOENIXGRAPH_API or http://localhost:8081)
  --ocr                   OCR scanned PDFs with pdftoppm + tesseract
  --ocr-lang <lang>       tesseract language (default: eng)
  --no-dedupe             keep case/whitespace/punctuation variants as separate entities
  --api-key <key>         API key (default: PHOENIXGRAPH_API_KEY)
  --help                  this text

Supported files: .md, .markdown, .txt, .html, .htm, and .pdf (pdftotext, optional OCR).`);
};

const args = process.argv.slice(2);
const options = {
  graph: 'docs-demo',
  rulePack: 'sanguo',
  dictionary: null,
  aliases: null,
  gold: null,
  apply: false,
  api: API,
  apiKey: API_KEY,
  ocr: false,
  ocrLang: 'eng',
  dedupe: true,
};
let target = null;
for (let i = 0; i < args.length; i += 1) {
  const arg = args[i];
  const value = () => {
    const next = args[i + 1];
    if (!next || next.startsWith('--')) {
      throw new Error(`${arg} needs a value`);
    }
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
  else if (arg === '--ocr') options.ocr = true;
  else if (arg === '--ocr-lang') options.ocrLang = value();
  else if (arg === '--no-dedupe') options.dedupe = false;
  else if (arg === '--apply') options.apply = true;
  else if (arg === '--dry-run') options.apply = false;
  else if (arg === '--help' || arg === '-h') {
    usage();
    process.exit(0);
  } else if (!arg.startsWith('-') && !target) target = arg;
  else throw new Error(`unknown argument '${arg}'`);
}
if (!target) {
  usage();
  process.exit(2);
}

const files = [];
const walk = (path) => {
  const info = statSync(path);
  if (info.isDirectory()) {
    for (const name of readdirSync(path).sort()) {
      if (name.startsWith('.')) continue;
      walk(join(path, name));
    }
    return;
  }
  files.push(path);
};
try {
  walk(target);
} catch (error) {
  console.error(`Cannot read ${target}: ${error.message}`);
  process.exit(1);
}

const stripHtml = (html) => html
  .replace(/<script[\s\S]*?<\/script>/gi, ' ')
  .replace(/<style[\s\S]*?<\/style>/gi, ' ')
  .replace(/<\/(p|div|li|h[1-6])>/gi, '\n')
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

const titleOf = (text, fallback) => {
  const heading = /^#\s+(.+)$/m.exec(text);
  return heading ? heading[1].trim() : fallback;
};


const documents = [];
const skipped = [];
let ocrUsed = 0;
for (const path of files) {
  const extension = extname(path).toLowerCase();
  const fallbackTitle = basename(path, extension);
  if (!['.md', '.markdown', '.txt', '.html', '.htm', '.pdf'].includes(extension)) {
    skipped.push({ path, reason: `unsupported extension ${extension || '(none)'}` });
    continue;
  }
  let text;
  if (extension === '.pdf') {
    const pdf = extractPdfText(path, { ocr: options.ocr, lang: options.ocrLang });
    if (pdf.text === null) {
      skipped.push({ path, reason: pdf.reason });
      continue;
    }
    text = pdf.text;
    if (pdf.method === 'ocr') ocrUsed += 1;
  } else {
    const raw = readFileSync(path, 'utf8');
    text = extension === '.html' || extension === '.htm' ? stripHtml(raw) : raw;
  }
  if (!text || !text.trim()) {
    skipped.push({ path, reason: 'no readable text' });
    continue;
  }
  const id = relative(process.cwd(), resolve(path)).replace(/\.[^.]+$/, '').replace(/\\/g, '/');
  documents.push({ id, title: titleOf(text, fallbackTitle), text });
}

if (documents.length === 0) {
  console.error('No readable documents found.');
  for (const item of skipped) console.error(`  skipped ${item.path}: ${item.reason}`);
  process.exit(1);
}

let dictionary = {};
let aliases = {};
let gold = [];
try {
  if (options.dictionary) dictionary = JSON.parse(readFileSync(options.dictionary, 'utf8'));
  if (options.aliases) aliases = JSON.parse(readFileSync(options.aliases, 'utf8'));
} catch (error) {
  console.error(`Cannot read the dictionary: ${error.message}`);
  process.exit(1);
}
try {
  if (options.gold) gold = JSON.parse(readFileSync(options.gold, 'utf8'));
} catch (error) {
  console.error(`Cannot read the gold set: ${error.message}`);
  process.exit(1);
}

const body = {
  graph: options.graph,
  description: `Ingested from ${documents.length} document(s) with the ${options.rulePack} rule pack`,
  documents,
  dictionary,
  aliases,
  gold,
  apply: options.apply,
  rulePack: options.rulePack,
  dedupe: options.dedupe,
};

console.log('PhoenixGraph document ingestion');
console.log(`  target:    ${resolve(target)}`);
console.log(`  graph:     ${options.graph}`);
console.log(`  rule pack: ${options.rulePack}`);
console.log(`  mode:      ${options.apply ? 'write' : 'dry run (nothing is written)'}`);
console.log(`  documents: ${documents.length}${files.length > documents.length ? ` of ${files.length} files` : ''}`);
if (ocrUsed > 0) console.log(`  OCR:       ${ocrUsed} PDF page set(s) read with tesseract`);
if (skipped.length) {
  for (const item of skipped) console.log(`  skipped:   ${item.path} - ${item.reason}`);
}

const headers = { Accept: 'application/json', 'Content-Type': 'application/json; charset=utf-8' };
if (options.apiKey) headers.Authorization = `Bearer ${options.apiKey}`;
let response;
try {
  response = await fetch(`${options.api}/api/v1/agent/ingest`, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  });
} catch (error) {
  console.error(`Cannot reach ${options.api}: ${error.message}`);
  console.error('Start the service with tools/start-phoenixgraph.sh');
  process.exit(1);
}
const raw = await response.text();
let result;
try {
  result = raw ? JSON.parse(raw) : {};
} catch {
  console.error(`The service answered ${response.status} with a non-JSON body: ${raw.slice(0, 300)}`);
  process.exit(1);
}
if (!response.ok) {
  console.error(`Ingestion failed (${response.status}): ${result.error || raw}`);
  if (result.hint) console.error(`  ${result.hint}`);
  process.exit(1);
}

const quality = result.quality || {};
const percent = (value) => (value === undefined || value === null ? 'n/a' : `${(Number(value) * 100).toFixed(0)}%`);
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
