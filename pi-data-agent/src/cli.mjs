#!/usr/bin/env node
/**
 * The package without pi: the same use-cases from a terminal.
 *
 *   node src/cli.mjs scan  examples/retail --data-graph demo-data --vertex-type MarketingDay
 *   node src/cli.mjs build examples/retail --graph pi-data-agent-demo \
 *        --data-graph demo-data --vertex-type MarketingDay
 *   node src/cli.mjs model --graph pi-data-agent-demo
 *   node src/cli.mjs edit --graph pi-data-agent-demo --file edits.json
 *   node src/cli.mjs dashboard --graph pi-data-agent-demo --year 2025
 *   node src/cli.mjs attribute --graph pi-data-agent-demo --metric revenue --by channel --from 2024 --to 2025
 */
import { readFileSync } from 'node:fs';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import {
  scanSources, buildDataAgent, loadModel, editDataAgent, dashboardFor, attributeFor, statusFor,
  clientFor, renderScanReport, renderBuildReport, renderModel, rememberChoice,
} from './service.mjs';
import { loadConfig } from './config.mjs';
import { renderSimilar, similarWithGraph } from './similar.mjs';
import { renderSchedule, runSchedule } from './schedule.mjs';
import { askFor } from './service.mjs';
import {
  readRiskCases, loadRiskManifest, riskDashboard, riskAttribution, renderRiskAttribution,
  renderRiskDashboardMarkdown, saveRiskDashboard,
} from './risk.mjs';

const PACKAGE_ROOT = fileURLToPath(new URL('../', import.meta.url));
const RISK_CASES = join(PACKAGE_ROOT, 'examples', 'risk-control', 'risk-cases.csv');
const RISK_MANIFEST = join(PACKAGE_ROOT, 'examples', 'risk-control', 'risk-model.json');

const USAGE = `pi-data-agent

Usage:
  node src/cli.mjs <command> [sources...] [options]

Commands:
  scan <sources...>        read documents/DB dumps and print the extracted model (writes nothing)
  build <sources...>       scan then write the metadata + semantic layer into PhoenixGraph
  model                    print the model stored in the graph
  edit <operations.json>   apply validated reviewer edits (or read JSON from stdin)
  dashboard                build a dashboard from the model and the fact graph
  attribute                attribute a change to one dimension
  risk-dashboard           risk KPI dashboard + ring evidence from the payment graph
  risk-attribute           risk change attribution + ring evidence + refutable hypotheses
  ask <question>           a question in words -> the dashboard or the attribution it asks for
  similar <text>           vector similarity over the model, expanded through its relations
  schedule                 produce the dashboard + attribution for a list of periods, on a timer
  status                   endpoint, graphs, model counts, state file
  graphs                   list the served graphs

Options:
  --graph <name>           semantic/metadata graph (default: data-agent)
  --data-graph <name>      fact graph the model executes on
  --vertex-type <name>     fact vertex type (default: detected when the fact graph has one)
  --endpoint <url>         PhoenixGraph service (default: PHOENIXGRAPH_API or http://localhost:8081)
  --api-key <key>          bearer token (default: PHOENIXGRAPH_API_KEY)
  --bind <id=property>     explicit binding, repeatable (e.g. --bind metric/revenue=revenue)
  --override <file>        reviewer operations applied to the preview (JSON array)
  --ocr                    OCR a scanned PDF with pdftoppm + tesseract
  --json                   print the structured result instead of prose
  --dry-run                do everything except write (build/edit)
  --year <year>            period filter (dashboard/attribute)
  --as-of <instant>        read the model as it stood at this instant (ISO date/time)
  --periods <a,b,c>        periods for schedule (e.g. 2024,2025,2026)
  --every <ms>             wait between schedule cycles
  --k <n>                  how many similar candidates (default 5)
  --hops <n>               how far similar expands through relations (default 1)
  --top <n>                top N rows per breakdown (default 5)
  --dimensions <a,b>       dimensions to break a dashboard down by
  --metric <id>            metric (attribute)
  --by <id>                dimension (attribute)
  --from <year> --to <year>  periods (attribute)
  --out <dir>              dashboard output directory
  --cases <file>           risk case ledger (CSV/JSON; default examples/risk-control/risk-cases.csv)
  --manifest <file>        risk model manifest (default examples/risk-control/risk-model.json)
  --risk-graph <name>      entity graph for risk evidence (default from the manifest)
  --period <period>        risk dashboard period (default the latest in the ledger)
  --from <period> --to <period>  risk attribution periods
  --enrich-rings <a,b>     extra rings to resolve in the graph
  --schema-dir <dir>       where to keep the schema copy a restart discovers (default .pida-schemas)
  --no-write               do not write dashboard files
  --allow-delete           allow delete operations without confirm:true
  --help                   this text
`;

