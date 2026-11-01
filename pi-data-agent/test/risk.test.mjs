import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  parseCsv, readRiskCases, loadRiskManifest, metricValue, groupMetric, periodsOf, riskSummary,
  enrichRing, riskAttribution, renderRiskAttribution, riskDashboard, renderRiskDashboardMarkdown,
  renderRiskDashboardHtml, saveRiskDashboard,
} from '../src/risk.mjs';

// The examples are read relative to the package, not to the shell (same reason as tests/extract.test.mjs).
const ROOT = fileURLToPath(new URL('..', import.meta.url));
const CASES = join(ROOT, 'examples/risk-control/risk-cases.csv');
const MANIFEST = join(ROOT, 'examples/risk-control/risk-model.json');

const ledger = () => readRiskCases(CASES);
const manifest = () => loadRiskManifest(MANIFEST);

/**
 * A client that answers by the shape of the query, so the test drives the same queries enrichRing really sends.
 * `overrides` replaces one answer or makes it throw, which is how a partial graph is simulated.
 */
function fakeClient(overrides = {}) {
  const queries = [];
  const answers = {
    count: [40],
    payers: [{ id: 'buyer-1' }, { id: 'buyer-2' }, { id: 'buyer-3' }],
    receivers: [{ id: 'collector-9' }],
    payments: [{ id: 'p1', amount: 1200, status: 'SUCCESS' }, { id: 'p2', amount: 800, status: 'SUCCESS' }],
    links: [{ id: 'fl-a', key: 'mail.ru|10.20|621700', patternId: 0, pattern: 'mail-domain' }],
    members: [{ id: 'fz_a1', firstName: 'Ann' }, { id: 'fz_a2', firstName: 'Bob' }, { id: 'fz_a3', firstName: 'Cid' }],
  };
  const send = async (query) => {
    queries.push(query);
    const pick = query.includes('count()') ? 'count'
      : query.includes("bothE('Payment').otherV()") ? 'payers'
        : query.includes("outE('Payment').otherV().dedup()") ? 'receivers'
          : query.includes("outE('Payment').elementMap()") ? 'payments'
            : query.includes("inE('FuzzyLinkedTo')") || query.includes('.fuzzyLink(') ? 'links'
              : 'members';
    if (overrides[pick] instanceof Error) throw overrides[pick];
    if (overrides[pick] !== undefined) return overrides[pick];
    return answers[pick];
  };
  return { queries, query: async (_graph, query) => send(query) };
}

test('the CSV reader keeps quotes, embedded commas and CRLF line ends', () => {
  const { header, rows } = parseCsv('a,b,c\r\n1,"x,y",3\r\n"line\nbreak",2,4\r\n');
  assert.deepEqual(header, ['a', 'b', 'c']);
  assert.equal(rows.length, 2);
  assert.equal(rows[0].b, 'x,y');
  assert.equal(rows[1].a, 'line\nbreak');
  assert.deepEqual(parseCsv(''), { header: [], rows: [] });
});

test('a ledger is read from the example CSV, from inline CSV and from JSON, and numbers stay numbers', () => {
  const fromFile = ledger();
  assert.equal(fromFile.rows.length, 120);
  assert.equal(typeof fromFile.rows[0].exposure, 'number');
  assert.deepEqual(fromFile.columns.slice(0, 3), ['period', 'channel', 'rule']);

  const inline = readRiskCases('period,ring,exposure\n2025-09,ring_a,10\n');
  assert.deepEqual(inline.rows, [{ period: '2025-09', ring: 'ring_a', exposure: 10 }]);

  const fromJson = readRiskCases([{ period: '2025-09', exposure: '12', note: 'x' }]);
  assert.equal(fromJson.rows[0].exposure, 12);
  assert.equal(fromJson.rows[0].note, 'x');

  assert.throws(() => readRiskCases('not-a-file.csv'), /neither a file nor inline/);
  assert.throws(() => readRiskCases('  '), /empty input/);
});

