// P0 + P1 of docs/PI-PLUGIN.md, asserted without pi: a fake `pi` records what the extension registers, a fake
// `ctx` supplies a session branch and a cwd, and a fake fetch answers the HTTP calls. What is checked is what
// the design promises: the MCP server is registered, the two tools exist, `pg_asof` pins the instant, the pin is
// inherited by `pg_context`, it survives a new session (the state file), the branch wins over the file, and an
// unreachable service produces an instruction instead of an exception.
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

import phoenixgraphExtension, { stateFile, toInstant } from '../index.js';

/** A fake pi that keeps every registration and lets the test drive the handlers. */
function fakePi() {
  const tools = new Map();
  const commands = new Map();
  const servers = new Map();
  const entries = [];
  const handlers = new Map();
  return {
    tools, commands, servers, entries, handlers,
    registerTool(tool) { tools.set(tool.name, tool); },
    registerCommand(name, options) { commands.set(name, options); },
    registerMcpServer(name, config) { servers.set(name, config); },
    appendEntry(customType, data) { entries.push({ type: 'custom', customType, data }); },
    on(event, handler) { handlers.set(event, handler); },
    events: { emit() {} },
  };
}

function fakeContext({ cwd = mkdtempSync(join(tmpdir(), 'pg-ext-cwd-')), branch = [] } = {}) {
  const notifications = [];
  return {
    cwd,
    notifications,
    sessionManager: { getBranch: () => branch },
    ui: { notify: (message, type) => notifications.push({ message, type }) },
  };
}

function fakeFetch(handler) {
  const calls = [];
  const fetch = async (url, init) => {
    calls.push({ url, body: init?.body ? JSON.parse(init.body) : null });
    const payload = handler(url, init);
    return { ok: true, status: 200, text: async () => JSON.stringify(payload) };
  };
  fetch.calls = calls;
  return fetch;
}

const contextAnswer = (question) => ({
  tool: 'context',
  graph: 'incident-triage',
  request: { question },
  entities: [{ id: 'deploy-48', type: 'Deploy', name: 'deploy-48', via: 'name' }],
  relations: [{ head: 'deploy-48', relation: 'caused', tail: 'cache-key-prefix-churn', sentence: 'deploy-48 caused cache-key-prefix-churn.' }],
  citations: [{ chunkId: '30-change-window#7' }],
  coverage: { entities: 9, relations: 6, citations: 3, seeds: 2, hops: 1 },
});

function setup({ home, fetchHandler, branch } = {}) {
  const pi = fakePi();
  const fetch = fakeFetch(fetchHandler || ((url) => (url.includes('/context') ? contextAnswer('q') : {})));
  const extension = phoenixgraphExtension(pi, { home: home || mkdtempSync(join(tmpdir(), 'pg-ext-home-')), fetch });
  const ctx = fakeContext({ branch });
  return { pi, fetch, extension, ctx };
}

test('P0: the MCP server is registered, exposed to codemode, pointing at a real script', () => {
  const { pi } = setup();
  const server = pi.servers.get('phoenixgraph');
  assert.ok(server, 'the phoenixgraph MCP server is registered');
  assert.equal(server.exposure, 'codemode', 'fourteen tools must not crowd the model context');
  assert.equal(server.command, 'node');
  assert.match(server.args[0], /tools[\\/]mcp-server\.mjs$/);
  assert.ok(server.description.includes('coverage'), 'the description says what the server is for');
});

test('P1: the two tools and the /pg command are registered', () => {
  const { pi } = setup();
  assert.deepEqual([...pi.tools.keys()].sort(), ['pg_asof', 'pg_context', 'pg_remember', 'pg_triage']);
  assert.ok(pi.commands.get('pg'), '/pg is registered');
  assert.equal(pi.tools.get('pg_context').parameters.required[0], 'question');
});

test('P1: an instant is normalised, and a bad one is refused with the shape it wants', () => {
  assert.equal(toInstant('2026-10-21'), '2026-10-21T00:00:00.000Z');
  assert.equal(toInstant('2026-10-21T09:30:00Z'), '2026-10-21T09:30:00.000Z');
  assert.equal(toInstant(null), null);
  assert.throws(() => toInstant('last tuesday'), /2026-10-21/);
});

