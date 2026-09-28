#!/usr/bin/env node
/*
 * Generates the 《三国演义》 knowledge-graph schemas from one definition:
 *
 *   graph-http/src/main/resources/sanguo-kg.json
 *     Aerospike: one `Entity` vertex type whose id is `name_shard`
 *     (relation-sharded, see SanguoGraphIds) and one directed `Entity_Entity`
 *     edge type carrying relation/xrelation/relationLabel/relationFamily.
 *
 *   graph-http/src/main/resources/sanguo-kg-rocksdb-edge.json
 *     RocksDB-Edge: the same two types, but the vertex id is the plain entity
 *     name (no hash / no shard) and the storage block opens a RocksDB directory.
 *     The `shard` property does not exist here.
 *
 * Property names avoid `id` / `label` / `type` / `source` / `target`: those are
 * meta keys the Gremlin and Cypher layers resolve before the property map, so a
 * property with one of those names would be shadowed.
 *
 * Usage: node tools/generate-sanguo-schema.js
 */
'use strict';

const fs = require('fs');
const path = require('path');

const RESOURCES = path.join(__dirname, '..', 'graph-http', 'src', 'main', 'resources');
const MAX_DEGREE = 2000;

const obj = (o) => `{ ${Object.entries(o).map(([k, v]) => `"${k}": ${JSON.stringify(v)}`).join(', ')} }`;

const meta = (name, description) => ({ id: name === 'createdTime' ? -2 : -1, name, description, type: 'LONG' });

/** The `Entity` vertex type. `withShard` adds the relation-shard property of the Aerospike form. */
function entityVertex(withShard) {
  const properties = [
    { id: 1, name: 'name', description: 'Entity name (人物/势力/籍贯/官职/章回)', type: 'STRING', display: true },
  ];
  if (withShard) {
    properties.push({ id: 2, name: 'shard', description: 'Relation shard (floorMod(relation.hashCode(), 10))', type: 'LONG' });
  }
  properties.push(
    { id: withShard ? 3 : 2, name: 'entityType', description: 'Male/Female/Person/Faction/Place/Position/Chapter', type: 'STRING' },
    { id: withShard ? 4 : 3, name: 'gender', description: '男 / 女 (documented persons only)', type: 'STRING' },
    { id: withShard ? 5 : 4, name: 'courtesyName', description: '字', type: 'STRING' },
    { id: withShard ? 6 : 5, name: 'historicity', description: '史实人物 / 虚构人物', type: 'STRING' },
    { id: withShard ? 7 : 6, name: 'history', description: '历史简介', type: 'STRING' },
    { id: withShard ? 8 : 7, name: 'novel', description: '演义简介', type: 'STRING' },
    // 别名：字以外的称呼——号（道号水镜先生）、他人所称（称诸葛亮为卧龙）、姓+字（司马德操）。
    // 多值属性，查询靠 has('alias', TextP.containing('…'))。
    { id: withShard ? 9 : 8, name: 'alias', description: '别名/号（多值）', type: { list: 'STRING' } },
  );
  return {
    id: 1,
    name: 'Entity',
    description: withShard
      ? 'A knowledge-graph entity, replicated per relation shard'
      : 'A knowledge-graph entity (person / faction / place / office / chapter), keyed by its name',
    idSchema: {
      id: 0,
      name: 'id',
      description: withShard ? 'Entity id (name_shard)' : 'Entity id (the entity name)',
      type: 'STRING',
    },
    createdTime: meta('createdTime', 'Creation time'),
    lastUpdatedTime: meta('lastUpdatedTime', 'Last update time'),
    properties,
  };
}

