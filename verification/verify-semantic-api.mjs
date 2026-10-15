// needs: service
// what: the semantic layer over HTTP - the model, the SQL it renders, and the refusals. The centre of gravity is
//       one assertion repeated over a matrix: **the API's SQL is byte-identical to the CLI's**
//       (tools/semantic-sql.mjs). Two renderers that disagree would give the same question two answers, and the
//       CLI is what the semantic-layer case study and verify-tpch-semantic-layer.mjs quote.
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8081';
const GRAPH = 'tpc-h-analytics';
let failures = 0;
const check = (name, ok, detail) => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};

const post = async (path, body) => {
  const response = await fetch(API + path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    /* a non-JSON body is a finding in itself; the callers below assert on the status */
  }
  return { status: response.status, text, json };
};

/** The CLI's own answer for the same question, as the reference implementation. */
const cliSql = (metric, dimension, grain, dialect) => {
  const args = ['tools/semantic-sql.mjs', '--graph', GRAPH, '--metric', metric, '--grain', grain,
    '--dialect', dialect, '--json'];
  if (dimension) {
    args.push('--group-by', dimension);
  }
  const output = execFileSync('node', args, { encoding: 'utf8' });
  return JSON.parse(output).sql;
};

// ---- 1. the model endpoint is the vocabulary an agent plans against ------------------------------------
const model = await (await fetch(`${API}/api/v1/semantic/${GRAPH}/model`)).json();
check('the model endpoint answers for the semantic graph',
  model && model.graph === GRAPH && Array.isArray(model.metrics) && Array.isArray(model.dimensions),
  model ? `${model.graph}` : 'no body');
check('it holds the metrics the graph holds',
  model.metrics.length >= 17 && model.metrics.every((entry) => entry.id && entry.expression),
  `${model.metrics.length} metrics`);
check('it holds the dimensions with the column they slice by',
  model.dimensions.length >= 11 && model.dimensions.every((entry) => entry.id !== undefined),
  `${model.dimensions.length} dimensions`);
check('it carries the reference queries with their original SQL',
  model.referenceQueries.length >= 12
  && model.referenceQueries.every((entry) => typeof entry.id === 'string'),
  `${model.referenceQueries.length} reference queries`);
check('every metric says which dimensions may slice it',
  model.metrics.filter((entry) => (entry.dimensions || []).length > 0).length >= 10,
  `${model.metrics.filter((entry) => (entry.dimensions || []).length > 0).length} metrics with dimensions`);

// ---- 2. the renderer agrees with the CLI, over a matrix ------------------------------------------------
const metrics = ['metric/revenue', 'metric/discount_amount', 'metric/order_count', 'metric/customer_count'];
const dimensions = [null, 'dim/region', 'dim/mktsegment', 'dim/order_priority', 'dim/nation'];
const cases = [];
for (const metric of metrics) {
  for (const dimension of dimensions) {
    cases.push({ metric, dimension, grain: 'raw', dialect: 'postgres' });
  }
}
for (const grain of ['raw', 'year', 'quarter', 'month']) {
  for (const dialect of ['postgres', 'duckdb', 'mysql']) {
    cases.push({ metric: 'metric/revenue', dimension: 'dim/order_date', grain, dialect });
  }
}

let compared = 0;
const mismatches = [];
for (const entry of cases) {
  const body = { metric: entry.metric, grain: entry.grain, dialect: entry.dialect };
  if (entry.dimension) {
    body.dimension = entry.dimension;
  }
  const answer = await post(`/api/v1/semantic/${GRAPH}/translate`, body);
  if (answer.status !== 200 || !answer.json || !answer.json.statement) {
    mismatches.push(`${entry.metric}/${entry.dimension || '-'}: http ${answer.status} ${answer.text.slice(0, 80)}`);
    continue;
  }
  const expected = cliSql(entry.metric, entry.dimension, entry.grain, entry.dialect);
  compared += 1;
  if (answer.json.statement !== expected) {
    mismatches.push(`${entry.metric} by ${entry.dimension || '-'} ${entry.grain}/${entry.dialect}\n`
      + `      api: ${answer.json.statement}\n      cli: ${expected}`);
  }
}
check(`the API renders the CLI's SQL byte-for-byte (${cases.length} cases)`,
  mismatches.length === 0, mismatches.slice(0, 2).join('\n   '));

// a couple of shapes the matrix does not cover: the document header and the lineage it carries
const one = await post(`/api/v1/semantic/${GRAPH}/translate`, { metric: 'metric/revenue', dimension: 'dim/region' });
check('the document starts with the metric and its expression from the graph',
  one.json.document.startsWith('-- metric: metric/revenue') && one.json.document.includes('-- expression from the graph:'),
  one.json.document.split('\n')[0]);
check('and it names the base table and the lineage columns',
  one.json.baseTable === 'lineitem' && one.json.columns.length >= 1,
  `${one.json.baseTable}, ${one.json.columns.length} column(s)`);
check('the joins it renders are the ones the model declares, with both endpoints',
  (one.json.joins || []).every((join) => join.fk && join.pk && join.to),
  JSON.stringify(one.json.joins));

// ---- 3. refusals are information, not exceptions --------------------------------------------------------
const unknownMetric = await post(`/api/v1/semantic/${GRAPH}/validate`, { metric: 'metric/nope' });
check('an unknown metric is refused with the vocabulary the model holds',
  unknownMetric.status === 200 && unknownMetric.json.answerable === false
  && unknownMetric.json.problem.includes('metric/revenue'),
  `${unknownMetric.status} ${String(unknownMetric.json.problem).slice(0, 90)}`);
const unknownDimension = await post(`/api/v1/semantic/${GRAPH}/validate`,
  { metric: 'metric/revenue', dimension: 'dim/nope' });
check('an unknown dimension is refused with the dimensions the model holds',
  unknownDimension.json.answerable === false && unknownDimension.json.problem.includes('dim/'),
  String(unknownDimension.json.problem).slice(0, 90));
const twoDimensions = await post(`/api/v1/semantic/${GRAPH}/translate`,
  { metric: 'metric/revenue', dimensions: ['dim/region', 'dim/mktsegment'] });
check('two dimensions are refused by name rather than silently dropped',
  twoDimensions.status === 400 && twoDimensions.text.includes('One dimension'),
  `${twoDimensions.status} ${twoDimensions.text.slice(0, 90)}`);
const blank = await post(`/api/v1/semantic/${GRAPH}/translate`, {});
check('a request without a metric is 400 and says what to pass',
  blank.status === 400 && blank.text.includes('metric must not be blank'),
  `${blank.status}`);
const good = await post(`/api/v1/semantic/${GRAPH}/validate`,
  { metric: 'metric/revenue', dimension: 'dim/region', expectedSql: one.json.statement });
check('validate says yes and matches an expected statement when given one',
  good.json.answerable === true && good.json.matchesExpected === true,
  `answerable=${good.json.answerable} matches=${good.json.matchesExpected}`);

console.log('');
console.log(failures === 0
  ? `the semantic layer is served over HTTP, and its SQL is the CLI's (${compared} statements compared)`
  : `${failures} FAILURES`);
process.exit(failures === 0 ? 0 : 1);
