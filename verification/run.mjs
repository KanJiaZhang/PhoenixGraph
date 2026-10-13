// The runner for the live verification suite: it lists what can be checked, refuses to run a check whose
// prerequisite is missing (instead of reporting a false failure), runs the rest one at a time, and reports
// how many assertions each one made.
//
//   node verification/run.mjs                 # everything that can run here
//   node verification/run.mjs --list          # what each check does, and what it needs
//   node verification/run.mjs --only dag      # substring match on the file name
//   node verification/run.mjs --no-browser    # service-only checks (no Chromium needed)
//   node verification/run.mjs --retry 0       # strict: no second try for a timing-sensitive check
//   node verification/run.mjs --timeout 300   # per-check wall clock, seconds (default 900)
//
// Exit code: 0 when every check that ran passed, 1 when one failed, 2 when nothing could run (the services
// are not up, or no browser was found). A check is a program: it prints "ok <what>" / "FAIL <what>" lines
// and exits non-zero on failure, so this file does not need to know what any of them assert.
//
// A failed check is retried once by default and reported as `flaky` when the retry passes: the UI checks
// drive a real browser against a real server, and on a loaded machine a fixed wait inside one of them can
// expire before the page settles. The retry is *visible* - a flaky line means "look at this check", never
// "this is fine" - and `--retry 0` is what CI should use to be strict.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const flag = (name) => args.includes(`--${name}`);
const value = (name, fallback) => {
  const at = args.indexOf(`--${name}`);
  return at >= 0 && args[at + 1] && !args[at + 1].startsWith('--') ? args[at + 1] : fallback;
};

const BROWSERS = [
  process.env.PG_BROWSER,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/microsoft-edge', '/usr/bin/microsoft-edge-stable',
  '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable',
  '/usr/bin/chromium', '/usr/bin/chromium-browser',
].filter(Boolean);
const browserPath = BROWSERS.find((path) => { try { return statSync(path).isFile(); } catch { return false; } });

/** Reads the "// needs:" and "// what:" markers the published checks carry. */
function describe(file) {
  const text = readFileSync(join(here, file), 'utf8').split(/\r?\n/).slice(0, 6);
  const needs = (/^\/\/ needs:\s*(\S+)/.exec(text.find((line) => line.startsWith('// needs:')) || '') || [])[1] || 'service';
  const what = (/^\/\/ what:\s*(.+)$/.exec(text.find((line) => line.startsWith('// what:')) || '') || [])[1] || '';
  return { file, needs, what };
}

const all = readdirSync(here)
  .filter((name) => /^verify-.*\.(mjs|cjs)$/.test(name))
  .sort()
  .map(describe);
const only = value('only', null);
const selected = all.filter((check) => !only || check.file.includes(only));

if (flag('list') || !selected.length) {
  console.log(`${all.length} live check(s) in verification/\n`);
  for (const check of all) {
    console.log(`  ${check.file.padEnd(30)} needs=${check.needs.padEnd(8)} ${check.what}`);
  }
  console.log(`\nbrowser: ${browserPath || 'not found (set PG_BROWSER to a Chromium-based browser)'}`);
  console.log('service: expected at http://localhost:8081 (tools/start-phoenixgraph.sh --no-build)');
  process.exit(only && !selected.length ? 2 : 0);
}

/** Is the service the checks talk to actually up? Without it every HTTP check would fail for the wrong reason. */
async function serviceUp() {
  if (flag('no-service')) return false;
  try {
    const response = await fetch('http://localhost:8081/api/v1/graphs', { signal: AbortSignal.timeout(4000) });
    return response.ok;
  } catch {
    return false;
  }
}

const up = await serviceUp();
const timeout = Number(value('timeout', '900')) * 1000;
const retries = Number(value('retry', '1'));
console.log(`running ${selected.length} check(s): service=${up ? 'up' : 'DOWN'} browser=${browserPath ? 'found' : 'missing'}`
  + `${retries ? ` (retry ${retries} on failure)` : ' (strict)'}\n`);

const spawnCheck = (check) => spawnSync(process.execPath, [join(here, check.file)], {
  stdio: ['ignore', 'pipe', 'pipe'],
  timeout,
  env: { ...process.env, PG_BROWSER: process.env.PG_BROWSER || browserPath },
});

let failed = 0;
let flaky = 0;
let skipped = 0;
const results = [];
for (const check of selected) {
  const needsBrowser = check.needs === 'browser';
  const needsService = check.needs !== 'none';
  if (needsBrowser && (flag('no-browser') || !browserPath)) {
    skipped += 1;
    results.push([check.file, 'skipped', needsBrowser && !browserPath ? 'no browser (set PG_BROWSER)' : '--no-browser']);
    continue;
  }
  if (needsService && !up) {
    skipped += 1;
    results.push([check.file, 'skipped', 'the service is not up on 8081']);
    continue;
  }
  const started = Date.now();
  let run = spawnCheck(check);
  let attempt = 0;
  while (run.status !== 0 && attempt < retries) {
    attempt += 1;
    console.log(`    ${check.file} failed, retrying (attempt ${attempt + 1})`);
    run = spawnCheck(check);
  }
  const output = `${run.stdout || ''}${run.stderr || ''}`.split(/\r?\n/).filter(Boolean);
  const ok = output.filter((line) => line.startsWith('ok ')).length;
  const bad = output.filter((line) => line.startsWith('FAIL')).length;
  const skippedItself = run.status === 0 && ok === 0 && output.some((line) => line.startsWith('skip '));
  const passed = run.status === 0 && !skippedItself;
  const flakyNow = passed && attempt > 0;
  if (!passed && !skippedItself) failed += 1;
  else if (skippedItself) skipped += 1;
  else if (flakyNow) flaky += 1;
  results.push([check.file, skippedItself ? 'skipped' : passed ? (flakyNow ? 'flaky' : 'pass') : 'FAIL',
    `${ok} assertion(s)${bad ? `, ${bad} failed` : ''} in ${((Date.now() - started) / 1000).toFixed(1)}s`]);
  // a failing check prints *all* its FAIL lines, not just the tail: a flake nobody can read is useless
  const interesting = passed && !flakyNow ? output.slice(-1) : output.filter((line) => line.startsWith('FAIL') || line.startsWith('ok ') || /FAILURES/.test(line)).slice(-14);
  for (const line of interesting) console.log(`    ${line}`);
}

console.log('\n' + '-'.repeat(78));
for (const [file, status, detail] of results) {
  const label = status === 'pass' ? 'ok  ' : status === 'FAIL' ? 'FAIL' : status === 'flaky' ? 'FLKY' : 'skip';
  console.log(`${label} ${file.padEnd(30)} ${detail}`);
}
console.log('-'.repeat(78));
console.log(`${results.filter((r) => r[1] === 'pass').length} passed, ${failed} failed, ${flaky} flaky, ${skipped} skipped`);
if (failed) process.exit(1);
if (skipped === selected.length) process.exit(2);
process.exit(0);
