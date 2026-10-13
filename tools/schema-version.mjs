/*
 * Stamps and checks the schema file format version (M3-4).
 *
 *   node tools/schema-version.mjs --check graph-http/src/main/resources       # exit 1 if a file is unstamped
 *   node tools/schema-version.mjs --write graph-http/src/main/resources       # stamp every schema file
 *   node tools/schema-version.mjs --dry-run graph-http/src/main/resources     # what --write would change
 *   node tools/schema-version.mjs --check file1.json file2.json
 *
 * A file is a *schema* if it parses and has a `name` and `vertexSchemas`; anything else in the directory is
 * reported and skipped, because a directory that holds schemas also holds other JSON.
 *
 * Where the number comes from: the running service (`POST /api/v1/schema/version`), so the tool and the engine
 * cannot disagree about what this build writes. Without a service (or with --offline) the tool falls back to
 * `--version`, defaulting to 1, and says so.
 *
 * Why stamp at all: a format with no version cannot be changed. The day a field means something different, a
 * reader has to guess which files are old - and guessing is how a constraint silently disappears.
 */
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const args = process.argv.slice(2);
const flag = (name) => args.includes('--' + name);
const value = (name, fallback) => {
  const at = args.indexOf('--' + name);
  return at >= 0 && args[at + 1] && !args[at + 1].startsWith('--') ? args[at + 1] : fallback;
};
const targets = args.filter((arg) => !arg.startsWith('--') && arg !== value('version', null) && arg !== value('api', null));
const mode = flag('write') ? 'write' : flag('dry-run') ? 'dry-run' : 'check';
const API = value('api', 'http://localhost:8081');
const offline = flag('offline');

/** The files to look at: the directories and files named on the command line, or the served schemas. */
function collect(paths) {
  const found = [];
  for (const path of paths) {
    const stats = statSync(path);
    if (stats.isDirectory()) {
      for (const name of readdirSync(path).filter((entry) => entry.endsWith('.json'))) {
        found.push(join(path, name));
      }
    } else {
      found.push(path);
    }
  }
  return found;
}

/** The version this build writes: ask the service, or fall back and admit it. */
async function supportedThrough() {
  if (!offline) {
    try {
      const response = await fetch(API + '/api/v1/schema/version', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{"name":"probe","vertexSchemas":[],"edgeSchemas":[]}',
        signal: AbortSignal.timeout(4000),
      });
      if (response.ok) {
        const answer = await response.json();
        return { version: answer.supportedThrough, source: API };
      }
    } catch {
      // no service: the fallback below is the honest answer, and it is reported
    }
  }
  return { version: Number(value('version', '1')), source: offline ? '--offline' : 'no service (default)' };
}

const isSchema = (document) => document && typeof document === 'object'
  && typeof document.name === 'string' && Array.isArray(document.vertexSchemas);

const { version, source } = await supportedThrough();
console.log(`schema format version this build writes: ${version} (from ${source})`);

const paths = collect(targets);
if (!paths.length) {
  console.error('usage: node tools/schema-version.mjs [--check|--write|--dry-run] <file-or-directory> [...]');
  process.exit(2);
}

let changed = 0;
let unstamped = 0;
let refused = 0;
let skipped = 0;
for (const path of paths) {
  let text;
  try {
    text = readFileSync(path, 'utf8');
  } catch (unreadable) {
    console.log(`skip ${path}: ${unreadable.message}`);
    skipped += 1;
    continue;
  }
  let document;
  try {
    document = JSON.parse(text);
  } catch (broken) {
    console.log(`FAIL ${path}: not JSON (${broken.message})`);
    refused += 1;
    continue;
  }
  if (!isSchema(document)) {
    console.log(`skip ${path}: not a schema (no name/vertexSchemas)`);
    skipped += 1;
    continue;
  }

  const declared = document.schemaVersion === undefined ? null : document.schemaVersion;
  if (declared !== null && declared > version) {
    console.log(`FAIL ${path}: declares schemaVersion ${declared}, this build reads up to ${version}`);
    refused += 1;
    continue;
  }
  if (declared === version) {
    console.log(`ok   ${path}: schemaVersion ${declared}`);
    continue;
  }

  unstamped += 1;
  if (mode === 'check') {
    console.log(`FAIL ${path}: no schemaVersion (a file written before the field existed is version 1)`);
    continue;
  }

  // Rewrite the file with the field next to the name, keeping the rest of the document - and its formatting,
  // which a JSON.stringify round trip would destroy (these files are read and diffed by people).
  const withVersion = declared === null
    ? text.replace(/(\n\s*"name"\s*:\s*"[^"]*",)/, `$1\n  "schemaVersion": ${version},`)
    : text.replace(/("schemaVersion"\s*:\s*)\d+/, `$1${version}`);
  if (withVersion === text) {
    console.log(`FAIL ${path}: could not find where to put schemaVersion`);
    refused += 1;
    continue;
  }
  if (mode === 'dry-run') {
    console.log(`would stamp ${path}: schemaVersion ${version}`);
    changed += 1;
    continue;
  }
  writeFileSync(path, withVersion);
  console.log(`stamped ${path}: schemaVersion ${version}`);
  changed += 1;
}

const summary = `${paths.length} file(s): ${changed} stamped, ${unstamped} unstamped, ${refused} refused, ${skipped} skipped`;
if (mode === 'check') {
  console.log(unstamped || refused ? `\n${summary} - a schema without a version cannot be migrated later` : `\n${summary}`);
  process.exit(unstamped || refused ? 1 : 0);
}
console.log(`\n${summary}${mode === 'dry-run' ? ' (nothing written)' : ''}`);
process.exit(refused ? 1 : 0);
