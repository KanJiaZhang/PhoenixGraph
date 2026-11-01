/**
 * The package's use-cases, above the readers/writers:
 *
 *   scan       documents + DB dumps -> observations -> a model preview (nothing written)
 *   build      scan, then write the model into a PhoenixGraph graph (schema created when new)
 *   model      read the graph back as the model the dashboard and edits work on
 *   edit       validated reviewer edits, with an audit record
 *   dashboard  tiles computed from the fact graph
 *   attribute  a change decomposed by one dimension, with the arithmetic shown
 *
 * Every function takes the same options object (`endpoint`, `graph`, `dataGraph`, `vertexType`,
 * `apiKey`, ...), so the extension, the CLI and the tests share one implementation.
 */
import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { PhoenixClient, writeModel } from './graph/client.mjs';
import { readSources } from './readers/index.mjs';
import { extract } from './extract/documents.mjs';
import { buildModel, modelFromGraph } from './extract/model.mjs';
import { applyEdits } from './edits.mjs';
import { buildDashboard, renderDashboardHtml, renderDashboardMarkdown } from './dashboard.mjs';
import { runAttribution, renderAttribution } from './attribution.mjs';
import { loadConfig, remember, readJson, writeJson, stateFile, projectRoot } from './config.mjs';
import { quote, unique } from './util.mjs';

export function clientFor(options = {}) {
  return new PhoenixClient({
    endpoint: options.endpoint,
    apiKey: options.apiKey,
    fetchImpl: options.fetchImpl,
    timeout: options.timeout,
  });
}

function reviewOf(model, skipped = []) {
  const items = [];
  for (const node of model.nodes || []) {
    if (node.entityType === 'Metric') {
      if (!node.properties?.expression) {
        items.push({ id: node.id, kind: 'metric-without-expression', why: 'no aggregation expression was found; it cannot answer', evidence: node.properties?.evidence || null });
      }
      if (!node.properties?.graphProperty) {
        items.push({ id: node.id, kind: 'metric-unbound', why: 'no fact-graph property is bound; the SQL is available, execution is not', evidence: node.properties?.evidence || null });
      }
    }
    if (node.entityType === 'Dimension' && !node.properties?.graphProperty) {
      items.push({ id: node.id, kind: 'dimension-unbound', why: 'no fact-graph property is bound; the SQL is available, grouping is not', evidence: node.properties?.evidence || null });
    }
    if (['Metric', 'Dimension', 'Table', 'Column'].includes(node.entityType) && !node.properties?.evidence) {
      items.push({ id: node.id, kind: 'no-evidence', why: 'no sentence or row was recorded for this node', evidence: null });
    }
  }
  for (const item of skipped) items.push({ id: item.path, kind: 'source-skipped', why: item.reason, evidence: null });
  return items;
}

/**
 * Reads sources and builds a model in memory. Nothing is written; `options.overrides` are applied as
 * reviewer operations so a preview already reflects the corrections a user is about to make.
 */
export async function scanSources(sources, options = {}) {
  const config = loadConfig(options);
  const client = options.client || clientFor({ ...config, ...options });
  const sourcesList = sources ?? config.sources ?? [];
  if (!sourcesList.length) throw new Error('No sources: pass a file, a directory, an http(s) wiki URL, or inline {name,text}');
  const read = await readSources(sourcesList, {
    ocr: options.ocr === true,
    ocrLang: options.ocrLang,
    runPdf: options.runPdf,
    fetchImpl: options.fetchImpl,
    headers: options.headers,
    kind: options.kind,
  });
  if (!read.documents.length) {
    throw new Error(`No readable documents. ${read.skipped.map((item) => `${item.path}: ${item.reason}`).join('; ')}`);
  }
  let factSchema = null;
  const warnings = [...read.warnings];
  if (config.dataGraph) {
    factSchema = await client.schema(config.dataGraph);
    if (!factSchema) warnings.push(`data graph '${config.dataGraph}' is not served, so no graphProperty can be bound automatically`);
  }
  const observations = extract(read.documents, { inferDimensions: options.inferDimensions !== false });
  let model = buildModel({
    graph: options.graph || config.graph,
    dataGraph: config.dataGraph,
    vertexType: config.vertexType,
    documents: read.documents,
    observations,
    factSchema,
    bindings: options.bindings || {},
    inferBindings: options.inferBindings !== false,
    now: options.now,
  });
  if (Array.isArray(options.overrides) && options.overrides.length) {
    const applied = await applyEdits(client, model.graph, model, options.overrides, { dryRun: true, allowDelete: false, now: options.now });
    model = applied.model;
    warnings.push(...applied.warnings.map((warning) => `override: ${warning}`));
  }
  const review = reviewOf(model, read.skipped);
  return {
    ok: true,
    documents: read.documents.map((document) => ({ id: document.id, name: document.name, title: document.title, kind: document.kind, path: document.path, url: document.url })),
    skipped: read.skipped,
    warnings: unique([...warnings, ...model.warnings]),
    model,
    review,
  };
}

