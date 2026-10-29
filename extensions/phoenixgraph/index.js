/**
 * PhoenixGraph for pi - P0 + P1 of docs/PI-PLUGIN.md.
 *
 * P0: registers the PhoenixGraph MCP server (`tools/mcp-server.mjs`), so every session gets the fourteen
 *     graph tools without a line of glue.
 * P1: adds the part a stateless MCP server cannot do - a *sticky* validity instant. `pg_asof` sets "answer as
 *     of 2026-10-21", every later `pg_context` carries it, and it survives the conversation: the instant is
 *     written to the session (a branch-safe custom entry, never sent to the model) and to a small state file,
 *     so `/pg status` after a restart still says what the graph is answering as of.
 *
 * Deliberately not here (P2 in the design): pg_triage, and the before_agent_start block that tells the model
 * the contract. Until then the tools speak for themselves and the commands are the switchboard.
 *
 * Written as plain ESM JavaScript so `node --test` can drive it with a fake `pi` - see test/extension.test.mjs.
 * The second parameter is for tests only; pi calls the factory with `pi` alone.
 */
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { homedir } from 'node:os';
import { basename, dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

/** The default endpoint and graph: what a fresh checkout serves after `tools/start-phoenixgraph.sh`. */
const DEFAULTS = {
  endpoint: 'http://localhost:8081',
  graph: 'incident-triage',
};

/** The state file: outside any session, so a new conversation starts where the last one left off. */
export function stateFile(home = homedir()) {
  return process.env.PHOENIXGRAPH_STATE_FILE || join(home, '.pi', 'agent', 'state', 'phoenixgraph.json');
}

/** A date a client wrote, as an ISO instant: `2026-10-21` means midnight UTC, a full instant is kept. */
export function toInstant(written) {
  if (written == null) return null;
  const text = String(written).trim();
  if (!text) return null;
  if (/^\d+$/.test(text)) return new Date(Number(text)).toISOString();
  const iso = text.length === 10 ? `${text}T00:00:00.000Z` : text;
  const millis = Date.parse(iso);
  if (Number.isNaN(millis)) throw new Error(`Not a date: '${written}' (use 2026-10-21 or 2026-10-21T09:30:00Z)`);
  return new Date(millis).toISOString();
}

/** Where the MCP server lives: next to this extension in the repository, or wherever PHOENIXGRAPH_REPO says. */
function mcpServerPath() {
  const repo = process.env.PHOENIXGRAPH_REPO;
  if (repo) return join(repo, 'tools', 'mcp-server.mjs');
  // the extension may be symlinked into ~/.pi/agent/extensions, so this path is the repository's
  return resolve(HERE, '..', '..', 'tools', 'mcp-server.mjs');
}

/**
 * The extension.
 *
 * @param {import('@earendil-works/pi-coding-agent').ExtensionAPI} pi
 * @param {{ fetch?: typeof fetch, home?: string, now?: () => Date, env?: Record<string, string|undefined> }} [options]
 */
export default function phoenixgraphExtension(pi, options = {}) {
  const env = options.env || process.env;
  const call = options.fetch || globalThis.fetch;
  const home = options.home || homedir();
  const now = options.now || (() => new Date());
  const file = stateFile(home);

  /** The state every tool reads: the graph, the endpoint, and the instant the answers are pinned to. */
  let state = {
    ...DEFAULTS,
    // stdio spawns the server next to the extension; an URL points at a shared deployment (tools/mcp-server.mjs
    // --http), which is where tokens, the audit file and the rate limit live
    mcpUrl: (options.env || process.env).PHOENIXGRAPH_MCP_URL || null,
    asOf: null,
    timeline: true,
    inject: true,
    lastContext: null,
  };
  let loaded = false;
  let triageLib = null;

  /**
   * The ranking lives in tools/triage.mjs - one implementation for the example, the MCP server and this
   * extension. It is imported from the repository on first use, so an extension copied out of the repo says so
   * instead of running a stale copy of the weights.
   */
  async function ranking() {
    if (triageLib) return triageLib;
    const path = resolve(HERE, '..', '..', 'tools', 'triage.mjs');
    if (!existsSync(path)) {
      throw new Error('pg_triage needs the repository: tools/triage.mjs was not found next to this extension '
        + '(set PHOENIXGRAPH_REPO to the checkout, or use pg_context / the MCP server for the graph tools)');
    }
    triageLib = await import(pathToFileURL(path).href);
    return triageLib;
  }

  function readFileState() {
    try {
      const parsed = JSON.parse(readFileSync(file, 'utf8'));
      return parsed && typeof parsed === 'object' ? parsed : {};
    } catch {
      return {};
    }
  }

  function writeFileState(next) {
    try {
      mkdirSync(dirname(file), { recursive: true });
      writeFileSync(file, `${JSON.stringify(next, null, 2)}\n`);
    } catch (error) {
      // a read-only home must not break a conversation: the session entry still carries the state
      pi.events?.emit?.('phoenixgraph.state_error', { error: String(error) });
    }
  }

  /** Applies a change: session entry (branch-safe, not model-facing), the file, and the in-memory state. */
  function commit(changes, { notify } = {}) {
    state = { ...state, ...changes };
    pi.appendEntry('phoenixgraph.state', {
      graph: state.graph, endpoint: state.endpoint, asOf: state.asOf, timeline: state.timeline,
      mcpUrl: state.mcpUrl, lastContext: state.lastContext,
    });
    writeFileState({
      graph: state.graph, endpoint: state.endpoint, asOf: state.asOf, timeline: state.timeline, mcpUrl: state.mcpUrl,
    });
    if (notify) notify();
    return state;
  }

  /** Rebuilds from the conversation: the last state entry on the current branch wins over the file. */
  function reconstruct(ctx) {
    if (!loaded) {
      state = { ...state, ...readFileState() };
      // a project may pin the graph and the endpoint; it does not decide the instant (that is the user's)
      try {
        const project = JSON.parse(readFileSync(join(ctx.cwd, '.pi', 'phoenixgraph.json'), 'utf8'));
        if (project.graph) state.graph = project.graph;
        if (project.endpoint) state.endpoint = project.endpoint;
      } catch { /* no project file */ }
      loaded = true;
    }
    const branch = ctx?.sessionManager?.getBranch?.() || [];
    for (const entry of branch) {
      if (entry?.type === 'custom' && entry.customType === 'phoenixgraph.state' && entry.data) {
        state = { ...state, ...entry.data };
      }
    }
  }

  /** The instant a tool call should use: what it asked for, else the sticky one while the timeline is on. */
  function effectiveAsOf(explicit) {
    if (explicit != null && String(explicit).trim() !== '') return toInstant(explicit);
    return state.timeline ? state.asOf : null;
  }

  async function postJson(path, body) {
    const response = await call(`${state.endpoint.replace(/\/+$/, '')}${path}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json; charset=utf-8' },
      body: JSON.stringify(body),
    });
    const text = await response.text();
    let payload = text;
    try { payload = text ? JSON.parse(text) : null; } catch { /* keep the raw text */ }
    if (!response.ok) {
      const detail = payload && typeof payload === 'object' ? payload.error || payload.message : text;
      throw new Error(`${response.status}: ${String(detail || 'no body').slice(0, 300)}`);
    }
    return payload;
  }

  /** The answer as the model should read it: the relations with their sentences, then the coverage. */
  function renderContext(answer, asOf) {
    const lines = [];
    if (asOf) lines.push(`as of ${asOf} (facts after this instant are hidden)`);
    if (answer.refusal) {
      lines.push(`no entity in the graph is named by this question - refusing rather than guessing.`);
      return lines.join('\n');
    }
    for (const entity of (answer.entities || []).slice(0, 12)) {
      lines.push(`entity ${entity.name} (${entity.type || '?'}, via ${entity.via})`);
    }
    for (const relation of (answer.relations || []).slice(0, 20)) {
      const sentence = relation.sentence ? `  "${String(relation.sentence).trim().slice(0, 200)}"` : '';
      lines.push(`${relation.head} -${relation.relation}-> ${relation.tail}${sentence}`);
    }
    const coverage = answer.coverage || {};
    lines.push(`coverage: ${coverage.entities ?? 0} entities, ${coverage.relations ?? 0} relations, `
      + `${coverage.citations ?? 0} citations`);
    if (!(answer.relations || []).length) {
      lines.push('no relation was found: say so, do not fill the gap.');
    }
    return lines.join('\n');
  }

  // ---- P0: the tool surface ---------------------------------------------------------------------------
  const MCP_DESCRIPTION = 'PhoenixGraph: an auditable context layer. Entities, relations with the sentence each '
    + 'was read from, citations and coverage over an ingested knowledge graph, plus a semantic layer for data '
    + 'questions.';

  /** The MCP server as this session reaches it: a spawned process, or a shared HTTP deployment. */
  function mcpConfig() {
    if (state.mcpUrl) {
      return {
        type: 'http',
        url: state.mcpUrl,
        ...(env.PHOENIXGRAPH_MCP_TOKEN ? { headers: { Authorization: `Bearer ${env.PHOENIXGRAPH_MCP_TOKEN}` } } : {}),
        exposure: 'codemode',
        description: MCP_DESCRIPTION,
      };
    }
    return {
      type: 'stdio',
      command: 'node',
      args: [mcpServerPath()],
      env: { PHOENIXGRAPH_API: state.endpoint, ...(env.PHOENIXGRAPH_API_KEY ? { PHOENIXGRAPH_API_KEY: env.PHOENIXGRAPH_API_KEY } : {}) },
      exposure: 'codemode',
      description: MCP_DESCRIPTION,
    };
  }

  pi.registerMcpServer('phoenixgraph', mcpConfig());

  // ---- P1: the sticky instant and the tools that use it ------------------------------------------------
  pi.registerTool({
    name: 'pg_context',
    label: 'PhoenixGraph context',
    description: 'The context a question needs from the PhoenixGraph knowledge graph: the entities it names, the '
      + 'relations among them with the sentence each was read from, and a coverage count. Answers with the '
      + 'validity instant the session is pinned to unless asOf overrides it.',
    promptSnippet: 'pg_context(question) - cited graph context (entities, relations, sentences, coverage)',
    parameters: {
      type: 'object',
      properties: {
        question: { type: 'string', description: 'The question or symptom, in words' },
        k: { type: 'number', description: 'How many seed entities to consider (default 5)' },
        hops: { type: 'number', description: 'How far to walk from them (default 1)' },
        asOf: { type: 'string', description: 'Answer as of this instant (2026-10-21 or an ISO instant); defaults to the session instant' },
        graph: { type: 'string', description: 'Graph name; defaults to the session graph' },
      },
      required: ['question'],
    },
    async execute(_toolCallId, params) {
      const asOf = effectiveAsOf(params.asOf);
      const graph = params.graph || state.graph;
      let answer;
      try {
        answer = await postJson('/api/v1/agent/context', {
          graph,
          question: params.question,
          k: params.k ?? 5,
          hops: params.hops ?? 1,
          asOf: asOf || undefined,
        });
      } catch (error) {
        return {
          content: [{ type: 'text', text: `PhoenixGraph is not answering at ${state.endpoint} (${error.message}). `
            + 'Start it with tools/start-phoenixgraph.sh --no-build, or set the endpoint with /pg endpoint <url>.' }],
          details: { error: String(error), graph, endpoint: state.endpoint },
        };
      }
      const seeds = (answer.entities || []).filter((entity) => entity.via === 'name').map((entity) => entity.name);
      commit({ lastContext: {
        graph, question: params.question, asOf, seeds, at: now().toISOString(),
      } });
      return {
        content: [{ type: 'text', text: renderContext(answer, asOf) }],
        details: { graph, asOf, answer },
      };
    },
  });

  pi.registerTool({
    name: 'pg_asof',
    label: 'PhoenixGraph timeline',
    description: 'Set (or clear) the validity instant every later PhoenixGraph tool call answers as of. A fact '
      + 'outside its window - a release that had not happened yet, an incident whose configuration has been '
      + 'replaced - is hidden rather than deleted.',
    promptSnippet: 'pg_asof("2026-10-21" | null) - pin the answers to an instant, or go back to now',
    parameters: {
      type: 'object',
      properties: {
        instant: { type: 'string', description: 'The instant to answer as of (2026-10-21 or an ISO instant)' },
        on: { type: 'boolean', description: 'false clears the instant; true re-enables it' },
      },
      required: [],
    },
    async execute(_toolCallId, params) {
      if (params.on === false) {
        commit({ timeline: false, asOf: null });
        return { content: [{ type: 'text', text: 'The timeline is off: answers use every fact the graph has.' }], details: { ...state } };
      }
      if (params.instant == null) {
        const pinned = state.timeline && state.asOf;
        return {
          content: [{ type: 'text', text: pinned ? `Answers are pinned to ${state.asOf}.` : 'Answers use every fact the graph has.' }],
          details: { ...state },
        };
      }
      const instant = toInstant(params.instant);
      commit({ timeline: true, asOf: instant });
      return {
        content: [{ type: 'text', text: `Answers are pinned to ${instant}: facts outside their validity window are hidden.` }],
        details: { ...state },
      };
    },
  });

  // ---- P2: the ranked answer, and the contract around it ------------------------------------------------
  pi.registerTool({
    name: 'pg_triage',
    label: 'PhoenixGraph triage',
    description: 'Rank the causes a fault symptom supports in the PhoenixGraph graph: each candidate with the score it '
      + 'earned, the sentence behind its causal edge, the documented incidents that failed the same way before, and '
      + 'the checks that would overturn it. Answers as of the validity instant the session is pinned to.',
    promptSnippet: 'pg_triage(symptom) - what is most likely wrong, with the evidence and what would refute it',
    parameters: {
      type: 'object',
      properties: {
        symptom: { type: 'string', description: 'What moved: the component, the metric, how much, and when' },
        asOf: { type: 'string', description: 'Answer as of this instant; defaults to the session instant' },
        graph: { type: 'string', description: 'Graph name; defaults to the session graph' },
      },
      required: ['symptom'],
    },
    async execute(_toolCallId, params) {
      const asOf = effectiveAsOf(params.asOf);
      const graph = params.graph || state.graph;
      let lib;
      try {
        lib = await ranking();
      } catch (error) {
        return { content: [{ type: 'text', text: String(error.message || error) }], details: { error: String(error) } };
      }
      let result;
      try {
        result = await lib.triage({ endpoint: state.endpoint, graph, symptom: params.symptom, asOf: asOf ? Date.parse(asOf) : null, fetchImpl: call });
      } catch (error) {
        return {
          content: [{ type: 'text', text: `PhoenixGraph is not answering at ${state.endpoint} (${error.message}). `
            + 'Start it with tools/start-phoenixgraph.sh --no-build, or set the endpoint with /pg endpoint <url>.' }],
          details: { error: String(error), graph, endpoint: state.endpoint },
        };
      }
      return {
        content: [{ type: 'text', text: lib.render(result) }],
        details: { graph, asOf, result },
      };
    },
  });

  /**
   * The block a model sees on every turn: the graph, the instant, the tools, and the answer contract. It is a
   * *section* of the structured prompt (mutated in place), not a replacement - pi keeps the transcript delta and
   * the provider keeps its cache. It is only added when this session is actually about a graph: a pin, a previous
   * answer, a project file, or the user running /pg.
   */
  function contextBlock() {
    const pinned = state.timeline && state.asOf;
    return [
      `[phoenixgraph] graph=${state.graph} endpoint=${state.endpoint}`,
      pinned
        ? `answers are pinned to ${state.asOf}: facts outside their validity window are hidden (pg_asof moves it)`
        : 'answers use every fact the graph has (pg_asof instants pin them to a moment)',
      'tools: pg_triage(symptom) ranks causes with their evidence; pg_context(question) returns cited context; pg_asof(instant)',
      'answer contract: quote the sentence and the coverage behind every claim; when the ranking is thin or there is',
      'no relation at all, say so - a missing edge is a finding, not a gap to fill.',
    ].join('\n');
  }

  pi.on('before_agent_start', async (event, ctx) => {
    reconstruct(ctx);
    if (!state.inject) return;
    const aware = Boolean(state.lastContext) || Boolean(state.asOf) || loadedFromProject(ctx);
    if (!aware) return;
    const options = event && event.systemPromptOptions;
    if (options && typeof options === 'object') {
      // a named section, so later turns can replace it without rebuilding the prompt
      options.sections = { ...(options.sections || {}), phoenixgraph: contextBlock() };
    }
  });

  /** Whether the current project carries a .pi/phoenixgraph.json (a team that pinned a graph). */
  function loadedFromProject(ctx) {
    try {
      return existsSync(join(ctx?.cwd || '', '.pi', 'phoenixgraph.json'));
    } catch {
      return false;
    }
  }

  /**
   * The link that shows what the agent just used: the seeded entities of the last context, the relation edges around
   * them, and the instant the answer was pinned to. It is a plain URL, so it also works pasted into a browser when
   * nothing can open one.
   */
  /** The entities with the most relations, from the artifact: what to show when nothing has been asked yet. */
  function busiestEntities(index, limit = 4) {
    const degree = new Map();
    for (const relation of index.relations || []) {
      for (const end of [relation.head, relation.tail]) {
        if (end) degree.set(end, (degree.get(end) || 0) + 1);
      }
    }
    return [...degree.entries()].sort((a, b) => b[1] - a[1]).slice(0, limit).map(([name]) => name);
  }

  async function contextLink(entity) {
    let seeds = entity ? [entity] : (state.lastContext?.seeds || []);
    let source = entity ? `the entity you named (${entity})`
      : state.lastContext ? `${state.lastContext.question} (${state.lastContext.graph})`
        : null;
    if (!seeds.length) {
      // Nothing asked yet in this session, so there are no seeds to draw. The engine has no full scan (a start is
      // ids or a type scan), and the artifact knows every entity - so the busiest ones are the honest answer to
      // "show me the graph", where an empty g.V().limit(0) was only a blank canvas.
      try {
        const response = await call(`${state.endpoint.replace(/\/+$/, '')}/api/v1/agent/index/${encodeURIComponent(state.graph)}`);
        const index = await response.json();
        seeds = busiestEntities(index);
        source = seeds.length
          ? `nothing asked yet - the ${seeds.length} most connected entities of '${state.graph}'`
          : `nothing asked yet, and '${state.graph}' has no relations`;
      } catch {
        source = `nothing asked yet, and the artifact of '${state.graph}' is not readable - ingest it first`;
      }
    }
    const quoted = (name) => `'${String(name).replace(/'/g, "''")}','Entity'`;
    const ids = seeds.map(quoted).join(',');
    const query = ids ? `g.V(${ids}).bothE('Relates').otherV().dedup().limit(40)` : '';
    // the UI is the API's port with 8080, unless /pg ui said otherwise
    const ui = (state.ui || state.endpoint.replace(/:\d+$/, ':8080')).replace(/\/+$/, '');
    const asOf = state.timeline && state.asOf ? `&asOf=${encodeURIComponent(state.asOf)}` : '';
    return {
      query,
      source: source || 'the graph',
      url: `${ui}/?graph=${encodeURIComponent(state.lastContext?.graph || state.graph)}${query ? `&q=${encodeURIComponent(query)}` : ''}${asOf}`,
    };
  }

  /** Opens a URL with the platform's opener; returns the command it tried (or null when it could not). */
  async function openUrl(url) {
    const platform = options.platform || process.platform;
    const [command, args] = platform === 'win32' ? ['cmd', ['/c', 'start', '', url]]
      : platform === 'darwin' ? ['open', [url]] : ['xdg-open', [url]];
    try {
      await pi.exec(command, args);
      return command;
    } catch {
      return null;
    }
  }

  // ---- remembering a conversation -------------------------------------------------------------------------
  // A session's conclusions live in a transcript: searchable by the next model only if someone pastes them back.
  // `/pg remember` (and the pg_remember tool) turn the conversation into a document and ingest it into the graph,
  // so a later session - another day, another person - can cite it the same way it cites an RCA.
  //
  // What comes out is what the extraction rules can read: prose yields chunks and citations, and the sentences
  // written in the rule pack's vocabulary (caused, depends on, uses, deployed on, affects, ...) become edges. The
  // quality report says which, so nobody mistakes "remembered" for "understood".

  const REMEMBER_MAX_CHARS = 120_000;

  /** The text of one message, whatever shape its content has. */
  function messageText(message) {
    const content = message && message.content;
    if (typeof content === 'string') return content.trim();
    if (!Array.isArray(content)) return '';
    return content.filter((block) => block && block.type === 'text' && block.text)
      .map((block) => block.text).join('\n').trim();
  }

  /**
   * The conversation as one document: the user and assistant turns, oldest first, cut down to the newest
   * REMEMBER_MAX_CHARS characters. System prompts and tool output are left out - the first is the harness talking,
   * the second is the corpus of the work and not the reasoning about it.
   */
  function conversationText(ctx) {
    const branch = (ctx && ctx.sessionManager && ctx.sessionManager.getBranch && ctx.sessionManager.getBranch()) || [];
    const turns = [];
    for (const entry of branch) {
      if (!entry || entry.type !== 'message') continue;
      const role = entry.message && entry.message.role;
      if (role !== 'user' && role !== 'assistant') continue;
      const text = messageText(entry.message);
      if (text) turns.push(`${role}: ${text}`);
    }
    const kept = [];
    let chars = 0;
    for (let index = turns.length - 1; index >= 0; index -= 1) {
      chars += turns[index].length + 2;
      if (chars > REMEMBER_MAX_CHARS && kept.length) break;
      kept.unshift(turns[index]);
    }
    return kept.join('\n\n');
  }

  /** The entities the graph already knows, as the gazetteer for this ingestion (names keep their types). */
  async function graphDictionary(graph) {
    try {
      const response = await call(`${state.endpoint.replace(/\/+$/, '')}/api/v1/agent/index/${encodeURIComponent(graph)}`);
      const index = await response.json();
      const dictionary = {};
      for (const entity of index.entities || []) {
        if (entity.name && entity.type) dictionary[entity.name] = entity.type;
      }
      return dictionary;
    } catch {
      return {};
    }
  }

  /** Ingests text into a graph with the window starting now; returns the ingest report. */
  async function ingestText(graph, title, text) {
    const documents = [{
      id: `session-${now().toISOString().replace(/[^0-9]/g, '').slice(0, 14)}`,
      title,
      text,
      validFrom: now().toISOString(),
    }];
    const answer = await postJson('/api/v1/agent/ingest', {
      graph,
      apply: true,
      rulePack: 'incident',
      dictionary: await graphDictionary(graph),
      documents,
    });
    return answer;
  }

  /** A one-screen report of what the ingestion actually read out of the text. */
  function ingestSummary(answer) {
    const quality = (answer && answer.quality) || {};
    const lines = [
      `${answer.entities} entities, ${answer.relations} relations from ${quality.documents ?? '?'} document(s)`
        + ` (${quality.chunks ?? '?'} chunks)`,
      `provenanceRate ${quality.provenanceRate ?? '?'} · typeAgreement ${quality.typeAgreement ?? '?'}`
        + ` · entityLinkRate ${quality.entityLinkRate ?? '?'}`,
    ];
    if (quality.duplicatesRemoved) lines.push(`${quality.duplicatesRemoved} duplicate name(s) merged`);
    const merged = quality.mergedEntities || [];
    for (const entry of merged.slice(0, 5)) lines.push(`merged: ${entry.canonical} <- ${(entry.aliases || []).join(', ')}`);
    const candidates = quality.mergeCandidates || [];
    for (const pair of candidates.slice(0, 3)) lines.push(`review: ${pair.left} ~ ${pair.right} (${pair.reason})`);
    if (!answer.relations) {
      lines.push('no relation was read: the conversation is now citable, but it did not state a fact in the pack\'s vocabulary.');
    }
    return lines.join('\n');
  }

  pi.registerTool({
    name: 'pg_remember',
    label: 'PhoenixGraph remember',
    description: 'Ingest this conversation into a PhoenixGraph graph so later sessions can cite it: the turns become '
      + 'chunks with provenance, and the sentences written in the extraction vocabulary (caused, depends on, uses, '
      + 'deployed on, affects, ...) become entities and relations. Returns the ingestion report.',
    promptSnippet: 'pg_remember() - put this conversation into the graph, with citations',
    parameters: {
      type: 'object',
      properties: {
        graph: { type: 'string', description: 'Graph to write into; defaults to the session graph' },
        note: { type: 'string', description: 'One extra fact to record verbatim, e.g. "checkout-api depends on redis-cache."' },
      },
      required: [],
    },
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      const graph = params.graph || state.graph;
      const text = [conversationText(ctx), params.note].filter(Boolean).join('\n\n') || '';
      if (!text.trim()) {
        return { content: [{ type: 'text', text: 'There is nothing to remember yet: this session has no turns.' }], details: { graph } };
      }
      try {
        const answer = await postJson('/api/v1/agent/ingest', {
          graph, apply: true, rulePack: 'incident',
          dictionary: await graphDictionary(graph),
          documents: [{
            id: `session-${now().toISOString().replace(/[^0-9]/g, '').slice(0, 14)}`,
            title: 'conversation', text, validFrom: now().toISOString(),
          }],
        });
        return { content: [{ type: 'text', text: ingestSummary(answer) }], details: { graph, answer } };
      } catch (error) {
        return {
          content: [{ type: 'text', text: `PhoenixGraph is not answering at ${state.endpoint} (${error.message}).` }],
          details: { error: String(error), graph },
        };
      }
    },
  });

  // ---- ingesting documents, by hand ---------------------------------------------------------------
  // `/pg ingest <path>` reads local Markdown/text (or a whole directory) and posts it to the ingest API. It is a
  // command and not a tool on purpose: writing to a graph is a human decision, so the model cannot do it while
  // answering a question. Following tools/ingest-docs.mjs, it is a **dry run unless --apply** is given - the same
  // pipeline runs and the report says what would be written.

  const INGEST_MAX_FILES = 50;
  const INGEST_MAX_BYTES = 2 * 1024 * 1024;
  const TEXT_EXTENSIONS = new Set(['.md', '.markdown', '.txt', '.text', '.html', '.htm', '.csv', '.yaml', '.yml', '.log']);

  /** Every readable document under a path (a file, or a directory walked three levels deep). */
  function readDocuments(target) {
    const documents = [];
    const skipped = [];
    let bytes = 0;
    const visit = (path, depth) => {
      let stats;
      try {
        stats = statSync(path);
      } catch {
        skipped.push(`${path}: not readable`);
        return;
      }
      if (stats.isFile()) {
        const extension = extname(path).toLowerCase();
        if (!TEXT_EXTENSIONS.has(extension)) {
          skipped.push(`${basename(path)}: not text (${extension || 'no extension'})`);
          return;
        }
        if (documents.length >= INGEST_MAX_FILES) {
          skipped.push(`${basename(path)}: over the ${INGEST_MAX_FILES}-file limit`);
          return;
        }
        if (bytes + stats.size > INGEST_MAX_BYTES) {
          skipped.push(`${basename(path)}: over the ${Math.round(INGEST_MAX_BYTES / 1024)} KB limit`);
          return;
        }
        const text = readFileSync(path, 'utf8');
        bytes += stats.size;
        documents.push({ id: basename(path).replace(/\.[^.]+$/, ''), title: basename(path), text });
        return;
      }
      if (!stats.isDirectory() || depth > 3) return;
      for (const entry of readdirSync(path).sort()) {
        if (entry === '.git' || entry === 'node_modules' || entry.startsWith('.')) continue;
        visit(join(path, entry), depth + 1);
      }
    };
    visit(target, 0);
    return { documents, skipped, bytes };
  }

  /** The gazetteer a directory may carry next to its documents (like the examples do). */
  function dictionaryFrom(target) {
    const file = join(statSync(target).isDirectory() ? target : dirname(target), 'dictionary.json');
    try {
      const parsed = JSON.parse(readFileSync(file, 'utf8'));
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
    } catch {
      return {};
    }
  }

  /** `--flag value` pairs and the positional argument, for the /pg subcommands that take options. */
  function parseOptions(args, valueFlags = ['from', 'to', 'graph', 'pack']) {
    const words = String(args || '').trim().split(/\s+/).filter(Boolean);
    const options = { positional: [], flags: {} };
    for (let index = 0; index < words.length; index += 1) {
      const word = words[index];
      if (word.startsWith('--')) {
        const name = word.slice(2);
        if (valueFlags.includes(name)) {
          options.flags[name] = words[index + 1];
          index += 1;
        } else {
          options.flags[name] = true;
        }
      } else {
        options.positional.push(word);
      }
    }
    return options;
  }

  // ---- P1: the switchboard ------------------------------------------------------------------------------
  pi.registerCommand('pg', {
    description: 'PhoenixGraph: status | show [entity] | remember [graph] | note <sentence> | ingest <path> [--apply] [--from <date>] | graph <name> | endpoint <url> | timeline on|off|<instant> | mcp stdio|url | inject on|off',
    getArgumentCompletions: (prefix) => ['status', 'show', 'remember', 'note', 'ingest', 'graph', 'endpoint', 'timeline', 'inject', 'mcp']
      .filter((word) => word.startsWith(prefix)).map((word) => ({ value: word, label: word })),
    async handler(args, ctx) {
      reconstruct(ctx);
      const [subcommand, ...rest] = args.trim().split(/\s+/).filter(Boolean);
      const value = rest.join(' ');
      switch ((subcommand || 'status').toLowerCase()) {
        case 'graph': {
          if (!value) { ctx.ui.notify(`graph: ${state.graph}`, 'info'); return; }
          commit({ graph: value }, { notify: () => ctx.ui.notify(`graph: ${value}`, 'info') });
          return;
        }
        case 'endpoint': {
          if (!value) { ctx.ui.notify(`endpoint: ${state.endpoint}`, 'info'); return; }
          commit({ endpoint: value }, { notify: () => ctx.ui.notify(`endpoint: ${value}`, 'info') });
          return;
        }
        case 'timeline': {
          const word = value.toLowerCase();
          if (!word || word === 'status') {
            ctx.ui.notify(state.timeline && state.asOf ? `timeline: ${state.asOf}` : 'timeline: off', 'info');
            return;
          }
          if (word === 'off') {
            commit({ timeline: false, asOf: null }, { notify: () => ctx.ui.notify('timeline: off', 'info') });
            return;
          }
          if (word === 'on') {
            commit({ timeline: true }, { notify: () => ctx.ui.notify(`timeline: ${state.asOf || 'on (no instant set)'}`, 'info') });
            return;
          }
          try {
            const instant = toInstant(value);
            commit({ timeline: true, asOf: instant }, { notify: () => ctx.ui.notify(`timeline: ${instant}`, 'info') });
          } catch (error) {
            ctx.ui.notify(String(error.message || error), 'error');
          }
          return;
        }
        case 'ingest': {
          const parsed = parseOptions(value);
          const target = parsed.positional[0];
          if (!target) {
            ctx.ui.notify('usage: /pg ingest <file-or-directory> [--apply] [--from <date>] [--graph <name>]'
              + ' [--pack incident]', 'warning');
            return;
          }
          const path = resolve(ctx.cwd || process.cwd(), target);
          let found;
          try {
            found = readDocuments(path);
          } catch (error) {
            ctx.ui.notify(`cannot read ${target}: ${error.message}`, 'error');
            return;
          }
          if (!found.documents.length) {
            ctx.ui.notify(`nothing to ingest under ${target}${found.skipped.length ? `: ${found.skipped.slice(0, 3).join('; ')}` : ''}`, 'warning');
            return;
          }
          const graph = parsed.flags.graph || state.graph;
          const apply = parsed.flags.apply === true;
          try {
            const answer = await postJson('/api/v1/agent/ingest', {
              graph,
              apply,
              rulePack: parsed.flags.pack || 'incident',
              dictionary: { ...(await graphDictionary(graph)), ...dictionaryFrom(join(path, '')) },
              documents: found.documents.map((document) => ({
                ...document,
                ...(parsed.flags.from ? { validFrom: parsed.flags.from } : {}),
                ...(parsed.flags.to ? { validTo: parsed.flags.to } : {}),
              })),
            });
            const head = apply
              ? `ingested ${found.documents.length} document(s) into '${graph}'`
              : `dry run: ${found.documents.length} document(s) would be ingested into '${graph}' (add --apply)`;
            const skipped = found.skipped.length ? `
skipped: ${found.skipped.slice(0, 5).join('; ')}` : '';
            ctx.ui.notify(`${head}
${ingestSummary(answer)}${skipped}`, 'info');
          } catch (error) {
            ctx.ui.notify(`PhoenixGraph is not answering at ${state.endpoint} (${error.message})`, 'error');
          }
          return;
        }
        case 'remember': {
          const graph = value || state.graph;
          const text = conversationText(ctx);
          if (!text.trim()) { ctx.ui.notify('nothing to remember yet: this session has no turns', 'warning'); return; }
          try {
            const answer = await ingestText(graph, 'conversation', text);
            ctx.ui.notify(`remembered into '${graph}':
${ingestSummary(answer)}`, 'info');
          } catch (error) {
            ctx.ui.notify(`PhoenixGraph is not answering at ${state.endpoint} (${error.message})`, 'error');
          }
          return;
        }
        case 'note': {
          if (!value) { ctx.ui.notify('usage: /pg note <sentence>', 'warning'); return; }
          try {
            const answer = await ingestText(state.graph, 'note', `user: ${value}`);
            ctx.ui.notify(`noted into '${state.graph}':
${ingestSummary(answer)}`, 'info');
          } catch (error) {
            ctx.ui.notify(`PhoenixGraph is not answering at ${state.endpoint} (${error.message})`, 'error');
          }
          return;
        }
        case 'show': {
          const link = await contextLink(value || undefined);
          const opened = await openUrl(link.url);
          ctx.ui.notify([
            opened ? `opened ${link.url}` : `open this: ${link.url}`,
            link.query ? `query: ${link.query}` : 'query: none - the graph has nothing to draw yet',
            `from: ${link.source}`,
          ].join(String.fromCharCode(10)), opened ? 'info' : 'warning');
          return;
        }
        case 'mcp': {
          if (!value) {
            ctx.ui.notify(state.mcpUrl ? `mcp: http ${state.mcpUrl}` : 'mcp: stdio (spawned next to this extension)', 'info');
            return;
          }
          const transport = value.toLowerCase() === 'stdio' ? null : value;
          commit({ mcpUrl: transport }, {
            notify: () => ctx.ui.notify(transport
              ? `mcp: http ${transport} - takes effect on the next session (a server is registered at load)`
              : 'mcp: stdio - takes effect on the next session', 'info'),
          });
          return;
        }
        case 'inject': {
          if (!value) { ctx.ui.notify(`inject: ${state.inject ? 'on' : 'off'}`, 'info'); return; }
          const on = value.toLowerCase() !== 'off';
          commit({ inject: on }, { notify: () => ctx.ui.notify(`inject: ${on ? 'on' : 'off'}`, 'info') });
          return;
        }
        case 'status':
        default: {
          const lines = [
            `graph:    ${state.graph}`,
            `endpoint: ${state.endpoint}`,
            `timeline: ${state.timeline && state.asOf ? `pinned to ${state.asOf}` : 'off (answers use every fact)'}`,
            `inject:   ${state.inject ? 'on (the contract is in the prompt when this session is about a graph)' : 'off'}`,
            `mcp:      ${state.mcpUrl ? `http ${state.mcpUrl}` : 'stdio (spawned next to this extension)'}`,
            state.lastContext
              ? `last:     ${state.lastContext.question} (${state.lastContext.graph}${state.lastContext.asOf ? `, as of ${state.lastContext.asOf}` : ''})`
              : 'last:     nothing asked yet',
          ];
          ctx.ui.notify(lines.join('\n'), 'info');
        }
      }
    },
  });

  // ---- P1: the state follows the conversation ------------------------------------------------------------
  pi.on('session_start', async (_event, ctx) => reconstruct(ctx));
  pi.on('session_tree', async (_event, ctx) => reconstruct(ctx));

  return { state: () => ({ ...state }) };
}
