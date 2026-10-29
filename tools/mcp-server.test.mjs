import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const ROOT = fileURLToPath(new URL('..', import.meta.url));
const SERVER = join(ROOT, 'tools', 'mcp-server.mjs');

const mockApi = async () => {
  const server = createServer((request, response) => {
    if (request.url === '/api/v1/agent/tools' && request.method === 'GET') {
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({
        tools: [
          { name: 'find_entities', description: 'find entities', parameters: [{ name: 'graph', type: 'string' }, { name: 'question', type: 'string' }] },
          { name: 'related', description: 'related entities', parameters: [{ name: 'graph', type: 'string' }, { name: 'entity', type: 'string' }] },
        ],
        bindings: {
          find_entities: { method: 'POST', path: '/api/v1/agent/find-entities' },
          related: { method: 'POST', path: '/api/v1/agent/related' },
        },
      }));
      return;
    }
    if (request.url === '/api/v1/agent/find-entities' && request.method === 'POST') {
      let body = '';
      request.on('data', (chunk) => { body += chunk; });
      request.on('end', () => {
        const parsed = JSON.parse(body);
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify({ tool: 'find_entities', graph: parsed.graph, question: parsed.question }));
      });
      return;
    }
    response.writeHead(404);
    response.end();
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return server;
};

const startMcp = async (apiPort, env = {}) => {
  const child = spawn(process.execPath, ['tools/mcp-server.mjs', '--http'], {
    cwd: ROOT,
    env: {
      ...process.env,
      PHOENIXGRAPH_API: `http://127.0.0.1:${apiPort}`,
      PHOENIXGRAPH_MCP_PORT: '0',
      PHOENIXGRAPH_MCP_TOKEN: 'test-token',
      ...env,
    },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  let stderr = '';
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', (chunk) => { stderr += chunk; });
  for (let i = 0; i < 100; i += 1) {
    const match = stderr.match(/listening on http:\/\/127\.0\.0\.1:(\d+)\/mcp/);
    if (match) {
      return { child, port: Number(match[1]) };
    }
    await delay(50);
  }
  child.kill();
  throw new Error('MCP HTTP did not start: ' + stderr);
};

const rpc = async (port, body, token = 'test-token') => {
  const headers = { 'content-type': 'application/json' };
  if (token) headers.authorization = `Bearer ${token}`;
  const response = await fetch(`http://127.0.0.1:${port}/mcp`, {
    method: 'POST', headers, body: JSON.stringify(body),
  });
  return { status: response.status, body: await response.json() };
};

test('MCP HTTP requires its token and passes the graph through to the API', async () => {
  const api = await mockApi();
  const { child, port } = await startMcp(api.address().port);
  try {
    const unauthorized = await rpc(port, { jsonrpc: '2.0', id: 1, method: 'ping' }, '');
    assert.equal(unauthorized.status, 401);

    const initialize = await rpc(port, { jsonrpc: '2.0', id: 2, method: 'initialize', params: {} });
    assert.equal(initialize.status, 200);
    assert.equal(initialize.body.result.serverInfo.name, 'phoenixgraph');

    const list = await rpc(port, { jsonrpc: '2.0', id: 3, method: 'tools/list' });
    assert.deepEqual(list.body.result.tools.map((tool) => tool.name), ['find_entities', 'related', 'triage']);

    const call = await rpc(port, {
      jsonrpc: '2.0', id: 4, method: 'tools/call',
      params: { name: 'find_entities', arguments: { graph: 'demo', question: 'customer' } },
    });
    assert.equal(call.status, 200);
    const answer = JSON.parse(call.body.result.content[0].text);
    assert.equal(answer.graph, 'demo');
    assert.equal(answer.question, 'customer');

    const streamed = await fetch(`http://127.0.0.1:${port}/mcp`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: 'Bearer test-token', accept: 'text/event-stream' },
      body: JSON.stringify({ jsonrpc: '2.0', id: 5, method: 'ping' }),
    });
    assert.ok((streamed.headers.get('content-type') || '').includes('text/event-stream'));
    assert.match(await streamed.text(), /event: message/);
  } finally {
    child.kill();
    api.close();
  }
});