function snapshotPath(graph) {
  return join(projectRoot(), '.pi', 'data-agent', 'models', `${graph}.json`);
}

export function saveSnapshot(model, options = {}) {
  const path = options.path || snapshotPath(model.graph);
  writeJson(path, { graph: model.graph, dataGraph: model.dataGraph, vertexType: model.vertexType, builtAt: model.builtAt, stats: model.stats, nodes: model.nodes, edges: model.edges, sources: model.sources, warnings: model.warnings });
  return path;
}

export function appendAudit(graph, records, options = {}) {
  const path = options.path || join(projectRoot(), '.pi', 'data-agent', 'audit.jsonl');
  mkdirSync(join(projectRoot(), '.pi', 'data-agent'), { recursive: true });
  const at = new Date(options.now ?? Date.now()).toISOString();
  const lines = records.map((record) => JSON.stringify({ at, graph, ...record }));
  appendFileSync(path, `${lines.join('\n')}\n`);
  return path;
}

export function saveSchemaCopy(graph, schema, options = {}) {
  const directory = options.schemaDir || join(projectRoot(), '.pida-schemas');
  mkdirSync(directory, { recursive: true });
  const path = join(directory, `${graph}.json`);
  writeFileSync(path, `${JSON.stringify(schema, null, 2)}\n`);
  return path;
}

/** Scans and writes. The returned model is read back from the graph, so the report is about what persisted. */
export async function buildDataAgent(sources, options = {}) {
  const config = loadConfig(options);
  const client = options.client || clientFor({ ...config, ...options });
  const scan = await scanSources(sources, { ...config, ...options, client });
  const write = await writeModel(client, scan.model, {
    graph: scan.model.graph,
    storage: options.storage,
    dryRun: options.dryRun === true,
  });
  let model = scan.model;
  let stored = null;
  if (options.dryRun !== true) {
    try {
      stored = await loadModel(client, scan.model.graph, { client });
      model = stored;
    } catch (error) {
      write.warnings.push(`the graph could not be read back yet: ${error.message}`);
    }
    if (options.snapshot !== false) write.snapshot = saveSnapshot(model);
    // The service writes a schema file in its working directory and registers it in memory; a restart only
    // discovers graphs under phoenixgraph.storage.schema-dir. Keeping a copy there is what makes a built
    // model survive a restart. It is a no-op for a deployment that does not configure the directory.
    if (options.persistSchema !== false) {
      try {
        const schema = await client.schema(scan.model.graph);
        if (schema) write.schemaFile = saveSchemaCopy(scan.model.graph, schema, { schemaDir: options.schemaDir });
      } catch (error) {
        write.warnings.push(`the schema copy was not written: ${error.message}`);
      }
    }
  }
  return {
    ok: true,
    dryRun: options.dryRun === true,
    graph: scan.model.graph,
    dataGraph: scan.model.dataGraph,
    vertexType: scan.model.vertexType,
    write,
    scan,
    model,
    stored: Boolean(stored),
    review: reviewOf(model, scan.skipped),
  };
}

