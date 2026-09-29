#!/usr/bin/env node
/*
 * Generates the TPC-H data-instance schema (graph-http/src/main/resources/tpc-h-data.json):
 *
 *   - ONE vertex schema `Sale` — a materialised lineitem fact row. The measure expressions
 *     (revenue = extendedprice*(1-discount), tax_amount, ...) are stored as their own properties
 *     because the Gremlin engine has no arithmetic in expressions; the dimensional attributes
 *     (region / nation / segment / priority / shipmode / partType / brand / supplier) are
 *     denormalised onto the fact row, which is what makes `group().by('region').by(values('revenue').sum())`
 *     a single scan rather than a four-hop walk. This is the star schema a warehouse would build.
 *   - ONE DIRECTED edge schema `Rel` carrying `relation`, for the links a graph question needs
 *     (sale --ofPart--> part, part --ofSupplier--> supplier, sale --ofOrder--> order,
 *      order --placedBy--> customer, customer/supplier --inNation--> nation, nation --inRegion--> region).
 *
 * This is the DATA instance behind the semantic layer (`tpc-h-analytics`): the semantic layer says
 * how a metric is defined, this instance is the rows the query runs over.
 *
 * Usage: node tools/generate-tpch-data-schema.js
 */
'use strict';

const fs = require('fs');
const path = require('path');

const OUT = path.join(__dirname, '..', 'graph-http', 'src', 'main', 'resources', 'tpc-h-data.json');

const obj = (o) => `{ ${Object.entries(o).map(([k, v]) => `"${k}": ${JSON.stringify(v)}`).join(', ')} }`;
const meta = (name, description) => ({ id: name === 'createdTime' ? -2 : -1, name, description, type: 'LONG' });

function saleVertex() {
  return {
    name: 'Sale',
    description: 'A materialised order-line fact row (TPC-H lineitem), with its dimensions denormalised',
    idSchema: { id: 0, name: 'id', description: 'Sale id (lineitem key)', type: 'STRING' },
    createdTime: meta('createdTime', 'Creation timestamp'),
    lastUpdatedTime: meta('lastUpdatedTime', 'Last update timestamp'),
    properties: [
      { id: 1, name: 'name', description: 'Sale id (display)', type: 'STRING', display: true },
      // 原始度量
      { id: 2, name: 'extendedprice', description: '金额 = 单价 × 数量', type: 'DOUBLE' },
      { id: 3, name: 'discount', description: '折扣率 0..1', type: 'DOUBLE' },
      { id: 4, name: 'tax', description: '税率 0..1', type: 'DOUBLE' },
      { id: 5, name: 'quantity', description: '数量', type: 'DOUBLE' },
      // 预计算指标（物化，因为 Gremlin 表达式无算术）
      { id: 6, name: 'revenue', description: '净收入 = extendedprice × (1 - discount)', type: 'DOUBLE' },
      { id: 7, name: 'gross', description: '毛收入 = extendedprice', type: 'DOUBLE' },
      { id: 8, name: 'discountAmount', description: '折扣额 = extendedprice × discount', type: 'DOUBLE' },
      { id: 9, name: 'taxAmount', description: '税额 = extendedprice × (1 - discount) × tax', type: 'DOUBLE' },
      // 标记
      { id: 10, name: 'lateDelivery', description: '迟到标记（收货日 > 承诺日 = 1）', type: 'LONG' },
      { id: 11, name: 'returned', description: '退货标记（returnflag = R = 1）', type: 'LONG' },
      // 反规范化的维度
      { id: 12, name: 'region', description: '地区（客户所在大区）', type: 'STRING' },
      { id: 13, name: 'nation', description: '国家', type: 'STRING' },
      { id: 14, name: 'segment', description: '市场细分', type: 'STRING' },
      { id: 15, name: 'priority', description: '订单优先级', type: 'STRING' },
      { id: 16, name: 'shipmode', description: '运输方式', type: 'STRING' },
      { id: 17, name: 'partType', description: '商品类型（PROMO 前缀 = 促销品）', type: 'STRING' },
      { id: 18, name: 'brand', description: '品牌', type: 'STRING' },
      { id: 19, name: 'supplierName', description: '供应商名', type: 'STRING' },
      { id: 20, name: 'orderYear', description: '下单年', type: 'LONG' },
      { id: 21, name: 'orderMonth', description: '下单月 1..12', type: 'LONG' },
    ],
  };
}

