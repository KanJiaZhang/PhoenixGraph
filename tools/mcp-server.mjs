#!/usr/bin/env node
// PhoenixGraph as an agent plugin: a Model Context Protocol server over stdio or HTTP.
//
//   node tools/mcp-server.mjs                 # speak MCP on stdin/stdout (what a client spawns)
//   node tools/mcp-server.mjs --http          # Streamable HTTP (JSON response mode) on /mcp
//   node tools/mcp-server.mjs --selftest      # initialize, tools/list, one tools/call, then exit
//   node tools/mcp-server.mjs --list          # print the tools this server would advertise
//
// Why it is a plugin at all: the product's value to an agent is not a chat box, it is a *tool surface* - the
// graph tools (find entities, traverse, paths, context) and the semantic layer (what can be asked, what SQL a
// question becomes, whether it is answerable). Every client that speaks MCP can then ask data questions with
// provenance instead of inventing its own SQL.
//
// Two decisions worth keeping:
//
//   1. **The tool list comes from the API** (`GET /api/v1/agent/tools`, which carries `bindings` - how to call
//      each tool). This file holds no tool knowledge: adding a tool in AgentToolCatalog makes it available here,
//      and a tool that exists here but not there cannot happen. It is also why this server exposes the semantic
//      tools the moment they are served, without a plugin release.
//   2. **No session state**, matching the MCP 2026-07-28 revision (sessions, the handshake and server push were
//      removed): every request stands on its own, and the user-confirmation flow is a *token* returned by the API
//      (plan hash + expiry, signed), not a server-side conversation. A confirmation therefore survives a restart
//      of this plugin and works across replicas, which a session-based flow would not.
//
//   PHOENIXGRAPH_API        the service (default http://localhost:8081)
//   PHOENIXGRAPH_API_KEY    sent as Authorization: Bearer <key> when security is enabled
//
// HTTP mode:
//   PHOENIXGRAPH_MCP_HOST   bind address (default 127.0.0.1; a non-loopback bind requires a token or clients map)
//   PHOENIXGRAPH_MCP_PORT   port (default 8090)
//   PHOENIXGRAPH_MCP_TOKEN  require Authorization: Bearer <token> on /mcp
//   PHOENIXGRAPH_MCP_CLIENTS  JSON token -> {name, tools} map; per-client scopes, takes precedence
//   PHOENIXGRAPH_MCP_TOOLS  comma-separated allowlist of tool names (default: all)
//   PHOENIXGRAPH_MCP_DENY   comma-separated denylist of tool names, applied after the allowlist
//   PHOENIXGRAPH_MCP_AUDIT  JSONL file: one line per tools/call with client, tool, graph, status, duration
//   PHOENIXGRAPH_MCP_RATE_LIMIT  calls per client per minute (default 0 = unlimited)
import { appendFileSync, mkdirSync } from 'node:fs';
import { triage as runTriage } from './triage.mjs';
import { dirname } from 'node:path';
import { createInterface } from 'node:readline';
import { createServer } from 'node:http';

const API = process.env.PHOENIXGRAPH_API || 'http://localhost:8081';
const API_KEY = process.env.PHOENIXGRAPH_API_KEY || '';
const MCP_TOKEN = process.env.PHOENIXGRAPH_MCP_TOKEN || '';
const MCP_HOST = process.env.PHOENIXGRAPH_MCP_HOST || '127.0.0.1';
const MCP_PORT = process.env.PHOENIXGRAPH_MCP_PORT ? Number(process.env.PHOENIXGRAPH_MCP_PORT) : 8090;
const listed = (name) => new Set(String(process.env[name] || '').split(',').map((tool) => tool.trim()).filter(Boolean));
const ALLOW_TOOLS = listed('PHOENIXGRAPH_MCP_TOOLS');
const DENY_TOOLS = listed('PHOENIXGRAPH_MCP_DENY');

