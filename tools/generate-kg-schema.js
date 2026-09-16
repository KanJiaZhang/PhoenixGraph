#!/usr/bin/env node
/*
 * Generates the knowledge-graph schema (graph-http/src/main/resources/kg-entity-graph.json):
 *
 *   - ONE vertex schema `Entity` with `name` (display property) and `shard`
 *   - ONE DIRECTED edge schema `Entity_Entity` (max out-/in-degree 2000) carrying
 *     the `relation` / `xrelation` properties (xrelation = the reverse relation name)
 *
 * Vertices are sharded by relation: an entity's vertex id is
 * `name + "_" + floorMod(relation.hashCode(), 10)`, so the edges of a relation all
 * live in a single shard (see KnowledgeGraphIds).
 *
 * Usage: node tools/generate-kg-schema.js
 */
'use strict';

const fs = require('fs');
const path = require('path');

const MAX_DEGREE = 2000;
const OUT = path.join(__dirname, '..', 'graph-http', 'src', 'main', 'resources', 'kg-entity-graph.json');

const obj = (o) => `{ ${Object.entries(o).map(([k, v]) => `"${k}": ${JSON.stringify(v)}`).join(', ')} }`;

const schema = {
  id: 2,
  name: 'kg-entity-graph',
  description: 'One Entity vertex type, relation-sharded by floorMod(relation.hashCode(), 10); '
    + `directed Entity_Entity relations (max out-/in-degree ${MAX_DEGREE}) carrying relation/xrelation`,
  constraints: { idSchemaIndex: 0, createdTimeIndex: -2, lastUpdatedTimeIndex: -1 },
  vertexSchemas: [
    {
      id: 1,
      name: 'Entity',
      description: 'A knowledge-graph entity, replicated per relation shard',
      idSchema: { id: 0, name: 'id', description: 'Entity id (name_shard)', type: 'STRING' },
      createdTime: { id: -2, name: 'createdTime', description: 'Creation time', type: 'LONG' },
      lastUpdatedTime: { id: -1, name: 'lastUpdatedTime', description: 'Last update time', type: 'LONG' },
      properties: [
        { id: 1, name: 'name', description: 'Entity name', type: 'STRING', display: true },
        { id: 2, name: 'shard', description: 'Relation shard (floorMod(relation.hashCode(), 10))', type: 'LONG' },
      ],
    },
  ],
  edgeSchemas: [
    {
      id: 1,
      name: 'Entity_Entity',
      description: 'A directed relation between two entities',
      idSchema: { id: 0, name: 'id', description: 'Edge id', type: 'STRING' },
      source: { id: 1, name: 'source', description: 'Source vertex id', type: 'STRING', vertex: 'Entity' },
      target: { id: 2, name: 'target', description: 'Target vertex id', type: 'STRING', vertex: 'Entity' },
      createdTime: { id: -2, name: 'createdTime', description: 'Creation time', type: 'LONG' },
      lastUpdatedTime: { id: -1, name: 'lastUpdatedTime', description: 'Last update time', type: 'LONG' },
      direction: 'DIRECTED',
      maxOutgoingEdges: MAX_DEGREE,
      maxIncomingEdges: MAX_DEGREE,
      properties: [
        { id: 3, name: 'relation', description: 'Forward relation name', type: 'STRING' },
        { id: 4, name: 'xrelation', description: 'Reverse relation name', type: 'STRING' },
      ],
    },
  ],
};

const lines = [];
lines.push('{');
lines.push(`  "id": ${schema.id},`);
lines.push(`  "name": ${JSON.stringify(schema.name)},`);
lines.push(`  "description": ${JSON.stringify(schema.description)},`);
lines.push(`  "constraints": ${obj(schema.constraints)},`);
lines.push('  "vertexSchemas": [');
schema.vertexSchemas.forEach((v, i) => {
  lines.push('    {');
  lines.push(`      "id": ${v.id},`);
  lines.push(`      "name": ${JSON.stringify(v.name)},`);
  lines.push(`      "description": ${JSON.stringify(v.description)},`);
  lines.push(`      "idSchema": ${obj(v.idSchema)},`);
  lines.push(`      "createdTime": ${obj(v.createdTime)},`);
  lines.push(`      "lastUpdatedTime": ${obj(v.lastUpdatedTime)},`);
  lines.push('      "properties": [');
  v.properties.forEach((p, k) => lines.push(`        ${obj(p)}${k < v.properties.length - 1 ? ',' : ''}`));
  lines.push('      ]');
  lines.push(`    }${i < schema.vertexSchemas.length - 1 ? ',' : ''}`);
});
lines.push('  ],');
lines.push('  "edgeSchemas": [');
schema.edgeSchemas.forEach((e, i) => {
  lines.push('    {');
  lines.push(`      "id": ${e.id},`);
  lines.push(`      "name": ${JSON.stringify(e.name)},`);
  lines.push(`      "description": ${JSON.stringify(e.description)},`);
  lines.push(`      "idSchema": ${obj(e.idSchema)},`);
  lines.push(`      "source": ${obj(e.source)},`);
  lines.push(`      "target": ${obj(e.target)},`);
  lines.push(`      "createdTime": ${obj(e.createdTime)},`);
  lines.push(`      "lastUpdatedTime": ${obj(e.lastUpdatedTime)},`);
  lines.push(`      "direction": ${JSON.stringify(e.direction)},`);
  lines.push(`      "maxOutgoingEdges": ${e.maxOutgoingEdges},`);
  lines.push(`      "maxIncomingEdges": ${e.maxIncomingEdges},`);
  lines.push('      "properties": [');
  e.properties.forEach((p, k) => lines.push(`        ${obj(p)}${k < e.properties.length - 1 ? ',' : ''}`));
  lines.push('      ]');
  lines.push(`    }${i < schema.edgeSchemas.length - 1 ? ',' : ''}`);
});
lines.push('  ]');
lines.push('}');

fs.writeFileSync(OUT, lines.join('\n') + '\n', 'utf8');
console.log(`wrote ${OUT}: ${schema.vertexSchemas.length} vertex schema, ${schema.edgeSchemas.length} edge schema`);
