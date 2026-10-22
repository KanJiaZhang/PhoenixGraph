// needs: service
// what: the end-to-end case of docs/END-TO-END.md, one script. Every number the case study quotes is
//       asserted here against the running instance, so the document is a measurement, not a promise.
const API = 'http://localhost:8081';
const REVENUE_1996 = 98879432.89;
const REVENUE_1995 = 95612794.05;
const SPEND_2025 = 14731149.38;

let failures = 0;
const check = (name, ok, detail) => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};
const post = async (path, body) => {
  const response = await fetch(API + path, {
    method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' }, body: JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* non-JSON bodies are a finding */ }
  return { status: response.status, text, json };
};
const close = (a, b, tol = 1) => Math.abs((a ?? 0) - b) <= tol;

// ---- 1. documents -> auditable graph (dry run: extraction + dedup, no write) --------------------------
console.log('--- 1. documents become entities with provenance');
const ingest = await post('/api/v1/agent/ingest', {
  graph: 'case-postmortem', apply: false, rulePack: 'incident',
  dictionary: { 'payment-service': 'Service', 'PaymentService': 'Service', 'payment service': 'Service',
    'payments-service': 'Service', 'Redis': 'Service', 'database': 'Service', 'checkout-api': 'Service',
    'Deploy 42': 'Deploy', 'Alice': 'Person', 'cache leak': 'Incident', 'outage': 'Incident' },
  documents: [{ id: 'postmortem', title: 'Checkout latency postmortem', text:
    'The payment-service depends on Redis.\nDeploy 42 caused the checkout-api outage.\n'
    + 'Alice fixed the cache leak.\nPaymentService depends on the database.\n'
    + 'The payment service depends on Redis.\npayments-service depends on Redis.\n'
    + 'The outage was caused by Deploy 42.' }],
});
const quality = ingest.json?.quality || {};
check('the dry run extracts entities and relations', ingest.json?.entities === 9 && ingest.json?.relations === 5,
  `${ingest.json?.entities} entities / ${ingest.json?.relations} relations`);
check('the three spellings of one service are merged, not counted three times',
  quality.duplicatesRemoved === 2, `duplicatesRemoved=${quality.duplicatesRemoved}`);
const merged = (quality.mergedEntities || []).find((m) => m.canonical === 'payment service');
check('the merged entity keeps the variant spellings as aliases',
  !!merged && merged.aliases.includes('payment-service') && merged.aliases.includes('PaymentService'),
  JSON.stringify(merged));
const candidate = (quality.mergeCandidates || []).find((c) => c.right === 'payments-service');
check('the one-character near-duplicate is listed for review, not merged',
  !!candidate && candidate.score === 0.8, JSON.stringify(candidate));

// ---- 2. 智能问数: the number comes with its plan, traversal and SQL --------------------------------
console.log('--- 2. 智能问数 (TPC-H)');
const asked = await post('/api/v1/semantic/tpc-h-analytics/ask', { question: '1996 年各地区营收' });
check('the answer is the verified 1996 revenue',
  close(asked.json?.answer?.total, REVENUE_1996, 1), String(asked.json?.answer?.total));
check('it carries the traversal a reader can repeat',
  /group\(\)\.by\('region'\)\.by\(values\('revenue'\)\.sum\(\)\)/.test(asked.json?.provenance?.traversal || ''),
  asked.json?.provenance?.traversal);
check('and the warehouse SQL for the same question',
  /SUM\(lineitem\.l_extendedprice \* \(1 - lineitem\.l_discount\)\)/.test(asked.json?.sql || ''),
  (asked.json?.sql || '').slice(0, 80));
const refused = await post('/api/v1/semantic/tpc-h-analytics/ask', { question: '1996 年各渠道营收' });
check('a breakdown by a dimension the model does not hold is refused, not answered as a total',
  !!refused.json?.refusal && /names no dimension this graph holds/.test(refused.json.refusal),
  String(refused.json?.refusal || '').slice(0, 80));

// ---- 3. 业务归因: the change decomposed, and the decomposition checked -------------------------------
console.log('--- 3. 业务归因 (TPC-H)');
const attributed = await post('/api/v1/analysis/tpc-h-analytics/attribute',
  { question: '1995 到 1996 年各地区营收为什么变化' });
const attribution = attributed.json?.attribution;
check('the change is the verified delta and the contributions add up',
  !!attribution && close(attribution.totalBefore, REVENUE_1995, 1)
  && close(attribution.totalAfter, REVENUE_1996, 1) && attribution.decompositionAddsUp === true,
  attribution ? `${attribution.totalBefore} -> ${attribution.totalAfter}, addsUp=${attribution.decompositionAddsUp}`
    : String(attributed.json).slice(0, 120));
check('AMERICA is the largest contributor, as the case study says',
  attribution?.contributions?.[0]?.value === 'AMERICA'
  && close(attribution.contributions[0].delta, 2261797.17, 1),
  JSON.stringify(attribution?.contributions?.[0]));

// ---- 4. the marketing demo: a second semantic graph, and a ratio that is refused ---------------------
console.log('--- 4. the marketing demo');
const spend = await post('/api/v1/semantic/demo-semantics/ask', { question: '2025 年各渠道花费' });
check('spend by channel answers the deterministic total',
  close(spend.json?.answer?.total, SPEND_2025, 1) && spend.json?.answer?.rows?.length === 4,
  `${spend.json?.answer?.total} over ${spend.json?.answer?.rows?.length} rows`);
const cac = await post('/api/v1/semantic/demo-semantics/ask', { question: '2025 年各渠道获客成本' });
check('a ratio metric is refused with the warehouse SQL, never answered as a sum',
  !!cac.json?.executionRefused
  && /SUM\(marketing\.spend\) \/ SUM\(marketing\.new_customers\)/.test(cac.json?.sql || ''),
  String(cac.json?.executionRefused || '').slice(0, 60));
const mkt = await post('/api/v1/analysis/demo-semantics/attribute',
  { question: '2024 到 2025 年各渠道花费为什么变化' });
check('the marketing attribution still decomposes and adds up',
  mkt.json?.attribution?.decompositionAddsUp === true && mkt.json.attribution.contributions.length === 4,
  `addsUp=${mkt.json?.attribution?.decompositionAddsUp}`);

// ---- 5. volume or rate: the denominator metric makes the follow-up answerable ------------------------
console.log('--- 5. volume or rate');
const vol95 = await post('/api/v1/semantic/tpc-h-analytics/ask', { question: '1995 年各运输方式订单行数' });
const vol96 = await post('/api/v1/semantic/tpc-h-analytics/ask', { question: '1996 年各运输方式订单行数' });
const late95 = await post('/api/v1/semantic/tpc-h-analytics/ask', { question: '1995 年各运输方式延迟交付' });
const late96 = await post('/api/v1/semantic/tpc-h-analytics/ask', { question: '1996 年各运输方式延迟交付' });
const mail = (rows) => (rows || []).find((r) => r.value === 'MAIL')?.measure ?? 0;
const rate = (late, volume) => Math.round((late / volume) * 10000) / 100;
const r95 = rate(mail(late95.json?.answer?.rows), mail(vol95.json?.answer?.rows));
const r96 = rate(mail(late96.json?.answer?.rows), mail(vol96.json?.answer?.rows));
check('MAIL volume fell while its late count rose, so the rate got worse (7.33% -> 9.60%)',
  close(mail(vol95.json?.answer?.rows), 709, 1) && close(mail(vol96.json?.answer?.rows), 698, 1)
  && close(mail(late95.json?.answer?.rows), 52, 1) && close(mail(late96.json?.answer?.rows), 67, 1)
  && close(r95, 7.33, 0.01) && close(r96, 9.6, 0.01),
  `MAIL ${mail(vol95.json?.answer?.rows)}->${mail(vol96.json?.answer?.rows)} rows, `
  + `${mail(late95.json?.answer?.rows)}->${mail(late96.json?.answer?.rows)} late, ${r95}% -> ${r96}%`);

console.log('');
console.log(failures === 0
  ? 'the end-to-end case holds: documents become an auditable graph, questions answer with provenance, and the follow-up reads a real rate'
  : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