test('P1: pg_asof pins the instant, and the next pg_context inherits it', async () => {
  const { pi, fetch, ctx } = setup();
  await pi.on_registered?.();
  pi.handlers.get('session_start')({}, ctx);

  const pinned = await pi.tools.get('pg_asof').execute('t1', { instant: '2026-10-21' }, undefined, undefined, ctx);
  assert.match(pinned.content[0].text, /2026-10-21T00:00:00.000Z/);

  const answer = await pi.tools.get('pg_context').execute('t2', { question: 'what broke?' }, undefined, undefined, ctx);
  const body = fetch.calls.at(-1).body;
  assert.equal(body.asOf, '2026-10-21T00:00:00.000Z', 'the tool call carries the pinned instant');
  assert.match(answer.content[0].text, /as of 2026-10-21T00:00:00\.000Z/);
  assert.match(answer.content[0].text, /deploy-48 -caused-> cache-key-prefix-churn/);
  assert.match(answer.content[0].text, /deploy-48 caused cache-key-prefix-churn\./, 'the sentence is quoted');
  assert.match(answer.content[0].text, /coverage: 9 entities, 6 relations, 3 citations/);
});

test('P1: a tool call may override the pin for one question without moving it', async () => {
  const { pi, fetch, ctx } = setup();
  pi.handlers.get('session_start')({}, ctx);
  await pi.tools.get('pg_asof').execute('t1', { instant: '2026-10-21' }, undefined, undefined, ctx);
  await pi.tools.get('pg_context').execute('t2', { question: 'q', asOf: '2026-09-13' }, undefined, undefined, ctx);
  assert.equal(fetch.calls.at(-1).body.asOf, '2026-09-13T00:00:00.000Z');
  const status = await pi.tools.get('pg_asof').execute('t3', {}, undefined, undefined, ctx);
  assert.match(status.content[0].text, /2026-10-21T00:00:00\.000Z/, 'the session pin is unchanged');
});

test('P1: switching the timeline off clears the pin and stops sending it', async () => {
  const { pi, fetch, ctx } = setup();
  pi.handlers.get('session_start')({}, ctx);
  await pi.tools.get('pg_asof').execute('t1', { instant: '2026-10-21' }, undefined, undefined, ctx);
  await pi.tools.get('pg_asof').execute('t2', { on: false }, undefined, undefined, ctx);
  await pi.tools.get('pg_context').execute('t3', { question: 'q' }, undefined, undefined, ctx);
  assert.equal(fetch.calls.at(-1).body.asOf, undefined, 'no instant is sent while the timeline is off');
});

test('P1: the state survives a conversation (the file), and the branch wins over the file', async () => {
  const home = mkdtempSync(join(tmpdir(), 'pg-ext-home-'));
  const first = setup({ home });
  first.pi.handlers.get('session_start')({}, first.ctx);
  await first.pi.tools.get('pg_asof').execute('t1', { instant: '2026-10-21' }, undefined, undefined, first.ctx);
  first.pi.commands.get('pg').handler('graph incident-triage', first.ctx);

  const saved = JSON.parse(readFileSync(stateFile(home), 'utf8'));
  assert.equal(saved.asOf, '2026-10-21T00:00:00.000Z', 'the instant is on disk, outside the session');
  assert.ok(first.pi.entries.some((entry) => entry.customType === 'phoenixgraph.state'),
    'and in the session as a custom entry (not sent to the model)');

  // a new conversation: a fresh extension instance reads the file
  const second = setup({ home });
  second.pi.handlers.get('session_start')({}, second.ctx);
  const status = await second.pi.tools.get('pg_asof').execute('t1', {}, undefined, undefined, second.ctx);
  assert.match(status.content[0].text, /2026-10-21T00:00:00\.000Z/, 'a new session starts where the last one left off');

  // a branch that recorded a different instant: the conversation wins over the file
  const branch = [{ type: 'custom', customType: 'phoenixgraph.state', data: { asOf: '2026-10-26T00:00:00.000Z', timeline: true } }];
  const third = setup({ home, branch });
  third.pi.handlers.get('session_start')({}, third.ctx);
  const branched = await third.pi.tools.get('pg_asof').execute('t1', {}, undefined, undefined, third.ctx);
  assert.match(branched.content[0].text, /2026-10-26T00:00:00\.000Z/);
});

test('P1: a project file pins the graph and the endpoint, not the instant', async () => {
  const home = mkdtempSync(join(tmpdir(), 'pg-ext-home-'));
  const cwd = mkdtempSync(join(tmpdir(), 'pg-ext-cwd-'));
  mkdirSync(join(cwd, '.pi'), { recursive: true });
  writeFileSync(join(cwd, '.pi', 'phoenixgraph.json'), JSON.stringify({ graph: 'ops-estate', endpoint: 'http://pg.internal:8081' }));
  const { pi, ctx } = setup({ home });
  const projectCtx = { ...ctx, cwd };
  pi.handlers.get('session_start')({}, projectCtx);
  const status = await pi.tools.get('pg_asof').execute('t1', {}, undefined, undefined, projectCtx);
  assert.equal(status.details.graph, 'ops-estate');
  assert.equal(status.details.endpoint, 'http://pg.internal:8081');
  assert.equal(status.details.asOf, null, 'the instant is the user\'s decision, not the repository\'s');
});

