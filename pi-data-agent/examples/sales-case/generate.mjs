#!/usr/bin/env node
/**
 * Generate and import the case's own data.
 *
 * The case must not lean on a dataset somebody else loaded: it defines the facts, generates them deterministically
 * (same seed -> same numbers, so the document can quote them), creates the graph with its own schema, and imports
 * the rows over the same HTTP write API an operator would use. `--graph sales-facts` is the default.
 *
 *   node examples/sales-case/generate.mjs                    # create + import
 *   node examples/sales-case/generate.mjs --dry-run          # print the rows it would write
 *   node examples/sales-case/generate.mjs --graph other --years 2024,2025,2026
 */
import process from 'node:process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { clientFor } from '../../src/service.mjs';
import { loadConfig, projectRoot } from '../../src/config.mjs';

const CHANNELS = ['APP', 'WEB', 'MINI', 'STORE'];
const REGIONS = ['华东', '华南', '华北'];
const CAMPAIGNS = ['新客季', '会员日'];

/** mulberry32: small, deterministic, and enough for synthetic volume. */
function random(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

/** The vertex schema the case owns. One row = one month x channel x region. */
export function salesDaySchema(graph) {
  const property = (name, type, description) => ({ name, type, description });
  return {
    name: graph,
    description: 'pi-data-agent sales case: monthly fact rows (order counts, amounts and cost) by channel and region',
    idType: 'string',
    storage: { type: 'Aerospike', path: graph, regionCount: 64, maxVersions: 1, cacheEdges: true },
    vertices: [{
      name: 'SalesDay',
      description: 'One month of sales for a channel in a region',
      display: 'name',
      properties: [
        property('name', 'string', 'A readable label'),
        property('year', 'long', '自然年'),
        property('month', 'long', '自然月'),
        property('channel', 'string', '渠道'),
        property('region', 'string', '地区'),
        property('campaign', 'string', '活动'),
        property('orders', 'long', '订单数'),
        property('customers', 'long', '客户数'),
        property('revenue', 'double', '成交金额（毛）'),
        property('net_revenue', 'double', '成交金额（扣除退款）'),
        property('cost', 'double', '成本'),
      ],
    }],
    edges: [],
  };
}

/**
 * The rows: a growth trend per channel, a seasonal bump, and a refund share that rises in 2026 (which is what
 * makes the 2026-10-15 definition change matter).
 */
export function generateRows({ years = [2024, 2025, 2026], seed = 20260101 } = {}) {
  const next = random(seed);
  const rows = [];
  for (const year of years) {
    for (let month = 1; month <= 12; month += 1) {
      for (const [channelIndex, channel] of CHANNELS.entries()) {
        for (const [regionIndex, region] of REGIONS.entries()) {
          const base = 260 + channelIndex * 95 + regionIndex * 40;
          const growth = 1 + (year - 2024) * 0.18;
          const season = 1 + Math.sin((month / 12) * Math.PI * 2) * 0.12 + (month === 11 ? 0.35 : 0);
          const noise = 0.9 + next() * 0.2;
          const orders = Math.round(base * growth * season * noise);
          const customers = Math.max(1, Math.round(orders * (0.72 + next() * 0.1)));
          const aov = 320 + next() * 60;                       // average order value, 320..380
          const revenue = Number((orders * aov).toFixed(2));
          const refundShare = year >= 2026 ? 0.035 + next() * 0.015 : 0.018 + next() * 0.006;
          const netRevenue = Number((revenue * (1 - refundShare)).toFixed(2));
          const cost = Number((revenue * (0.24 + next() * 0.05)).toFixed(2));
          rows.push({
            id: `sd-${year}-${String(month).padStart(2, '0')}-${channel.toLowerCase()}-${regionIndex + 1}`,
            year, month, channel, region,
            campaign: channel === 'MINI' || channel === 'STORE' ? CAMPAIGNS[1] : CAMPAIGNS[0],
            orders, customers, revenue, net_revenue: netRevenue, cost,
            name: `${year}-${String(month).padStart(2, '0')} ${channel}/${region}`,
          });
        }
      }
    }
  }
  return rows;
}

/** The property indexes the *served* schema declares: never assume the order, read it. */
function requestsFor(rows, schema) {
  const vertex = (schema?.vertexSchemas || []).find((candidate) => candidate.name === 'SalesDay');
  const index = new Map((vertex?.properties || []).map((property) => [property.name, property.id]));
  if (!index.size) throw new Error('sales-facts has no SalesDay schema');
  return rows.map((row) => ({
    id: row.id,
    vertexSchemaName: 'SalesDay',
    properties: Object.entries(row)
      .filter(([name, value]) => index.has(name) && value != null)
      .map(([name, value]) => ({ index: index.get(name), value })),
  }));
}

export async function importFacts(options = {}) {
  const graph = options.graph || 'sales-facts';
  const years = options.years || [2024, 2025, 2026];
  const rows = generateRows({ years, seed: options.seed });
  if (options.dryRun) return { graph, rows, written: 0, created: false };
  const config = loadConfig(options);
  const client = options.client || clientFor({ ...config, ...options });
  const ensured = await client.ensureGraph(graph, salesDaySchema(graph));
  // A graph created over HTTP lives in the running instance only. Keeping its schema in the configured
  // schema-dir (.pida-schemas) is what makes a restart serve it again - the same trick pi-data-agent uses for
  // the model graph. Without this the fact graph disappears with the process and every dashboard refuses.
  let schemaFile = null;
  if (options.persistSchema !== false) {
    const schemaDir = options.schemaDir || join(projectRoot(), '.pida-schemas');
    mkdirSync(schemaDir, { recursive: true });
    schemaFile = join(schemaDir, `${graph}.json`);
    writeFileSync(schemaFile, `${JSON.stringify(ensured.schema, null, 2)}
`);
  }
  const requests = requestsFor(rows, ensured.schema);
  let written = 0;
  for (let index = 0; index < requests.length; index += 200) {
    const chunk = requests.slice(index, index + 200);
    const results = await client.batch(graph, { vertices: chunk, edges: [] });
    written += (results || []).filter((result) => result && !result.error).length || chunk.length;
  }
  return { graph, rows, written, created: Boolean(ensured.created), schema: ensured.schema?.name || graph, schemaFile };
}

if (process.argv[1] && process.argv[1].endsWith('generate.mjs')) {
  const args = process.argv.slice(2);
  const flag = (name, fallback) => {
    const index = args.indexOf(`--${name}`);
    return index >= 0 ? args[index + 1] : fallback;
  };
  const result = await importFacts({
    graph: flag('graph', 'sales-facts'),
    years: String(flag('years', '2024,2025,2026')).split(',').map((year) => Number(year.trim())).filter(Boolean),
    dryRun: args.includes('--dry-run'),
  });
  console.log(`graph ${result.graph}: ${result.rows.length} row(s)${result.created ? ' (schema created)' : ' (schema existed)'}, ${result.written} written`);
  console.log(JSON.stringify(result.rows.slice(0, 3), null, 2));
}
