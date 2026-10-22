// needs: service
// what: the marketing demo of the semantic layer - a second semantic graph (demo-semantics) over a second fact
//       graph (demo-data), proving the runner, the interpreter and the renderer are not hardcoded to TPC-H. The
//       baselines are the deterministic fact instance (DemoMarketingGraph, seed 7), so a wrong number here is a
//       wrong number, not a different-but-plausible one.
//
//       The demo also pins the two sides of the binding rule: a base metric (spend) binds to a fact property and
//       answers; a ratio metric (cac) binds nowhere and is refused with the warehouse SQL, never guessed.
const API = 'http://localhost:8081';
const GRAPH = 'demo-semantics';
const SPEND_2025 = 14731149.38;   // SUM(spend) over 2025, deterministic
const SPEND_2024 = 14754787.14;   // SUM(spend) over 2024, deterministic

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
  try { json = JSON.parse(text); } catch { /* non-JSON bodies are a finding in themselves */ }
  return { status: response.status, text, json };
};
const close = (a, b, tol = 1) => Math.abs((a ?? 0) - b) <= tol;

// ---- 1. the model is the vocabulary, with the binding on the nodes --------------------------------
console.log('--- the model');
const model = await (await fetch(`${API}/api/v1/semantic/${GRAPH}/model`)).json();
check('the model answers for the marketing semantic graph',
  model.graph === GRAPH && model.counts.metrics === 10 && model.counts.dimensions === 4,
  `${model.counts?.metrics} metrics / ${model.counts?.dimensions} dimensions`);
const spend = model.metrics.find((m) => m.id === 'metric/spend');
const cac = model.metrics.find((m) => m.id === 'metric/cac');
const date = model.dimensions.find((d) => d.id === 'dim/date');
check('a base metric carries its fact property on the node (graphProperty)',
  spend.graphProperty === 'spend' && spend.aggFunction === 'SUM' && spend.unit === '元',
  JSON.stringify(spend.graphProperty));
check('a ratio metric binds nowhere, so the runner will refuse rather than guess',
  (cac.graphProperty === null || cac.graphProperty === undefined)
  && /SUM\(spend\) \/ SUM\(new_customers\)/.test(cac.expression),
  `graphProperty=${JSON.stringify(cac.graphProperty)}`);
check('the date dimension binds a year grain, so the year filter can find it',
  date.graphProperty && date.graphProperty.year === 'year' && date.graphProperty.month === 'month',
  JSON.stringify(date.graphProperty));

// ---- 2. 智能问数 over a base metric ------------------------------------------------------------------
console.log('--- 智能问数 (base metric)');
const asked = await post(`/api/v1/semantic/${GRAPH}/ask`, { question: '2025 年各渠道花费' });
check('a base metric is answered, not refused',
  asked.status === 200 && !asked.json.refusal && !asked.json.executionRefused && !!asked.json.answer,
  asked.json?.refusal || asked.json?.executionRefused || `http ${asked.status}`);
if (asked.json.answer) {
  check('the plan names the marketing metric and dimension',
    asked.json.plan.metric === 'metric/spend' && asked.json.plan.dimension === 'dim/channel',
    `${asked.json.plan.metric} by ${asked.json.plan.dimension}`);
  check(`the total is the deterministic 2025 spend (${SPEND_2025})`,
    close(asked.json.answer.total, SPEND_2025, 1), String(asked.json.answer.total));
  check('it groups into the four channels and the rows sum to the total',
    asked.json.answer.rows.length === 4
    && close(asked.json.answer.rows.reduce((s, r) => s + r.measure, 0), asked.json.answer.total, 1),
    asked.json.answer.rows.map((r) => `${r.value}=${Math.round(r.measure)}`).join(' '));
  check('the traversal a reader can repeat groups the fact vertices by the bound property',
    /g\.V\('MarketingDay'\)\.has\('year', 2025\)\.group\(\)\.by\('channel'\)\.by\(values\('spend'\)\.sum\(\)\)/
      .test(asked.json.provenance.traversal),
    asked.json.provenance.traversal);
}

// ---- 3. a ratio metric is refused with the SQL, not guessed ------------------------------------------
console.log('--- the ratio metric (refused, SQL available)');
const ratio = await post(`/api/v1/semantic/${GRAPH}/ask`, { question: '2025 年各渠道获客成本' });
check('a ratio metric is refused with the reason rather than answered as a sum',
  !!ratio.json.executionRefusal === false && !!ratio.json.executionRefused
  && /neither the model nor the binding says which property carries it/.test(ratio.json.executionRefused),
  String(ratio.json.executionRefused).slice(0, 90));
check('the refusal still carries the warehouse SQL a reader can run',
  /SUM\(marketing\.spend\) \/ SUM\(marketing\.new_customers\) AS cac/.test(ratio.json.sql || ''),
  (ratio.json.sql || '').slice(0, 90));
const translated = await post(`/api/v1/semantic/${GRAPH}/translate`,
  { metric: 'metric/cac', dimension: 'dim/channel' });
check('the translate endpoint renders the ratio the warehouse computes',
  /SUM\(marketing\.spend\) \/ SUM\(marketing\.new_customers\) AS cac/.test(translated.json.statement || ''),
  translated.json.statement);

// ---- 4. a count metric answers with numbers -----------------------------------------------------------
console.log('--- a count metric');
const orders = await post(`/api/v1/semantic/${GRAPH}/ask`, { question: '2025 年各渠道订单数' });
check('a count metric groups into numbers (row count per channel)',
  orders.json.answer && orders.json.answer.rows.length === 4
  && orders.json.answer.rows.every((r) => typeof r.measure === 'number' && r.measure > 0),
  JSON.stringify(orders.json.answer?.rows || []).slice(0, 120));

// ---- 5. 业务归因 over the marketing metric -----------------------------------------------------------
console.log('--- 业务归因');
const attributed = await post(`/api/v1/analysis/${GRAPH}/attribute`,
  { question: '2024 到 2025 年各渠道花费为什么变化' });
const attribution = attributed.json?.attribution;
check('the attribution decomposes the change over the channel',
  !!attribution && attribution.contributions.length === 4,
  attribution ? `${attribution.contributions.length} contributions` : String(attributed.json).slice(0, 120));
if (attribution) {
  check('the two endpoints are the deterministic years',
    close(attribution.totalBefore, SPEND_2024, 1) && close(attribution.totalAfter, SPEND_2025, 1),
    `${attribution.totalBefore} -> ${attribution.totalAfter}`);
  check('the contributions add up to the change (the answer says so itself)',
    attribution.decompositionAddsUp === true
    && close(attribution.contributionsSum, Math.round((SPEND_2025 - SPEND_2024) * 100) / 100, 1),
    `sum=${attribution.contributionsSum} addsUp=${attribution.decompositionAddsUp}`);
}

console.log('');
console.log(failures === 0
  ? 'the marketing demo answers base metrics from the graph, refuses ratio metrics with the SQL, and attributes a change'
  : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