test('P1: /pg reports status and sets the timeline', async () => {
  const { pi, ctx } = setup();
  pi.handlers.get('session_start')({}, ctx);
  await pi.commands.get('pg').handler('', ctx);
  assert.match(ctx.notifications.at(-1).message, /graph:\s+incident-triage/);
  assert.match(ctx.notifications.at(-1).message, /timeline: off/);

  await pi.commands.get('pg').handler('timeline 2026-10-21', ctx);
  assert.match(ctx.notifications.at(-1).message, /2026-10-21T00:00:00\.000Z/);
  await pi.commands.get('pg').handler('status', ctx);
  assert.match(ctx.notifications.at(-1).message, /pinned to 2026-10-21/);
});

test('P1: an unreachable service is explained, not thrown at the model', async () => {
  const pi = fakePi();
  const failing = async () => { throw new Error('fetch failed'); };
  phoenixgraphExtension(pi, { home: mkdtempSync(join(tmpdir(), 'pg-ext-home-')), fetch: failing });
  const ctx = fakeContext();
  pi.handlers.get('session_start')({}, ctx);
  const answer = await pi.tools.get('pg_context').execute('t1', { question: 'q' }, undefined, undefined, ctx);
  assert.match(answer.content[0].text, /not answering at http:\/\/localhost:8081/);
  assert.match(answer.content[0].text, /start-phoenixgraph\.sh/, 'it says what to do');
});

test('P1: a question that names nothing is reported as a refusal, not as an empty success', async () => {
  const { pi, ctx } = setup({ fetchHandler: () => ({ entities: [], relations: [], coverage: { entities: 0, relations: 0 } }) });
  pi.handlers.get('session_start')({}, ctx);
  const answer = await pi.tools.get('pg_context').execute('t1', { question: 'anything' }, undefined, undefined, ctx);
  assert.match(answer.content[0].text, /no relation was found: say so, do not fill the gap/);
});

// ---- P2: the ranked answer and the contract in the prompt --------------------------------------------------
const artifact = {
  graph: 'incident-triage',
  entities: [
    { name: 'redis-cpu-percent', type: 'Metric' },
    { name: 'error-rate-5xx', type: 'Metric' },
    { name: 'deploy-48', type: 'Deploy' },
    { name: 'deploy-42', type: 'Deploy' },
  ],
  relations: [
    { head: 'redis-cpu-percent', relation: 'affects', tail: 'redis-cache' },
    { head: 'error-rate-5xx', relation: 'affects', tail: 'checkout-api' },
    { head: 'deploy-48', relation: 'caused', tail: 'churn', sentence: 'deploy-48 caused churn.', validFrom: Date.parse('2026-10-24') },
    { head: 'deploy-48', relation: 'affects', tail: 'redis-cache' },
    { head: 'deploy-42', relation: 'caused', tail: 'storm', sentence: 'deploy-42 caused storm.', validFrom: Date.parse('2026-09-12') },
    { head: 'churn', relation: 'affects', tail: 'redis-cache' },
    { head: 'storm', relation: 'affects', tail: 'redis-cache' },
    { head: 'INC-1042', relation: 'reported', tail: 'storm' },
  ],
  chunks: [],
};

function triageFetch() {
  const calls = [];
  const impl = async (url, init) => {
    calls.push({ url, body: init?.body ? JSON.parse(init.body) : null });
    const payload = url.includes('/agent/index/') ? artifact
      : {
        entities: [
          { name: 'redis-cpu-percent', type: 'Metric', via: 'name' },
          { name: 'error-rate-5xx', type: 'Metric', via: 'name' },
        ],
      };
    return { ok: true, status: 200, json: async () => payload, text: async () => JSON.stringify(payload) };
  };
  impl.calls = calls;
  return impl;
}

test('P2: pg_triage ranks the causes and renders the evidence', async () => {
  const pi = fakePi();
  const fetchImpl = triageFetch();
  phoenixgraphExtension(pi, { home: mkdtempSync(join(tmpdir(), 'pg-ext-home-')), fetch: fetchImpl });
  const ctx = fakeContext();
  pi.handlers.get('session_start')({}, ctx);

  const answer = await pi.tools.get('pg_triage').execute('t1', { symptom: 'redis cpu 95%, p99 up' }, undefined, undefined, ctx);
  assert.match(answer.content[0].text, /1\. deploy-48 -> churn\s+score/);
  assert.match(answer.content[0].text, /"deploy-48 caused churn\."/, 'the sentence behind the edge is quoted');
  assert.match(answer.content[0].text, /most likely: deploy-48/);
  assert.match(answer.content[0].text, /what would overturn it:/);
  assert.equal(answer.details.result.ranked[0].cause, 'deploy-48');
});

