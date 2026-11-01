#!/usr/bin/env node
/**
 * Live verification of pi-data-agent against a running PhoenixGraph instance.
 *
 *   PHOENIXGRAPH_LIVE=1 node verification/verify-package.mjs
 *   PHOENIXGRAPH_LIVE=1 PHOENIXGRAPH_API=http://localhost:8081 node verification/verify-package.mjs
 *
 * It builds the retail example into a graph of its own, reads it back through the Model catalog, computes a
 * dashboard and an attribution, and applies one audited edit. It leaves the model graph in place: PhoenixGraph
 * has no delete-graph endpoint, and a verification that quietly removes what it built is not one you can
 * inspect afterwards. The graph name is printed at the end.
 */
import process from 'node:process';
import { clientFor, buildDataAgent, loadModel, dashboardFor, attributeFor, editDataAgent } from '../src/service.mjs';
import { renderDashboardHtml } from '../src/dashboard.mjs';

if (process.env.PHOENIXGRAPH_LIVE !== '1') {
  console.log('skipped: set PHOENIXGRAPH_LIVE=1 (and start PhoenixGraph) to run the live verification');
  process.exit(0);
}

const API = process.env.PHOENIXGRAPH_API || 'http://localhost:8081';
const BASE_GRAPH = process.env.PI_DATA_AGENT_VERIFY_GRAPH || 'pi-data-agent-verify';
const DATA_GRAPH = process.env.PI_DATA_AGENT_VERIFY_DATA_GRAPH || 'demo-data';
const VERTEX_TYPE = process.env.PI_DATA_AGENT_VERIFY_VERTEX_TYPE || 'MarketingDay';
const SOURCES = ['examples/retail'];
let failures = 0;
let checks = 0;

