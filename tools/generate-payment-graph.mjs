#!/usr/bin/env node
/**
 * Generate the payment demo graph and load it over the HTTP write API.
 *
 *   node tools/generate-payment-graph.mjs                          # 20k accounts, 4 payments each
 *   node tools/generate-payment-graph.mjs --accounts 5000          # smaller
 *   node tools/generate-payment-graph.mjs --graph payment-graph --api http://localhost:8081
 *
 * Why it exists: the Aerospike sets are the one part of this repository that does not live in a file, so a new
 * container (or a wiped namespace) leaves `payment-graph` empty. The Java loaders in `graph-integration-test` seed
 * the *curated* demo (a handful of rings, for the fuzzy tests); this fills the graph with a distribution that the
 * query docs, the UI and the benchmarks can actually walk:
 *
 *   - accounts follow a **power law** in their payment degree (a few merchants/hubs, a long tail of payers),
 *     and the money follows a log-normal amount distribution over the last half year;
 *   - an IP is shared by 1..8 accounts and a phone by 1..4, so `in(IPToAccount).out(IPToAccount)` finds real
 *     neighbours instead of a singleton;
 *   - the three behaviour clusters (cash-like / card-like / mixed) are the 8-dimensional `accountVector` the
 *     schema indexes, so `nearest(k)` and the fuzzy patterns have something to group;
 *   - `100`, `135` and `1000` keep the degrees the docs quote (`V(135).out(Payment)` = 50 accounts,
 *     `V(1000).inE(Payment)` = its many payers).
 *
 * It is deterministic (same seed -> same graph) and additive: the ids it writes carry a family prefix, so it does
 * not overwrite the curated `fz_*` demo the fuzzy tests seed.
 */
import process from 'node:process';

const args = process.argv.slice(2);
const flag = (name, fallback) => {
  const index = args.indexOf(`--${name}`);
  return index >= 0 ? args[index + 1] : fallback;
};
const API = (flag('api', process.env.PHOENIXGRAPH_API || 'http://localhost:8081')).replace(/\/+$/, '');
const GRAPH = flag('graph', 'payment-graph');
const ACCOUNTS = Math.max(200, Number(flag('accounts', 20000)));
const SEED = Number(flag('seed', 20260101));
const BATCH = Math.max(20, Math.min(500, Number(flag('batch', 200))));
const API_KEY = flag('api-key', process.env.PHOENIXGRAPH_API_KEY || '');
const QUIET = args.includes('--quiet');
const CONCURRENCY = Math.max(1, Math.min(8, Number(flag('concurrency', 4))));
const SKIP_VECTORS = args.includes('--skip-vectors');
const VECTORS = flag('vectors', 'all'); // all | none | a number

const now = Date.now();
const DAY = 86_400_000;

/** mulberry32: deterministic, small, enough for a demo. */
function random(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}
const next = random(SEED);
const pick = (items) => items[Math.floor(next() * items.length)];
const between = (low, high) => low + next() * (high - low);
const pad = (value, width) => String(value).padStart(width, '0');
/** Log-normal: the long tail of transaction sizes (median ~ 180). */
const amount = () => Math.round(Math.exp(5.2 + next() * 0.9) * 100) / 100;
/** Zipf-ish pick over 0..n-1: small index = hub. */
const zipf = (n) => Math.min(n - 1, Math.floor(n * Math.pow(next(), 2.6)));

const FIRST = ['张', '王', '李', '赵', '陈', '刘', '杨', '黄', '周', '吴', '徐', '孙', '马', '朱', '胡', '郭', '林', '何', '高', '罗'];
const LAST = ['伟', '芳', '娜', '敏', '静', '丽', '强', '磊', '洋', '勇', '艳', '杰', '娟', '涛', '明', '超', '秀英', '霞', '平', '刚'];
const DOMAINS = ['mail.ru', 'qq.com', '163.com', 'gmail.com', 'outlook.com', 'yandex.ru', '126.com', 'hotmail.com'];
const STATUSES = ['ACTIVE', 'ACTIVE', 'ACTIVE', 'ACTIVE', 'ACTIVE', 'FROZEN', 'CLOSED'];
const PAY_STATUS = ['SUCCESS', 'SUCCESS', 'SUCCESS', 'SUCCESS', 'SUCCESS', 'PENDING', 'FAILED'];
const USAGE = [['TRANSFER'], ['TRANSFER', 'SHOPPING'], ['SHOPPING'], ['TRANSFER', 'BILL'], ['SHOPPING', 'BILL']];
const LIMITS = [{}, { daily: '5000', channel: 'MINI' }, { channel: 'APP' }, { daily: '20000', channel: 'WEB' }];