test('P2: pg_triage answers as of the pinned instant, so a later change is not a candidate', async () => {
  const pi = fakePi();
  const fetchImpl = triageFetch();
  phoenixgraphExtension(pi, { home: mkdtempSync(join(tmpdir(), 'pg-ext-home-')), fetch: fetchImpl });
  const ctx = fakeContext();
  pi.handlers.get('session_start')({}, ctx);
  await pi.tools.get('pg_asof').execute('t0', { instant: '2026-10-20' }, undefined, undefined, ctx);
  const answer = await pi.tools.get('pg_triage').execute('t1', { symptom: 'redis cpu 95%' }, undefined, undefined, ctx);
  assert.equal(answer.details.result.ranked[0].cause, 'deploy-42', 'deploy-48 did not exist on 2026-10-20');
  assert.ok(fetchImpl.calls.some((call) => call.body && call.body.asOf === '2026-10-20T00:00:00.000Z'));
});

test('P2: a symptom that names nothing is a refusal, and the model is told not to name a cause', async () => {
  const pi = fakePi();
  const fetchImpl = async (url) => {
    const payload = url.includes('/agent/index/') ? artifact : { entities: [] };
    return { ok: true, status: 200, json: async () => payload, text: async () => JSON.stringify(payload) };
  };
  phoenixgraphExtension(pi, { home: mkdtempSync(join(tmpdir(), 'pg-ext-home-')), fetch: fetchImpl });
  const ctx = fakeContext();
  pi.handlers.get('session_start')({}, ctx);
  const answer = await pi.tools.get('pg_triage').execute('t1', { symptom: 'the certificate expiring?' }, undefined, undefined, ctx);
  assert.match(answer.content[0].text, /refused: no entity in the graph is named by this symptom/);
  assert.match(answer.content[0].text, /Do not name a cause/);
});

test('P2: the contract is a prompt section, only when the session is about a graph', async () => {
  const home = mkdtempSync(join(tmpdir(), 'pg-ext-home-'));
  const first = setup({ home });
  const quiet = { systemPromptOptions: { sections: { preamble: 'x' } } };
  await first.pi.handlers.get('before_agent_start')(quiet, first.ctx);
  assert.equal(quiet.systemPromptOptions.sections.phoenixgraph, undefined,
    'a fresh session with no graph history stays quiet');

  await first.pi.tools.get('pg_asof').execute('t1', { instant: '2026-10-21' }, undefined, undefined, first.ctx);
  const aware = { systemPromptOptions: { sections: { preamble: 'x' } } };
  await first.pi.handlers.get('before_agent_start')(aware, first.ctx);
  const block = aware.systemPromptOptions.sections.phoenixgraph;
  assert.match(block, /\[phoenixgraph\] graph=incident-triage/);
  assert.match(block, /pinned to 2026-10-21T00:00:00\.000Z/);
  assert.match(block, /pg_triage\(symptom\)/);
  assert.match(block, /answer contract: quote the sentence and the coverage/);
  assert.equal(aware.systemPromptOptions.sections.preamble, 'x', 'the other sections are untouched');

  await first.pi.commands.get('pg').handler('inject off', first.ctx);
  const muted = { systemPromptOptions: { sections: {} } };
  await first.pi.handlers.get('before_agent_start')(muted, first.ctx);
  assert.equal(muted.systemPromptOptions.sections.phoenixgraph, undefined, '/pg inject off silences it');
});

// ---- P3: distribution, the HTTP transport, and the team file ------------------------------------------------
test('P3: the package declares the pi entry point, and it is the file that exists', () => {
  const here = dirname(fileURLToPath(import.meta.url));
  const root = resolve(here, '..');
  const manifest = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
  assert.deepEqual(manifest.pi.extensions, ['./index.js'], 'pi discovers the extension from the manifest');
  assert.ok(existsSync(join(root, manifest.pi.extensions[0])), 'the declared entry exists');
  assert.ok((manifest.keywords || []).includes('pi-package'), 'the keyword that makes it a pi package');
  // host-provided packages must not be dependencies (they would be installed twice)
  for (const host of ['@earendil-works/pi-ai', '@earendil-works/pi-coding-agent', 'typebox']) {
    assert.equal(manifest.dependencies?.[host], undefined, `${host} must not be a dependency`);
  }
});