const check = (name, ok, detail) => {
  checks += 1;
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ` - ${detail}` : ''}`);
};
const close = (a, b, tolerance = 0.01) => Math.abs(Number(a) - Number(b)) <= tolerance;

try {
  const client = clientFor({ endpoint: API });
  const graphs = await client.graphs();
  check(`PhoenixGraph answers at ${API}`, Array.isArray(graphs) && graphs.length > 0, `${graphs.length} graphs`);

  // a graph built before the catalog/schema properties existed cannot store the model faithfully; pick a fresh name
  let graph = BASE_GRAPH;
  const existing = await client.schema(graph);
  if (existing) {
    const nodeProps = new Set((existing.vertexSchemas || []).find((vertex) => vertex.name === 'Node')?.properties?.map((property) => property.name) || []);
    if (!nodeProps.has('nodeIds') || !nodeProps.has('evidence')) graph = `${BASE_GRAPH}-${Date.now()}`;
  }
  console.log(`\n--- build (${graph})`);
  const build = await buildDataAgent(SOURCES, {
    endpoint: API, graph, dataGraph: DATA_GRAPH, vertexType: VERTEX_TYPE, snapshot: false,
  });
  check('the graph was created or updated', build.write.vertices > 0 && build.write.edges > 0, `${build.write.vertices} vertices, ${build.write.edges} edges`);
  check('the model was read back from the graph', build.stored === true, build.stored ? 'catalog read' : 'read-back failed');
  check('every metric has an evidence field', build.model.nodes.filter((node) => node.entityType === 'Metric').every((node) => node.properties.evidence), '');
  check('the ratio metric is recorded without a graphProperty', build.model.nodes.find((node) => node.id === 'metric/点击率')?.properties.graphProperty == null, '');
  check('the review names the unbound ratio metric', build.review.some((item) => item.id === 'metric/点击率' && item.kind === 'metric-unbound'), '');

  console.log('\n--- model');
  const model = await loadModel(client, graph);
  check('the Model node names the fact graph and vertex type', model.dataGraph === DATA_GRAPH && model.vertexType === VERTEX_TYPE, `${model.dataGraph}/${model.vertexType}`);
  check('metrics were extracted', model.stats.metrics >= 7, `${model.stats.metrics} metrics`);
  check('dimensions were extracted', model.stats.dimensions >= 4, `${model.stats.dimensions} dimensions`);
  check('the date dimension binds year and month', model.nodes.find((node) => node.id === 'dim/日期')?.properties.graphProperty === 'year=year,month=month', '');
  check('the catalog lists the metrics', model.nodes.find((node) => node.entityType === 'Model')?.properties.nodeIds.includes('metric/营收'), '');

  console.log('\n--- dashboard');
  const dashboard = await dashboardFor({ client, graph, dataGraph: DATA_GRAPH, vertexType: VERTEX_TYPE, year: 2025, topN: 3, write: false });
  check('the dashboard has executed tiles', dashboard.dashboard.executed > 0, `${dashboard.dashboard.executed} executed, ${dashboard.dashboard.refused} refused`);
  const revenueTotal = dashboard.dashboard.tiles.find((tile) => tile.kind === 'total' && tile.metric === 'metric/营收');
  check('the revenue total is a number computed on the fact graph', revenueTotal?.executed === true && revenueTotal.total > 0, String(revenueTotal?.total));
  const trend = dashboard.dashboard.tiles.find((tile) => tile.kind === 'trend' && tile.metric === 'metric/营收');
  check('the trend tile carries its traversal', trend?.executed === true && /g\.V\('MarketingDay'\)/.test(trend.gremlin), trend?.gremlin);
  const ratio = dashboard.dashboard.tiles.find((tile) => tile.metric === 'metric/点击率' && tile.executed === false);
  check('the unbound ratio is refused with its SQL, not drawn as zero', Boolean(ratio) && /SUM\([^)]*clicks\)/.test(ratio.sql || ''), ratio?.reason);
  const dashboardHtml = renderDashboardHtml(dashboard.dashboard);
  check('the HTML dashboard renders tiles and provenance', /<!doctype html>/.test(dashboardHtml) && /<\/html>/.test(dashboardHtml) && /provenance/.test(dashboardHtml), '');

  console.log('\n--- attribution');
  const attributed = await attributeFor({ client, graph, dataGraph: DATA_GRAPH, vertexType: VERTEX_TYPE, metric: '营收', by: '渠道', from: 2024, to: 2025 });
  check('the attribution executed', attributed.result.executed === true, attributed.result.reason || '');
  const a = attributed.result.attribution;
  if (a) {
    check('the contributions add up to the change', a.decompositionAddsUp === true, `sum=${a.contributionsSum}, delta=${a.delta}`);
    check('the total is 2024 -> 2025', a.from == 2024 && a.to == 2025 && a.totalBefore > 0 && a.totalAfter > 0, `${a.totalBefore} -> ${a.totalAfter}`);
    check('the attribution says it is not causal', a.limits.some((limit) => /Descriptive, not causal/.test(limit)), '');
    check('the markup reports the top mover', /top mover/.test(attributed.markdown), '');
  }

  console.log('\n--- edit');
  const edit = await editDataAgent([
    { op: 'update-node', id: 'metric/营收', properties: { description: 'live verification: reviewed definition' } },
    { op: 'add-alias', id: 'metric/营收', alias: '销售收入' },
  ], { endpoint: API, graph, dryRun: false });
  check('the edit wrote a node', edit.written.vertices >= 1, JSON.stringify(edit.written));
  check('the edit produced an audit record', edit.audit.length >= 2, `${edit.audit.length} records`);
  const after = await loadModel(client, graph);
  const revenue = after.nodes.find((node) => node.id === 'metric/营收');
  check('the description persisted', revenue?.properties.description === 'live verification: reviewed definition', revenue?.properties.description);
  check('the alias persisted', revenue?.properties.alias.includes('销售收入'), JSON.stringify(revenue?.properties.alias));

  console.log(`\n${checks - failures}/${checks} checks passed; model graph: ${graph}`);
  process.exit(failures ? 1 : 0);
} catch (error) {
  console.error(`verification failed: ${error.stack || error.message}`);
  process.exit(1);
}
