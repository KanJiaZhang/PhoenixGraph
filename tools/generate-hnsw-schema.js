#!/usr/bin/env node
/*
 * Generates the HNSW demo graph schema (graph-http/src/main/resources/hnsw-sift.json).
 *
 * The schema describes one vector vertex type plus, optionally, one bidirectional
 * edge class per HNSW layer: a dataset of `size` vectors gets the layers
 * 0..ceil(log2(size)), so 4096 vectors become the 13 edge classes vector_0 ..
 * vector_12.
 *
 * The vector property is flagged `"isVector": true` with its index tunables; the
 * layer edge classes are listed explicitly here (stable ids for an existing
 * dataset) but the graph creates them on demand for any vector property that does
 * not declare them, so `--layers 0` produces a schema whose layers appear as the
 * index grows.
 *
 * Usage:
 *   node tools/generate-hnsw-schema.js [--size 10000] [--m 15] [--dimensions 128]
 *                                      [--ef-construction 100] [--ef-search 30]
 *                                      [--metric L2] [--layers auto]
 *                                      [--vertex Vector] [--out <path>]
 */
'use strict';

const fs = require('fs');
const path = require('path');

function parseArgs(argv) {
  const defaults = {
    size: 10000,
    m: 15,
    dimensions: 128,
    'ef-construction': 100,
    'ef-search': 30,
    metric: 'L2',
    layers: 'auto',
    vertex: 'Vector',
    'vector-property': 'vector',
    graph: 'hnsw-sift',
    id: 3,
    out: path.join(__dirname, '..', 'graph-http', 'src', 'main', 'resources', 'hnsw-sift.json'),
  };
  const args = { ...defaults };
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i].replace(/^--/, '');
    const value = argv[i + 1];
    if (!(key in defaults)) throw new Error(`Unknown option: ${argv[i]}`);
    args[key] = typeof defaults[key] === 'number' && key !== 'out' ? Number(value) : value;
  }
  return args;
}

/** HNSW keeps about size / 2^l vertices on layer l, so the top layer is ceil(log2(size)). */
function topLevel(size) {
  if (size <= 1) return 0;
  return Math.max(1, Math.ceil(Math.log2(size)));
}

function property(id, name, description, type, extra = {}) {
  return { id, name, description, type, ...extra };
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const top = topLevel(args.size);
  const vertex = args.vertex;
  const layerCount = args.layers === 'auto' ? top + 1 : Number(args.layers);

  const meta = (id) => property(id, id === -2 ? 'createdTime' : 'lastUpdatedTime',
      id === -2 ? 'Creation time' : 'Last update time', 'LONG');

  const vertexSchema = {
    id: 1,
    name: vertex,
    description: `A vector of the HNSW demo dataset (${args.dimensions} dimensions, ${args.size} rows)`,
    idSchema: property(0, 'id', `${vertex} id (the dataset row as a string)`, 'STRING'),
    createdTime: meta(-2),
    lastUpdatedTime: meta(-1),
    properties: [
      property(1, 'name', 'Human readable vector name', 'STRING', { display: true }),
      property(2, args['vector-property'], `The ${args.dimensions}-dimensional vector`,
          { list: 'DOUBLE' }, {
            // the property is an embedding: writes index it automatically
            isVector: true,
            metric: args.metric,
            m: args.m,
            efConstruction: args['ef-construction'],
            efSearch: args['ef-search'],
          }),
    ],
  };

  // one bidirectional edge class per layer: vector_0 holds every vertex, the top
  // layer holds about one (the entry point of a search). They are listed here so
  // an existing dataset keeps stable ids; a vector property without them gets
  // them created on demand while the index grows.
  const edgeSchemas = [];
  for (let level = 0; level < layerCount; level++) {
    const maxLinks = level === 0 ? 2 * args.m : args.m;
    const degree = 2 * maxLinks; // head-room above the HNSW cap: the index prunes first
    edgeSchemas.push({
      id: level + 1,
      name: `vector_${level}`,
      description: `HNSW layer ${level} (${maxLinks} links per vertex, ${level === 0 ? 'all' : 'about ' + Math.max(1, Math.round(args.size / 2 ** level))} vertices)`,
      idSchema: property(0, 'id', 'Link id', 'STRING'),
      source: property(1, 'source', 'First vertex id', 'STRING', { vertex }),
      target: property(2, 'target', 'Second vertex id', 'STRING', { vertex }),
      createdTime: meta(-2),
      lastUpdatedTime: meta(-1),
      direction: 'BIDIRECTIONAL',
      maxOutgoingEdges: degree,
      maxIncomingEdges: degree,
      maxDegree: degree,
      properties: [],
    });
  }

  const schema = {
    id: args.id,
    name: args.graph,
    description: `HNSW vector index of ${args.size} ${args.dimensions}-d vectors over ${top + 1} layers`,
    constraints: { idSchemaIndex: 0, createdTimeIndex: -2, lastUpdatedTimeIndex: -1 },
    vertexSchemas: [vertexSchema],
    edgeSchemas,
  };

  fs.mkdirSync(path.dirname(args.out), { recursive: true });
  fs.writeFileSync(args.out, JSON.stringify(schema, null, 2) + '\n');
  console.log(`Wrote ${args.out}`);
  console.log(`  size=${args.size} -> layers 0..${top} (${top + 1} edge classes vector_0..vector_${top})`);
  console.log(`  m=${args.m}: maxLinks(0)=${2 * args.m}, maxLinks(>=1)=${args.m}`);
  console.log(`  declared layer edge classes: ${layerCount}` +
      (layerCount === top + 1 ? '' : ' (the rest are created on demand)'));
  console.log(`  vector property '${args['vector-property']}': isVector=true, metric=${args.metric}, ` +
      `m=${args.m}, efConstruction=${args['ef-construction']}, efSearch=${args['ef-search']}`);
}

main();