function dimensionVertex(name, description, extraProperty) {
  const properties = [{ id: 1, name: 'name', description: description + ' name', type: 'STRING', display: true }];
  if (extraProperty) {
    properties.push({ id: 2, name: extraProperty.name, description: extraProperty.description, type: 'STRING' });
  }
  return {
    name,
    description,
    idSchema: { id: 0, name: 'id', description: name + ' id', type: 'STRING' },
    createdTime: meta('createdTime', 'Creation timestamp'),
    lastUpdatedTime: meta('lastUpdatedTime', 'Last update timestamp'),
    properties,
  };
}

function relEdge(name, sourceVertex, targetVertex, label) {
  return {
    name,
    description: label + '：' + sourceVertex + ' → ' + targetVertex,
    idSchema: { id: 0, name: 'id', description: 'Edge id', type: 'STRING' },
    source: { id: 1, name: 'source', description: 'Source vertex id', type: 'STRING', vertex: sourceVertex },
    target: { id: 2, name: 'target', description: 'Target vertex id', type: 'STRING', vertex: targetVertex },
    direction: 'DIRECTED',
    properties: [
      { id: 3, name: 'relationLabel', description: '中文关系名', type: 'STRING' },
    ],
    label,
  };
}

/** 每个关系一个边类，端点类型明确（否则引擎按 schema 端点类型解析，会读错顶点类型）。 */
const EDGE_DEFS = [
  ['ofPart', 'Sale', 'Part', '含商品'],
  ['ofSupplier', 'Sale', 'Supplier', '由供应商供'],
  ['ofOrder', 'Sale', 'Order', '属于订单'],
  ['placedBy', 'Order', 'Customer', '下单客户'],
  ['customerNation', 'Customer', 'Nation', '客户所在国家'],
  ['supplierNation', 'Supplier', 'Nation', '供应商所在国家'],
  ['nationRegion', 'Nation', 'Region', '所在大区'],
];

function serialize(schema) {
  const lines = ['{'];
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

const schema = {
  id: 52,
  name: 'tpc-h-data',
  description: 'The TPC-H data instance: materialised lineitem fact rows (with denormalised dimensions) '
    + 'and the star-schema links, so the metrics of the semantic layer can be answered with real numbers.',
  constraints: { idSchemaIndex: 0, createdTimeIndex: -2, lastUpdatedTimeIndex: -1 },
  storage: { type: 'RocksDB-Edge', path: 'tpc-h-data', regionCount: 64, maxVersions: 1, cacheEdges: true },
  vertexSchemas: [
    { id: 1, ...saleVertex() },
    { id: 2, ...dimensionVertex('Part', '商品', { name: 'partType', description: '类型' }) },
    { id: 3, ...dimensionVertex('Supplier', '供应商', { name: 'nation', description: '所在国家' }) },
    { id: 4, ...dimensionVertex('Customer', '客户', { name: 'segment', description: '市场细分' }) },
    { id: 5, ...dimensionVertex('Nation', '国家', { name: 'region', description: '所在大区' }) },
    { id: 6, ...dimensionVertex('Region', '地区', null) },
    { id: 7, ...dimensionVertex('Order', '订单', { name: 'priority', description: '优先级' }) },
  ],
  edgeSchemas: EDGE_DEFS.map(([name, source, target, label], i) => ({
    id: i + 1,
    ...relEdge(name, source, target, label),
    createdTime: meta('createdTime', 'Creation timestamp'),
    lastUpdatedTime: meta('lastUpdatedTime', 'Last update timestamp'),
  })),
};

fs.writeFileSync(OUT, serialize(schema));
console.log(`wrote ${OUT}: ${schema.vertexSchemas.length} vertex schemas, ${schema.edgeSchemas.length} edge schema(s), storage RocksDB-Edge`);
