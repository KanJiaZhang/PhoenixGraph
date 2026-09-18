#!/usr/bin/env node
/**
 * Writes the two "smart" payment schemas: the same graph once on Aerospike (where the vector
 * index lives today) and once on RocksDB-Inline (where the S2 geo index lives).
 *
 *   node tools/generate-smart-payment-schema.js
 *
 * Emits graph-http/src/main/resources/payment-graph-smart.json (id 41) and
 * payment-graph-smart-rocksdb.json (id 42).
 *
 * The properties the two instances share:
 *   Account  - name, accountStatus, city, embedding (16-d, isVector), location (isGeo)
 *   Merchant - name, category, embedding (16-d, isVector), location (isGeo)
 *   Payment  - Account -> Account, with status / amount / currency
 *   Visited  - Account -> Merchant, with times
 */
const fs = require('fs');
const path = require('path');

const IDS = { account: 1, merchant: 2, payment: 1, visited: 2 };
const CONSTRAINTS = { idSchemaIndex: 0, createdTimeIndex: -2, lastUpdatedTimeIndex: -1 };

const meta = (id, name, description, type) => ({ id, name, description, type });

const account = () => ({
  id: IDS.account,
  name: 'Account',
  description: 'A person with a spending profile (embedding) and a home location',
  idSchema: meta(0, 'id', 'Account id', 'STRING'),
  createdTime: meta(-2, 'createdTime', 'Creation time', 'LONG'),
  lastUpdatedTime: meta(-1, 'lastUpdatedTime', 'Last update time', 'LONG'),
  properties: [
    { id: 1, name: 'name', description: 'Display name', type: 'STRING', display: true },
    { id: 2, name: 'accountStatus', description: 'Lifecycle state', type: { enum: ['ACTIVE', 'FROZEN', 'CLOSED'] }, display: true },
    { id: 3, name: 'city', description: 'City the account is registered in', type: 'STRING' },
    {
      id: 4, name: 'embedding', description: 'The 16-dimensional spending profile (HNSW indexed)',
      type: { list: 'DOUBLE' }, isVector: true, metric: 'COSINE', m: 8, efConstruction: 64, efSearch: 32,
    },
    {
      id: 5, name: 'location', description: 'Where the account lives, as {lat, lon}; indexed with Google S2 cells',
      type: { map: 'DOUBLE' }, isGeo: true,
    },
  ],
});

const merchant = () => ({
  id: IDS.merchant,
  name: 'Merchant',
  description: 'A shop with a category, a profile (embedding) and a location',
  idSchema: meta(0, 'id', 'Merchant id', 'STRING'),
  createdTime: meta(-2, 'createdTime', 'Creation time', 'LONG'),
  lastUpdatedTime: meta(-1, 'lastUpdatedTime', 'Last update time', 'LONG'),
  properties: [
    { id: 1, name: 'name', description: 'Shop name', type: 'STRING', display: true },
    { id: 2, name: 'category', description: 'Shop category', type: 'STRING', display: true },
    {
      id: 3, name: 'embedding', description: 'The 16-dimensional shop profile (HNSW indexed)',
      type: { list: 'DOUBLE' }, isVector: true, metric: 'COSINE', m: 8, efConstruction: 64, efSearch: 32,
    },
    {
      id: 4, name: 'location', description: 'Where the shop is, as {lat, lon}; indexed with Google S2 cells',
      type: { map: 'DOUBLE' }, isGeo: true,
    },
  ],
});

const payment = () => ({
  id: IDS.payment,
  name: 'Payment',
  description: 'A payment from one account to another',
  idSchema: meta(0, 'id', 'Payment id', 'STRING'),
  source: { id: 1, name: 'source', description: 'The paying account', type: 'STRING', vertex: 'Account' },
  target: { id: 2, name: 'target', description: 'The receiving account', type: 'STRING', vertex: 'Account' },
  createdTime: meta(-2, 'createdTime', 'Creation time', 'LONG'),
  lastUpdatedTime: meta(-1, 'lastUpdatedTime', 'Last update time', 'LONG'),
  direction: 'DIRECTED',
  properties: [
    { id: 3, name: 'status', description: 'Payment state', type: { enum: ['PENDING', 'SUCCESS', 'FAILED'] }, display: true },
    { id: 4, name: 'amount', description: 'Amount', type: 'DOUBLE' },
    { id: 5, name: 'currency', description: 'Currency code', type: 'STRING' },
  ],
});

const visited = () => ({
  id: IDS.visited,
  name: 'Visited',
  description: 'An account has been to a shop',
  idSchema: meta(0, 'id', 'Visit id', 'STRING'),
  source: { id: 1, name: 'source', description: 'The account', type: 'STRING', vertex: 'Account' },
  target: { id: 2, name: 'target', description: 'The shop', type: 'STRING', vertex: 'Merchant' },
  createdTime: meta(-2, 'createdTime', 'Creation time', 'LONG'),
  lastUpdatedTime: meta(-1, 'lastUpdatedTime', 'Last update time', 'LONG'),
  direction: 'DIRECTED',
  properties: [
    { id: 3, name: 'times', description: 'How often the account visited', type: 'LONG' },
  ],
});

const schema = (id, name, description, storage) => {
  const out = {
    id,
    name,
    description,
    constraints: CONSTRAINTS,
    vertexSchemas: [account(), merchant()],
    edgeSchemas: [payment(), visited()],
  };
  if (storage) out.storage = storage;
  return out;
};

const target = path.join('graph-http', 'src', 'main', 'resources');
const files = [
  ['payment-graph-smart.json', schema(41, 'payment-graph-smart',
    'Accounts and merchants with an embedding and a location: vectors on Aerospike, geo cells on RocksDB', null)],
  ['payment-graph-smart-rocksdb.json', schema(42, 'payment-graph-smart-rocksdb',
    'The same graph in RocksDB-Inline, where the S2 geo index is a real index',
    { type: 'RocksDB-Inline', path: 'payment-graph-smart-rocksdb', regionCount: 512, maxVersions: 1, cacheEdges: true })],
];

for (const [file, body] of files) {
  const out = path.join(target, file);
  fs.writeFileSync(out, JSON.stringify(body, null, 2) + '\n');
  console.log(`${out}: ${body.vertexSchemas.length} vertex types, ${body.edgeSchemas.length} edge types`);
}
