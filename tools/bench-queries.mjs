#!/usr/bin/env node
// A repeatable query-latency benchmark against a running service.
//
//   node tools/bench-queries.mjs --graph payment-graph --rounds 40
//   node tools/bench-queries.mjs --suite tpc-h --graph tpc-h-data --json
//
// It measures the HTTP round trip (the number a caller sees), reports p50/p95/p99 and writes JSON with
// --json. It is not a check: it does not fail on a threshold, because a threshold belongs in docs/SLO.md.
const args = process.argv.slice(2);
const options = {
  api: process.env.PHOENIXGRAPH_API || 'http://localhost:8081',
  suite: 'payment',
  graph: 'payment-graph',
  rounds: 40,
  warmup: 5,
  json: false,
};
const value = (index) => {
  const next = args[index + 1];
  if (!next || next.startsWith('--')) throw new Error(`${args[index]} needs a value`);
  return next;
};
for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '--api') options.api = value(i++).replace(/\/+$/, '');
  else if (args[i] === '--suite') options.suite = value(i++);
  else if (args[i] === '--graph') options.graph = value(i++);
  else if (args[i] === '--rounds') options.rounds = Number(value(i++));
  else if (args[i] === '--warmup') options.warmup = Number(value(i++));
  else if (args[i] === '--json') options.json = true;
  else if (args[i] === '--help' || args[i] === '-h') {
    console.log(`Usage: node tools/bench-queries.mjs [--suite payment|tpc-h] [--graph name]
       [--rounds 40] [--warmup 5] [--api URL] [--json]`);
    process.exit(0);
  } else {
    throw new Error(`unknown argument '${args[i]}'`);
  }
}

const SUITES = {
  payment: [
    ['point', "g.V('1000','Account')"],
    ['one-hop', "g.V('1000','Account').bothE('Payment').limit(50)"],
    ['two-hop', "g.V('1000','Account').bothE('Payment').otherV().bothE('Payment').otherV().dedup().limit(200)"],
  ],
  'tpc-h': [
    ['year-revenue', "g.V('Sale').group().by('orderYear').by(values('revenue').sum())"],
    ['year-gross', "g.V('Sale').group().by('orderYear').by(values('gross').sum())"],
    ['year-discount', "g.V('Sale').group().by('orderYear').by(values('discountAmount').sum())"],
  ],
};
const queries = SUITES[options.suite];
if (!queries) {
  console.error(`unknown suite '${options.suite}'; use payment or tpc-h`);
  process.exit(2);
}

const ask = async (query) => {
  const response = await fetch(`${options.api}/api/v1/query`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      graphName: options.graph,
      query,
      includeResult: false,
      returnSubgraph: false,
    }),
  });
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${(await response.text()).slice(0, 200)}`);
  }
  await response.text();
};
const percentile = (sorted, p) => sorted[Math.min(sorted.length - 1,
  Math.max(0, Math.ceil((p / 100) * sorted.length) - 1))];
const round = (value) => Math.round(value * 1000) / 1000;

const results = [];
for (const [name, query] of queries) {
  for (let i = 0; i < Math.max(0, options.warmup); i += 1) {
    await ask(query);
  }
  const samples = [];
  for (let i = 0; i < Math.max(1, options.rounds); i += 1) {
    const started = performance.now();
    await ask(query);
    samples.push(performance.now() - started);
  }
  samples.sort((a, b) => a - b);
  const result = {
    graph: options.graph,
    query: name,
    rounds: samples.length,
    p50Ms: round(percentile(samples, 50)),
    p95Ms: round(percentile(samples, 95)),
    p99Ms: round(percentile(samples, 99)),
    minMs: round(samples[0]),
    maxMs: round(samples[samples.length - 1]),
    meanMs: round(samples.reduce((sum, value) => sum + value, 0) / samples.length),
  };
  results.push(result);
  if (!options.json) {
    console.log(`${result.query.padEnd(14)} p50 ${String(result.p50Ms).padStart(8)} ms  `
      + `p95 ${String(result.p95Ms).padStart(8)} ms  p99 ${String(result.p99Ms).padStart(8)} ms  `
      + `mean ${String(result.meanMs).padStart(8)} ms  (${result.rounds} rounds)`);
  }
}
if (options.json) {
  console.log(JSON.stringify({ api: options.api, suite: options.suite, results }, null, 2));
}
