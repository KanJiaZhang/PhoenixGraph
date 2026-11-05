/**
 * pi-data-agent for pi.
 *
 * The tools are thin: they read sources, write a metadata + semantic layer into PhoenixGraph, apply
 * reviewer edits, and compute a dashboard / an attribution. The policy lives in src/service.mjs and the
 * modules below it, so the CLI, the tests and this extension answer the same way.
 *
 * State (which graph, which fact graph, the last build) is session-scoped in a custom entry and survives a
 * restart in ~/.pi/agent/state/pi-data-agent.json. The extractor never writes state.
 */
import { existsSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import {
  clientFor, scanSources, buildDataAgent, loadModel, editDataAgent, dashboardFor, attributeFor,
  statusFor, renderScanReport, renderBuildReport, renderModel, rememberChoice,
} from '../../src/service.mjs';
import { loadConfig, projectFile, readJson, stateFile, writeJson } from '../../src/config.mjs';
import { renderSimilar, similarWithGraph } from '../../src/similar.mjs';
import { renderSchedule, runSchedule } from '../../src/schedule.mjs';
import { askFor, runPlanned } from '../../src/service.mjs';
import { renderPlan } from '../../src/plan.mjs';
import {
  readRiskCases, loadRiskManifest, riskDashboard, riskAttribution, renderRiskAttribution,
  renderRiskDashboardMarkdown, saveRiskDashboard,
} from '../../src/risk.mjs';

const PACKAGE_ROOT = fileURLToPath(new URL('../../', import.meta.url));
const RISK_CASES = join(PACKAGE_ROOT, 'examples', 'risk-control', 'risk-cases.csv');
const RISK_MANIFEST = join(PACKAGE_ROOT, 'examples', 'risk-control', 'risk-model.json');

const DEFAULTS = {
  endpoint: 'http://localhost:8081',
  graph: 'data-agent',
  dataGraph: null,
  vertexType: null,
  active: false,
  lastBuild: null,
};

function pickState(saved) {
  const out = {};
  for (const key of Object.keys(DEFAULTS)) if (saved && saved[key] !== undefined) out[key] = saved[key];
  return out;
}

export default function piDataAgentExtension(pi, options = {}) {
  const env = options.env || process.env;
  const home = options.home || homedir();
  const fetchImpl = options.fetchImpl;
  const file = stateFile(home);
  let state = { ...DEFAULTS, ...pickState(readJson(file)) };
  let loaded = false;

  function readProject(ctx) {
    try {
      return JSON.parse(readFileSync(projectFile(ctx?.cwd || process.cwd()), 'utf8')) || {};
    } catch {
      return {};
    }
  }

  function reconstruct(ctx) {
    if (!loaded) {
      const project = readProject(ctx);
      state = {
        ...state,
        ...(project.endpoint ? { endpoint: project.endpoint } : {}),
        ...(project.graph ? { graph: project.graph } : {}),
        ...(project.dataGraph ? { dataGraph: project.dataGraph } : {}),
        ...(project.vertexType ? { vertexType: project.vertexType } : {}),
        ...(env.PHOENIXGRAPH_API ? { endpoint: env.PHOENIXGRAPH_API } : {}),
        ...(env.PI_DATA_AGENT_GRAPH ? { graph: env.PI_DATA_AGENT_GRAPH } : {}),
      };
      loaded = true;
    }
    const branch = ctx?.sessionManager?.getBranch?.() || [];
    for (const entry of branch) {
      if (entry?.type === 'custom' && entry.customType === 'pi-data-agent.state' && entry.data) {
        state = { ...state, ...pickState(entry.data) };
      }
    }
    return state;
  }

  function commit(changes) {
    state = { ...state, ...changes };
    try { pi.appendEntry('pi-data-agent.state', pickState(state)); } catch { /* non-interactive */ }
    writeJson(file, pickState(state));
    return state;
  }

  function config(overrides = {}) {
    return loadConfig({
      endpoint: state.endpoint,
      graph: state.graph,
      dataGraph: state.dataGraph,
      vertexType: state.vertexType,
      apiKey: env.PHOENIXGRAPH_API_KEY || '',
      env,
      ...overrides,
    });
  }

  function client(overrides = {}) {
    return clientFor({ ...config(overrides), fetchImpl, env });
  }

  function stateLine() {
    return `graph=${state.graph} endpoint=${state.endpoint} facts=${state.dataGraph || '(none)'}/${state.vertexType || '(none)'}`
      + (state.lastBuild ? ` lastBuild=${state.lastBuild.at}` : '');
  }

  /**
   * The confirmation gate: in an interactive session the plan is shown and the user decides; anywhere else
   * (print mode, JSON, RPC without UI, a tool called by another tool) it runs and the result says it was not
   * confirmed. `autoApprove` exists for a caller that has already shown the plan itself.
   */
  function confirmationGate(ctx, params = {}) {
    // no UI (print/JSON/RPC-without-dialog) or an explicit autoApprove: there is nobody to ask, so no gate is
    // passed at all - the answer then records confirmed:false instead of pretending somebody agreed.
    if (params.autoApprove === true) return undefined;
    if (!ctx?.hasUI || typeof ctx.ui?.confirm !== 'function') return undefined;
    return async (plan, planText) => ctx.ui.confirm('确认执行这个数据查询？', planText || renderPlan(plan));
  }

  /** The reply when the user declines: the plan, and nothing executed. */
  function cancelledResult(planText, reason) {
    return {
      content: [{ type: 'text', text: `未执行（未确认）：

${planText}

${reason || ''}`.trim() }],
      details: { ok: false, cancelled: true, planText },
      isError: false,
    };
  }

  const contract = [
    'contract:',
    '- a metric without an expression is a term needing review; never invent a formula.',
    '- a metric/dimension without a graphProperty is SQL-only: refuse execution instead of guessing a column.',
    '- every node and edge carries the sentence/row it was read from; show it when the user asks why.',
    '- attribution is descriptive, not causal; report the contributions and say so.',
    '- edits are validated and audited; deletes need confirm:true.',
  ].join('\n');

  // ---- tools ------------------------------------------------------------------------------------------

  pi.registerTool({
    name: 'pg_data_scan',
    label: 'Data agent scan',
    description: 'Read PDF/Markdown/wiki/Word documents and database SHOW/DDL dumps, extract tables, columns, metrics, dimensions, units and synonyms, and return the model preview with the evidence and the review list. Writes nothing.',
    promptSnippet: 'pg_data_scan(sources) - extract a metadata/semantic model from documents and DB dumps (dry run)',
    annotations: { readOnlyHint: true, openWorldHint: false },
    parameters: {
      type: 'object',
      properties: {
        sources: { type: 'array', items: { type: 'string' }, description: 'Files, directories, or http(s) wiki URLs' },
        graph: { type: 'string', description: 'Model graph name (default: the session graph)' },
        dataGraph: { type: 'string', description: 'Fact graph used to bind graphProperty' },
        vertexType: { type: 'string', description: 'Fact vertex type (auto-detected when the data graph has one)' },
        bindings: { type: 'object', description: 'Explicit bindings, e.g. {"metric/revenue":"revenue"}' },
        ocr: { type: 'boolean', description: 'OCR a scanned PDF (needs pdftoppm + tesseract)' },
        inferBindings: { type: 'boolean', description: 'Bind a metric/dimension when the fact vertex declares the property (default true)' },
      },
      required: ['sources'],
    },
    async execute(_toolCallId, params) {
      try {
        const scan = await scanSources(params.sources, {
          ...config(params), client: client(params), bindings: params.bindings || {}, inferBindings: params.inferBindings,
        });
        return {
          content: [{ type: 'text', text: renderScanReport(scan) }],
          details: { graph: scan.model.graph, documents: scan.documents, skipped: scan.skipped, review: scan.review, stats: scan.model.stats, warnings: scan.warnings },
        };
      } catch (error) {
        return { content: [{ type: 'text', text: `scan failed: ${error.message}` }], details: { error: String(error) }, isError: true };
      }
    },
  });

  pi.registerTool({
    name: 'pg_data_ask',
    label: 'Data agent question',
    description: 'Ask the model a question in words: "2025年营收按渠道拆分，前3" becomes a dashboard, "2024 到 2025 营收按渠道为什么增长" becomes an attribution. The parsed request comes back with the answer so the understanding can be checked, and a question naming nothing the model holds is refused with the alternatives instead of guessed.',
    promptSnippet: 'pg_data_ask(question) - a question in words, parsed against the model into a dashboard or an attribution',
    annotations: { readOnlyHint: true, openWorldHint: false },
    parameters: {
      type: 'object',
      properties: {
        question: { type: 'string', description: 'The question as a person would ask it (Chinese or English)' },
        graph: { type: 'string', description: 'Model graph (default: the session graph)' },
        asOf: { type: 'string', description: 'Answer with the definitions in force at this instant (ISO date/time)' },
      },
      required: ['question'],
    },
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      try {
        const result = await askFor(params.question, {
          ...config(params), client: client(params), graph: params.graph || state.graph, asOf: params.asOf,
          autoApprove: params.autoApprove,
          confirm: confirmationGate(ctx, params),
        });
        if (result.cancelled) return cancelledResult(result.planText, result.reason);
        const understood = result.ok ? `understood as ${result.kind}: ${JSON.stringify(result.request)}` : `not understood: ${result.reason}`;
        return {
          content: [{ type: 'text', text: `${understood}

${result.markdown || ''}`.trim() }],
          details: { ok: result.ok, kind: result.kind, confirmed: result.confirmed === true, understood: result.understood || null, request: result.request || null, reason: result.reason || null, files: result.files || null, plan: result.plan || null },
          isError: result.ok !== true,
        };
      } catch (error) {
        return { content: [{ type: 'text', text: `ask failed: ${error.message}` }], details: { error: String(error) }, isError: true };
      }
    },
  });

  pi.registerTool({
    name: 'pg_data_similar',
    label: 'Data agent similarity',
    description: 'Vector similarity over the model combined with its relations: embed the text, rank the metrics/dimensions/terms/columns by cosine, then expand each match through the Relation edges so the answer carries the lineage (columns, tables, dimensions, source) behind every candidate. A candidate with no relations is reported as isolated rather than dressed up.',
    promptSnippet: 'pg_data_similar(text) - which definition is meant, and what it is made of (vector + graph)',
    annotations: { readOnlyHint: true, openWorldHint: false },
    parameters: {
      type: 'object',
      properties: {
        text: { type: 'string', description: 'The phrase to find a definition for (a business word, a synonym, a question fragment)' },
        k: { type: 'number', description: 'How many candidates (default 5)' },
        hops: { type: 'number', description: 'How far to walk the relations from each match (default 1)' },
        entityTypes: { type: 'array', items: { type: 'string' }, description: 'Restrict to Metric / Dimension / Term / Column / Table / Domain' },
        graph: { type: 'string', description: 'Model graph (default: the session graph)' },
        asOf: { type: 'string', description: 'Search the definitions in force at this instant (ISO date/time)' },
      },
      required: ['text'],
    },
    async execute(_toolCallId, params) {
      try {
        const model = await loadModel(client(params), params.graph || state.graph, { asOf: params.asOf });
        const bundle = similarWithGraph(model, params.text, { k: params.k, hops: params.hops, entityTypes: params.entityTypes });
        return {
          content: [{ type: 'text', text: renderSimilar(bundle) }],
          details: { graph: model.graph, asOf: model.asOf, query: bundle.query, matches: bundle.matches.map((match) => ({ id: match.id, score: match.score, relations: match.relations.length })), isolated: bundle.isolated },
        };
      } catch (error) {
        return { content: [{ type: 'text', text: `similar failed: ${error.message}` }], details: { error: String(error) }, isError: true };
      }
    },
  });
  pi.registerTool({
    name: 'pg_data_build',
    label: 'Data agent build',
    description: 'Scan the sources and write the metadata + semantic layer into a PhoenixGraph graph (schema created when new). Returns what persisted, the review list and the graph snapshot path. A dry run writes nothing.',
    promptSnippet: 'pg_data_build(sources) - write the extracted metadata/semantic layer into PhoenixGraph',
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false },
    parameters: {
      type: 'object',
      properties: {
        sources: { type: 'array', items: { type: 'string' }, description: 'Files, directories, or http(s) wiki URLs' },
        graph: { type: 'string', description: 'Model graph to build (default: the session graph)' },
        dataGraph: { type: 'string', description: 'Fact graph the model executes on' },
        vertexType: { type: 'string', description: 'Fact vertex type' },
        bindings: { type: 'object', description: 'Explicit bindings, e.g. {"metric/revenue":"revenue","dim/date":"year=year,month=month"}' },
        dryRun: { type: 'boolean', description: 'Extract and map everything, write nothing' },
        storage: { type: 'string', description: 'Storage type for a new graph (default Aerospike; the package reads it through the Model catalog)' },
        schemaDir: { type: 'string', description: 'Directory for the schema copy a restart discovers (default .pida-schemas)' },
      },
      required: ['sources'],
    },
    async execute(_toolCallId, params) {
      try {
        const build = await buildDataAgent(params.sources, {
          ...config(params), client: client(params), bindings: params.bindings || {}, dryRun: params.dryRun === true, storage: params.storage, schemaDir: params.schemaDir,
        });
        if (params.dryRun !== true) {
          commit({ graph: build.graph, dataGraph: build.dataGraph, vertexType: build.vertexType, active: true, lastBuild: { at: new Date().toISOString(), graph: build.graph, dataGraph: build.dataGraph, vertexType: build.vertexType, vertices: build.write.vertices, edges: build.write.edges } });
          rememberChoice({ graph: build.graph, dataGraph: build.dataGraph, vertexType: build.vertexType, endpoint: state.endpoint }, { home });
        }
        return {
          content: [{ type: 'text', text: renderBuildReport(build) }],
          details: { graph: build.graph, dryRun: build.dryRun, write: build.write, stats: build.model.stats, review: build.review, warnings: build.warnings || build.model.warnings },
        };
      } catch (error) {
        return { content: [{ type: 'text', text: `build failed: ${error.message}` }], details: { error: String(error) }, isError: true };
      }
    },
  });

  pi.registerTool({
    name: 'pg_data_model',
    label: 'Data agent model',
    description: 'Show the metadata and semantic model stored in a PhoenixGraph graph: metrics with expressions and bindings, dimensions, tables, queries, terms and the review list.',
    promptSnippet: 'pg_data_model() - the stored model, its bindings and what needs review',
    annotations: { readOnlyHint: true, openWorldHint: false },
    parameters: {
      type: 'object',
      properties: {
        graph: { type: 'string', description: 'Model graph (default: the session graph)' },
        asOf: { type: 'string', description: 'Read the model as it stood at this instant (ISO date/time), so a replaced definition answers instead of the current one' },
        json: { type: 'boolean', description: 'Return the structured model instead of prose' },
      },
      required: [],
    },
    async execute(_toolCallId, params) {
      try {
        const model = await loadModel(client(params), params.graph || state.graph, { asOf: params.asOf });
        return {
          content: [{ type: 'text', text: params.json ? JSON.stringify({ graph: model.graph, dataGraph: model.dataGraph, vertexType: model.vertexType, asOf: model.asOf, stats: model.stats, nodes: model.nodes, edges: model.edges, review: model.review }, null, 2) : renderModel(model) }],
          details: { graph: model.graph, asOf: model.asOf, stats: model.stats, review: model.review },
        };
      } catch (error) {
        return { content: [{ type: 'text', text: `model failed: ${error.message}` }], details: { error: String(error) }, isError: true };
      }
    },
  });

  pi.registerTool({
    name: 'pg_data_edit',
    label: 'Data agent edit',
    description: 'Add, change or remove metadata, entities and relations, validated and audited. Operations: upsert-node, update-node, set-binding, add-alias, upsert-edge, update-edge, delete-node, delete-edge. A delete needs confirm:true (and cascade:true when the node has edges).',
    promptSnippet: 'pg_data_edit(operations) - reviewer edits to nodes, relations and bindings, with an audit trail',
    annotations: { readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false },
    parameters: {
      type: 'object',
      properties: {
        operations: { type: 'array', items: { type: 'object' }, description: 'Validated edit operations' },
        graph: { type: 'string', description: 'Model graph (default: the session graph)' },
        dryRun: { type: 'boolean', description: 'Validate and preview, write nothing' },
        allowDelete: { type: 'boolean', description: 'Allow deletes without confirm:true (default false)' },
      },
      required: ['operations'],
    },
    async execute(_toolCallId, params) {
      try {
        const result = await editDataAgent(params.operations, { ...config(params), client: client(params), dryRun: params.dryRun === true, allowDelete: params.allowDelete === true });
        const lines = [
          `edits: ${result.summary.operations} operation(s), ${result.summary.changedNodes} node(s), ${result.summary.changedEdges} edge(s)`,
          `written: ${JSON.stringify(result.written)}${result.dryRun ? ' (dry run)' : ''}`,
          result.snapshot ? `snapshot: ${result.snapshot}` : null,
          result.auditFile ? `audit: ${result.auditFile}` : null,
          ...result.warnings.map((warning) => `warning: ${warning}`),
        ].filter(Boolean);
        return { content: [{ type: 'text', text: lines.join('\n') }], details: { graph: result.graph, summary: result.summary, written: result.written, audit: result.audit, dryRun: result.dryRun } };
      } catch (error) {
        return { content: [{ type: 'text', text: `edit failed: ${error.message}` }], details: { error: String(error) }, isError: true };
      }
    },
  });

  pi.registerTool({
    name: 'pg_data_dashboard',
    label: 'Data agent dashboard',
    description: 'Build a dashboard from the semantic layer and the fact graph: totals, a trend over the date dimension, and top-N breakdowns, each tile carrying its traversal and warehouse SQL. Tiles that cannot execute are shown as refusals, not empty charts. Writes an HTML file (and JSON) under .pi/data-agent/dashboards.',
    promptSnippet: 'pg_data_dashboard() - totals, trends and breakdowns with provenance, written as HTML',
    annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: false },
    parameters: {
      type: 'object',
      properties: {
        graph: { type: 'string', description: 'Model graph (default: the session graph)' },
        year: { type: 'string', description: 'Period filter, e.g. 2025' },
        topN: { type: 'number', description: 'Rows per breakdown (default 5)' },
        dimensions: { type: 'array', items: { type: 'string' }, description: 'Dimensions to break down by' },
        metrics: { type: 'array', items: { type: 'string' }, description: 'Metrics to show (default all)' },
        write: { type: 'boolean', description: 'Write the HTML/JSON files (default true)' },
        asOf: { type: 'string', description: 'Compute with the definitions in force at this instant (ISO date/time)' },
        autoApprove: { type: 'boolean', description: 'Skip the confirmation dialog (default: ask when the session has a UI)' },
      },
      required: [],
    },
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      try {
        const planned = await runPlanned({ kind: 'dashboard', request: params }, {
          ...config(params), client: client(params), graph: params.graph || state.graph, asOf: params.asOf,
          write: params.write !== false,
          confirm: confirmationGate(ctx, params),
        });
        if (planned.cancelled) return cancelledResult(planned.planText, planned.reason);
        if (!planned.ok) return { content: [{ type: 'text', text: `dashboard refused before running:\n\n${planned.planText}` }], details: { plan: planned.plan, reason: planned.reason }, isError: true };
        const result = { dashboard: planned.dashboard, files: planned.files, markdown: planned.markdown, confirmed: planned.confirmed };
        return {
          content: [{ type: 'text', text: `${result.markdown}${result.files ? `\n\nhtml: ${result.files.html}\njson: ${result.files.json}` : ''}` }],
          details: { graph: result.dashboard.graph, dataGraph: result.dashboard.dataGraph, executed: result.dashboard.executed, refused: result.dashboard.refused, files: result.files, tiles: result.dashboard.tiles.map((tile) => ({ id: tile.id, kind: tile.kind, executed: tile.executed, reason: tile.reason })) },
        };
      } catch (error) {
        return { content: [{ type: 'text', text: `dashboard failed: ${error.message}` }], details: { error: String(error) }, isError: true };
      }
    },
  });

  pi.registerTool({
    name: 'pg_data_attribute',
    label: 'Data agent attribution',
    description: 'Attribute a change between two periods to one dimension: totals, per-slice before/after/delta/shareOfChange/growth, concentrations and an outlier reading, with the arithmetic shown and the "descriptive, not causal" limit. Refuses when the metric or dimension is unbound.',
    promptSnippet: 'pg_data_attribute(metric, by, from, to) - which slice accounts for a change, with the arithmetic',
    annotations: { readOnlyHint: true, openWorldHint: false },
    parameters: {
      type: 'object',
      properties: {
        metric: { type: 'string', description: 'Metric id or name, e.g. metric/revenue' },
        by: { type: 'string', description: 'Dimension id or name, e.g. dim/region' },
        from: { type: 'string', description: 'Earlier period, e.g. 2024' },
        to: { type: 'string', description: 'Later period, e.g. 2025' },
        graph: { type: 'string', description: 'Model graph (default: the session graph)' },
        asOf: { type: 'string', description: 'Compute with the definitions in force at this instant (ISO date/time)' },
        autoApprove: { type: 'boolean', description: 'Skip the confirmation dialog (default: ask when the session has a UI)' },
      },
      required: ['metric', 'by', 'from', 'to'],
    },
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      try {
        const planned = await runPlanned({ kind: 'attribution', request: params }, {
          ...config(params), client: client(params), graph: params.graph || state.graph, asOf: params.asOf,
          confirm: confirmationGate(ctx, params),
        });
        if (planned.cancelled) return cancelledResult(planned.planText, planned.reason);
        if (!planned.ok) return { content: [{ type: 'text', text: `attribution refused before running:\n\n${planned.planText}` }], details: { plan: planned.plan, reason: planned.reason }, isError: true };
        const result = { result: planned.result, markdown: planned.markdown, confirmed: planned.confirmed };
        return {
          content: [{ type: 'text', text: result.markdown }],
          details: { executed: result.result.executed, reason: result.result.reason, attribution: result.result.attribution, before: result.result.before?.total, after: result.result.after?.total },
        };
      } catch (error) {
        return { content: [{ type: 'text', text: `attribution failed: ${error.message}` }], details: { error: String(error) }, isError: true };
      }
    },
  });

  pi.registerTool({
    name: 'pg_risk_dashboard',
    label: 'Risk dashboard',
    description: 'Risk-control dashboard from an internal case ledger: exposure / loss / blocked / cases / block rate / false-positive rate / review hours, breakdowns by ring, rule and channel, and entity evidence for each ring (FuzzyLink key, payments, collection receiver degree). Writes an HTML/JSON dashboard under .pi/data-agent/risk.',
    promptSnippet: 'pg_risk_dashboard(period) - risk KPIs, ring/rule/channel breakdowns and graph evidence',
    annotations: { readOnlyHint: false, destructiveHint: false, openWorldHint: false },
    parameters: {
      type: 'object',
      properties: {
        cases: { type: 'string', description: 'Risk case ledger (CSV/JSON path or inline); default examples/risk-control/risk-cases.csv' },
        manifest: { type: 'string', description: 'Risk model manifest; default examples/risk-control/risk-model.json' },
        period: { type: 'string', description: 'Period to show (default the latest in the ledger)' },
        riskGraph: { type: 'string', description: 'Entity graph holding Account/FuzzyLink/Payment (default from the manifest)' },
        topN: { type: 'number', description: 'Rows per breakdown (default 8)' },
        write: { type: 'boolean', description: 'Write the HTML/JSON files (default true)' },
      },
      required: [],
    },
    async execute(_toolCallId, params) {
      try {
        const { rows } = readRiskCases(params.cases || RISK_CASES);
        const manifest = loadRiskManifest(params.manifest || RISK_MANIFEST);
        const dashboard = await riskDashboard(client(params), rows, manifest, { period: params.period, riskGraph: params.riskGraph, topN: params.topN });
        const files = params.write === false ? null : saveRiskDashboard(dashboard);
        return {
          content: [{ type: 'text', text: `${renderRiskDashboardMarkdown(dashboard)}${files ? `\n\nhtml: ${files.html}\njson: ${files.json}` : ''}` }],
          details: { period: dashboard.period, summary: dashboard.summary, deltas: dashboard.deltas, evidence: dashboard.evidence.map((ring) => ({ ring: ring.ring, verdict: ring.verdict, link: ring.link?.key, memberCount: ring.memberCount })), files },
        };
      } catch (error) {
        return { content: [{ type: 'text', text: `risk dashboard failed: ${error.message}` }], details: { error: String(error) }, isError: true };
      }
    },
  });

  pi.registerTool({
    name: 'pg_risk_attribution',
    label: 'Risk attribution',
    description: 'Attribute a risk-metric change between two periods to ring/rule/channel, then resolve the contributing rings in the PhoenixGraph payment graph: shared FuzzyLink key, payment count/amount, collection receiver degree, and ranked hypotheses with the checks that would refute them. Descriptive, not causal; FuzzyLink is a candidate, not proof.',
    promptSnippet: 'pg_risk_attribution(periods) - what moved, which ring explains it, and what would refute the story',
    annotations: { readOnlyHint: true, openWorldHint: false },
    parameters: {
      type: 'object',
      properties: {
        cases: { type: 'string', description: 'Risk case ledger (CSV/JSON path or inline); default examples/risk-control/risk-cases.csv' },
        manifest: { type: 'string', description: 'Risk model manifest; default examples/risk-control/risk-model.json' },
        metric: { type: 'string', description: 'Metric to decompose (default exposure)' },
        by: { type: 'string', description: 'Dimension to decompose by (default ring; rule/channel also available)' },
        from: { type: 'string', description: 'Earlier period, e.g. 2025-09' },
        to: { type: 'string', description: 'Later period, e.g. 2025-10' },
        riskGraph: { type: 'string', description: 'Entity graph (default from the manifest)' },
        enrichRings: { type: 'array', items: { type: 'string' }, description: 'Extra rings to resolve' },
      },
      required: [],
    },
    async execute(_toolCallId, params) {
      try {
        const { rows } = readRiskCases(params.cases || RISK_CASES);
        const manifest = loadRiskManifest(params.manifest || RISK_MANIFEST);
        const bundle = await riskAttribution(client(params), rows, manifest, {
          metric: params.metric, by: params.by, from: params.from, to: params.to,
          riskGraph: params.riskGraph, enrichRings: params.enrichRings, casesPath: params.cases || RISK_CASES,
        });
        return {
          content: [{ type: 'text', text: renderRiskAttribution(bundle) }],
          details: { graph: bundle.graph, from: bundle.from, to: bundle.to, summaryBefore: bundle.summaryBefore, summaryAfter: bundle.summaryAfter, deltas: bundle.deltas, hypotheses: bundle.hypotheses.map((hypothesis) => ({ id: hypothesis.id, status: hypothesis.status, score: hypothesis.score })), evidence: bundle.ringEvidence.map((ring) => ({ ring: ring.ring, verdict: ring.verdict })) },
        };
      } catch (error) {
        return { content: [{ type: 'text', text: `risk attribution failed: ${error.message}` }], details: { error: String(error) }, isError: true };
      }
    },
  });

  // ---- command ----------------------------------------------------------------------------------------

  const HELP = [
    '/data status',
    '/data graph <name>',
    '/data endpoint <url>',
    '/data scan <file-or-dir> [--data-graph <g> --vertex-type <T>]',
    '/data build <file-or-dir> [--graph <g> --data-graph <g> --vertex-type <T>]',
    '/data model [--as-of <instant>]',
  '/data ask <question>      (e.g. 2025年营收按渠道拆分，前3)',
  '/data similar <text> [--k 5] [--hops 1]',
  '/data schedule --periods 2024,2025 [--metric 营收 --by 渠道] [--every 60000]',
    '/data dashboard [--year 2025] [--top 5]',
    '/data attribute <metric> --by <dimension> --from 2024 --to 2025',
    '/data risk [cases] [--period 2025-10]',
    '/data risk-attr [cases] [--by ring --from 2025-09 --to 2025-10]',
    '/data edit <operations.json>',
  ].join('\n');

  pi.registerCommand('data', {
    description: 'pi-data-agent: scan/build the semantic layer, edit it, and read the dashboard/attribution',
    handler: async (args, ctx) => {
      reconstruct(ctx);
      const parts = String(args || '').trim().split(/\s+/).filter(Boolean);
      const command = parts.shift() || 'status';
      const flags = {};
      const positional = [];
      for (let index = 0; index < parts.length; index += 1) {
        const part = parts[index];
        if (part.startsWith('--')) {
          const key = part.slice(2);
          const next = parts[index + 1];
          if (next && !next.startsWith('--')) { flags[key] = next; index += 1; }
          else flags[key] = true;
        } else positional.push(part);
      }
      try {
        if (command === 'help') { ctx.ui?.notify?.(HELP, 'info'); return; }
        if (command === 'status') {
          const status = await statusFor({ ...config(), client: client() });
          const text = [
            `endpoint: ${status.endpoint}`,
            `graph: ${status.graph}${status.model ? ` (${status.model.stats?.metrics ?? 0} metrics, ${status.model.stats?.dimensions ?? 0} dimensions)` : ' (not built)'}`,
            `facts: ${status.dataGraph || status.model?.dataGraph || '(none)'}/${status.vertexType || status.model?.vertexType || '(none)'}`,
            `state: ${stateFile(home)}`,
            status.error ? `error: ${status.error}` : null,
          ].filter(Boolean).join('\n');
          ctx.ui?.notify?.(text, status.error ? 'error' : 'info');
          return;
        }
        if (command === 'graph') {
          if (!positional[0]) { ctx.ui?.notify?.('usage: /data graph <name>', 'warning'); return; }
          commit({ graph: positional[0], active: true });
          rememberChoice({ graph: positional[0], endpoint: state.endpoint }, { home });
          ctx.ui?.notify?.(`data-agent graph is now ${positional[0]}`, 'info');
          return;
        }
        if (command === 'endpoint') {
          if (!positional[0]) { ctx.ui?.notify?.('usage: /data endpoint <url>', 'warning'); return; }
          commit({ endpoint: positional[0].replace(/\/+$/, '') });
          ctx.ui?.notify?.(`endpoint is now ${state.endpoint}`, 'info');
          return;
        }
        if (command === 'scan' || command === 'build') {
          if (!positional.length) { ctx.ui?.notify?.(`usage: /data ${command} <file-or-dir>`, 'warning'); return; }
          const options = { ...config(), client: client(), dataGraph: flags['data-graph'] || state.dataGraph, vertexType: flags['vertex-type'] || state.vertexType, graph: flags.graph || state.graph };
          if (command === 'scan') {
            const scan = await scanSources(positional, options);
            ctx.ui?.notify?.(renderScanReport(scan).slice(0, 6000), 'info');
          } else {
            const build = await buildDataAgent(positional, options);
            commit({ graph: build.graph, dataGraph: build.dataGraph, vertexType: build.vertexType, active: true, lastBuild: { at: new Date().toISOString(), graph: build.graph, vertices: build.write.vertices, edges: build.write.edges } });
            ctx.ui?.notify?.(renderBuildReport(build).slice(0, 6000), 'info');
          }
          return;
        }
        if (command === 'model') {
          const model = await loadModel(client(), state.graph, { asOf: flags['as-of'] });
          ctx.ui?.notify?.(renderModel(model).slice(0, 6000), 'info');
          return;
        }
        if (command === 'dashboard') {
          const result = await dashboardFor({ ...config(), client: client(), year: flags.year, topN: flags.top ? Number(flags.top) : undefined, asOf: flags['as-of'] });
          ctx.ui?.notify?.(`${result.markdown.split('\n').slice(0, 12).join('\n')}\n${result.files ? `\nhtml: ${result.files.html}` : ''}`, 'info');
          return;
        }
        if (command === 'attribute') {
          const metric = positional[0];
          if (!metric || !flags.by || !flags.from || !flags.to) { ctx.ui?.notify?.('usage: /data attribute <metric> --by <dimension> --from 2024 --to 2025', 'warning'); return; }
          const result = await attributeFor({ ...config(), client: client(), metric, by: flags.by, from: flags.from, to: flags.to, asOf: flags['as-of'] });
          ctx.ui?.notify?.(result.markdown.slice(0, 6000), 'info');
          return;
        }
        if (command === 'risk') {
          const { rows } = readRiskCases(positional[0] || RISK_CASES);
          const manifest = loadRiskManifest(flags.manifest || RISK_MANIFEST);
          const dashboard = await riskDashboard(client(), rows, manifest, { period: flags.period, riskGraph: flags['risk-graph'], topN: flags.top ? Number(flags.top) : undefined });
          const files = saveRiskDashboard(dashboard);
          ctx.ui?.notify?.(`${renderRiskDashboardMarkdown(dashboard).slice(0, 5000)}\nhtml: ${files.html}`, 'info');
          return;
        }
        if (command === 'risk-attr') {
          const { rows } = readRiskCases(positional[0] || RISK_CASES);
          const manifest = loadRiskManifest(flags.manifest || RISK_MANIFEST);
          const bundle = await riskAttribution(client(), rows, manifest, {
            metric: flags.metric, by: flags.by, from: flags.from, to: flags.to, riskGraph: flags['risk-graph'],
          });
          ctx.ui?.notify?.(renderRiskAttribution(bundle).slice(0, 8000), 'info');
          return;
        }
        if (command === 'ask') {
          const question = positional.join(' ');
          if (!question) { ctx.ui?.notify?.('usage: /data ask <question>', 'warning'); return; }
          const result = await askFor(question, {
            ...config(), client: client(), graph: state.graph, asOf: flags['as-of'],
            confirm: confirmationGate(ctx),
          });
          ctx.ui?.notify?.(result.cancelled ? `未执行（未确认）：
${result.planText}` : result.ok
            ? `understood as ${result.kind}: ${JSON.stringify(result.request)}

${(result.markdown || '').slice(0, 5000)}`
            : `not understood: ${result.reason}`, result.ok ? 'info' : 'warning');
          return;
        }
        if (command === 'similar') {
          const text = positional.join(' ');
          if (!text) { ctx.ui?.notify?.(`usage: /data similar <text> [--k 5] [--hops 1]`, 'warning'); return; }
          const model = await loadModel(client(), state.graph, { asOf: flags['as-of'] });
          const bundle = similarWithGraph(model, text, { k: flags.k ? Number(flags.k) : undefined, hops: flags.hops ? Number(flags.hops) : undefined });
          ctx.ui?.notify?.(renderSimilar(bundle).slice(0, 6000), 'info');
          return;
        }
        if (command === 'schedule') {
          const periods = String(flags.periods || '').split(',').map((period) => period.trim()).filter(Boolean);
          if (!periods.length) { ctx.ui?.notify?.(`usage: /data schedule --periods 2024,2025[,2026] [--metric 营收 --by 渠道] [--every 60000]`, 'warning'); return; }
          const schedule = await runSchedule({
            ...config(), client: client(), periods, metric: flags.metric, by: flags.by,
            everyMs: flags.every ? Number(flags.every) : 0,
            outDir: flags.out, asOf: flags['as-of'],
            onRun: (run) => ctx.ui?.notify?.(`${run.period}: ${run.dashboard.executed} executed / ${run.dashboard.refused} refused`,
              run.dashboard.executed ? 'info' : 'warning'),
          });
          ctx.ui?.notify?.(renderSchedule(schedule), 'info');
          return;
        }
        if (command === 'edit') {
          if (!positional[0]) { ctx.ui?.notify?.('usage: /data edit <operations.json>', 'warning'); return; }
          const operations = JSON.parse(readFileSync(positional[0], 'utf8'));
          const result = await editDataAgent(operations, { ...config(), client: client(), dryRun: flags['dry-run'] === true });
          ctx.ui?.notify?.(`edits: ${result.summary.operations} operation(s); ${result.dryRun ? 'dry run' : `written ${JSON.stringify(result.written)}`}`, 'info');
          return;
        }
        ctx.ui?.notify?.(`unknown /data subcommand '${command}'\n${HELP}`, 'warning');
      } catch (error) {
        ctx.ui?.notify?.(`/data ${command} failed: ${error.message}`, 'error');
      }
    },
  });

  // ---- injection --------------------------------------------------------------------------------------

  pi.on('before_agent_start', async (event, ctx) => {
    reconstruct(ctx);
    const aware = state.active || Boolean(state.lastBuild) || existsSync(projectFile(ctx?.cwd || process.cwd()));
    if (!aware) return;
    const options2 = event?.systemPromptOptions;
    if (!options2 || typeof options2 !== 'object') return;
    let modelLine = '';
    if (state.lastBuild) {
      modelLine = `last build: ${state.lastBuild.graph} (${state.lastBuild.vertices ?? '?'} vertices, ${state.lastBuild.edges ?? '?'} edges) at ${state.lastBuild.at}`;
    }
    const block = [
      `[pi-data-agent] ${stateLine()}`,
      modelLine,
      'tools: pg_data_scan(sources) previews; pg_data_build(sources) writes; pg_data_model() shows; pg_data_edit(operations) fixes; pg_data_dashboard() and pg_data_attribute(metric,by,from,to) read; pg_data_ask(question) takes the question in words and returns what it understood; pg_data_similar(text) finds the definition a word means and shows its lineage; /data schedule runs both products on a timer; every tool takes asOf to answer with the definitions of that instant; pg_risk_dashboard() and pg_risk_attribution() combine the risk ledger with the payment graph.',
      contract,
    ].filter(Boolean).join('\n');
    options2.sections = { ...(options2.sections || {}), piDataAgent: block };
  });

  pi.on('session_start', async (_event, ctx) => {
    reconstruct(ctx);
  });
}
