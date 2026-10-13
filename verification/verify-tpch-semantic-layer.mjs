// needs: service
// what: the TPC-H semantic layer answers the eight questions of TPC-H-ANALYTICS.md §3 (definition, lineage,
//       columns, dimensions, attribution, second-order attribution, join keys, reverse lineage) and the data
//       instance matches the year figures the README quotes
//
// This replaces an older script that only *printed* those answers: a report that cannot fail is not a check,
// and a stale one is worse than none (it still asked for the `promotionRevenue` property that this build does
// not have, and for `orderPriority`, which is `priority`).
const API = 'http://localhost:8081/api/v1/query';
const ask = async (graph, query) => {
  const body = await fetch(API, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ graphName: graph, query, includeResult: true, returnSubgraph: false }),
  }).then((response) => response.json());
  if (body.error) throw new Error(`${graph} :: ${query} -> ${body.error}`);
  return Array.isArray(body) ? body : body.result;
};
const scalar = async (graph, query) => (await ask(graph, query))[0];
const list = async (graph, query) => (await ask(graph, query)).map(String);

let failures = 0;
const check = (name, ok, detail) => { if (!ok) failures += 1; console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`); };
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

const S = 'tpc-h-analytics';
const D = 'tpc-h-data';

// ---- the eight questions the semantic layer has to answer --------------------------------------------
const definition = await ask(S, "g.V('metric/revenue','Node').values('aggFunction','expression','unit')");
check('① revenue is defined in the layer, with its aggregation, expression and unit',
  same(definition, ['SUM', 'SUM(l_extendedprice * (1 - l_discount))', '元']), JSON.stringify(definition));
check('② its lineage names the table the measure lives in',
  same(await list(S, "g.V('metric/revenue','Node').outE().filter(relation.equals('computedFrom')).otherV().values('name')"),
    ['table/lineitem']));
check('③ the columns the expression is made of come from the graph, not from a comment',
  same(await list(S, "g.V('metric/revenue','Node').outE().filter(relation.equals('usesColumn')).otherV().values('name')"),
    ['column/lineitem.l_discount', 'column/lineitem.l_extendedprice']));
check('④ the dimensions a reader may group by are declared on the metric',
  same(await list(S, "g.V('metric/revenue','Node').outE().filter(relation.equals('groupedBy')).otherV().values('name')"),
    ['dim/mktsegment', 'dim/region', 'dim/nation', 'dim/order_date']));
check('⑤ attribution: what drives profit (the in-edges of contributesTo)',
  same(await list(S, "g.V('metric/profit','Node').inE().filter(relation.equals('contributesTo')).otherV().values('name')"),
    ['metric/return_rate', 'metric/supply_cost', 'metric/revenue']));
check('⑥ second-order attribution reaches seven upstream metrics',
  Number(await scalar(S, "g.V('metric/profit','Node').inE().filter(relation.equals('contributesTo')).otherV()"
    + ".inE().filter(relation.equals('contributesTo')).otherV().dedup().count()")) === 7);
check('⑦ the join path exists as foreign keys pointing at primary keys',
  same(await list(S, "g.V('metric/revenue','Node').outE().filter(relation.equals('computedFrom')).otherV()"
    + ".outE().filter(relation.equals('hasColumn')).otherV().has('columnRole','FK')"
    + ".outE().filter(relation.equals('references')).otherV().values('name')"),
    ['column/supplier.s_suppkey', 'column/part.p_partkey', 'column/orders.o_orderkey']));
const reverse = await list(S, "g.V('column/lineitem.l_extendedprice','Node').inE().filter(relation.equals('usesColumn')).otherV().values('name')");
check('⑧ reverse lineage: ten metrics depend on the extended price', reverse.length === 10 && reverse.includes('metric/revenue'),
  `${reverse.length} metrics`);

// ---- the data instance: the year figures the README quotes -------------------------------------------
const revenue = await scalar(D, "g.V('Sale').group().by('orderYear').by(values('revenue').sum())");
const gross = await scalar(D, "g.V('Sale').group().by('orderYear').by(values('gross').sum())");
const discount = await scalar(D, "g.V('Sale').group().by('orderYear').by(values('discountAmount').sum())");
check('the data instance answers the year figures the README prints',
  Math.round(revenue['1995']) === 95612794 && Math.round(revenue['1996']) === 98879433
  && gross['1995'] === 100509809 && gross['1996'] === 104059747
  && Math.round(discount['1995']) === 4897015 && Math.round(discount['1996']) === 5180314,
  `revenue ${Math.round(revenue['1995'])}/${Math.round(revenue['1996'])}`);
check('the analytic query runs as a graph query, not as SQL against a store the graph cannot see',
  (await scalar(D, "g.V('Sale').count()")) === 8000);
const chain = await list(D, "g.V('sale_5882','Sale').out('ofOrder').out('placedBy').out('customerNation').out('nationRegion').values('name')");
const flattened = await list(D, "g.V('sale_5882','Sale').values('nation','region','segment')");
check('the snowflake chain sale -> order -> customer -> nation -> region resolves',
  chain.length === 1 && flattened.length === 3, `${JSON.stringify(chain)} vs ${JSON.stringify(flattened)}`);

console.log(failures
  ? `\n${failures} FAILURES`
  : 'the semantic layer answers all eight questions and the data instance matches the quoted year figures');
process.exit(failures ? 1 : 0);