/** The model as read from a graph, with the Model node's fact binding. */
export async function loadModel(client, graph, options = {}) {
  const read = await client.readModel(graph);
  if (!read.nodes.length) throw new Error(`graph '${graph}' holds no Node vertices (build the model first)`);
  const model = modelFromGraph(graph, read.nodes, read.edges);
  model.sources = read.nodes.filter((node) => node.entityType === 'Source').map((node) => ({ id: node.id.replace(/^source\//, ''), name: node.properties?.name, title: node.properties?.name, kind: 'source' }));
  model.warnings = [];
  model.review = reviewOf(model);
  return model;
}

export async function editDataAgent(operations, options = {}) {
  const config = loadConfig(options);
  const client = options.client || clientFor({ ...config, ...options });
  const graph = options.graph || config.graph;
  const model = await loadModel(client, graph, { client });
  const result = await applyEdits(client, graph, model, operations, {
    dryRun: options.dryRun === true,
    allowDelete: options.allowDelete === true,
    now: options.now,
  });
  if (options.dryRun !== true) {
    result.snapshot = saveSnapshot(result.model);
    result.auditFile = appendAudit(graph, result.audit, { now: options.now });
  }
  result.graph = graph;
  result.model = result.model;
  return result;
}

export async function dashboardFor(options = {}) {
  const config = loadConfig(options);
  const client = options.client || clientFor({ ...config, ...options });
  const graph = options.graph || config.graph;
  const model = options.model || await loadModel(client, graph, { client });
  const dashboard = await buildDashboard(client, model, {
    ...options,
    dataGraph: options.dataGraph || config.dataGraph || model.dataGraph,
    vertexType: options.vertexType || config.vertexType || model.vertexType,
  });
  let files = null;
  if (options.write !== false) {
    const directory = options.outDir || join(projectRoot(), '.pi', 'data-agent', 'dashboards');
    mkdirSync(directory, { recursive: true });
    const stamp = new Date(options.now ?? Date.now()).toISOString().replace(/[:.]/g, '-');
    const base = join(directory, `${graph}-${stamp}`);
    writeFileSync(`${base}.html`, renderDashboardHtml(dashboard));
    writeFileSync(`${base}.json`, `${JSON.stringify(dashboard, null, 2)}\n`);
    files = { html: `${base}.html`, json: `${base}.json` };
  }
  return { ok: true, dashboard, files, markdown: renderDashboardMarkdown(dashboard) };
}

export async function attributeFor(options = {}) {
  const config = loadConfig(options);
  const client = options.client || clientFor({ ...config, ...options });
  const graph = options.graph || config.graph;
  const model = options.model || await loadModel(client, graph, { client });
  const result = await runAttribution(client, model, {
    ...options,
    dataGraph: options.dataGraph || config.dataGraph || model.dataGraph,
    vertexType: options.vertexType || config.vertexType || model.vertexType,
  });
  return { ok: true, result, markdown: renderAttribution(result) };
}

export async function statusFor(options = {}) {
  const config = loadConfig(options);
  const client = options.client || clientFor({ ...config, ...options });
  let graphs = [];
  let error = null;
  try { graphs = await client.graphs(); } catch (caught) { error = caught.message; }
  let model = null;
  if (!error) {
    try { model = await loadModel(client, config.graph, { client }); } catch { /* graph may not exist yet */ }
  }
  return {
    ok: !error,
    error,
    endpoint: config.endpoint,
    graph: config.graph,
    dataGraph: config.dataGraph,
    vertexType: config.vertexType,
    graphs: Array.isArray(graphs) ? graphs.map((graph) => graph.name) : [],
    model,
    stateFile: stateFile(),
  };
}

export function rememberChoice(changes, options = {}) {
  return remember(changes, options.home);
}

export function renderScanReport(scan) {
  const model = scan.model;
  const lines = [];
  lines.push(`# scan: ${model.graph}`);
  lines.push(`sources: ${scan.documents.length} read, ${scan.skipped.length} skipped`);
  for (const document of scan.documents) lines.push(`- ${document.kind}: ${document.title || document.name} (${document.id})`);
  for (const skip of scan.skipped) lines.push(`- skipped: ${skip.path}: ${skip.reason}`);
  lines.push(`model: ${model.stats.metrics} metrics, ${model.stats.dimensions} dimensions, ${model.stats.tables} tables, ${model.stats.columns} columns, ${model.stats.edges} edges`);
  lines.push(`facts: ${model.dataGraph || '(none)'}/${model.vertexType || '(none)'}`);
  if (model.stats.metrics) {
    lines.push('');
    lines.push('| metric | expression | unit | binding |');
    lines.push('|---|---|---|---|');
    for (const node of model.nodes.filter((candidate) => candidate.entityType === 'Metric').slice(0, 30)) {
      lines.push(`| ${node.id} | ${node.properties.expression || '(none)'} | ${node.properties.unit || ''} | ${node.properties.graphProperty || '(unbound)'} |`);
    }
  }
  if (model.stats.dimensions) {
    lines.push('');
    lines.push('| dimension | column | type | binding |');
    lines.push('|---|---|---|---|');
    for (const node of model.nodes.filter((candidate) => candidate.entityType === 'Dimension').slice(0, 30)) {
      const columnEdge = (model.edges || []).find((edge) => edge.head === node.id && edge.relation === 'dimensionColumn');
      lines.push(`| ${node.id} | ${columnEdge?.tail?.replace(/^column\//, '') || ''} | ${node.properties.dataType || ''} | ${node.properties.graphProperty || '(unbound)'} |`);
    }
  }
  if (model.warnings.length) {
    lines.push('');
    lines.push('## warnings');
    for (const warning of model.warnings.slice(0, 40)) lines.push(`- ${warning}`);
  }
  if (scan.review.length) {
    lines.push('');
    lines.push('## review');
    for (const item of scan.review.slice(0, 60)) lines.push(`- ${item.id}: ${item.kind} - ${item.why}`);
  }
  return lines.join('\n');
}

export function renderBuildReport(build) {
  const lines = [renderScanReport(build.scan)];
  lines.push('');
  lines.push('## written');
  lines.push(`graph: ${build.graph}${build.write.created ? ' (schema created)' : ''}`);
  lines.push(`vertices: ${build.write.vertices}, edges: ${build.write.edges}`);
  if (build.write.dropped?.length) lines.push(`dropped properties (not in the graph's schema): ${build.write.dropped.join(', ')}`);
  for (const warning of build.write.warnings || []) lines.push(`- ${warning}`);
  if (build.write.snapshot) lines.push(`snapshot: ${build.write.snapshot}`);
  if (build.write.schemaFile) lines.push(`schema copy: ${build.write.schemaFile} (phoenixgraph.storage.schema-dir discovers it on restart)`);
  if (build.dryRun) lines.push('dry run: nothing was written');
  return lines.join('\n');
}

export function renderModel(model) {
  const lines = [];
  lines.push(`# model: ${model.graph}`);
  lines.push(`facts: ${model.dataGraph || '(none)'}/${model.vertexType || '(none)'}`);
  lines.push(`nodes: ${model.stats?.nodes ?? model.nodes.length}, edges: ${model.stats?.edges ?? model.edges.length}`);
  const types = model.stats?.byType || model.nodes.reduce((acc, node) => { acc[node.entityType] = (acc[node.entityType] || 0) + 1; return acc; }, {});
  lines.push(`by type: ${Object.entries(types).map(([type, count]) => `${type}=${count}`).join(', ')}`);
  for (const entityType of ['Metric', 'Dimension', 'Table', 'Query', 'Term']) {
    const nodes = model.nodes.filter((node) => node.entityType === entityType);
    if (!nodes.length) continue;
    lines.push('');
    lines.push(`## ${entityType}`);
    for (const node of nodes.slice(0, 40)) {
      const properties = node.properties || {};
      const detail = [properties.expression, properties.unit, properties.graphProperty ? `binding=${properties.graphProperty}` : null, properties.dataType].filter(Boolean).join(' · ');
      lines.push(`- ${node.id}${detail ? ` — ${detail}` : ''}`);
    }
  }
  if (model.review?.length) {
    lines.push('');
    lines.push('## review');
    for (const item of model.review.slice(0, 50)) lines.push(`- ${item.id}: ${item.kind} - ${item.why}`);
  }
  return lines.join('\n');
}

export { quote, unique, readJson };