// Per-client tokens: {"token-a":{"name":"agent-a","tools":["find_entities","context"]}}. A client whose
// tools contain "*" sees everything the global allowlist allows.
const parseClients = () => {
  const raw = (process.env.PHOENIXGRAPH_MCP_CLIENTS || '').trim();
  const clients = new Map();
  if (!raw) return clients;
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch (error) {
    console.error('PHOENIXGRAPH_MCP_CLIENTS is not JSON: ' + error.message);
    process.exit(1);
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    console.error('PHOENIXGRAPH_MCP_CLIENTS must be an object mapping token -> {name, tools}');
    process.exit(1);
  }
  for (const [token, value] of Object.entries(parsed)) {
    const tools = value && Array.isArray(value.tools) ? value.tools : ['*'];
    if (!tools.every((tool) => typeof tool === 'string')) {
      console.error('PHOENIXGRAPH_MCP_CLIENTS tools must be strings');
      process.exit(1);
    }
    clients.set(token, {
      name: value && typeof value.name === 'string' ? value.name : 'client',
      tools: new Set(tools),
    });
  }
  return clients;
};
const CLIENTS = parseClients();
const AUDIT_FILE = process.env.PHOENIXGRAPH_MCP_AUDIT || '';
const RATE_LIMIT = Number(process.env.PHOENIXGRAPH_MCP_RATE_LIMIT || 0);
const RATE_WINDOW_MS = 60_000;
const RATE_STATE = new Map();
const PROTOCOL = '2026-07-28';
const argv = process.argv.slice(2);

const call = async (method, path, body) => {
  const options = { method, headers: {} };
  if (API_KEY) {
    options.headers.Authorization = `Bearer ${API_KEY}`;
  }
  if (body !== undefined && method !== 'GET') {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  const response = await fetch(API + path, options);
  const text = await response.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* the API answers JSON; a non-JSON body is reported as the raw text below */
  }
  return { status: response.status, text, json: json === null ? text : json };
};

/** The catalog as the server publishes it: MCP wants JSON Schema per tool, the catalog lists parameters. */
const catalog = async (scope = null) => {
  const answer = await call('GET', '/api/v1/agent/tools');
  if (answer.status !== 200 || !answer.json || !Array.isArray(answer.json.tools)) {
    throw new Error(`the tool catalog is not available (${answer.status}): is the service running at ${API}?`);
  }
  const bindings = answer.json.bindings || {};
  const tools = answer.json.tools.map((entry) => {
    const properties = {};
    const required = [];
    for (const parameter of entry.parameters || []) {
      properties[parameter.name] = { type: parameter.type, description: parameter.description };
      if (parameter.required) {
        required.push(parameter.name);
      }
    }
    return {
      name: entry.name,
      description: entry.description,
      inputSchema: { type: 'object', properties, ...(required.length ? { required } : {}),
        additionalProperties: true },
      _binding: bindings[entry.name] || null,
    };
  });
  // the ranking is not an HTTP binding, but it belongs on the same surface: "what is most likely wrong" is the
  // question an on-call agent actually asks
  tools.push(LOCAL_TRIAGE);
  const clientAllows = (name) => !scope || !scope.tools || scope.tools.has('*') || scope.tools.has(name);
  return tools.filter((tool) => (ALLOW_TOOLS.size === 0 || ALLOW_TOOLS.has(tool.name))
    && !DENY_TOOLS.has(tool.name)
    && clientAllows(tool.name));
};

/**
 * The one tool that is not an HTTP binding: the triage ranking runs here, over the artifact the API serves, so the
 * weights and the tie-breaks have a single home (tools/triage.mjs) instead of one per client.
 */
const LOCAL_TRIAGE = {
  name: 'triage',
  description: 'Rank the causes a fault symptom supports in a PhoenixGraph graph: each candidate with the score '
    + 'it earned, the sentence behind its causal edge, the documented incidents that failed the same way, and the '
    + 'checks that would overturn it. Answers as of a validity instant when one is given.',
  inputSchema: {
    type: 'object',
    properties: {
      symptom: { type: 'string', description: 'The fault symptom, in words (what moved, by how much, when)' },
      graph: { type: 'string', description: 'Graph name (default incident-triage)' },
      asOf: { type: 'string', description: 'Answer as of this instant (2026-10-21 or an ISO instant)' },
      k: { type: 'number', description: 'How many seed entities to consider (default 20)' },
    },
    required: ['symptom'],
  },
  _local: 'triage',
};

