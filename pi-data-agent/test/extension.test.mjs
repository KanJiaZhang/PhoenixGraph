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

test('the extension registers the six tools, the /data command and the lifecycle hook', () => {
  const fake = fakePi();
  extension(fake.pi, { env: {}, home: mkdtempSync(join(tmpdir(), 'pda-home-')) });
  assert.deepEqual([...fake.tools.keys()].sort(), [
    'pg_data_attribute', 'pg_data_build', 'pg_data_dashboard', 'pg_data_edit', 'pg_data_model', 'pg_data_scan',
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
