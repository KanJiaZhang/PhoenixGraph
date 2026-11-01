import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/**
 * The package as pi sees it: the manifest, the resources it names, and the folders the npm tarball carries.
 *
 * A pi package is a promise - `pi install` copies this directory and pi loads what `package.json#pi` points at.
 * Nothing in the other tests would notice a manifest that names a file which does not exist, a skill whose
 * frontmatter lost its `description` (the model then never loads it), or a `files` list that leaves `src/` out of
 * the published tarball, so the installed package imports nothing. These assertions are that promise, checked by
 * reading the same files pi reads.
 */
const ROOT = fileURLToPath(new URL('..', import.meta.url));
const manifest = JSON.parse(readFileSync(join(ROOT, 'package.json'), 'utf8'));

const HOST_PACKAGES = ['@earendil-works/pi-ai', '@earendil-works/pi-agent-core',
  '@earendil-works/pi-coding-agent', '@earendil-works/pi-tui', 'typebox'];

/** The files a declared resource can expand to: one path, a directory, or a `*` glob. */
function resolveResource(entry) {
  const path = join(ROOT, entry);
  if (!entry.includes('*')) {
    if (!existsSync(path)) return [];
    if (!statSync(path).isDirectory()) return [path];
    const found = [];
    const walk = (directory, depth) => {
      for (const name of readdirSync(directory)) {
        const child = join(directory, name);
        if (statSync(child).isDirectory()) { if (depth < 4) walk(child, depth + 1); } else found.push(child);
      }
    };
    walk(path, 0);
    return found;
  }
  // a flat `dir/*.ext` glob is all this manifest uses, and saying so is better than depending on a matcher
  const directory = join(ROOT, dirname(entry));
  const suffix = entry.slice(entry.lastIndexOf('*') + 1);
  if (!existsSync(directory)) return [];
  return readdirSync(directory).filter((name) => name.endsWith(suffix)).map((name) => join(directory, name));
}