/** Calls one tool by mapping its arguments onto the endpoint the catalog binds it to. */
const invoke = async (name, args, scope = null) => {
  const tools = await catalog(scope);
  const tool = tools.find((entry) => entry.name === name);
  if (!tool) {
    throw new Error(`unknown tool '${name}'; this server advertises ${tools.map((t) => t.name).join(', ')}`);
  }
  if (tool._local === 'triage') {
    const asOf = args.asOf ? Date.parse(String(args.asOf).length === 10 ? `${args.asOf}T00:00:00Z` : String(args.asOf)) : null;
    const result = await runTriage({ endpoint: API, graph: args.graph || 'incident-triage', symptom: args.symptom,
      k: args.k, asOf: Number.isNaN(asOf) ? null : asOf });
    return { status: 200, text: JSON.stringify(result), json: result };
  }
  if (!tool._binding) {
    throw new Error(`tool '${name}' has no HTTP binding: the catalog and the server disagree`);
  }
  const needsGraph = tool._binding.path.includes('{graph}');
  const path = tool._binding.path.replace('{graph}', encodeURIComponent(args.graph || ''));
  const body = { ...args };
  if (needsGraph) {
    delete body.graph;
  }
  if (tool._binding.method === 'GET') {
    const query = new URLSearchParams();
    Object.entries(body).forEach(([key, value]) => {
      if (value !== undefined && value !== null) {
        query.append(key, String(value));
      }
    });
    const suffix = query.toString() ? `?${query}` : '';
    return call('GET', `${path}${suffix}`);
  }
  return call('POST', path, body);
};

const message = (id, result, error) => (error
  ? { jsonrpc: '2.0', id, error: { code: -32000, message: String(error.message || error) } }
  : { jsonrpc: '2.0', id, result });

const respond = (id, result, error) => {
  process.stdout.write(JSON.stringify(message(id, result, error)) + String.fromCharCode(10));
};

/** One JSON-RPC request in, one response object out (or undefined for a notification). */
const handleResult = async (request, scope = null) => {
  if (!request || typeof request !== 'object' || Array.isArray(request)) {
    return message(null, undefined, new Error('the request must be a JSON-RPC object'));
  }
  const { id = null, method, params } = request;
  switch (method) {
    case 'initialize':
      return message(id, {
        protocolVersion: PROTOCOL,
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: 'phoenixgraph', version: '1.0.1' },
        instructions: 'Data questions with provenance: read semantic_model first, then semantic_validate, then '
          + 'semantic_translate (or ask the service to run it). Nothing is executed from a generated SQL string.',
      });
    case 'notifications/initialized':
    case 'notifications/cancelled':
      return undefined; // notifications have no response
    case 'ping':
      return message(id, {});
    case 'tools/list': {
      try {
        const tools = await catalog(scope);
        return message(id, { tools: tools.map(({ _binding, ...tool }) => tool) });
      } catch (error) {
        return message(id, undefined, error);
      }
    }
    case 'tools/call': {
      const name = params && params.name;
      const args = (params && params.arguments) || {};
      const started = Date.now();
      if (rateLimited(scope)) {
        appendAudit({ client: clientName(scope), tool: name, graph: args.graph, status: 'rate_limited',
          durationMs: Date.now() - started });
        return message(id, { content: [{ type: 'text', text: 'MCP rate limit exceeded; retry in a minute' }],
          isError: true });
      }
      try {
        const answer = await invoke(name, args, scope);
        appendAudit({ client: clientName(scope), tool: name, graph: args.graph,
          status: answer.status >= 400 ? 'http_error' : 'ok', httpStatus: answer.status,
          durationMs: Date.now() - started });
        const payload = answer.json;
        const text = typeof payload === 'string' ? payload : JSON.stringify(payload, null, 2);
        return message(id, { content: [{ type: 'text', text }], isError: answer.status >= 400 });
      } catch (error) {
        appendAudit({ client: clientName(scope), tool: name, graph: args.graph, status: 'error',
          error: String(error.message || error), durationMs: Date.now() - started });
        return message(id, { content: [{ type: 'text', text: String(error.message || error) }], isError: true });
      }
    }
    default:
      return message(id, undefined, new Error(`unknown method '${method}'`));
  }
};