test('the manifest defaults a metric to SUM and refuses one without metrics', () => {
  const example = manifest();
  assert.equal(example.metrics.exposure.agg, 'SUM');
  assert.equal(example.metrics.review_hours.agg, 'AVG');
  assert.deepEqual(Object.keys(example.rings), ['ring_a', 'ring_b', 'none']);
  assert.deepEqual(loadRiskManifest({ metrics: { x: { column: 'x' } } }).dimensions, {});
  assert.throws(() => loadRiskManifest({ dimensions: {} }), /metrics are required/);
  assert.throws(() => loadRiskManifest('nope.json'), /not a file or JSON/);
});

test('each aggregation is computed, and a ratio with a zero denominator is null rather than infinity', () => {
  const rows = [{ a: 10, b: 4, n: 1 }, { a: 20, b: 6, n: 3 }];
  assert.equal(metricValue(rows, { agg: 'SUM', column: 'a' }), 30);
  assert.equal(metricValue(rows, { agg: 'COUNT' }), 2);
  assert.equal(metricValue(rows, { agg: 'AVG', column: 'a' }), 15);
  assert.equal(metricValue(rows, { agg: 'RATIO', numerator: 'n', denominator: 'b' }), (100 * 4) / 10);
  assert.equal(metricValue(rows, { agg: 'RATIO', numerator: 'n', denominator: 'a' }), 400 / 30);
  assert.equal(metricValue([{ n: 0, b: 0 }], { agg: 'RATIO', numerator: 'n', denominator: 'b' }), null);
  assert.equal(metricValue([], { agg: 'SUM', column: 'a' }), 0);
});

test('a breakdown sorts by measure and keeps how many rows each slice came from', () => {
  const rows = [
    { ring: 'a', exposure: 5 }, { ring: 'b', exposure: 30 }, { ring: 'b', exposure: 10 }, { ring: 'a', exposure: 1 },
  ];
  const grouped = groupMetric(rows, { column: 'exposure' }, { column: 'ring' });
  assert.deepEqual(grouped.map((item) => [item.value, item.measure, item.rows]), [['b', 40, 2], ['a', 6, 2]]);
  assert.deepEqual(periodsOf(rows.map((row, index) => ({ ...row, period: index < 2 ? '2025-10' : '2025-09' }))), ['2025-09', '2025-10']);
});

test('the KPIs of a period are the ledger sums, and the rates are derived from them', () => {
  const rows = ledger().rows;
  const definitions = manifest();
  assert.deepEqual(riskSummary(rows, definitions, '2025-09'), {
    period: '2025-09', rows: 60, exposure: 1000000, loss: 80000, blocked: 250000.02,
    cases: 120, fp_cases: 18, block_rate: 25, fp_rate: 15, review_hours: 4.34,
  });
  const october = riskSummary(rows, definitions, '2025-10');
  assert.equal(october.exposure, 1420000);
  assert.equal(october.cases, 180);
  assert.equal(october.fp_rate, 25);
  assert.equal(october.block_rate, 43.66);
  // an empty period is zero exposure and a null rate, not a measured zero block rate
  const missing = riskSummary(rows, definitions, '2030-01');
  assert.equal(missing.rows, 0);
  assert.equal(missing.block_rate, null);
});

