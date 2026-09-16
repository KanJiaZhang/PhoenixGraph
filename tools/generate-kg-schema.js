#!/usr/bin/env node
/*
 * Generates the knowledge-graph schema (graph-http/src/main/resources/kg-entity-graph.json):
 *
 *   - 10 vertex schemas Entity0 .. Entity9, each with `name` (display property) and `type`
 *   - all 100 ordered (EntityI, EntityJ) pairs as DIRECTED edge schemas `EntityI_EntityJ`
 *     (including I == J), each declaring max out-/in-degree 2000 and the `relation` /
 *     `xrelation` properties (xrelation = the reverse relation name)
 *
 * Usage: node tools/generate-kg-schema.js
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ENTITY_COUNT = 10;
const MAX_DEGREE = 2000;
const OUT = path.join(__dirname, '..', 'graph-http', 'src', 'main', 'resources', 'kg-entity-graph.json');

const idSchema = (kind) => ({ id: 0, name: 'id', description: `${kind} id`, type: 'STRING' });
const createdTime = () => ({ id: -2, name: 'createdTime', description: 'Creation time', type: 'LONG' });
const lastUpdatedTime = () => ({ id: -1, name: 'lastUpdatedTime', description: 'Last update time', type: 'LONG' });

const vertexSchemas = [];
for (let i = 0; i < ENTITY_COUNT; i++) {
  vertexSchemas.push({
    id: i + 1,
    name: `Entity${i}`,
    description: `Knowledge graph entity of kind Entity${i}`,
    idSchema: idSchema('Entity'),
    createdTime: createdTime(),
    lastUpdatedTime: lastUpdatedTime(),
    properties: [
      { id: 1, name: 'name', description: 'Entity name (its knowledge-graph id)', type: 'STRING', display: true },
      { id: 2, name: 'type', description: `Entity kind (Entity${i})`, type: 'STRING' },
    ],
  });
}

const edgeSchemas = [];
let edgeId = 0;
for (let i = 0; i < ENTITY_COUNT; i++) {
  for (let j = 0; j < ENTITY_COUNT; j++) {
    edgeId++;
    edgeSchemas.push({
      id: edgeId,
      name: `Entity${i}_Entity${j}`,
      description: `Directed relation from Entity${i} to Entity${j}`,
      idSchema: { id: 0, name: 'id', description: 'Edge id', type: 'STRING' },
      source: { id: 1, name: 'source', description: 'Source vertex id', type: 'STRING', vertex: `Entity${i}` },
      target: { id: 2, name: 'target', description: 'Target vertex id', type: 'STRING', vertex: `Entity${j}` },
      createdTime: createdTime(),
      lastUpdatedTime: lastUpdatedTime(),
      direction: 'DIRECTED',
      maxOutgoingEdges: MAX_DEGREE,
      maxIncomingEdges: MAX_DEGREE,
      properties: [
        { id: 3, name: 'relation', description: 'Forward relation name', type: 'STRING' },
        { id: 4, name: 'xrelation', description: 'Reverse relation name', type: 'STRING' },
      ],
    });
  }
}

const schema = {
  id: 2,
  name: 'kg-entity-graph',
  description: `${ENTITY_COUNT} Entity vertex types and all ${ENTITY_COUNT * ENTITY_COUNT} ordered relation types `
    + `(directed, max out-/in-degree ${MAX_DEGREE}) carrying relation/xrelation`,
  constraints: { idSchemaIndex: 0, createdTimeIndex: -2, lastUpdatedTimeIndex: -1 },
  vertexSchemas,
  edgeSchemas,
};

// hand-formatted so the committed file stays readable (one property / short object per line)
const obj = (o) => `{ ${Object.entries(o).map(([k, v]) => `"${k}": ${JSON.stringify(v)}`).join(', ')} }`;
const lines = [];
lines.push('{');
lines.push(`  "id": ${schema.id},`);
lines.push(`  "name": ${JSON.stringify(schema.name)},`);
lines.push(`  "description": ${JSON.stringify(schema.description)},`);
lines.push(`  "constraints": ${obj(schema.constraints)},`);
lines.push('  "vertexSchemas": [');
vertexSchemas.forEach((v, i) => {
  lines.push('    {');
  lines.push(`      "id": ${v.id},`);
  lines.push(`      "name": ${JSON.stringify(v.name)},`);
  lines.push(`      "description": ${JSON.stringify(v.description)},`);
  lines.push(`      "idSchema": ${obj(v.idSchema)},`);
  lines.push(`      "createdTime": ${obj(v.createdTime)},`);
  lines.push(`      "lastUpdatedTime": ${obj(v.lastUpdatedTime)},`);
  lines.push('      "properties": [');
  v.properties.forEach((p, k) => {
    lines.push(`        ${obj(p)}${k < v.properties.length - 1 ? ',' : ''}`);
  });
  lines.push('      ]');
  lines.push(`    }${i < vertexSchemas.length - 1 ? ',' : ''}`);
});
lines.push('  ],');
lines.push('  "edgeSchemas": [');
edgeSchemas.forEach((e, i) => {
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
  e.properties.forEach((p, k) => {
    lines.push(`        ${obj(p)}${k < e.properties.length - 1 ? ',' : ''}`);
  });
  lines.push('      ]');
  lines.push(`    }${i < edgeSchemas.length - 1 ? ',' : ''}`);
});
lines.push('  ]');
lines.push('}');

fs.writeFileSync(OUT, lines.join('\n') + '\n', 'utf8');
console.log(`wrote ${OUT}: ${vertexSchemas.length} vertex schemas, ${edgeSchemas.length} edge schemas`);