// ---- self test: the same three calls a client makes, so the wiring is checkable without a client --------------
if (argv.includes('--selftest')) {
  let failures = 0;
  const check = (name, ok, detail) => {
    if (!ok) failures += 1;
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
  };
  try {
    const tools = await catalog();
    check('the tool catalog is reachable and carries bindings',
      tools.length >= 10 && tools.every((tool) => tool._binding || tool._local), `${tools.length} tools`);
    check('the triage ranking is advertised next to the tools that feed it',
      tools.some((tool) => tool.name === 'triage'), tools.map((tool) => tool.name).join(', '));
    check('the graph tools an agent uses first are advertised',
      ['find_entities', 'related', 'context'].every((name) => tools.some((tool) => tool.name === name)),
      tools.slice(0, 3).map((tool) => tool.name).join(', '));
    check('the semantic layer is advertised too, with what it needs',
      ['semantic_model', 'semantic_translate', 'semantic_validate']
        .every((name) => tools.some((tool) => tool.name === name)),
      tools.filter((tool) => tool.name.startsWith('semantic_')).map((tool) => tool.name).join(', '));
    const translate = tools.find((tool) => tool.name === 'semantic_translate');
    check('every advertised tool carries a JSON Schema the client can validate against',
      translate.inputSchema.type === 'object' && translate.inputSchema.properties.metric
      && translate.inputSchema.required.includes('metric'), JSON.stringify(translate.inputSchema.required));

    const model = await invoke('semantic_model', { graph: 'tpc-h-analytics' });
    check('semantic_model answers with the vocabulary',
      model.status === 200 && model.json.graph === 'tpc-h-analytics'
      && model.json.metrics.length >= 17, `${model.status}, ${model.json.metrics?.length} metrics`);

    const sql = await invoke('semantic_translate',
      { graph: 'tpc-h-analytics', metric: 'metric/revenue', dimension: 'dim/region' });
    check('semantic_translate renders SQL through the model',
      sql.status === 200 && /SELECT/.test(sql.json.statement) && /revenue/.test(sql.json.statement),
      String(sql.json.statement || '').split('\n')[0]);

    const verdict = await invoke('semantic_validate', { graph: 'tpc-h-analytics', metric: 'metric/nope' });
    check('semantic_validate refuses an unknown metric with the vocabulary',
      verdict.status === 200 && verdict.json.answerable === false
      && String(verdict.json.problem).includes('metric/'),
      String(verdict.json.problem || '').slice(0, 80));

    console.log('');
    console.log(failures === 0
      ? `the MCP server advertises ${tools.length} tools and they answer`
      : `${failures} FAILURES`);
  } catch (error) {
    console.log(`FAIL the self test could not run - ${error.message}`);
    failures += 1;
  }
  process.exit(failures === 0 ? 0 : 1);
}

if (argv.includes('--list')) {
  const tools = await catalog();
  for (const tool of tools) {
    const binding = tool._binding;
    console.log(`${tool.name.padEnd(20)} ${binding ? `${binding.method} ${binding.path}` : '(no HTTP binding)'}`);
  }
  process.exit(0);
}

// ---- HTTP MCP (Streamable HTTP, JSON response mode) ----------------------------------------------------------
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'content-type, authorization, mcp-session-id, mcp-protocol-version',
  'Access-Control-Allow-Methods': 'POST, GET, DELETE, OPTIONS',
};

const readBody = (request, limit = 1_048_576) => new Promise((resolve, reject) => {
  let size = 0;
  const chunks = [];
  request.on('data', (chunk) => {
    size += chunk.length;
    if (size > limit) {
      reject(new Error('request body too large'));
      request.destroy();
      return;
    }
    chunks.push(chunk);
  });
  request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
  request.on('error', reject);
});

const clientName = (scope) => (scope && scope.name) || 'anonymous';

const appendAudit = (entry) => {
  if (!AUDIT_FILE) return;
  try {
    mkdirSync(dirname(AUDIT_FILE), { recursive: true });
    appendFileSync(AUDIT_FILE, JSON.stringify({ ts: new Date().toISOString(), ...entry }) + String.fromCharCode(10));
  } catch (error) {
    console.error('cannot write MCP audit ' + AUDIT_FILE + ': ' + error.message);
  }
};

const rateLimited = (scope) => {
  if (!RATE_LIMIT || RATE_LIMIT <= 0) return false;
  const now = Date.now();
  const key = clientName(scope);
  const recent = (RATE_STATE.get(key) || []).filter((at) => at > now - RATE_WINDOW_MS);
  if (recent.length >= RATE_LIMIT) {
    RATE_STATE.set(key, recent);
    return true;
  }
  recent.push(now);
  RATE_STATE.set(key, recent);
  return false;
};

/** Resolve the bearer token to a client scope; null means "reject". */
const authenticate = (authorization) => {
  const header = authorization || '';
  if (CLIENTS.size > 0) {
    const token = header.startsWith('Bearer ') ? header.slice('Bearer '.length).trim() : '';
    return CLIENTS.get(token) || null;
  }
  if (MCP_TOKEN) {
    return header === 'Bearer ' + MCP_TOKEN ? { name: 'default', tools: null } : null;
  }
  return { name: 'anonymous', tools: null };
};

const wantsSse = (request) => String(request.headers.accept || '').includes('text/event-stream');

