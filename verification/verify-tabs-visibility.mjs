// needs: browser
// what: exactly one panel is painted per tab
// run:  node verification/run.mjs
import { spawn } from 'node:child_process';
import { mkdtempSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
// The browser is found, not hard-coded: PG_BROWSER wins, then the usual install paths.
const BROWSERS = [
  process.env.PG_BROWSER,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/microsoft-edge', '/usr/bin/microsoft-edge-stable',
  '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable',
  '/usr/bin/chromium', '/usr/bin/chromium-browser',
].filter(Boolean);
const EDGE = BROWSERS.find((path) => { try { return statSync(path).isFile(); } catch { return false; } });
if (!EDGE) {
  console.error('no Chromium-based browser found for ' + 'verify-tabs-visibility.mjs' + ': set PG_BROWSER=/path/to/msedge (or chrome/chromium)');
  process.exit(2);
}
const PORT = Number(process.env.PG_CDP_PORT) || 9300 + (process.pid % 600);
const profile = mkdtempSync(join(tmpdir(), 'pg-tabs-'));
const edge = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, '--no-first-run', 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let page = null;
for (let i = 0; i < 60 && !page; i++) { try { page = (await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()).find((t) => t.type === 'page'); } catch {} if (!page) await sleep(500); }
const socket = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => socket.addEventListener('open', r));
let id = 0; const pending = new Map();
socket.addEventListener('message', (e) => { const m = JSON.parse(e.data); const p = pending.get(m.id); if (p) { pending.delete(m.id); p(m); } });
const send = (method, params = {}) => new Promise((resolve) => { const i = ++id; pending.set(i, resolve); socket.send(JSON.stringify({ id: i, method, params })); });
const evaluate = async (expr) => (await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result?.result?.value;
await send('Page.enable'); await send('Runtime.enable');
await send('Page.navigate', { url: 'http://localhost:8080/' });
for (let i = 0; i < 60; i++) { if (await evaluate("!!document.getElementById('graphSelect') && document.getElementById('graphSelect').options.length > 0")) break; await sleep(500); }
await sleep(1500);
let failures = 0;
const painted = () => evaluate(`[...document.querySelectorAll('#sidePanel section')].filter((s) => !s.hidden && getComputedStyle(s).display !== 'none' && s.getClientRects().length > 0).map((s) => s.id)`);
/**
 * Polls until the painted set settles on what the tab promises.
 *
 * <p>A fixed sleep was the first version and it made this check flaky: clicking a tab can start work (the
 * rows tab refreshes itself, the metrics tab fetches), so under load the DOM was read mid-switch and the
 * assertion failed for a reason that has nothing to do with the bug it is here to catch.
 */
const waitForPainted = async (expected, ms = 8000) => {
  const deadline = Date.now() + ms;
  let last = [];
  while (Date.now() < deadline) {
    last = await painted();
    if (last.length === 1 && last[0] === expected) return last;
    await sleep(150);
  }
  return last;
};
for (const tab of ['stats', 'props', 'metrics', 'rows', 'stats']) {
  await evaluate(`document.querySelector('.tab[data-tab="${tab}"]').click()`);
  // 没有结果时点「结果」按设计退回「统计」（activateTab 的兜底）；其余每个 tab 应当只有自己的面板被画出来
  const expected = tab === 'rows' ? 'statsPanel' : tab + 'Panel';
  const shown = await waitForPainted(expected);
  const ok = shown.length === 1 && shown[0] === expected;
  if (!ok) { failures += 1; }
  console.log(`${ok ? 'ok  ' : 'FAIL'} tab ${tab} -> painted ${JSON.stringify(shown)}`);
}
socket.close(); edge.kill();
console.log(failures ? `${failures} FAILURES` : 'exactly one panel is painted per tab');
process.exit(failures ? 1 : 0);