test('P3: a shared deployment is reached over HTTP, with the bearer token', () => {
  const pi = fakePi();
  phoenixgraphExtension(pi, {
    home: mkdtempSync(join(tmpdir(), 'pg-ext-home-')),
    env: { PHOENIXGRAPH_MCP_URL: 'http://pg.internal:8090/mcp', PHOENIXGRAPH_MCP_TOKEN: 'secret' },
  });
  const server = pi.servers.get('phoenixgraph');
  assert.equal(server.type, 'http');
  assert.equal(server.url, 'http://pg.internal:8090/mcp');
  assert.equal(server.headers.Authorization, 'Bearer secret');
  assert.equal(server.exposure, 'codemode');
});

test('P3: /pg mcp switches the transport and remembers it', async () => {
  const home = mkdtempSync(join(tmpdir(), 'pg-ext-home-'));
  const first = setup({ home });
  first.pi.handlers.get('session_start')({}, first.ctx);
  await first.pi.commands.get('pg').handler('mcp', first.ctx);
  assert.match(first.ctx.notifications.at(-1).message, /mcp: stdio/);

  await first.pi.commands.get('pg').handler('mcp http://pg.internal:8090/mcp', first.ctx);
  assert.match(first.ctx.notifications.at(-1).message, /takes effect on the next session/);
  assert.equal(JSON.parse(readFileSync(stateFile(home), 'utf8')).mcpUrl, 'http://pg.internal:8090/mcp');

  // a new process reads it and registers the HTTP server
  const pi = fakePi();
  phoenixgraphExtension(pi, { home });
  const ctx = fakeContext();
  pi.handlers.get('session_start')({}, ctx);
  await pi.commands.get('pg').handler('status', ctx);
  assert.match(ctx.notifications.at(-1).message, /mcp:\s+http http:\/\/pg\.internal:8090\/mcp/);
});

// ---- the UI command: show what the agent just used ---------------------------------------------------------
test('/pg show opens the UI at the context of the last answer', async () => {
  const opened = [];
  const pi = fakePi();
  pi.exec = async (command, args) => { opened.push({ command, args }); return { exitCode: 0 }; };
  const fetchImpl = fakeFetch((url) => (url.includes('/context') ? contextAnswer('q') : {}));
  phoenixgraphExtension(pi, { home: mkdtempSync(join(tmpdir(), 'pg-ext-home-')), fetch: fetchImpl, platform: 'linux' });
  const ctx = fakeContext();
  pi.handlers.get('session_start')({}, ctx);

  await pi.tools.get('pg_asof').execute('t0', { instant: '2026-10-21' }, undefined, undefined, ctx);
  await pi.tools.get('pg_context').execute('t1', { question: 'checkout p99 up' }, undefined, undefined, ctx);
  await pi.commands.get('pg').handler('show', ctx);

  assert.equal(opened.length, 1, 'the opener was called once');
  const url = decodeURIComponent(opened[0].args[0]);
  assert.match(url, /^http:\/\/localhost:8080\/\?graph=incident-triage&q=/);
  assert.match(url, /g\.V\('deploy-48','Entity'\)\.bothE\('Relates'\)/,
    'the query is built from the entities the answer seeded on');
  assert.match(url, /asOf=/, 'and it carries the instant the answer was pinned to');
  assert.match(ctx.notifications.at(-1).message, /opened http/);
  assert.match(ctx.notifications.at(-1).message, /from: checkout p99 up/);
});

const drawingFetch = (inner) => async (url, init) => (url.includes('/api/v1/query')
  ? { ok: true, status: 200, json: async () => ({ subgraph: { vertices: [{ id: 'seed' }], edges: [] } }),
      text: async () => JSON.stringify({ subgraph: { vertices: [{ id: 'seed' }], edges: [] } }) }
  : inner(url, init));

