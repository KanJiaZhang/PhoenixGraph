// needs: service
// what: the answer-level ledger for the data agent - five categories of question, each asserted against what the
//       answer must contain. This is the piece the market comparison said was missing everywhere: 967 unit tests
//       and 380 live assertions all check *behaviour*, and none of them checks whether an answer is right.
//
// The categories, and why each one is here:
//
//   1. correct        - the number matches a baseline this repository verified by hand (1996 revenue), and the
//                       answer carries the provenance that lets a reader re-derive it
//   2. must refuse    - questions the model cannot answer (a dimension that may not slice the metric, a grain on
//                       a non-date dimension): a refusal with the reason is the correct answer, and a plausible
//                       total is the wrong one
//   3. underspecified - "top 5" without a dimension to rank: the agent must ask rather than pick one
//   4. unknown term   - a word the model does not know: the ledger records what happens *today* (the term is
//                       ignored) as a known gap rather than pretending it is handled
//   5. stale/absent   - a year the data does not cover (2026): the answer must not read as "revenue was zero"
//                       without saying so, which is also recorded as a gap because the warning is not there yet
//
// A failure here is a product failure: an answer that is wrong, or right for the wrong reason, is worse than a
// refusal. The gaps are printed as GAP lines, counted, and never silently pass.
const API = 'http://localhost:8081';
const GRAPH = 'tpc-h-analytics';
const REVENUE_1996 = 98879432.89;

let failures = 0;
let gaps = 0;
const check = (name, ok, detail) => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};
const gap = (name, detail) => {
  gaps += 1;
  console.log(`GAP  ${name}${detail ? ' - ' + detail : ''}`);
};
const ask = async (body, path = 'semantic') => {
  const response = await fetch(`${API}/api/v1/${path}/${GRAPH}/ask`, {
    method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* reported when a category needs the raw body */
  }
  return { status: response.status, text, json };
};
const total = (answer) => answer?.json?.answer?.total;
const close = (actual, expected, tolerance = 1) => Math.abs((actual ?? 0) - expected) <= tolerance;

// ---- 1. correct ---------------------------------------------------------------------------------------
console.log('--- 1. correct');
const correct = await ask({ question: '1996 年各地区营收' });
check('the number matches the verified baseline', close(total(correct), REVENUE_1996), String(total(correct)));
check('and the answer carries the traversal it ran',
  /has\('orderYear', 1996\)/.test(correct.json?.provenance?.traversal || ''),
  String(correct.json?.provenance?.traversal || '').slice(0, 70));
check('and the rows it was computed from (which sum to the total)',
  Math.abs((correct.json?.answer?.rows || []).reduce((sum, row) => sum + row.measure, 0) - REVENUE_1996) <= 1,
  `${correct.json?.answer?.rows?.length} rows`);
check('and the plan a reader can check against the model',
  correct.json?.plan?.metric === 'metric/revenue' && correct.json?.plan?.dimension === 'dim/region'
  && correct.json?.plan?.fromYear === 1996,
  JSON.stringify(correct.json?.plan));

// ---- 2. must refuse -----------------------------------------------------------------------------------
console.log('--- 2. must refuse');
const notAllowed = await ask({ question: '1996 年各退货标志营收' });
check('a dimension that may not slice the metric is refused, with the dimensions that do',
  !!notAllowed.json?.refusal && notAllowed.json.refusal.includes('may not slice'),
  String(notAllowed.json?.refusal || '').slice(0, 90));
check('and no total is returned alongside it',
  notAllowed.json?.answer === undefined, notAllowed.json?.answer === undefined ? 'no answer' : 'AN ANSWER CAME BACK');
const badGrain = await ask({ question: '1996 年各地区月度营收' });
check('a grain on a dimension that is not a date is refused',
  (badGrain.json?.refusal || '').includes('not a date'),
  String(badGrain.json?.refusal || '').slice(0, 90));
const noMetric = await ask({ question: '今天天气怎么样' });
check('a question with no metric in it is refused with the vocabulary',
  !!noMetric.json?.refusal && /the model holds/.test(noMetric.json.refusal),
  String(noMetric.json?.refusal || '').slice(0, 80));

// ---- 3. underspecified --------------------------------------------------------------------------------
console.log('--- 3. underspecified');
const topWithoutDimension = await ask({ question: '1996 年营收 top 5' });
check('"top 5" without a dimension to rank is refused rather than ranked by a guess',
  !!topWithoutDimension.json?.refusal,
  String(topWithoutDimension.json?.refusal || JSON.stringify(topWithoutDimension.json).slice(0, 90)).slice(0, 100));

// ---- 4. unknown term (recorded, not pretended) --------------------------------------------------------
console.log('--- 4. unknown term');
const unknownTerm = await ask({ question: '1996 年各渠道营收' });
check('a breakdown by a dimension the model has no word for is refused, not answered as a total',
  !!unknownTerm.json?.refusal && /breakdown/.test(unknownTerm.json.refusal)
  && /dimensions that may slice/.test(unknownTerm.json.refusal),
  String(unknownTerm.json?.refusal || `total=${total(unknownTerm)}`).slice(0, 110));
check('and a question that asks for one number is still answered',
  !!correct.json?.answer && correct.json.answer.total > 0, String(total(correct)));

// ---- 5. stale / absent data ---------------------------------------------------------------------------
console.log('--- 5. a year the data does not cover');
const absent = await ask({ question: '2026 年各地区营收' });
if (close(total(absent), 0)) {
  gap('a year outside the data answers 0 without saying the data does not cover it: a reader cannot tell '
    + '"no revenue in 2026" from "no data for 2026"',
    `total=${total(absent)} rows=${absent.json?.answer?.rows?.length}`);
} else {
  check('a year outside the data is not answered as zero', true, String(total(absent)));
}

// ---- the ledger ---------------------------------------------------------------------------------------
console.log('');
console.log(`${failures} failure(s), ${gaps} recorded gap(s)`);
console.log(failures === 0
  ? 'every answer that should be right matches its verified baseline, and every answer that should be refused is'
  : 'the answer-level ledger has failures: an answer is wrong, or right for the wrong reason');
process.exit(failures === 0 ? 0 : 1);
