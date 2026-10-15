/*
 * Generates the endpoint table of docs/API.md from the controllers themselves.
 *
 *   node tools/api-endpoints.mjs            # rewrite the generated block in place
 *   node tools/api-endpoints.mjs --check    # exit 1 if the file is out of date (what CI runs)
 *   node tools/api-endpoints.mjs --stdout   # print the table, write nothing
 *
 * Why generate instead of documenting by hand: an endpoint list is the part of the reference that rots
 * first, and a stale list is worse than none. The hand-written parts of the document (error model,
 * examples, semantics) stay hand-written on purpose - those are the parts a generator cannot know, and
 * they carry the reasoning a reader needs.
 */
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

// every @RestController under the http package, not only the controller/ directory: the semantic layer keeps
// its own package (semantic/) next to the model it serves, and an endpoint that the reference does not list is an
// endpoint nobody finds.
const HTTP_ROOT = 'graph-http/src/main/java/com/pengcheng/phoenixgraph/http';
const CONTROLLERS = HTTP_ROOT;
const TARGET = 'docs/API.md';
const START = '<!-- endpoints:start -->';
const END = '<!-- endpoints:end -->';

/** Reads the path of an annotation: `value`/`path` when named, otherwise the first literal. */
function pathOf(annotationArgs) {
  // `value = "..."` / `path = "..."` wins: a `produces = "..."` literal is not a path, and neither is a
  // `@RequestParam(value = "replace")` that happens to sit in the method below
  const named = /(?:value|path)\s*=\s*(\{[^}]*\}|"[^"]*")/.exec(annotationArgs);
  const literal = named ? named[1] : (/("[^"]*")/.exec(annotationArgs) || [])[1];
  if (!literal) return '';
  // an array value lists alternative paths: keep them all, or the table would silently drop one
  const paths = [...literal.matchAll(/"([^"]*)"/g)].map((match) => match[1]);
  return paths.join(' | ');
}

/**
 * Reads an annotation and its arguments, including a multi-line argument list.
 *
 * <p>An annotation with no parentheses (`@GetMapping`) has no arguments - the first version kept scanning
 * to the end of the file and picked up the first string literal in the method body instead, which turned
 * `@PostMapping` + `@RequestParam(value = "replace")` into the path `/api/v1/schemareplace`.
 */
function annotationAt(text, index) {
  const name = /^@(Get|Post|Put|Delete|Patch|Request)Mapping/.exec(text.slice(index));
  if (!name) return null;
  let cursor = index + name[0].length;
  while (cursor < text.length && /\s/.test(text[cursor])) cursor += 1;
  const verb = name[1].replace('Request', '');
  if (text[cursor] !== '(') return { verb, args: '', end: cursor };
  let depth = 0;
  const start = cursor;
  while (cursor < text.length) {
    if (text[cursor] === '(') depth += 1;
    else if (text[cursor] === ')') { depth -= 1; if (depth === 0) { cursor += 1; break; } }
    cursor += 1;
  }
  return { verb, args: text.slice(start, cursor), end: cursor };
}

/** The parameter list of the first method declared after an offset, with its nesting respected. */
function firstSignatureAfter(text, offset) {
  const match = /\bpublic\s+([^(){};]+?)\s+([A-Za-z0-9_]+)\s*\(/.exec(text.slice(offset));
  if (!match) return null;
  const open = offset + match.index + match[0].length;
  let depth = 1;
  let cursor = open;
  while (cursor < text.length && depth > 0) {
    if (text[cursor] === '(') depth += 1;
    else if (text[cursor] === ')') depth -= 1;
    cursor += 1;
  }
  return {
    returns: match[1].replace(/\s+/g, ' ').trim(),
    method: match[2],
    params: text.slice(open, cursor - 1).replace(/\s+/g, ' ').trim(),
  };
}

/**
 * A controller's base path and its endpoints. The method's Java name is kept because it is what a reader
 * greps for when something goes wrong, and the request body type because that is what they have to build.
 */
function readController(file) {
  const text = readFileSync(file, 'utf8');
  const classMatch = /@RequestMapping\s*(\([^)]*\))?/.exec(text);
  const base = classMatch && classMatch[1] ? pathOf(classMatch[1]) : '';
  const classEnd = classMatch ? classMatch.index + classMatch[0].length : 0;

  const endpoints = [];
  const mapping = /@(Get|Post|Put|Delete|Patch)Mapping/g;
  let found;
  while ((found = mapping.exec(text)) !== null) {
    if (found.index < classEnd) continue; // the class-level annotation is not an endpoint
    const parsed = annotationAt(text, found.index);
    if (!parsed) continue;
    const signature = firstSignatureAfter(text, parsed.end);
    if (!signature) continue;
    const body = /@RequestBody\s+([A-Za-z0-9_<>,.\s]+?)\s+[a-zA-Z]/.exec(signature.params);
    endpoints.push({
      verb: parsed.verb.toUpperCase(),
      path: `${base}${pathOf(parsed.args)}`,
      method: signature.method,
      body: body ? body[1].replace(/\s+/g, ' ').trim() : '',
      returns: signature.returns,
      controller: file.split('/').pop().replace('.java', ''),
    });
  }
  return { base, endpoints };
}

const files = [];
const collect = (dir) => {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      collect(dir + '/' + entry.name);
    } else if (entry.name.endsWith('Controller.java') && !entry.name.includes('ApiException')) {
      files.push(dir + '/' + entry.name);
    }
  }
};
collect(CONTROLLERS);
const all = [];
for (const file of files) all.push(...readController(file).endpoints);
all.sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : a.verb.localeCompare(b.verb)));

const table = [
  START,
  `<!-- generated by tools/api-endpoints.mjs from ${files.length} controllers - do not edit by hand -->`,
  '',
  '| Method | Path | Java method | Request body | Returns |',
  '| --- | --- | --- | --- | --- |',
  ...all.map((e) => `| \`${e.verb}\` | \`${e.path}\` | \`${e.controller}#${e.method}\` | ${e.body ? '`' + e.body + '`' : '—'} | \`${e.returns}\` |`),
  '',
  `${all.length} endpoints across ${files.length} controllers.`,
  END,
].join('\n');

if (process.argv.includes('--stdout')) {
  console.log(table);
  process.exit(0);
}

const document = readFileSync(TARGET, 'utf8');
const start = document.indexOf(START);
const end = document.indexOf(END);
if (start < 0 || end < 0) throw new Error(`${TARGET} must contain ${START} ... ${END}`);
const updated = document.slice(0, start) + table + document.slice(end + END.length);

if (process.argv.includes('--check')) {
  if (updated !== document) {
    console.error(`${TARGET} is out of date: run node tools/api-endpoints.mjs`);
    process.exit(1);
  }
  console.log(`${TARGET} matches the ${all.length} endpoints in ${files.length} controllers`);
  process.exit(0);
}

writeFileSync(TARGET, updated);
console.log(`wrote ${all.length} endpoints from ${files.length} controllers into ${TARGET}`);