/** The 8-dimensional behaviour profile: three spending clusters, as the schema's vector property expects. */
function profile(cluster) {
  const vector = [];
  for (let index = 0; index < 8; index += 1) {
    const high = cluster === 0 ? index % 2 === 0 : cluster === 1 ? index % 2 === 1 : index % 3 === 0;
    vector.push(Number((high ? between(0.75, 0.98) : between(0.02, 0.25)).toFixed(4)));
  }
  return vector;
}

/** The account without its vector: the vector is written in a second pass (see main). */
const account = (id, cluster, index) => ({
  id,
  firstName: pick(FIRST),
  lastName: pick(LAST),
  username: `${pick(['zhang', 'xia', 'wang', 'li', 'chen', 'liu', 'sun', 'zhou'])}${1980 + (index % 40)}`,
  email: `${pad(index % 100000, 6)}@${pick(DOMAINS)}`,
  phoneNumber: `1${pick(['3', '5', '7', '8', '9'])}${pad(index % 100000000, 8)}`,
  cardNumber: `6217${pad(index % 10000, 4)}${pad((index * 7919) % 100000000, 8)}`,
  ipAddress: `10.${20 + (index % 12)}.${index % 256}.${(index * 13) % 254 + 1}`,
  accountStatus: pick(STATUSES),
  accountUsage: pick(USAGE),
  accountLimited: pick(LIMITS),
  __vector: profile(cluster),
});

const ids = {
  account: (n) => `acct-${pad(n, 6)}`,
  ip: (n) => `ip-${pad(n, 5)}`,
  phone: (n) => `phone-${pad(n, 5)}`,
};

/** The requests, in the property-index shape the batch API wants (indexes read from the served schema). */
function vertex(id, type, properties, indexes) {
  const map = indexes.vertices.get(type);
  return {
    id,
    vertexSchemaName: type,
    properties: Object.entries(properties)
      .filter(([name, value]) => map.has(name) && value != null)
      .map(([name, value]) => ({ index: map.get(name), value })),
  };
}

