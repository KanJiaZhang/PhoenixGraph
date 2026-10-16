// needs: service
// what: the data-agent endpoints - 智能问数 (ask), 业务分析 (analyze) and 业务归因 (attribute) - with their two
//       modes. The numbers are asserted against baselines this repository verified earlier (1996 revenue
//       98,879,432.89, and the 1995->1996 change of 3,266,638.84), because an answer that is merely plausible is
//       the failure mode this whole layer exists to avoid.
//
// Written with fetch rather than curl on purpose: the request bodies carry Chinese, and a shell that mangles a
// UTF-8 body (or a path that crosses MSYS and Windows spellings) turns every call into a 400 that looks like a
// server bug. A suite has to be immune to the tooling it is typed into.
const API = 'http://localhost:8081';
const GRAPH = 'tpc-h-analytics';
const REVENUE_1995 = 95612794.05;
const REVENUE_1996 = 98879432.89;
const CHANGE = Math.round((REVENUE_1996 - REVENUE_1995) * 100) / 100;

let failures = 0;
const check = (name, ok, detail) => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};

const post = async (path, body) => {
  const response = await fetch(API + path, {
    method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* the body is reported below when it is not JSON */
  }
  return { status: response.status, text, json };
};
const close = (actual, expected, tolerance = 0.01) => Math.abs(actual - expected) <= tolerance;

// ---- 1. 智能问数, auto mode ---------------------------------------------------------------------------
console.log('--- 智能问数 (auto)');
const asked = await post(`/api/v1/semantic/${GRAPH}/ask`, { question: '1996 年各地区营收' });
check('the question is answered, not refused',
  asked.status === 200 && !asked.json.refusal && !asked.json.executionRefused,
  asked.json?.refusal || asked.json?.executionRefused || `http ${asked.status}`);
if (asked.json?.answer) {
  check('the metric, dimension and grain are the ones the question names',
    asked.json.plan.metric === 'metric/revenue' && asked.json.plan.dimension === 'dim/region'
    && asked.json.plan.grain === 'year',
    `${asked.json.plan.metric} by ${asked.json.plan.dimension} at ${asked.json.plan.grain}`);
  check('the answer is computed from the fact graph, by a traversal a reader can repeat',
    asked.json.executedOn === 'tpc-h-data'
    && /group\(\)\.by\('region'\)/.test(asked.json.provenance.traversal),
    String(asked.json.provenance.traversal).slice(0, 80));
  check(`the total is the verified 1996 revenue (${REVENUE_1996})`,
    close(asked.json.answer.total, REVENUE_1996, 1), String(asked.json.answer.total));
  check('it is grouped into the five regions of the model',
    asked.json.answer.rows.length === 5
    && asked.json.answer.rows.every((row) => typeof row.value === 'string' && row.measure > 0),
    asked.json.answer.rows.map((row) => `${row.value}=${Math.round(row.measure)}`).join(' '));
  check('the answer carries its plan and provenance',
    !!asked.json.plan && !!asked.json.provenance.measureProperty && !!asked.json.sql,
    `measure=${asked.json.provenance.measureProperty}`);
}

// ---- 2. confirm mode: the plan comes back, nothing runs, the token executes it -------------------------
console.log('--- 确认模式 (confirm)');
const confirming = await post(`/api/v1/semantic/${GRAPH}/ask`,
  { question: '1996 年各地区营收', mode: 'confirm' });
check('confirm mode returns the plan and a token, and runs nothing',
  confirming.status === 200 && confirming.json.mode === 'confirm' && !!confirming.json.confirmToken
  && confirming.json.answer === undefined && confirming.json.plan.metric === 'metric/revenue',
  `mode=${confirming.json?.mode} token=${String(confirming.json?.confirmToken || '').slice(0, 16)}... answer=${confirming.json?.answer === undefined ? 'absent' : 'PRESENT'}`);
const executed = await post(`/api/v1/semantic/${GRAPH}/ask`,
  { question: '1996 年各地区营收', mode: 'confirm', confirmToken: confirming.json?.confirmToken });
check('confirming with the token from the plan executes it',
  close(executed.json?.answer?.total ?? 0, REVENUE_1996, 1),
  String(executed.json?.answer?.total ?? executed.json?.error ?? 'no answer'));
const tampered = await post(`/api/v1/semantic/${GRAPH}/ask`,
  { question: '1995 年各地区营收', mode: 'confirm', confirmToken: confirming.json?.confirmToken });
check('a token issued for one plan does not execute another',
  tampered.status >= 400 && /does not match|different plan/.test(tampered.text),
  `http ${tampered.status} ${tampered.text.slice(0, 90)}`);

// ---- 3. 业务归因: the change decomposed, and the decomposition checked --------------------------------
console.log('--- 业务归因 (attribute)');
const attributed = await post(`/api/v1/analysis/${GRAPH}/attribute`,
  { question: '1995 到 1996 年各地区营收为什么变化' });
const attribution = attributed.json?.attribution;
check('the attribution endpoint decomposes the change over the dimension',
  !!attribution && attribution.contributions.length === 5,
  attribution ? `${attribution.contributions.length} contributions` : JSON.stringify(attributed.json).slice(0, 160));
if (attribution) {
  check('the endpoints are the two verified years',
    close(attribution.totalBefore, REVENUE_1995, 1) && close(attribution.totalAfter, REVENUE_1996, 1),
    `${attribution.totalBefore} -> ${attribution.totalAfter}`);
  check(`the change is the verified ${CHANGE}`,
    close(attribution.delta, CHANGE, 1), String(attribution.delta));
  check('the contributions add up to the change (the answer says so itself)',
    attribution.decompositionAddsUp === true && close(attribution.contributionsSum, CHANGE, 1),
    `sum=${attribution.contributionsSum} addsUp=${attribution.decompositionAddsUp}`);
  check('they are ranked by size, so the largest movers come first',
    attribution.contributions.every((entry, index, all) => index === 0
      || Math.abs(all[index - 1].delta) >= Math.abs(entry.delta)),
    attribution.contributions.slice(0, 3).map((entry) => `${entry.value}:${entry.delta}`).join(' '));
  check('the answer says it is descriptive rather than causal',
    attribution.limits.some((line) => /descriptive/i.test(line)),
    attribution.limits[0].slice(0, 80));
}

// ---- 4. 业务分析: the movers, the shares and the outlier score ---------------------------------------
console.log('--- 业务分析 (analyze)');
const analyzed = await post(`/api/v1/analysis/${GRAPH}/analyze`,
  { question: '1995 到 1996 年各地区营收变化' });
const analysis = analyzed.json?.analysis;
check('the analysis reports the change and its direction',
  !!analysis && close(analysis.delta, CHANGE, 1) && analysis.direction === 'up'
  && analysis.movers.length === 5,
  analysis ? `delta=${analysis.delta} direction=${analysis.direction}` : JSON.stringify(analyzed.json).slice(0, 160));
if (analysis) {
  check('every mover carries both periods, the delta and both shares',
    analysis.movers.every((mover) => mover.delta !== undefined && mover.shareBefore !== undefined
      && mover.shareAfter !== undefined),
    JSON.stringify(analysis.movers[0]));
  check('the concentration reading is the largest slice\u2019s share',
    typeof analysis.concentrationAfter === 'number' && analysis.concentrationAfter > 0
    && analysis.concentrationAfter <= 100,
    String(analysis.concentrationAfter));
}

// ---- 5. refusals are information ----------------------------------------------------------------------
console.log('--- 拒绝规则');
const grainOnName = await post(`/api/v1/semantic/${GRAPH}/ask`, { question: '1996 年各地区月度营收' });
check('a grain is refused on a dimension that is not a date, rather than applied silently',
  (grainOnName.json?.refusal || '').includes('not a date'),
  String(grainOnName.json?.refusal || JSON.stringify(grainOnName.json)).slice(0, 120));
const unbound = await post(`/api/v1/semantic/${GRAPH}/ask`, { question: '1996 年各退货标志营收' });
check('an unbound dimension is refused with the reason and keeps the SQL',
  !!unbound.json?.executionRefused && !!unbound.json.sql,
  String(unbound.json?.executionRefused || JSON.stringify(unbound.json)).slice(0, 140));
const noMetric = await post(`/api/v1/semantic/${GRAPH}/ask`, { question: '今年的天气怎么样' });
check('a question with no metric in it is refused with the vocabulary',
  !!noMetric.json?.refusal && noMetric.json.refusal.includes('gross_revenue'),
  String(noMetric.json?.refusal || '').slice(0, 120));
const empty = await post(`/api/v1/semantic/${GRAPH}/ask`, {});
check('a request without a question is 400 and says so',
  empty.status === 400 && empty.text.includes('question must not be blank'), `http ${empty.status}`);
const badMode = await post(`/api/v1/semantic/${GRAPH}/ask`, { question: '1996 年各地区营收', mode: 'maybe' });
check('an unknown mode is refused instead of guessed',
  badMode.status === 400 && /mode must be auto or confirm/.test(badMode.text),
  `http ${badMode.status} ${badMode.text.slice(0, 80)}`);

console.log('');
console.log(failures === 0
  ? 'a question is answered from the graph with its plan and provenance, in auto or confirmed mode, and refused when it cannot be'
  : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