const sse = (response, events) => {
  response.writeHead(200, {
    'content-type': 'text/event-stream',
    'cache-control': 'no-cache',
    connection: 'keep-alive',
    ...CORS,
  });
  for (const event of events) {
    response.write('event: message' + String.fromCharCode(10)
      + 'data: ' + JSON.stringify(event) + String.fromCharCode(10) + String.fromCharCode(10));
  }
  response.end();
};

const answerJson = (response, status, payload, headers = {}) => {
  response.writeHead(status, { 'content-type': 'application/json', ...CORS, ...headers });
  response.end(payload === undefined ? '' : JSON.stringify(payload));
};

const isLoopback = (host) => ['127.0.0.1', '::1', 'localhost'].includes(host);

const startHttp = () => {
  if (!isLoopback(MCP_HOST) && !MCP_TOKEN && CLIENTS.size === 0) {
    console.error('refusing to bind MCP HTTP to ' + MCP_HOST + ' without PHOENIXGRAPH_MCP_TOKEN or PHOENIXGRAPH_MCP_CLIENTS');
    process.exit(1);
  }
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, 'http://' + (request.headers.host || 'localhost'));
    if (url.pathname === '/healthz' && request.method === 'GET') {
      answerJson(response, 200, { status: 'ok', transport: 'http' });
      return;
    }
    if (url.pathname !== '/mcp') {
      answerJson(response, 404, { error: 'not found; the MCP endpoint is /mcp' });
      return;
    }
    if (request.method === 'OPTIONS') {
      response.writeHead(204, CORS);
      response.end();
      return;
    }
    const client = authenticate(request.headers.authorization);
    if (!client) {
      answerJson(response, 401, { error: 'missing or invalid MCP bearer token' },
        { 'www-authenticate': 'Bearer' });
      return;
    }
    if (request.method === 'DELETE') {
      response.writeHead(204, CORS);
      response.end();
      return;
    }
    if (request.method === 'GET') {
      if (!wantsSse(request)) {
        response.writeHead(405, { ...CORS, allow: 'GET, POST, DELETE, OPTIONS' });
        response.end();
        return;
      }
      response.writeHead(200, {
        'content-type': 'text/event-stream',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
        ...CORS,
      });
      response.write(': connected' + String.fromCharCode(10) + String.fromCharCode(10));
      const heartbeat = setInterval(() => {
        response.write(': ping' + String.fromCharCode(10) + String.fromCharCode(10));
      }, 15000);
      request.on('close', () => clearInterval(heartbeat));
      return;
    }
    if (request.method !== 'POST') {
      response.writeHead(405, { ...CORS, allow: 'GET, POST, DELETE, OPTIONS' });
      response.end();
      return;
    }
    let body;
    try {
      body = await readBody(request);
    } catch (error) {
      answerJson(response, 413, { error: error.message });
      return;
    }
    let parsed;
    try {
      parsed = JSON.parse(body);
    } catch {
      answerJson(response, 400, message(null, undefined, new Error('the request is not JSON')));
      return;
    }
    const requests = Array.isArray(parsed) ? parsed : [parsed];
    const answers = [];
    for (const one of requests) {
      const answer = await handleResult(one, client);
      if (answer) {
        answers.push(answer);
      }
    }
    if (answers.length === 0) {
      response.writeHead(202, CORS);
      response.end();
      return;
    }
    if (wantsSse(request)) {
      sse(response, answers);
      return;
    }
    answerJson(response, 200, Array.isArray(parsed) ? answers : answers[0]);
  });
  server.on('clientError', (error, socket) => {
    if (socket.writable) {
      socket.end('HTTP/1.1 400 Bad Request' + String.fromCharCode(13, 10) + String.fromCharCode(13, 10));
    }
  });
  server.listen(MCP_PORT, MCP_HOST, () => {
    const address = server.address();
    console.error('PhoenixGraph MCP HTTP listening on http://' + MCP_HOST + ':' + address.port + '/mcp');
  });
};

// ---- stdio MCP -----------------------------------------------------------------------------------------------
const stdio = () => {
  const lines = createInterface({ input: process.stdin });
  lines.on('line', async (line) => {
    const text = line.trim();
    if (!text) {
      return;
    }
    let request;
    try {
      request = JSON.parse(text);
    } catch {
      respond(null, undefined, new Error('the request is not JSON'));
      return;
    }
    const requests = Array.isArray(request) ? request : [request];
    for (const one of requests) {
      const answer = await handleResult(one, { name: 'stdio', tools: null });
      if (answer) {
        respond(answer.id, answer.result, answer.error);
      }
    }
  });
};

if (argv.includes('--http')) {
  startHttp();
} else {
  stdio();
}
