#!/usr/bin/env node
/*
 * Generates the enterprise data-agent schema (graph-http/src/main/resources/tpc-h-analytics.json):
 *
 *   - ONE vertex schema `Node` whose `entityType` is one of
 *     Table / Column / Metric / Dimension / Domain / Query
 *   - ONE edge schema `Relation` carrying relation / relationLabel / relationFamily
 *
 * The graph is the semantic layer of an enterprise data agent: it holds the warehouse schema
 * (tables and columns, with their keys and foreign keys), the business metrics defined over
 * them (with their aggregation and SQL expression), the dimensions each metric can be sliced
 * by, the domains the metrics belong to, and the attribution edges between metrics (which
 * base metric drives which derived one). The data is the TPC-H decision-support benchmark
 * (8 tables, 22 standard queries), a public, reproducible retail/order business model.
 *
 * Usage: node tools/generate-tpch-schema.js
 */
'use strict';

const fs = require('fs');
const path = require('path');

const OUT = path.join(__dirname, '..', 'graph-http', 'src', 'main', 'resources', 'tpc-h-analytics.json');

const obj = (o) => `{ ${Object.entries(o).map(([k, v]) => `"${k}": ${JSON.stringify(v)}`).join(', ')} }`;
const meta = (name, description) => ({ id: name === 'createdTime' ? -2 : -1, name, description, type: 'LONG' });

/** The `Node` vertex type: one shape for tables, columns, metrics, dimensions, domains and queries. */
function nodeVertex() {
  return {
    name: 'Node',
    description: 'A semantic-layer node: Table / Column / Metric / Dimension / Domain / Query',
    idSchema: { id: 0, name: 'id', description: 'Node id (its name)', type: 'STRING' },
    createdTime: meta('createdTime', 'Creation timestamp'),
    lastUpdatedTime: meta('lastUpdatedTime', 'Last update timestamp'),
    properties: [
      { id: 1, name: 'name', description: 'Node name (table / column / metric / dimension / domain / query id)', type: 'STRING', display: true },
      { id: 2, name: 'entityType', description: 'Table / Column / Metric / Dimension / Domain / Query', type: 'STRING' },
      { id: 3, name: 'description', description: 'Business meaning (中文说明)', type: 'STRING' },
      { id: 4, name: 'alias', description: 'Alternative names (多值)', type: { list: 'STRING' } },
      // Column-only
      { id: 5, name: 'dataType', description: 'Column SQL type (DECIMAL(15,2), INTEGER, CHAR(1), DATE, ...)', type: 'STRING' },
      { id: 6, name: 'columnRole', description: 'PK / FK / measure / attribute', type: 'STRING' },
      { id: 7, name: 'tableName', description: 'The table a column belongs to', type: 'STRING' },
      // Metric-only
      { id: 8, name: 'aggFunction', description: 'SUM / COUNT / COUNT_DISTINCT / AVG / RATIO', type: 'STRING' },
      { id: 9, name: 'expression', description: 'The SQL aggregation expression (the metric definition)', type: 'STRING' },
      { id: 10, name: 'metricKind', description: 'base / derived / ratio', type: 'STRING' },
      { id: 11, name: 'unit', description: 'Unit (元 / 个 / % / 天)', type: 'STRING' },
      // Query-only
      { id: 12, name: 'sqlText', description: 'The SQL of a standard query (TPC-H)', type: 'STRING' },
      { id: 13, name: 'queryNumber', description: 'The TPC-H query number (1..22)', type: 'LONG' },
    ],
  };
}

function relationEdge() {
  return {
    name: 'Relation',
    description: 'A semantic-layer relation: hasColumn / references / computedFrom / usesColumn / '
      + 'groupedBy / definedBy / contributesTo / belongsTo / readsTable / dimensionColumn',
    idSchema: { id: 0, name: 'id', description: 'Edge id (head|relation|tail)', type: 'STRING' },
    source: { id: 1, name: 'source', description: 'Source node id', type: 'STRING', vertex: 'Node' },
    target: { id: 2, name: 'target', description: 'Target node id', type: 'STRING', vertex: 'Node' },
    direction: 'DIRECTED',
    properties: [
      { id: 3, name: 'relation', description: 'Relation name (hasColumn / references / computedFrom / ...)', type: 'STRING' },
      { id: 4, name: 'xrelation', description: 'Reverse relation name; equals relation when it has no inverse', type: 'STRING' },
      { id: 5, name: 'relationLabel', description: '中文关系名 (包含字段 / 外键引用 / 取自表 / ...)', type: 'STRING' },
      { id: 6, name: 'relationFamily', description: '关系家族 (结构 / 血缘 / 指标 / 归因 / 维度)', type: 'STRING' },
    ],
  };
}

function serialize(schema) {
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
    lines.push(`      "createdTime": ${obj(meta('createdTime', 'Creation timestamp'))},`);
    lines.push(`      "lastUpdatedTime": ${obj(meta('lastUpdatedTime', 'Last update timestamp'))},`);
    lines.push(`      "direction": ${JSON.stringify(e.direction)},`);
    lines.push('      "properties": [');
    e.properties.forEach((p, k) => lines.push(`        ${obj(p)}${k < e.properties.length - 1 ? ',' : ''}`));
    lines.push('      ]');
    lines.push(`    }${i < schema.edgeSchemas.length - 1 ? ',' : ''}`);
  });
  lines.push('  ],');
  lines.push(`  "storage": ${obj(schema.storage)}`);
  lines.push('}');
  return lines.join('\n') + '\n';
}

const node = nodeVertex();
const edges = [relationEdge()];

const schema = {
  id: 51,
  name: 'tpc-h-analytics',
  description: 'The semantic layer of an enterprise data agent over the TPC-H retail model: '
    + 'tables, columns, metrics, dimensions, domains and queries, with lineage and attribution relations.',
  constraints: { idSchemaIndex: 0, createdTimeIndex: -2, lastUpdatedTimeIndex: -1 },
  storage: {
    type: 'RocksDB-Edge',
    path: 'tpc-h-analytics',
    regionCount: 64,
    maxVersions: 1,
    cacheEdges: true,
  },
  vertexSchemas: [
    { id: 1, ...node, createdTime: meta('createdTime', 'Creation timestamp'), lastUpdatedTime: meta('lastUpdatedTime', 'Last update timestamp') },
  ],
  edgeSchemas: edges.map((e, i) => ({
    id: i + 1,
    ...e,
    createdTime: meta('createdTime', 'Creation timestamp'),
    lastUpdatedTime: meta('lastUpdatedTime', 'Last update timestamp'),
  })),
};

fs.writeFileSync(OUT, serialize(schema));
console.log(`wrote ${OUT}: 1 vertex schema, 1 edge schema, storage RocksDB-Edge`);