/** The `Entity_Entity` edge type; identical in both schemes. */
function entityEdge() {
  return {
    id: 1,
    name: 'Entity_Entity',
    description: 'A directed relation between two entities',
    idSchema: { id: 0, name: 'id', description: 'Edge id', type: 'STRING' },
    source: { id: 1, name: 'source', description: 'Source vertex id', type: 'STRING', vertex: 'Entity' },
    target: { id: 2, name: 'target', description: 'Target vertex id', type: 'STRING', vertex: 'Entity' },
    createdTime: meta('createdTime', 'Creation time'),
    lastUpdatedTime: meta('lastUpdatedTime', 'Last update time'),
    direction: 'DIRECTED',
    maxOutgoingEdges: MAX_DEGREE,
    maxIncomingEdges: MAX_DEGREE,
    properties: [
      { id: 3, name: 'relation', description: 'Forward relation name (e.g. father)', type: 'STRING' },
      { id: 4, name: 'xrelation', description: 'Reverse relation name; equals relation when it has no inverse', type: 'STRING' },
      { id: 5, name: 'relationLabel', description: '中文关系名 (e.g. 父亲)', type: 'STRING' },
      { id: 6, name: 'relationFamily', description: '关系家族 (e.g. 亲属)', type: 'STRING' },
      // 时间维度：见 SANGUO-KG.md §7.8。两个端点的文档都带 `年代列表` / `章节列表`，
      // 它们的交集就是“这条关系被共同见证”的窗口 —— 一个下界，不是关系的起止年代。
      { id: 7, name: 'year', description: '年份区间：两端点共现的公元年，如 196-208', type: 'STRING' },
      { id: 8, name: 'chapter', description: '回数区间：两端点共现的章回号，如 35-48', type: 'STRING' },
      // 叙述序才是能排序的信号：传记是编年叙述。历史/小说是两套平行叙述，
      // 所以 seq = 字段序*1000 + 句子序（历史=1xxx，小说=2xxx），只能在同一字段内比较。
      { id: 9, name: 'seq', description: '叙述序：字段序*1000+句子序，越小越早；同字段内可比', type: 'LONG' },
      { id: 10, name: 'provenance', description: '边的来源：CURATED/EXTRA/DOCUMENT/INVERSE/SYMMETRIC', type: 'STRING' },
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
  lines.push('  ]' + (schema.storage ? ',' : ''));
  if (schema.storage) {
    lines.push(`  "storage": ${obj(schema.storage)}`);
  }
  lines.push('}');
  return lines.join('\n') + '\n';
}

const constraints = { idSchemaIndex: 0, createdTimeIndex: -2, lastUpdatedTimeIndex: -1 };

const aerospike = {
  id: 4,
  name: 'sanguo-kg',
  description: '《三国演义》knowledge graph: one Entity vertex type (person / faction / place / office / chapter), '
    + 'relation-sharded by floorMod(relation.hashCode(), 10); directed Entity_Entity relations '
    + `(max out-/in-degree ${MAX_DEGREE}) carrying relation/xrelation/relationLabel/relationFamily`,
  constraints,
  vertexSchemas: [entityVertex(true)],
  edgeSchemas: [entityEdge()],
};

const rocksDbEdge = {
  id: 5,
  name: 'sanguo-kg-rocksdb-edge',  description: 'The same 《三国演义》knowledge graph served by the RocksDB-Edge storage: the vertex id is the plain '
    + 'entity name (no relation shard), and the edges live in an edge table per edge class, so a whole person is one '
    + `range scan instead of ten sharded records. Directed Entity_Entity relations (max out-/in-degree ${MAX_DEGREE}) `
    + 'carrying relation/xrelation/relationLabel/relationFamily',
  constraints,
  vertexSchemas: [entityVertex(false)],
  edgeSchemas: [entityEdge()],
  storage: {
    type: 'RocksDB-Edge',
    path: 'sanguo-kg-rocksdb-edge',
    regionCount: 512,
    maxVersions: 1,
    cacheEdges: true,
  },
};

const rocksDbEdgeText = {
  id: 6,
  name: 'sanguo-kg-text',
  description: '方案 C: the same 《三国演义》 graph on RocksDB-Edge, but the data set is extended with what the '
    + 'document free text (历史/小说) yields — mentions, offices, 军师/谋士, 交战/斩杀 — so it holds more '
    + `entities and relations than sanguo-kg-rocksdb-edge. Vertex id = the entity name; max out-/in-degree ${MAX_DEGREE}.`,
  constraints,
  vertexSchemas: [entityVertex(false)],
  edgeSchemas: [entityEdge()],
  storage: {
    type: 'RocksDB-Edge',
    path: 'sanguo-kg-text',
    regionCount: 512,
    maxVersions: 1,
    cacheEdges: true,
  },
};

for (const schema of [aerospike, rocksDbEdge, rocksDbEdgeText]) {  const out = path.join(RESOURCES, `${schema.name}.json`);
  fs.writeFileSync(out, serialize(schema), 'utf8');
  console.log(`wrote ${out}: ${schema.vertexSchemas.length} vertex schema, ${schema.edgeSchemas.length} edge schema`
    + (schema.storage ? `, storage ${schema.storage.type}` : ', storage Aerospike'));
}