test('MCP HTTP honours the tool allowlist for both list and call', async () => {
  const api = await mockApi();
  const { child, port } = await startMcp(api.address().port, { PHOENIXGRAPH_MCP_TOOLS: 'find_entities' });
  try {
    const list = await rpc(port, { jsonrpc: '2.0', id: 1, method: 'tools/list' });
    assert.deepEqual(list.body.result.tools.map((tool) => tool.name), ['find_entities']);

    const blocked = await rpc(port, {
      jsonrpc: '2.0', id: 2, method: 'tools/call',
      params: { name: 'related', arguments: { graph: 'demo', entity: 'a' } },
    });
    assert.equal(blocked.body.result.isError, true);
    assert.match(blocked.body.result.content[0].text, /unknown tool/);
  } finally {
    child.kill();
    api.close();
  }
});


test('per-client tokens see only their tools', async () => {
  const api = await mockApi();
  const clients = JSON.stringify({
    'agent-token': { name: 'agent', tools: ['find_entities'] },
    'admin-token': { name: 'admin', tools: ['*'] },
  });
  const { child, port } = await startMcp(api.address().port, { PHOENIXGRAPH_MCP_CLIENTS: clients });
  try {
    const agent = await rpc(port, { jsonrpc: '2.0', id: 1, method: 'tools/list' }, 'agent-token');
    assert.deepEqual(agent.body.result.tools.map((tool) => tool.name), ['find_entities']);

    const admin = await rpc(port, { jsonrpc: '2.0', id: 2, method: 'tools/list' }, 'admin-token');
    assert.deepEqual(admin.body.result.tools.map((tool) => tool.name), ['find_entities', 'related', 'triage']);

    const rejected = await rpc(port, { jsonrpc: '2.0', id: 3, method: 'tools/list' }, 'no-such-token');
    assert.equal(rejected.status, 401);
  } finally {
    child.kill();
    api.close();
  }
});


test('MCP audit records calls and the rate limit stops the second one', async () => {
  const api = await mockApi();
  const directory = mkdtempSync(join(tmpdir(), 'phoenixgraph-mcp-audit-'));
  const audit = join(directory, 'audit.jsonl');
  const { child, port } = await startMcp(api.address().port, {
    PHOENIXGRAPH_MCP_AUDIT: audit,
    PHOENIXGRAPH_MCP_RATE_LIMIT: '1',
  });
  try {
    const call = () => rpc(port, {
      jsonrpc: '2.0', id: 1, method: 'tools/call',
      params: { name: 'find_entities', arguments: { graph: 'demo', question: 'customer' } },
    });
    assert.equal((await call()).body.result.isError, false);
    const limited = await call();
    assert.equal(limited.body.result.isError, true);
    assert.match(limited.body.result.content[0].text, /rate limit/);

    const lines = readFileSync(audit, 'utf8').trim().split(String.fromCharCode(10)).map((line) => JSON.parse(line));
    assert.deepEqual(lines.map((line) => line.status), ['ok', 'rate_limited']);
    assert.equal(lines[0].client, 'default');
    assert.equal(lines[0].tool, 'find_entities');
    assert.equal(lines[0].graph, 'demo');
  } finally {
    child.kill();
    api.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

test('the protocol revision is negotiated with the client', async () => {
  // pi 1.3 rejects a server that selects a revision it does not know ("MCP server selected unsupported protocol
  // version"), so the server answers with the client's when it speaks it, and with its own latest otherwise.
  const ask = (protocolVersion) => new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [SERVER], { stdio: ['pipe', 'pipe', 'inherit'] });
    let out = '';
    child.stdout.on('data', (chunk) => { out += chunk; });
    child.on('error', reject);
    child.on('close', () => {
      try { resolve(JSON.parse(out.split('\n')[0])); } catch (error) { reject(error); }
    });
    child.stdin.end(`${JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion } })}\n`);
  });

  const latest = await ask('2025-11-25');
  assert.equal(latest.result.protocolVersion, '2025-11-25', 'the revision pi speaks is echoed back');
  const older = await ask('2024-11-05');
  assert.equal(older.result.protocolVersion, '2024-11-05', 'an older one this server speaks is echoed too');
  const unknown = await ask('2099-01-01');
  assert.equal(unknown.result.protocolVersion, '2025-11-25', 'an unknown one falls back to our latest');
});
