#!/usr/bin/env node
// Generate a scale dataset for the payment schema as two streaming CSV files.
//
//   node tools/generate-scale-csv.mjs --accounts 100000 --payments 500000
//
// The files are written to .datasets/scale (gitignored): vertices.csv and edges.csv. They use the
// composite ids and property indices CsvBatchLoader expects (`graph-batch-load`). Start small; 10^7
// accounts is a multi-gigabyte file and a long load.
import { createWriteStream, mkdirSync, statSync } from 'node:fs';
import { once } from 'node:events';
import { join, resolve } from 'node:path';

const args = process.argv.slice(2);
const options = { accounts: 100_000, payments: null, out: '.datasets/scale', graph: 'payment-graph', seed: 42 };
const value = (index) => {
  const next = args[index + 1];
  if (!next || next.startsWith('--')) throw new Error(`${args[index]} needs a value`);
  return next;
};
for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '--accounts') options.accounts = Number(value(i++));
  else if (args[i] === '--payments') options.payments = Number(value(i++));
  else if (args[i] === '--out') options.out = value(i++);
  else if (args[i] === '--graph') options.graph = value(i++);
  else if (args[i] === '--seed') options.seed = Number(value(i++));
  else if (args[i] === '--help' || args[i] === '-h') {
    console.log(`Usage: node tools/generate-scale-csv.mjs [--accounts 100000] [--payments N]
       [--out .datasets/scale] [--graph payment-graph] [--seed 42]`);
    process.exit(0);
  } else throw new Error(`unknown argument '${args[i]}'`);
}
if (!Number.isInteger(options.accounts) || options.accounts < 1) {
  throw new Error('--accounts must be a positive integer');
}
const payments = options.payments == null ? options.accounts * 5 : options.payments;
if (!Number.isInteger(payments) || payments < 0) {
  throw new Error('--payments must be a non-negative integer');
}
const out = resolve(options.out);
mkdirSync(out, { recursive: true });
const verticesFile = join(out, 'vertices.csv');
const edgesFile = join(out, 'edges.csv');
const now = Date.now();
let random = options.seed >>> 0;
const nextRandom = () => {
  random = (Math.imul(random, 1664525) + 1013904223) >>> 0;
  return random;
};
const write = async (stream, line) => {
  if (!stream.write(line)) {
    await once(stream, 'drain');
  }
};

const started = Date.now();
const vertices = createWriteStream(verticesFile, { encoding: 'utf8' });
await write(vertices, 'VertexID,1,2,6,7,lastUpdatedTime\n');
for (let id = 1; id <= options.accounts; id += 1) {
  await write(vertices, `${options.graph}::Vertex::Account:${id},ACTIVE,SAVINGS,First${id},Last${id},${now}\n`);
  if (id % 1_000_000 === 0) {
    console.log(`  accounts ${id.toLocaleString()}`);
  }
}
vertices.end();
await once(vertices, 'close');

const edges = createWriteStream(edgesFile, { encoding: 'utf8' });
await write(edges, 'EdgeID,source,destination,3,4,5,6,lastUpdatedTime\n');
for (let id = 1; id <= payments; id += 1) {
  const source = 1 + (nextRandom() % options.accounts);
  const destination = 1 + (nextRandom() % options.accounts);
  const amount = (nextRandom() % 100_000) / 100;
  await write(edges, `${options.graph}::Edge::Payment:${id},${source},${destination},`
    + `SUCCESS,TRANSFER,${amount.toFixed(2)},CNY,${now}\n`);
  if (id % 1_000_000 === 0) {
    console.log(`  payments ${id.toLocaleString()}`);
  }
}
edges.end();
await once(edges, 'close');

const seconds = ((Date.now() - started) / 1000).toFixed(1);
console.log(`\nWrote ${options.accounts.toLocaleString()} accounts and ${payments.toLocaleString()} payments in ${seconds}s`);
console.log(`  vertices: ${verticesFile} (${(statSync(verticesFile).size / 1e6).toFixed(1)} MB)`);
console.log(`  edges:    ${edgesFile} (${(statSync(edgesFile).size / 1e6).toFixed(1)} MB)`);
console.log('Load with tools/bench-load.sh (see docs/BENCHMARK.md).');