test('the attribution decomposes the whole delta, ranks the rings, and says it is not causal', async () => {
  const rows = ledger().rows;
  const bundle = await riskAttribution(null, rows, manifest());
  assert.equal(bundle.from, '2025-09');
  assert.equal(bundle.to, '2025-10');
  assert.equal(bundle.primary.delta, 420000);
  assert.equal(bundle.primary.contributionsSum, 420000);
  assert.equal(bundle.primary.decompositionAddsUp, true);
  assert.deepEqual(bundle.primary.contributions.map((item) => [item.value, item.shareOfChange]).slice(0, 3),
    [['ring_a', 57.14], ['ring_b', 28.57], ['none', 14.29]]);
  assert.ok(bundle.limits.some((limit) => /描述性/.test(limit)));
  assert.ok(bundle.limits.some((limit) => /FuzzyLink/.test(limit)));
  // three explanations, best first, each with the checks that support it and what would overturn it
  assert.deepEqual(bundle.hypotheses.map((item) => item.id), ['rule-sensitivity', 'loss-improvement', 'ring-reentry']);
  assert.ok(bundle.hypotheses[0].score > bundle.hypotheses[1].score);
  assert.ok(bundle.hypotheses.every((item) => item.checks.length > 0 && item.status));
  assert.ok(bundle.hypotheses[0].support.some((line) => /DEVICE_SHARE|shareOfChange/.test(line)));
  // without a graph client the ring evidence is refused with a reason rather than reported as zeros
  assert.ok(bundle.ringEvidence.every((ring) => ring.executed === false && /no PhoenixGraph client/.test(ring.reason)));
  assert.equal(bundle.hypotheses.find((item) => item.id === 'ring-reentry').status, 'weak');
  assert.match(renderRiskAttribution(bundle), /## 候选解释（带推翻条件）/);
});

test('ring evidence is what the graph returns: link key, payments and the collection receiver', async () => {
  const client = fakeClient();
  const ring = { label: 'Ring A', accounts: ['fz_a1', 'fz_a2', 'fz_a3'], patternId: 0, vertexType: 'Account' };
  const evidence = await enrichRing(client, { name: 'payment-graph', vertexType: 'Account' }, 'ring_a', ring);

  assert.equal(evidence.executed, true);
  assert.equal(evidence.verdict, 'confirmed');
  assert.deepEqual(evidence.failedChecks, []);
  assert.equal(evidence.memberCount, 3);
  assert.equal(evidence.link.key, 'mail.ru|10.20|621700');
  assert.deepEqual(evidence.payments, { count: 2, amount: 2000, statuses: ['SUCCESS'] });
  assert.deepEqual(evidence.receivers, [{ id: 'collector-9', degree: 40 }]);
  assert.equal(evidence.centrality.receiverDegree, 40);
  assert.equal(evidence.centrality.payerCount, 3);
  assert.equal(evidence.centrality.receiverDominance, 13.33);
  assert.deepEqual(evidence.members[0], { id: 'fz_a1', name: 'Ann', email: undefined, phone: undefined, ip: undefined, card: undefined });
  assert.deepEqual(evidence.evidence.map((item) => item.kind), ['members', 'fuzzy link', 'payments', 'receiver', 'centrality']);
  assert.ok(evidence.evidence.every((item) => item.query));
  // the shared-link query follows the ring's declared pattern
  assert.ok(client.queries.some((query) => query.includes('.fuzzyLink(0)')));
});

test('a graph that cannot answer one query degrades the verdict and keeps the warning', async () => {
  const client = fakeClient({
    receivers: new Error('Cluster is empty'),
    members: [{ id: 'fz_a1' }, { id: 'fz_a2' }],
  });
  const ring = { label: 'Ring B', accounts: ['fz_a1', 'fz_a2'], vertexType: 'Account' };
  const evidence = await enrichRing(client, { name: 'payment-graph' }, 'ring_b', ring, { minMembers: 3 });

  assert.equal(evidence.executed, true);
  assert.equal(evidence.verdict, 'candidate');
  assert.ok(evidence.warnings.some((warning) => /receivers: Cluster is empty/.test(warning)));
  assert.deepEqual(evidence.receivers, []);
  // two accounts is below the minimum, so the ring size check fails too - and it is reported, not smoothed over
  assert.ok(evidence.failedChecks.includes('ring size'));
  assert.ok(evidence.failedChecks.includes('single collection receiver'));
});

test('a ring the manifest maps to nothing, or no client at all, is refused with its reason', async () => {
  const noAccounts = await enrichRing(fakeClient(), { name: 'g' }, 'none', { accounts: [] });
  assert.equal(noAccounts.executed, false);
  assert.match(noAccounts.reason, /maps no accounts/);
  const noClient = await enrichRing(null, { name: 'g' }, 'ring_a', { accounts: ['a'] });
  assert.equal(noClient.executed, false);
  assert.match(noClient.reason, /no PhoenixGraph client/);
  // the queries ran and found nothing: that is a finding, and it has to name what was asked for
  const empty = await enrichRing(fakeClient({ members: [] }), { name: 'g' }, 'ring_a', { accounts: ['fz_a1', 'fz_a2'] });
  assert.equal(empty.executed, false);
  assert.equal(empty.verdict, 'candidate');
  assert.match(empty.reason, /no Account vertex for the 2 account id\(s\)/);
  assert.ok(empty.failedChecks.includes('ring size'));
});

test('the dashboard has KPI deltas, three breakdowns, the ring evidence and both renders', async () => {
  const rows = ledger().rows;
  const definitions = manifest();
  const client = fakeClient();
  const dashboard = await riskDashboard(client, rows, definitions, { period: '2025-10' });

  assert.equal(dashboard.period, '2025-10');
  assert.equal(dashboard.previous.period, '2025-09');
  assert.deepEqual(dashboard.breakdowns.map((item) => item.dimension), ['ring', 'rule', 'channel']);
  assert.equal(dashboard.deltas.exposure, 420000);
  assert.equal(dashboard.deltas.loss, -25000.01);
  assert.equal(dashboard.deltas.fpRate, 10);
  assert.ok(dashboard.evidence.some((ring) => ring.verdict === 'confirmed'));
  assert.ok(dashboard.limits.some((limit) => /描述性/.test(limit)));

  const markdown = renderRiskDashboardMarkdown(dashboard);
  assert.match(markdown, /# 风控看板 · 2025-10/);
  assert.match(markdown, /\| 风险敞口 \| 1420000 \| 1000000 \| \+420000 \|/);
  assert.match(markdown, /\| FP 率 \| 25% \| 15% \| \+10 \|/);
  assert.match(markdown, /## by ring/);

  const html = renderRiskDashboardHtml(dashboard);
  assert.match(html, /^<!doctype html>/);
  assert.match(html, /<\/html>$/);
  assert.match(html, /class="tile confirmed"/);
  assert.match(html, /FuzzyLink <code>mail\.ru\|10\.20\|621700<\/code>/);
});

test('a name that is used as a file is kept as one path segment, and a traversal is not one', async () => {
  const { pathSegment } = await import('../src/util.mjs');
  assert.equal(pathSegment('pi-data-agent-verify'), 'pi-data-agent-verify', 'a normal graph name must not be renamed');
  assert.equal(pathSegment('2025-10'), '2025-10');
  assert.equal(pathSegment('订购模型'), '订购模型', 'a unicode graph name is a file name too');
  assert.equal(pathSegment('../../x'), 'x');
  assert.equal(pathSegment('..'), 'unnamed');
  assert.equal(pathSegment(''), 'unnamed');
  assert.equal(pathSegment('a/b\\c'), 'a_b_c');
});

test('the HTML escapes what came from the ledger, and the files are written where told', () => {
  const dashboard = {
    period: '2025-10<script>alert(1)</script>',
    graph: 'payment-graph',
    previous: null,
    summary: { exposure: 1, loss: 2, blocked: 3, cases: 4, block_rate: 5, fp_rate: 6, review_hours: 7 },
    deltas: null,
    breakdowns: [{ dimension: 'ring', rows: [{ value: '<img src=x onerror=alert(1)>', measure: 9 }] }],
    evidence: [],
    limits: ['描述性，不是因果'],
    warnings: [],
  };
  const html = renderRiskDashboardHtml(dashboard);
  assert.equal(/<script>alert\(1\)<\/script>/.test(html), false);
  assert.equal(/<img src=x/.test(html), false);
  assert.match(html, /&lt;script&gt;/);

  const outDir = mkdtempSync(join(tmpdir(), 'pda-risk-'));
  try {
    const files = saveRiskDashboard(dashboard, { outDir, now: Date.parse('2026-10-30T12:00:00Z') });
    // the period becomes ONE path segment: it comes from the ledger, so '../x' must not leave the directory
    assert.equal(files.html.startsWith(outDir), true);
    assert.equal(files.html.slice(outDir.length + 1).includes('/'), false);
    assert.match(files.html, /risk-2025-10_script_alert_1_script-2026-10-30T12-00-00-000Z\.html$/);
    assert.equal(readFileSync(files.json, 'utf8').includes('"exposure": 1'), true);
    assert.equal(readFileSync(files.html, 'utf8').includes('<!doctype html>'), true);
  } finally {
    rmSync(outDir, { recursive: true, force: true });
  }
});