function parse(argv) {
  const options = { sources: [], binds: {}, dimensions: [] };
  const positional = [];
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const value = () => {
      const next = argv[index + 1];
      if (next == null || String(next).startsWith('--')) throw new Error(`${arg} needs a value`);
      index += 1;
      return next;
    };
    if (arg === '--graph') options.graph = value();
    else if (arg === '--data-graph') options.dataGraph = value();
    else if (arg === '--vertex-type') options.vertexType = value();
    else if (arg === '--endpoint') options.endpoint = value().replace(/\/+$/, '');
    else if (arg === '--api-key') options.apiKey = value();
    else if (arg === '--bind') {
      const [id, ...rest] = value().split('=');
      options.binds[id.trim()] = rest.join('=').trim();
    } else if (arg === '--override') options.overrides = JSON.parse(readFileSync(value(), 'utf8'));
    else if (arg === '--ocr') options.ocr = true;
    else if (arg === '--json') options.json = true;
    else if (arg === '--dry-run') options.dryRun = true;
    else if (arg === '--year') options.year = value();
    else if (arg === '--as-of') options.asOf = value();
    else if (arg === '--periods') options.periods = value().split(',').map((part) => part.trim()).filter(Boolean);
    else if (arg === '--every') options.everyMs = Number(value());
    else if (arg === '--k') options.k = Number(value());
    else if (arg === '--hops') options.hops = Number(value());
    else if (arg === '--top') options.topN = Number(value());
    else if (arg === '--dimensions') options.dimensions = value().split(',').map((part) => part.trim()).filter(Boolean);
    else if (arg === '--metric') options.metric = value();
    else if (arg === '--by') options.by = value();
    else if (arg === '--from') options.from = value();
    else if (arg === '--to') options.to = value();
    else if (arg === '--out') options.outDir = value();
    else if (arg === '--cases') options.cases = value();
    else if (arg === '--manifest') options.manifest = value();
    else if (arg === '--risk-graph') options.riskGraph = value();
    else if (arg === '--period') options.period = value();
    else if (arg === '--enrich-rings') options.enrichRings = value().split(',').map((part) => part.trim()).filter(Boolean);
    else if (arg === '--schema-dir') options.schemaDir = value();
    else if (arg === '--no-write') options.write = false;
    else if (arg === '--allow-delete') options.allowDelete = true;
    else if (arg === '--help' || arg === '-h') options.help = true;
    else if (arg.startsWith('--')) throw new Error(`unknown option '${arg}'`);
    else positional.push(arg);
  }
  options.command = positional.shift() || null;
  options.sources = positional;
  return options;
}

function print(value, json) {
  if (json) console.log(JSON.stringify(value, null, 2));
  else console.log(typeof value === 'string' ? value : JSON.stringify(value, null, 2));
}

