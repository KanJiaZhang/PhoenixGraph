#!/usr/bin/env node
// PhoenixGraph as an agent plugin: a Model Context Protocol server over stdio.
//
//   node tools/mcp-server.mjs                 # speak MCP on stdin/stdout (what a client spawns)
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
import { createInterface } from 'node:readline';

const API = process.env.PHOENIXGRAPH_API || 'http://localhost:8081';
const PROTOCOL = '2026-07-28';
const argv = process.argv.slice(2);

const call = async (method, path, body) => {
  const options = { method, headers: {} };
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
const catalog = async () => {
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
  return tools;
};

/** Calls one tool by mapping its arguments onto the endpoint the catalog binds it to. */
const invoke = async (name, args) => {
  const tools = await catalog();
  const tool = tools.find((entry) => entry.name === name);
  if (!tool) {
    throw new Error(`unknown tool '${name}'; this server advertises ${tools.map((t) => t.name).join(', ')}`);
  }
  if (!tool._binding) {
    throw new Error(`tool '${name}' has no HTTP binding: the catalog and the server disagree`);
  }
  const path = tool._binding.path.replace('{graph}', encodeURIComponent(args.graph || ''));
  const body = { ...args };
  delete body.graph;
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

const respond = (id, result, error) => {
  const message = error
    ? { jsonrpc: '2.0', id, error: { code: -32000, message: String(error.message || error) } }
    : { jsonrpc: '2.0', id, result };
  process.stdout.write(JSON.stringify(message) + '\n');
};

/** One JSON-RPC request in, one response out. Stateless: no handshake to remember, no session to keep. */
const handle = async (request) => {
  const { id, method, params } = request;
  switch (method) {
    case 'initialize':
      return respond(id, {
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
      return respond(id, {});
    case 'tools/list': {
      const tools = await catalog();
      return respond(id, { tools: tools.map(({ _binding, ...tool }) => tool) });
    }
    case 'tools/call': {
      const name = params && params.name;
      const args = (params && params.arguments) || {};
      try {
        const answer = await invoke(name, args);
        const payload = answer.json;
        const text = typeof payload === 'string' ? payload : JSON.stringify(payload, null, 2);
        return respond(id, { content: [{ type: 'text', text }], isError: answer.status >= 400 });
      } catch (error) {
        return respond(id, { content: [{ type: 'text', text: String(error.message || error) }], isError: true });
      }
    }
    default:
      return respond(id, undefined, new Error(`unknown method '${method}'`));
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
      tools.length >= 10 && tools.every((tool) => tool._binding), `${tools.length} tools`);
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
    console.log(`${tool.name.padEnd(20)} ${tool._binding.method} ${tool._binding.path}`);
  }
  process.exit(0);
}

// ---- stdio MCP -----------------------------------------------------------------------------------------------
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
    await handle(one);
  }
});