test('/pg show <entity> shows one entity, and still works when nothing can open a browser', async () => {
  const pi = fakePi();
  pi.exec = async () => { throw new Error('no opener here'); };
  phoenixgraphExtension(pi, { home: mkdtempSync(join(tmpdir(), 'pg-ext-home-')), fetch: drawingFetch(fakeFetch(() => contextAnswer('q'))) });
  const ctx = fakeContext();
  pi.handlers.get('session_start')({}, ctx);
  await pi.commands.get('pg').handler('show redis-cache', ctx);
  const message = ctx.notifications.at(-1).message;
  assert.match(message, /open this: http:\/\/localhost:8080\//, 'the URL is printed when it cannot be opened');
  assert.match(message, /g\.V\('redis-cache','Entity'\)/);
  assert.equal(ctx.notifications.at(-1).type, 'warning');
});

test('/pg show with nothing asked shows the busiest entities instead of an empty canvas', async () => {
  const opened = [];
  const pi = fakePi();
  pi.exec = async (command, args) => { opened.push(args[0]); return { exitCode: 0 }; };
  // the artifact is what the fallback needs: entity ids the engine can be seeded with (it has no full scan)
  phoenixgraphExtension(pi, { home: mkdtempSync(join(tmpdir(), 'pg-ext-home-')), fetch: drawingFetch(triageFetch()), platform: 'linux' });
  const ctx = fakeContext();
  pi.handlers.get('session_start')({}, ctx);

  await pi.commands.get('pg').handler('show', ctx);
  const url = decodeURIComponent(opened[0]);
  assert.match(url, /q=g\.V\('[^']+','Entity'/, 'the seeds come from the artifact, not from an empty start');
  assert.doesNotMatch(url, /limit\(0\)/, 'an empty walk is not a picture');
  assert.match(ctx.notifications.at(-1).message, /from: nothing asked yet - the \d+ most connected entities/);
});

// ---- remembering a conversation -----------------------------------------------------------------------------
function branchWith(turns) {
  return turns.map((turn, index) => ({
    type: 'message', id: `m${index}`, parentId: index ? `m${index - 1}` : null,
    timestamp: new Date(Date.UTC(2026, 9, 29, 6, index)).toISOString(),
    message: turn.role === 'assistant'
      ? { role: 'assistant', content: [{ type: 'text', text: turn.text }] }
      : { role: turn.role, content: turn.text },
  }));
}

test('pg_remember ingests the conversation with a window that starts now', async () => {
  const pi = fakePi();
  const posted = [];
  const fetchImpl = async (url, init) => {
    const payload = init?.body ? JSON.parse(init.body) : null;
    if (url.includes('/agent/ingest')) posted.push(payload);
    const body = url.includes('/agent/index/') ? artifact
      : payload && payload.documents ? { applied: true, entities: 4, relations: 2, quality: { documents: 1, chunks: 2, provenanceRate: 1, typeAgreement: 1, entityLinkRate: 1, duplicatesRemoved: 0 } }
        : contextAnswer('q');
    return { ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) };
  };
  phoenixgraphExtension(pi, { home: mkdtempSync(join(tmpdir(), 'pg-ext-home-')), fetch: fetchImpl });
  const ctx = fakeContext({
    branch: branchWith([
      { role: 'user', text: '为什么 checkout 慢？' },
      { role: 'assistant', text: 'deploy-48 caused cache-key-prefix-churn.' },
      { role: 'toolResult', text: 'huge tool output that must not be remembered' },
    ]),
  });
  pi.handlers.get('session_start')({}, ctx);

  const answer = await pi.tools.get('pg_remember').execute('t1', { note: 'checkout-api depends on redis-cache.' }, undefined, undefined, ctx);
  assert.equal(posted.length, 1, 'one ingestion');
  const body = posted[0];
  assert.equal(body.rulePack, 'incident');
  assert.equal(body.documents.length, 1);
  const document = body.documents[0];
  assert.match(document.text, /^user: 为什么 checkout 慢？/m, 'the user turn is kept, oldest first');
  assert.match(document.text, /assistant: deploy-48 caused cache-key-prefix-churn\./);
  assert.match(document.text, /checkout-api depends on redis-cache\./, 'the explicit note is appended');
  assert.doesNotMatch(document.text, /huge tool output/, 'tool output is not the reasoning; it stays out');
  assert.match(document.validFrom, /^2026-|^20\d\d-/, 'the window starts at ingest time');
  assert.equal(body.dictionary['deploy-48'], 'Deploy', 'the graph\'s own entities are the gazetteer');
  assert.match(answer.content[0].text, /4 entities, 2 relations/);
});

test('/pg remember says so when there is nothing to remember', async () => {
  const pi = fakePi();
  phoenixgraphExtension(pi, { home: mkdtempSync(join(tmpdir(), 'pg-ext-home-')), fetch: fakeFetch(() => ({})) });
  const ctx = fakeContext({ branch: [] });
  pi.handlers.get('session_start')({}, ctx);
  await pi.commands.get('pg').handler('remember', ctx);
  assert.match(ctx.notifications.at(-1).message, /nothing to remember yet/);
  assert.equal(ctx.notifications.at(-1).type, 'warning');
});

// ---- ingesting documents by hand ----------------------------------------------------------------------------
function ingestSetup({ dir, fetchImpl }) {
  const pi = fakePi();
  const posted = [];
  const fetch = async (url, init) => {
    if (init?.body && url.includes('/agent/ingest')) posted.push(JSON.parse(init.body));
    return fetchImpl(url, init);
  };
  phoenixgraphExtension(pi, { home: mkdtempSync(join(tmpdir(), 'pg-ext-home-')), fetch });
  const ctx = fakeContext({ cwd: dir });
  pi.handlers.get('session_start')({}, ctx);
  return { pi, ctx, posted };
}

const ingestAnswer = (body) => ({
  applied: body.apply, entities: 3, relations: 2,
  quality: { documents: body.documents.length, chunks: body.documents.length, provenanceRate: 1, typeAgreement: 1, entityLinkRate: 1 },
});

test('/pg ingest is a dry run unless --apply, and reads the directory it is given', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'pg-ingest-'));
  writeFileSync(join(dir, 'runbook.md'), 'checkout-api depends on redis-cache.\n');
  writeFileSync(join(dir, 'notes.txt'), 'Deploy 41 caused the outage.\n');
  writeFileSync(join(dir, 'logo.png'), 'binary-ish');
  writeFileSync(join(dir, 'dictionary.json'), JSON.stringify({ 'checkout-api': 'Service', 'redis-cache': 'Datastore' }));

  const { pi, ctx, posted } = ingestSetup({
    dir,
    fetchImpl: async (url, init) => {
      const body = url.includes('/agent/ingest') ? ingestAnswer(JSON.parse(init.body)) : artifact;
      return { ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) };
    },
  });

  await pi.commands.get('pg').handler('ingest .', ctx);
  assert.equal(posted.length, 1);
  assert.equal(posted[0].apply, false, 'a command that writes must be asked to write');
  assert.deepEqual(posted[0].documents.map((document) => document.id).sort(), ['notes', 'runbook'],
    'the text files are read, the image and the dictionary are not documents');
  assert.equal(posted[0].dictionary['redis-cache'], 'Datastore', 'the directory gazetteer is merged in');
  assert.match(ctx.notifications.at(-1).message, /dry run: 2 document\(s\) would be ingested/);
  assert.match(ctx.notifications.at(-1).message, /not text \(\.png\)/);
});