/** The YAML-ish frontmatter pi reads: `---` delimited, `key: value` pairs, then the body. */
function frontmatter(file) {
  const text = readFileSync(file, 'utf8');
  const match = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?([\s\S]*)$/.exec(text);
  if (!match) return { data: {}, body: text };
  const data = {};
  for (const line of match[1].split(/\r?\n/)) {
    const pair = /^([A-Za-z][\w-]*):\s*(.*)$/.exec(line);
    if (pair) data[pair[1]] = pair[2].trim().replace(/^['"]|['"]$/g, '');
  }
  return { data, body: match[2] };
}

test('the manifest says what a pi package says, and every declared resource exists', async () => {
  assert.equal(manifest.name, 'pi-data-agent');
  assert.equal(manifest.type, 'module');
  assert.equal(manifest.license, 'Apache-2.0');
  assert.ok(manifest.keywords.includes('pi-package'), 'the pi-package keyword is what makes it discoverable');
  assert.match(manifest.engines.node, />=\s*2\d/);

  assert.ok(manifest.pi, 'package.json#pi is the manifest pi reads');
  const extensions = resolveResource(manifest.pi.extensions[0]);
  assert.equal(extensions.length, 1);
  assert.match(relative(ROOT, extensions[0]).replace(/\\/g, '/'), /^extensions\/pi-data-agent\/index\.js$/);
  assert.ok(resolveResource(manifest.pi.skills[0]).some((file) => file.endsWith('SKILL.md')));
  assert.ok(resolveResource(manifest.pi.prompts[0]).length >= 1);

  // the extension is a pi extension: a default export the runtime calls with (pi, options)
  const module = await import(pathToFileURL(extensions[0]).href);
  assert.equal(typeof module.default, 'function');
  assert.equal(module.default.length >= 1, true);
});

test('every skill has the frontmatter pi needs to route to it', () => {
  const skills = resolveResource(manifest.pi.skills[0]).filter((file) => file.endsWith('SKILL.md'));
  assert.ok(skills.length >= 1);
  for (const file of skills) {
    const { data, body } = frontmatter(file);
    const directory = dirname(file).split(/[\\/]/).pop();
    assert.equal(data.name, directory, `${file}: name must match its directory`);
    assert.ok(data.description && data.description.length >= 40, `${file}: a description is what the model routes on`);
    assert.match(data.description, /[Uu]se when/, `${file}: the description must say when the skill applies`);
    assert.ok(body.trim().length > 200, `${file}: the body is the instructions, not a stub`);
  }
});

test('every prompt template carries a description, so it is findable in command completion', () => {
  const prompts = resolveResource(manifest.pi.prompts[0]);
  assert.ok(prompts.length >= 1);
  for (const file of prompts) {
    const { data, body } = frontmatter(file);
    assert.ok(data.description && data.description.length >= 10, `${file}: description is missing`);
    assert.ok(body.trim().length > 200, `${file}: the template is the prompt itself`);
  }
});

test('the host packages are peers, never dependencies', () => {
  assert.ok(manifest.peerDependencies['@earendil-works/pi-coding-agent']);
  assert.equal(manifest.peerDependencies['@earendil-works/pi-coding-agent'], '*');
  for (const host of HOST_PACKAGES) {
    assert.equal(manifest.dependencies?.[host], undefined, `${host} must not be a dependency`);
  }
});

test('the published tarball would carry every resource: files covers what pi points at', () => {
  const files = manifest.files.map((entry) => entry.replace(/\/$/, ''));
  const roots = [
    ...manifest.pi.extensions, ...manifest.pi.skills, ...manifest.pi.prompts,
    ...Object.values(manifest.bin || {}),
  ].map((entry) => {
    // the directory pi points into: the parent of a file entry, the entry itself when it is a directory or a glob
    const path = entry.replace(/^\.\//, '');
    const last = path.split(/[\\/]/).pop();
    return /[.*]/.test(last) ? path.split(/[\\/]/).slice(0, -1).join('/') : path;
  });
  for (const root of roots) {
    assert.ok(files.some((entry) => root === entry || root.startsWith(`${entry}/`)),
      `files does not cover ${root}, so an installed package would point at nothing`);
  }
  // and the tests and the live verification are how the package is trusted; they ship with it
  assert.ok(files.includes('verification'));
  assert.ok(files.includes('examples'));
  assert.ok(existsSync(join(ROOT, ...manifest.bin['pi-data-agent'].replace(/^\.\//, '').split('/'))));
});

test('the CLI has a shebang and the npm scripts do not depend on a service', () => {
  const cli = join(ROOT, manifest.bin['pi-data-agent'].replace(/^\.\//, ''));
  assert.match(readFileSync(cli, 'utf8').split(/\r?\n/)[0], /^#!.*node/);
  assert.match(manifest.scripts.test, /node --test test\/\*\.test\.mjs/);
  assert.match(manifest.scripts.verify, /verification\/verify-package\.mjs/);
});

test('the README points at files that exist', () => {
  const readme = readFileSync(join(ROOT, 'README.md'), 'utf8');
  const links = [...readme.matchAll(/\]\(([^)#\s]+)(?:#[^)]*)?\)/g)]
    .map((match) => match[1])
    .filter((link) => !/^[a-z]+:/i.test(link) && !link.startsWith('#'));
  assert.ok(links.length >= 1, `only ${links.length} relative link(s) found`);
  for (const link of links) {
    assert.ok(existsSync(join(ROOT, link)), `README links to ${link}, which does not exist`);
  }
  // the package's own files, named as code spans (`docs/DESIGN.md` is how this README refers to them)
  const spans = [...readme.matchAll(/`((?:docs|src|test|prompts|skills|examples|verification|extensions)\/(?:<[^>`]+>|[A-Za-z0-9_./-])+\.(?:md|mjs|js))`/g)]
    .map((match) => match[1]);
  assert.ok(spans.length >= 3, `only ${spans.length} package file reference(s) found`);
  for (const span of spans) {
    if (span.includes('<')) continue;
    assert.ok(existsSync(join(ROOT, span)), `README names ${span}, which does not exist`);
  }
});
