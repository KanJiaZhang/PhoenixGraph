import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import extension from '../extensions/pi-data-agent/index.js';

function fakePi() {
  const tools = new Map();
  const commands = new Map();
  const handlers = new Map();
  const entries = [];
  const notifications = [];
  const pi = {
    registerTool(tool) { tools.set(tool.name, tool); },
    registerCommand(name, definition) { commands.set(name, definition); },
    on(event, handler) { handlers.set(event, handler); },
    appendEntry(type, data) { entries.push({ type, data }); },
    events: { emit() {} },
  };
  const ctx = {
    cwd: process.cwd(),
    hasUI: false,
    ui: { notify(message, level) { notifications.push({ message, level }); } },
    sessionManager: { getBranch() { return []; } },
  };
  return { pi, tools, commands, handlers, entries, notifications, ctx };
}

test('the extension registers the ten tools, the /data command and the lifecycle hook', () => {
  const fake = fakePi();
  extension(fake.pi, { env: {}, home: mkdtempSync(join(tmpdir(), 'pda-home-')) });
  assert.deepEqual([...fake.tools.keys()].sort(), [
    'pg_data_ask', 'pg_data_attribute', 'pg_data_build', 'pg_data_dashboard', 'pg_data_edit', 'pg_data_model', 'pg_data_scan', 'pg_data_similar',
    'pg_risk_attribution', 'pg_risk_dashboard',
  ]);
  assert.ok(fake.commands.has('data'));
  assert.ok(fake.handlers.has('before_agent_start'));
  assert.equal(fake.tools.get('pg_data_scan').annotations.readOnlyHint, true);
  assert.equal(fake.tools.get('pg_data_edit').annotations.destructiveHint, true);
});

test('a project file makes the contract part of the prompt', async () => {
  const home = mkdtempSync(join(tmpdir(), 'pda-home-'));
  const cwd = mkdtempSync(join(tmpdir(), 'pda-cwd-'));
  try {
    mkdirSync(join(cwd, '.pi'));
    writeFileSync(join(cwd, '.pi', 'data-agent.json'), JSON.stringify({ graph: 'sales-model', endpoint: 'http://localhost:8081', dataGraph: 'demo-data', vertexType: 'MarketingDay' }));
    const fake = fakePi();
    extension(fake.pi, { env: {}, home });
    const event = { messageType: 'user', prompt: 'build the model', systemPromptOptions: { sections: {} } };
    await fake.handlers.get('before_agent_start')(event, { ...fake.ctx, cwd });
    const block = event.systemPromptOptions.sections.piDataAgent;
    assert.match(block, /^\[pi-data-agent\] graph=sales-model/);
    assert.match(block, /facts=demo-data\/MarketingDay/);
    assert.match(block, /contract:/);
    assert.match(block, /pg_data_build/);
  } finally {
    rmSync(home, { recursive: true, force: true });
    rmSync(cwd, { recursive: true, force: true });
  }
});

test('/data graph commits session state and notifies', async () => {
  const home = mkdtempSync(join(tmpdir(), 'pda-home-'));
  const cwd = mkdtempSync(join(tmpdir(), 'pda-cwd-'));
  try {
    const fake = fakePi();
    extension(fake.pi, { env: {}, home });
    const ctx = { ...fake.ctx, cwd };
    await fake.commands.get('data').handler('graph sales-model', ctx);
    assert.ok(fake.entries.some((entry) => entry.type === 'pi-data-agent.state' && entry.data.graph === 'sales-model'));
    assert.ok(fake.notifications.some((item) => /graph is now sales-model/.test(item.message)));
  } finally {
    rmSync(home, { recursive: true, force: true });
    rmSync(cwd, { recursive: true, force: true });
  }
});