test('/pg ingest --apply --from writes, with a validity window on every document', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'pg-ingest-'));
  writeFileSync(join(dir, 'incident.md'), 'deploy-48 caused cache-key-prefix-churn.\n');
  const { pi, ctx, posted } = ingestSetup({
    dir,
    fetchImpl: async (url, init) => {
      const body = url.includes('/agent/ingest') ? ingestAnswer(JSON.parse(init.body)) : artifact;
      return { ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) };
    },
  });

  await pi.commands.get('pg').handler('ingest incident.md --apply --from 2026-10-24 --graph ops', ctx);
  assert.equal(posted[0].apply, true);
  assert.equal(posted[0].graph, 'ops');
  assert.equal(posted[0].documents[0].validFrom, '2026-10-24', 'the window is passed through as written');
  assert.match(ctx.notifications.at(-1).message, /ingested 1 document\(s\) into 'ops'/);
});

test('/pg ingest says so when there is nothing to read', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'pg-ingest-'));
  const { pi, ctx, posted } = ingestSetup({ dir, fetchImpl: async () => ({ ok: true, status: 200, json: async () => ({}), text: async () => '{}' }) });
  await pi.commands.get('pg').handler('ingest .', ctx);
  assert.equal(posted.length, 0, 'an empty directory does not post an empty ingestion');
  assert.match(ctx.notifications.at(-1).message, /nothing to ingest/);
});

test('/pg show explains an empty view instead of opening one', async () => {
  const opened = [];
  const pi = fakePi();
  pi.exec = async (command, args) => { opened.push(args[0]); return { exitCode: 0 }; };
  // the query returns nothing under the pin: the facts were ingested after the instant the session asked as of
  const fetchImpl = async (url, init) => {
    const payload = url.includes('/api/v1/query') ? { subgraph: { vertices: [], edges: [] } }
      : url.includes('/agent/index/') ? artifact : contextAnswer('q');
    return { ok: true, status: 200, json: async () => payload, text: async () => JSON.stringify(payload) };
  };
  phoenixgraphExtension(pi, { home: mkdtempSync(join(tmpdir(), 'pg-ext-home-')), fetch: fetchImpl, platform: 'linux' });
  const ctx = fakeContext();
  pi.handlers.get('session_start')({}, ctx);
  await pi.tools.get('pg_asof').execute('t0', { instant: '2026-10-20' }, undefined, undefined, ctx);

  await pi.commands.get('pg').handler('show redis-cache', ctx);
  const warning = ctx.notifications.find((notification) => /this view is empty/.test(notification.message));
  assert.ok(warning, 'an empty canvas is reported, not opened silently');
  assert.match(warning.message, /pinned to 2026-10-20/, 'and the reason is the pin');
  assert.match(warning.message, /timeline off/, 'with the way out');
  assert.equal(warning.type, 'warning');
  assert.match(ctx.notifications.at(-1).message, /opened http/, 'a pinned empty view still opens, so the pin can be moved');
});