async function main() {
  const options = parse(process.argv.slice(2));
  if (options.help || !options.command) { console.log(USAGE); return 0; }
  const config = loadConfig(options);
  const client = clientFor({ ...config, ...options });
  switch (options.command) {
    case 'graphs': {
      const graphs = await client.graphs();
      print(options.json ? graphs : graphs.map((graph) => `${graph.name} [${graph.storage}]`).join('\n'), false);
      return 0;
    }
    case 'status': {
      const status = await statusFor({ ...config, ...options, client });
      if (options.json) print(status, true);
      else {
        console.log(`endpoint: ${status.endpoint}`);
        console.log(`graph: ${status.graph}${status.model ? ` (${status.model.stats?.metrics ?? 0} metrics, ${status.model.stats?.dimensions ?? 0} dimensions)` : ' (not built)'}`);
        console.log(`facts: ${status.dataGraph || status.model?.dataGraph || '(none)'}/${status.vertexType || status.model?.vertexType || '(none)'}`);
        console.log(`graphs: ${status.graphs.join(', ')}`);
        console.log(`state: ${status.stateFile}`);
        if (status.error) console.log(`error: ${status.error}`);
      }
      return 0;
    }
    case 'scan': {
      const scan = await scanSources(options.sources, { ...config, ...options, binds: options.binds, bindings: options.binds, client });
      if (options.json) print(scan, true);
      else console.log(renderScanReport(scan));
      return 0;
    }
    case 'build': {
      const build = await buildDataAgent(options.sources, { ...config, ...options, binds: options.binds, bindings: options.binds, client });
      if (options.json) print(build, true);
      else console.log(renderBuildReport(build));
      rememberChoice({ graph: build.graph, dataGraph: build.dataGraph, vertexType: build.vertexType, endpoint: config.endpoint });
      return build.dryRun ? 0 : 0;
    }
    case 'model': {
      const model = await loadModel(client, options.graph || config.graph, { client, asOf: options.asOf });
      if (options.json) print(model, true);
      else console.log(renderModel(model));
      return 0;
    }
    case 'edit': {
      const inline = options.sources.length ? null : readFileSync(0, 'utf8');
      const operations = inline ? JSON.parse(inline) : JSON.parse(readFileSync(options.sources[0], 'utf8'));
      const result = await editDataAgent(operations, { ...config, ...options, client });
      if (options.json) print(result, true);
      else {
        console.log(`edits: ${result.summary.operations} operation(s), ${result.summary.changedNodes} node(s), ${result.summary.changedEdges} edge(s)`);
        console.log(`written: ${JSON.stringify(result.written)}${result.dryRun ? ' (dry run)' : ''}`);
        if (result.snapshot) console.log(`snapshot: ${result.snapshot}`);
        if (result.auditFile) console.log(`audit: ${result.auditFile}`);
        for (const warning of result.warnings) console.log(`warning: ${warning}`);
      }
      return 0;
    }
    case 'dashboard': {
      const result = await dashboardFor({ ...config, ...options, client });
      if (options.json) print({ dashboard: result.dashboard, files: result.files }, true);
      else {
        console.log(result.markdown);
        if (result.files) console.log(`\nhtml: ${result.files.html}\njson: ${result.files.json}`);
      }
      return 0;
    }
    case 'attribute': {
      if (!options.metric || !options.by || options.from == null || options.to == null) {
        throw new Error('attribute needs --metric, --by, --from and --to');
      }
      const result = await attributeFor({ ...config, ...options, client });
      if (options.json) print(result, true);
      else console.log(result.markdown);
      return result.result.executed ? 0 : 1;
    }
    case 'ask': {
      const question = options.sources.join(' ');
      if (!question) throw new Error('ask needs a question (e.g. ask "2025年营收按渠道拆分，前3")');
      const result = await askFor(question, { ...config, ...options, client });
      if (options.json) print(result, true);
      else {
        console.log(result.ok ? `understood as ${result.kind}: ${JSON.stringify(result.request)}` : `not understood: ${result.reason}`);
        if (result.markdown) console.log(`
${result.markdown}`);
      }
      return result.ok ? 0 : 1;
    }
    case 'similar': {
      const text = options.sources.join(' ');
      if (!text) throw new Error('similar needs a phrase (e.g. similar 收入 按渠道 怎么算)');
      const model = await loadModel(client, options.graph || config.graph, { client, asOf: options.asOf });
      const bundle = similarWithGraph(model, text, { k: options.k, hops: options.hops });
      print(options.json ? bundle : renderSimilar(bundle), options.json);
      return bundle.matches.length ? 0 : 1;
    }
    case 'schedule': {
      if (!options.periods || !options.periods.length) throw new Error('schedule needs --periods 2024,2025[,2026]');
      const schedule = await runSchedule({ ...config, ...options, client });
      print(options.json ? schedule : renderSchedule(schedule), options.json);
      return schedule.refused > 0 ? 0 : 0;
    }
    case 'risk-dashboard': {
      const { rows } = readRiskCases(options.cases || RISK_CASES);
      const manifest = loadRiskManifest(options.manifest || RISK_MANIFEST);
      const dashboard = await riskDashboard(client, rows, manifest, {
        period: options.period, riskGraph: options.riskGraph, topN: options.topN,
      });
      const files = options.write === false ? null : saveRiskDashboard(dashboard, { outDir: options.outDir });
      if (options.json) print({ dashboard, files }, true);
      else {
        console.log(renderRiskDashboardMarkdown(dashboard));
        if (files) console.log(`
html: ${files.html}
json: ${files.json}`);
      }
      return 0;
    }
    case 'risk-attribute': {
      const { rows } = readRiskCases(options.cases || RISK_CASES);
      const manifest = loadRiskManifest(options.manifest || RISK_MANIFEST);
      const bundle = await riskAttribution(client, rows, manifest, {
        metric: options.metric, by: options.by, from: options.from, to: options.to,
        riskGraph: options.riskGraph, enrichRings: options.enrichRings, casesPath: options.cases || RISK_CASES,
      });
      if (options.json) print(bundle, true);
      else console.log(renderRiskAttribution(bundle));
      return 0;
    }
    default:
      throw new Error(`unknown command '${options.command}'`);
  }
}

main().then((code) => process.exit(code)).catch((error) => {
  console.error(error.message || error);
  process.exit(1);
});
