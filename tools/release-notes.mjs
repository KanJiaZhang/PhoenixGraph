#!/usr/bin/env node
/**
 * The release notes for one version, taken from CHANGELOG.md.
 *
 *   node tools/release-notes.mjs 1.2.0            # print the section
 *   node tools/release-notes.mjs 1.2.0 --out notes.md
 *   node tools/release-notes.mjs --latest         # the newest dated section
 *
 * The changelog is the source of truth for what a release says (docs/RELEASE-PROCESS.md), so the GitHub Release
 * body is generated from it rather than typed twice. A version the file does not have is an error: a release whose
 * notes are empty is a release nobody can read later.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import process from 'node:process';

const args = process.argv.slice(2);
const outIndex = args.indexOf('--out');
const out = outIndex >= 0 ? args[outIndex + 1] : null;
const skipIndex = outIndex >= 0 ? outIndex + 1 : -1;   // the value of --out is not the version
const wanted = args.includes('--latest') ? null : args.filter((arg, index) => !arg.startsWith('--') && index !== skipIndex)[0];

const text = readFileSync('CHANGELOG.md', 'utf8').replace(/\r\n/g, '\n');
const lines = text.split('\n');
// the workflow passes the repository, so the notes name real images instead of a placeholder
const owner = (process.env.GITHUB_REPOSITORY_OWNER || (process.env.GITHUB_REPOSITORY || '').split('/')[0] || '<owner>').toLowerCase();

/** `## [1.2.0] - 2026-10-29` or `## [Unreleased]` -> the heading's version and the section's lines. */
const sections = [];
let current = null;
for (const line of lines) {
  const heading = /^## \[([^\]]+)\](?:\s*-\s*(\S+))?\s*$/.exec(line);
  if (heading) {
    current = { version: heading[1], date: heading[2] || null, lines: [] };
    sections.push(current);
    continue;
  }
  if (/^## /.test(line)) { current = null; continue; }
  if (current) current.lines.push(line);
}

const released = sections.filter((section) => section.version !== 'Unreleased');
const section = wanted
  ? released.find((candidate) => candidate.version === wanted.replace(/^v/, ''))
  : released[0];

if (!section) {
  console.error(`CHANGELOG.md has no section for '${wanted || '(latest)'}'; it has: ${released.map((item) => item.version).join(', ')}`);
  process.exit(1);
}

const body = section.lines.join('\n').replace(/^\n+|\n+$/g, '');
const notes = [
  `# PhoenixGraph ${section.version}${section.date ? ` (${section.date})` : ''}`,
  '',
  body,
  '',
  '---',
  '',
  'Images: `ghcr.io/' + owner + '/phoenixgraph-http:' + section.version + '` and `ghcr.io/' + owner + '/phoenixgraph-web:' + section.version + '`.',
  'Python client: `pip install phoenixgraph` (see the release notes for the version).',
].join('\n');

if (out) {
  writeFileSync(out, `${notes}\n`);
  console.log(`wrote ${out} (${notes.split('\n').length} lines)`);
} else {
  console.log(notes);
}