// ---- automatic per-turn memory ------------------------------------------------------------------------------
function memorySetup({ fetchImpl, branch }) {
  const pi = fakePi();
  const posts = [];
  const fetch = async (url, init) => {
    if (init?.body) posts.push({ url, body: JSON.parse(init.body) });
    return fetchImpl(url, init);
  };
  phoenixgraphExtension(pi, { home: mkdtempSync(join(tmpdir(), 'pg-ext-home-')), fetch });
  const ctx = fakeContext({ branch });
  pi.handlers.get('session_start')({}, ctx);
  return { pi, ctx, posts };
}

const memoryFetch = () => async (url, init) => {
  const body = url.includes('/agent/ingest')
    ? { applied: true, entities: 3, relations: 1, quality: { documents: 1, chunks: 2, provenanceRate: 1, typeAgreement: 1, entityLinkRate: 1 } }
    : url.includes('/agent/context') ? contextAnswer('q')
      : url.includes('/agent/index/') ? artifact : {};
  return { ok: true, status: 200, json: async () => body, text: async () => JSON.stringify(body) };
};

test('turn_end writes the question and the answer into the graph, once', async () => {
  const branch = branchWith([
    { role: 'user', text: 'checkout 为什么慢？' },
    { role: 'assistant', text: 'checkout-api depends on redis-cache.' },
  ]);
  const { pi, ctx, posts } = memorySetup({ fetchImpl: memoryFetch(), branch });
  // a session that has used the graph before: memory is on by decision
  await pi.commands.get('pg').handler('auto on', ctx);
  posts.length = 0;

  await pi.handlers.get('turn_end')({}, ctx);
  const ingest = posts.find((post) => post.url.includes('/agent/ingest'));
  assert.ok(ingest, 'the turn was ingested');
  assert.match(ingest.body.documents[0].text, /^user: checkout 为什么慢？/m);
  assert.match(ingest.body.documents[0].text, /assistant: checkout-api depends on redis-cache\./);
  assert.ok(ingest.body.documents[0].validFrom, 'with a window that starts now');
  assert.ok(pi.entries.some((entry) => entry.customType === 'phoenixgraph.turn'), 'and a session entry for the record');

  await pi.handlers.get('turn_end')({}, ctx);
  assert.equal(posts.filter((post) => post.url.includes('/agent/ingest')).length, 1,
    'the same turn is not written twice (a retry or a resume must be idempotent)');
});

test('before_agent_start puts what the graph holds about the new question into the prompt', async () => {
  const branch = branchWith([{ role: 'user', text: 'nothing yet' }, { role: 'assistant', text: 'ok' }]);
  const { pi, ctx, posts } = memorySetup({ fetchImpl: memoryFetch(), branch });
  await pi.commands.get('pg').handler('auto on', ctx);
  posts.length = 0;

  const event = { messageType: 'user', prompt: 'checkout 为什么慢', systemPromptOptions: { sections: {} } };
  await pi.handlers.get('before_agent_start')(event, ctx);
  const section = event.systemPromptOptions.sections.phoenixgraph;
  assert.match(section, /\[phoenixgraph\] graph=incident-triage/, 'the contract is still there');
  assert.match(section, /what the graph already holds about this question/);
  assert.match(section, /deploy-48 -caused-> cache-key-prefix-churn/, 'with the relations');
  assert.match(section, /coverage: 9 entities, 6 relations/, 'and the coverage');
  const asked = posts.find((post) => post.url.includes('/agent/context'));
  assert.equal(asked.body.question, 'checkout 为什么慢', 'retrieved with the question being asked');
});

test('auto memory can be switched off, and stays off across a session', async () => {
  const branch = branchWith([{ role: 'user', text: 'q' }, { role: 'assistant', text: 'a' }]);
  const home = mkdtempSync(join(tmpdir(), 'pg-ext-home-'));
  const first = memorySetup({ fetchImpl: memoryFetch(), branch });
  await first.pi.commands.get('pg').handler('auto off', first.ctx);
  first.posts.length = 0;
  await first.pi.handlers.get('turn_end')({}, first.ctx);
  const event = { messageType: 'user', prompt: 'anything', systemPromptOptions: { sections: {} } };
  await first.pi.handlers.get('before_agent_start')(event, first.ctx);
  assert.equal(first.posts.length, 0, 'off means no ingest and no retrieval');
  const section = event.systemPromptOptions.sections.phoenixgraph;
  assert.ok(!section || !/what the graph already holds/.test(section), 'a session that is not about a graph gets no section at all');
});