/** A fake HTTP layer: enough of the read path for a plan, and a record of everything asked. */
function fakeFetch() {
  const calls = [];
  const nodes = [
    { id: 'model/sales_model', entityType: 'Model', name: 'model/sales-model', dataGraph: 'sales-facts', vertexType: 'SalesDay' },
    { id: 'metric/营收', entityType: 'Metric', name: '营收', expression: 'SUM(net_revenue)', graphProperty: 'net_revenue' },
    { id: 'dim/日期', entityType: 'Dimension', name: '日期', graphProperty: 'year=year,month=month' },
    { id: 'dim/渠道', entityType: 'Dimension', name: '渠道', graphProperty: 'channel' },
  ];
  const fetchImpl = async (url, init = {}) => {
    const target = String(url);
    const body = init.body ? JSON.parse(init.body) : {};
    calls.push({ url: target, body });
    const json = (value) => ({ ok: true, status: 200, text: async () => JSON.stringify(value) });
    if (target.includes('/api/v1/graphs')) return json([{ name: 'sales-model', storage: 'Aerospike', vertexTypes: ['Node'], edgeTypes: ['Relation'] }]);
    if (target.includes('/api/v1/query')) {
      const gremlin = String(body.query || '');
      const quoted = (gremlin.match(/'[^']+'/g) || []);
      const ids = quoted.filter((value) => value !== "'Node'");
      // the catalog read asks for the Model node alone; a node batch asks for several ids
      if (gremlin.includes('elementMap') && ids.length === 1 && ids[0].startsWith("'model/")) {
        return json([{ id: 'model/sales_model', nodeIds: nodes.map((node) => node.id), edgeIds: [] }]);
      }
      if (gremlin.includes("outE('Relation')")) return json([]);
      if (quoted.includes("'Node'")) return json(nodes.filter((node) => ids.some((id) => id === `'${node.id}'`)));
      return json([]);
    }
    return json({});
  };
  return { calls, fetchImpl };
}
function uiContext(approve) {
  const asked = [];
  return {
    asked,
    cwd: process.cwd(),
    hasUI: true,
    mode: 'tui',
    ui: {
      confirm: async (title, text) => { asked.push({ title, text }); return approve; },
      notify() {},
    },
    sessionManager: { getBranch() { return []; } },
  };
}

test('in an interactive session the tool shows the plan and executes nothing until the user agrees', async () => {
  const home = mkdtempSync(join(tmpdir(), 'pda-home-'));
  try {
    const fake = fakeFetch();
    const extensionWith = extension;
    const tools = new Map();
    const pi = {
      registerTool(tool) { tools.set(tool.name, tool); },
      registerCommand() {}, on() {}, appendEntry() {}, events: { emit() {} },
    };
    extensionWith(pi, { env: {}, home, fetchImpl: fake.fetchImpl });

    const declined = uiContext(false);
    const debugAsk = await tools.get('pg_data_ask').execute('t0', { question: '2025年营收按渠道拆分，前3', graph: 'sales-model', autoApprove: true }, undefined, undefined, { hasUI: false, cwd: process.cwd(), sessionManager: { getBranch() { return []; } } });

    const answer = await tools.get('pg_data_ask').execute('t1', { question: '2025年营收按渠道拆分，前3', graph: 'sales-model' }, undefined, undefined, declined);
    assert.equal(declined.asked.length, 1, 'the user was asked once');
    assert.match(declined.asked[0].text, /将执行：看板/);
    assert.match(answer.content[0].text, /未执行（未确认）/);
    assert.equal(answer.details.cancelled, true);
    assert.equal(fake.calls.some((call) => String(call.url).includes('/api/v1/graphs/sales-model/batch')), false);

    const accepted = uiContext(true);
    const ran = await tools.get('pg_data_ask').execute('t2', { question: '2025年营收按渠道拆分，前3', graph: 'sales-model' }, undefined, undefined, accepted);
    assert.equal(accepted.asked.length, 1, 'accepting also asks, then runs');
    assert.equal(ran.details.confirmed, true);

    // a session without a UI (print, JSON, a nested tool call) does not ask and says it was not confirmed
    const headless = await tools.get('pg_data_ask').execute('t3', { question: '2025年营收按渠道拆分，前3', graph: 'sales-model' }, undefined, undefined, { hasUI: false, cwd: process.cwd(), sessionManager: { getBranch() { return []; } } });
    assert.equal(headless.details.confirmed, false);
  } finally {
    rmSync(home, { recursive: true, force: true });
  }
});