function edge(head, type, tail, properties, indexes) {
  const map = indexes.edges.get(type);
  return {
    edgeId: `${head}|${type}|${tail}`,
    edgeSchemaName: type,
    source: head,
    destination: tail,
    properties: Object.entries(properties || {})
      .filter(([name, value]) => map.has(name) && value != null)
      .map(([name, value]) => ({ index: map.get(name), value })),
  };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * One request, retried while the service is at its concurrency limit. `phoenixgraph.limits` admits a bounded number
 * of requests per graph (`per-graph-concurrent`, 8 by default) and answers 503 when the queue wait runs out, so a
 * loader that fires in parallel has to treat 503/429 as "slow down", not as a failure.
 */
async function request(path, body, attempt = 0) {
  const response = await fetch(`${API}${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'content-type': 'application/json', ...(API_KEY ? { authorization: `Bearer ${API_KEY}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  if ((response.status === 503 || response.status === 429) && attempt < 8) {
    await sleep(Math.min(4000, 250 * 2 ** attempt));
    return request(path, body, attempt + 1);
  }
  if (!response.ok) throw new Error(`${response.status} ${path}: ${text.slice(0, 400)}`);
  return text ? JSON.parse(text) : null;
}

function indexesOf(schema) {
  const vertices = new Map();
  const edges = new Map();
  for (const vertexSchema of schema.vertexSchemas || []) {
    vertices.set(vertexSchema.name, new Map((vertexSchema.properties || []).map((property) => [property.name, property.id])));
  }
  for (const edgeSchema of schema.edgeSchemas || []) {
    edges.set(edgeSchema.name, new Map((edgeSchema.properties || []).map((property) => [property.name, property.id])));
  }
  return { vertices, edges };
}

/**
 * The pending requests. One batch at a time is ~30 writes/s on this machine (each write is a request the service
 * validates, stores and audits); the service admits `limits.max-concurrent` requests, so a few batches in flight at
 * once is the difference between twenty minutes and two.
 */
const pending = [];
async function drain(force = false) {
  while (pending.length >= CONCURRENCY || (force && pending.length)) {
    await pending.shift();
  }
}
async function flush(batch) {
  if (!batch.vertices.length && !batch.edges.length) return;
  const body = { vertices: batch.vertices, edges: batch.edges };
  batch.vertices = [];
  batch.edges = [];
  const task = request(`/api/v1/graphs/${encodeURIComponent(GRAPH)}/batch`, body).then(() => undefined);
  pending.push(task);
  await drain();
}

async function main() {
  const schema = await request(`/api/v1/schema/${encodeURIComponent(GRAPH)}`);
  if (!schema) throw new Error(`graph '${GRAPH}' is not served (start the service first)`);
  const indexes = indexesOf(schema);
  console.log(`graph ${GRAPH} @ ${API}: ${ACCOUNTS} accounts, batch ${BATCH}, seed ${SEED}`);

  const batch = { vertices: [], edges: [] };
  let written = { vertices: 0, edges: 0, payments: 0 };
  const outDegree = new Map();
  const inDegree = new Map();
  const ipMembers = new Map();
  const phoneMembers = new Map();

  // 1. accounts, with the two documented hubs at the front so their degree is easy to arrange
  const hubs = ['100', '135', '1000'];
  const accountIds = [...hubs];
  for (let index = 0; index < ACCOUNTS - hubs.length; index += 1) accountIds.push(ids.account(index + 1));
  const vectors = new Map();
  for (const [index, id] of accountIds.entries()) {
    const cluster = index % 3;
    const properties = account(id, cluster, index);
    vectors.set(id, properties.__vector);
    delete properties.__vector;
    batch.vertices.push(vertex(id, 'Account', properties, indexes));
    written.vertices += 1;
    if (batch.vertices.length >= BATCH) await flush(batch);
  }

  // 2. IPs (one per two accounts) and phones (one per 2.5), each shared by a few accounts
  const ipCount = Math.floor(ACCOUNTS / 2);
  const phoneCount = Math.floor(ACCOUNTS / 2.5);
  for (let index = 0; index < ipCount; index += 1) {
    const id = ids.ip(index);
    batch.vertices.push(vertex(id, 'IP', { ipAddress: `10.${20 + (index % 12)}.${index % 256}.${(index * 7) % 254 + 1}` }, indexes));
    written.vertices += 1;
    ipMembers.set(id, []);
    if (batch.vertices.length >= BATCH) await flush(batch);
  }
  for (let index = 0; index < phoneCount; index += 1) {
    const id = ids.phone(index);
    batch.vertices.push(vertex(id, 'Phone', { cnCode: '86', phoneNumber: `1${pick(['3', '5', '8'])}${pad(index % 100000000, 8)}` }, indexes));
    written.vertices += 1;
    phoneMembers.set(id, []);
    if (batch.vertices.length >= BATCH) await flush(batch);
  }

  // 3. logins (account <-> ip) and phones (account <-> phone), with a skewed sharing factor
  const ipIds = [...ipMembers.keys()];
  const phoneIds = [...phoneMembers.keys()];
  for (const [index, accountId] of accountIds.entries()) {
    const shared = 1 + Math.floor(Math.pow(next(), 2) * 4); // 1..4 IPs per account, most accounts 1
    for (let step = 0; step < shared; step += 1) {
      const ipId = ipIds[zipf(ipIds.length)];
      const loginTime = now - Math.floor(next() * 180 * DAY);
      batch.edges.push(edge(accountId, 'AccountToIP', ipId, { loginTime }, indexes));
      batch.edges.push(edge(ipId, 'IPToAccount', accountId, { linkedAt: loginTime }, indexes));
      ipMembers.get(ipId).push(accountId);
      written.edges += 2;
      if (batch.edges.length >= BATCH) await flush(batch);
    }
    if (index % 4 === 0) { // three quarters of the accounts also have a phone
      const phoneId = phoneIds[zipf(phoneIds.length)];
      const linkedAt = now - Math.floor(next() * 240 * DAY);
      batch.edges.push(edge(accountId, 'AccountToPhone', phoneId, { linkedAt }, indexes));
      batch.edges.push(edge(phoneId, 'PhoneToAccount', accountId, { linkedAt }, indexes));
      phoneMembers.get(phoneId).push(accountId);
      written.edges += 2;
      if (batch.edges.length >= BATCH) await flush(batch);
    }
  }

  // 4. payments: 4 per account on average, to zipf-distributed targets (hubs receive most of them)
  const payers = accountIds.filter((id) => id !== '1000');
  const targets = accountIds.filter((id) => id !== '135'); // so V(135).out(Payment) is exactly the fifty below
  const payment = (from, to) => {
    const at = now - Math.floor(next() * 180 * DAY);
    batch.edges.push(edge(from, 'Payment', to, { amount: amount(), currency: 'CNY', status: pick(PAY_STATUS), type: pick(['TRANSFER', 'P2P', 'MERCHANT']) }, indexes));
    written.payments += 1;
    written.edges += 1;
    outDegree.set(from, (outDegree.get(from) || 0) + 1);
    inDegree.set(to, (inDegree.get(to) || 0) + 1);
  };
  // the documented hub: 135 pays exactly fifty accounts
  for (let step = 0; step < 50; step += 1) {
    payment('135', targets[zipf(targets.length)]);
    if (batch.edges.length >= BATCH) await flush(batch);
  }
  // the documented receiver: 1000 is paid by at least 120 accounts
  for (let step = 0; step < 120; step += 1) {
    payment(payers[zipf(payers.length)], '1000');
    if (batch.edges.length >= BATCH) await flush(batch);
  }
  const remaining = Math.max(0, ACCOUNTS * 4 - written.payments);
  for (let step = 0; step < remaining; step += 1) {
    payment(payers[zipf(payers.length)], targets[zipf(targets.length)]);
    if (batch.edges.length >= BATCH) await flush(batch);
  }
  await flush(batch);
  await drain(true);

  // 5. the behaviour vectors, then one index rebuild.
  //
  // Writing a vertex through the batch API also indexes its vector per row (HNSW insert), which made 20k accounts
  // take tens of minutes. A property update does NOT re-index, so the vectors go in a second pass and the index is
  // built once at the end - the same rows, one linear pass instead of 20k graph insertions.
  const vectorIndex = indexes.vertices.get('Account')?.get('accountVector');
  const vectorState = { written: 0, skipped: true, reason: null };
  if (SKIP_VECTORS) vectorState.reason = 'skipped by --skip-vectors';
  else if (VECTORS === 'none') vectorState.reason = 'skipped by --vectors none';
  else if (vectorIndex == null) vectorState.reason = 'the Account type declares no accountVector property';
  else vectorState.skipped = false;
  if (!vectorState.skipped) {
    const wanted = VECTORS === 'all' ? accountIds.length : Math.min(accountIds.length, Math.max(0, Number(VECTORS) || 0));
    const started = Date.now();
    const queue = [];
    for (const id of accountIds.slice(0, wanted)) {
      queue.push(request(`/api/v1/vertices/${encodeURIComponent(id)}/properties/${vectorIndex}`, { value: vectors.get(id) })
        .then(() => { vectorState.written += 1; }));
      if (queue.length >= CONCURRENCY * 4) await Promise.all(queue.splice(0, CONCURRENCY * 4));
    }
    await Promise.all(queue);
    vectorState.seconds = Math.round((Date.now() - started) / 100) / 10;
  }
  const vectorised = vectorState;
  // a rebuild is only needed when new vectors were written: it clears and rebuilds the whole layer set, which is
  // the expensive part of a load and holds the graph's write slots while it runs
  let rebuilt = null;
  if (vectorState.written > 0) {
    try {
      rebuilt = await request('/api/v1/vector-index/rebuild', { graph: GRAPH, vertexSchemaName: 'Account', vectorProperty: 'accountVector' });
    } catch (error) {
      rebuilt = { error: String(error.message || error).slice(0, 200) };
    }
  }

  const degrees = (map) => {
    const values = [...map.values()].sort((left, right) => left - right);
    const at = (p) => values[Math.min(values.length - 1, Math.floor(values.length * p))] ?? 0;
    return { p50: at(0.5), p90: at(0.9), p99: at(0.99), max: values[values.length - 1] ?? 0 };
  };
  const report = {
    graph: GRAPH,
    written,
    accounts: accountIds.length,
    ips: ipIds.length,
    phones: phoneIds.length,
    paymentsOut: degrees(outDegree),
    paymentsIn: degrees(inDegree),
    hub135Out: outDegree.get('135') || 0,
    hub1000In: inDegree.get('1000') || 0,
    vectorised,
    vectorIndex: rebuilt,
  };
  if (!QUIET) console.log(JSON.stringify(report, null, 2));
  return report;
}

main().then(() => process.exit(0)).catch((error) => {
  console.error(`generation failed: ${error.stack || error.message}`);
  process.exit(1);
});
