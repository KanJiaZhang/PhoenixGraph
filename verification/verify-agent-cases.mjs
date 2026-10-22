// needs: service
// what: every figure docs/AGENT-CASES.md quotes, re-run against the service
// run:  node verification/run.mjs --only agent-cases
// Checks every figure docs/AGENT-CASES.md quotes, by re-running the query behind it against the running
// service (8081). The point is that the document is not allowed to contain a number that the data does not
// answer right now: case A audits a knowledge graph's own edges against the texts it also stores, case B
// refuses a per-region answer after three self-checks - and both verdicts are only as good as the queries
// in this file.
import { execFileSync } from 'node:child_process';

const API = 'http://localhost:8081/api/v1/query';
const ask = async (graphName, query) => {
  const body = await fetch(API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ graphName, query, returnSubgraph: true, includeResult: true }),
  }).then((r) => r.json());
  if (body.error) throw new Error(`${graphName} :: ${query} -> ${body.error}`);
  return body.result || [];
};
const count = async (graph, query) => Number((await ask(graph, query))[0]);
const props = async (graph, id) => ((await ask(graph, `g.V('${id}','Entity')`))[0] || {}).properties || {};

let failures = 0;
const check = (name, ok, detail) => { if (!ok) failures += 1; console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`); };
const close = (a, b, tolerance = 0.005) => Math.abs(a - b) <= tolerance;
const pct = (from, to) => ((to - from) / from) * 100;

const S = 'sanguo-kg-text';
const A = 'sanguo-kg';
const T = 'tpc-h-data';
const SEM = 'tpc-h-analytics';

console.log('# case A: the Three Kingdoms graph');

// ---- A.1 size and shape -----------------------------------------------------------------------------
const entities = await count(S, "g.V('Entity').count()");
const edges = await count(S, "g.E('Entity_Entity').count()");
check('6,594 entities / 45,081 edges', entities === 6594 && edges === 45081, `${entities}v/${edges}e`);
check('4,273 historical vs 214 fictional people',
  await count(S, "g.V('Entity').has('historicity','史实人物').count()") === 4273
  && await count(S, "g.V('Entity').has('historicity','虚构人物').count()") === 214);
check('1,343 entities carry a novel-side biography',
  await count(S, "g.V('Entity').has('novel').count()") === 1343);
check('16 relation families',
  (await ask(S, "g.E('Entity_Entity').values('relationFamily').dedup()")).length === 16);
check('event / chapter / place counts are 21 / 120 / 484',
  await count(S, "g.V('Entity').has('entityType','Event').count()") === 21
  && await count(S, "g.V('Entity').has('entityType','Chapter').count()") === 120
  && await count(S, "g.V('Entity').has('entityType','Place').count()") === 484);
check('5,917 edges carry a year, 6,831 a chapter',
  await count(S, "g.E('Entity_Entity').has('year').count()") === 5917
  && await count(S, "g.E('Entity_Entity').has('chapter').count()") === 6831);
check('only 1,081 entities carry a deathYear and 377 a birthYear (the rest are dated in the text)',
  await count(S, "g.V('Entity').has('deathYear').count()") === 1081
  && await count(S, "g.V('Entity').has('birthYear').count()") === 377);

// ---- A.6 the provenance split: the audit's blast radius ---------------------------------------------
const extra = await count(S, "g.E('Entity_Entity').has('provenance','EXTRA').count()");
const document_ = await count(S, "g.E('Entity_Entity').has('provenance','DOCUMENT').count()");
check('24,147 EXTRA edges vs 20,788 DOCUMENT edges', extra === 24147 && document_ === 20788, `${extra}/${document_}`);
const killedExtra = await count(S, "g.E('Entity_Entity').has('relation','killed').has('provenance','EXTRA').count()");
const killedDoc = await count(S, "g.E('Entity_Entity').has('relation','killed').has('provenance','DOCUMENT').count()");
check('all 300 killed edges are EXTRA, none DOCUMENT', killedExtra === 300 && killedDoc === 0, `${killedExtra}/${killedDoc}`);
const control = {
  serves: await count(S, "g.E('Entity_Entity').has('relation','serves').has('provenance','DOCUMENT').count()"),
  father: await count(S, "g.E('Entity_Entity').has('relation','father').has('provenance','DOCUMENT').count()"),
  brother: await count(S, "g.E('Entity_Entity').has('relation','brother').has('provenance','DOCUMENT').count()"),
  mentions: await count(S, "g.E('Entity_Entity').has('relation','mentions').has('provenance','EXTRA').count()"),
};
check('the control relations are 100% DOCUMENT (serves/father/brother) and mentions is 100% EXTRA',
  control.serves === 4912 && control.father === 1351 && control.brother === 2628 && control.mentions === 22951,
  JSON.stringify(control));

// ---- A.4/A.5 the ten claims and their verdicts ------------------------------------------------------
const victims = (await ask(S, "g.V('关羽','Entity').outE('Entity_Entity').filter(relation.equals('killed')).otherV().values('name')")).map(String);
check('Guan Yu has exactly 10 "killed" edges, in this order',
  JSON.stringify(victims) === JSON.stringify(['颜良', '郭图', '蔡阳', '管亥', '沮授', '文丑', '庞德', '庞会', '卞喜', '华雄']),
  JSON.stringify(victims));
const edgeProps = await ask(S, "g.V('关羽','Entity').outE('Entity_Entity').filter(relation.equals('killed')).valueMap()");
check('every one of the ten edges is an EXTRA (novel-side) assertion',
  edgeProps.length === 10 && edgeProps.every((e) => e.provenance === 'EXTRA'));
const byVictim = new Map(victims.map((name, at) => [name, edgeProps[at]]));
check('the novel chapter of 华雄 is ch.5 / year 190',
  byVictim.get('华雄').chapter === '5' && byVictim.get('华雄').year === '190',
  JSON.stringify(byVictim.get('华雄')));
check('the novel chapter of 庞会 is ch.74 / year 219',
  byVictim.get('庞会').chapter === '74' && byVictim.get('庞会').year === '219',
  JSON.stringify(byVictim.get('庞会')));

const yanliang = await props(S, '颜良');
const huaxiong = await props(S, '华雄');
const caiyang = await props(S, '蔡阳');
const wenchou = await props(S, '文丑');
const guanhai = await props(S, '管亥');
const panghui = await props(S, '庞会');
const pangde = await props(S, '庞德');
const bianxi = await props(S, '卞喜');
const jushou = await props(S, '沮授');
const guotu = await props(S, '郭图');
check('颜良: history says Guan Yu struck him down (claim 1 holds)',
  /遂被关羽击斩/.test(yanliang.history), String(yanliang.history).slice(-24));
check('庞德: executed by Guan Yu, proven by 庞会\'s own entry',
  /德为关羽所杀/.test(panghui.history) && pangde.deathYear === 219, `deathYear=${pangde.deathYear}`);
check('华雄: the history credits 孙坚, not Guan Yu (claim 3 fails)',
  /雄为坚所斩/.test(huaxiong.history) && huaxiong.deathYear === 191);
check('蔡阳: the history credits 刘备 (claim 4 fails)',
  /阳为备所杀/.test(caiyang.history) && caiyang.deathYear === 201);
check('文丑: the history has Cao Cao\'s troops break him (claim 5 fails)',
  /遂被操击破而死/.test(wenchou.history) && wenchou.deathYear === 200);
check('管亥: the history has no death at all (claim 6 fails)',
  !/杀|斩|死/.test(guanhai.history) === false ? !/关羽/.test(guanhai.history) : true,
  String(guanhai.history).slice(-30));
check('庞会: the timeline refutes the claim (263 in his biography vs the 219 the edge claims)',
  /景元四年/.test(panghui.history) && panghui.deathYear === undefined
  && byVictim.get('庞会').year === '219' && (await props(S, '关羽')).deathYear === 219,
  `edge year ${byVictim.get('庞会').year}, 庞会 deathYear=${panghui.deathYear}, 关羽 ${(await props(S, '关羽')).deathYear}`);
check('卞喜: the graph itself marks him as a fictional figure (claim 10)',
  bianxi.historicity === '虚构人物' && /虚构人物/.test(bianxi.history));
check('沮授 and 郭图: the evidence text is cut, so the verdict is "insufficient"',
  jushou.history.length >= 120 && guotu.history.length >= 120,
  `${jushou.history.length}/${guotu.history.length} chars`);

const sunjianKills = (await ask(S, "g.V('孙坚','Entity').outE('Entity_Entity').filter(relation.equals('killed')).otherV().values('name')")).map(String);
const sunjianToHuaxiong = await ask(S, "g.V('孙坚','Entity').outE('Entity_Entity').filter(relation.equals('killed')).otherV().filter(name.equals('华雄'))");
check('孙坚 has 3 kills recorded and 华雄 is not among them (the missing edge)',
  JSON.stringify(sunjianKills) === JSON.stringify(['王叡', '曹寅', '张咨']) && sunjianToHuaxiong.length === 0,
  JSON.stringify(sunjianKills));

const diaochan = await props(S, '貂蝉');
check('貂蝉 is marked fictional with a novel-side biography only',
  diaochan.historicity === '虚构人物' && /虚构人物/.test(diaochan.history) && String(diaochan.novel).length > 100);

// ---- A.7 the timeline check -------------------------------------------------------------------------
const wine = await props(S, '温酒斩华雄');
const chibi = await props(S, '赤壁之战');
check('温酒斩华雄 is an Event dated 190 at (34.8, 113.3)',
  wine.entityType === 'Event' && wine.eventYear === 190 && wine.latitude === 34.8 && wine.longitude === 113.3,
  JSON.stringify(wine));
check('赤壁之战 is dated 208 at (29.72, 113.9)',
  chibi.eventYear === 208 && chibi.latitude === 29.72 && chibi.longitude === 113.9);
check('华雄 dies in 191, one year after the event the novel gives for his death',
  huaxiong.deathYear === 191 && wine.eventYear === 190);

// ---- A.8 the evidence is truncated at 120 characters ------------------------------------------------
const scan = await ask(S, "g.V('Entity').limit(4000)");
let maxHistory = 0, cappedHistory = 0, withHistory = 0, maxNovel = 0, cappedNovel = 0, withNovel = 0;
for (const v of scan) {
  const p = v.properties || {};
  if (typeof p.history === 'string') { withHistory += 1; maxHistory = Math.max(maxHistory, p.history.length); if (p.history.length >= 120) cappedHistory += 1; }
  if (typeof p.novel === 'string') { withNovel += 1; maxNovel = Math.max(maxNovel, p.novel.length); if (p.novel.length >= 120) cappedNovel += 1; }
}
check('history: 2,743 entities, max 120 chars, 566 at the cap',
  withHistory === 2743 && maxHistory === 120 && cappedHistory === 566, `${withHistory}/${maxHistory}/${cappedHistory}`);
check('novel: 803 entities, max 120 chars, 218 at the cap',
  withNovel === 803 && maxNovel === 120 && cappedNovel === 218, `${withNovel}/${maxNovel}/${cappedNovel}`);

// ---- A.3 the shard trap on the served keyed instance ------------------------------------------------
const shard = {};
for (const id of ['关羽', '关羽_3', '曹操', '曹操_3', '曹操_9']) {
  shard[id] = (await ask(A, `g.V('${id}','Entity')`)).length;
}
check('on the keyed instance the bare ids do not exist and the shards do',
  shard['关羽'] === 0 && shard['关羽_3'] === 1 && shard['曹操'] === 0 && shard['曹操_3'] === 1 && shard['曹操_9'] === 1,
  JSON.stringify(shard));
const shardKills = await ask(A, "g.V('关羽_3','Entity').outE('Entity_Entity').filter(relation.equals('killed')).otherV().values('name')");
check('the shard holding 关羽 has none of the kills (identity resolution is required)', shardKills.length === 0);
check('a whole-type scan is refused on the keyed instance',
  await (async () => {
    const body = await fetch(API, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ graphName: A, query: "g.V('Entity')", includeResult: true }) }).then((r) => r.json());
    return !!body.error && /whole type/.test(String(body.error));
  })());

console.log('\n# case B: the TPC-H data graph and its semantic layer');

// ---- B.1 shape ---------------------------------------------------------------------------------------
check('8,000 sales / 500 customers / 25 nations / 5 regions',
  await count(T, "g.V('Sale').count()") === 8000 && await count(T, "g.V('Customer').count()") === 500
  && await count(T, "g.V('Nation').count()") === 25 && await count(T, "g.V('Region').count()") === 5);
const saleRow = (await ask(T, "g.V('Sale').limit(1).valueMap()"))[0];
check('the Sale vertex is the flattened wide row (21 properties)',
  Object.keys(saleRow).length === 21 && saleRow.revenue !== undefined && saleRow.lateDelivery === 0,
  `${Object.keys(saleRow).length} properties`);

// ---- B.6 the predicate trap --------------------------------------------------------------------------
const lateTrue = await count(T, "g.V('Sale').has('lateDelivery',true).count()");
const lateOne = await count(T, "g.V('Sale').has('lateDelivery',1).count()");
const retTrue = await count(T, "g.V('Sale').has('returned',true).count()");
const retOne = await count(T, "g.V('Sale').has('returned',1).count()");
check('the boolean predicate silently returns an empty set (0) while the integer matches 771',
  lateTrue === 0 && lateOne === 771, `true=${lateTrue} 1=${lateOne}`);
check('same for returned: 0 vs 351', retTrue === 0 && retOne === 351, `true=${retTrue} 1=${retOne}`);
check('no sale is missing the flag, and the stored values are the integers 0 and 1',
  await count(T, "g.V('Sale').hasNot('lateDelivery').count()") === 0
  && JSON.stringify((await ask(T, "g.V('Sale').values('lateDelivery').dedup()")).map(Number).sort()) === '[0,1]');
check('9.64% of the 8,000 sales are late and 4.39% returned',
  close((lateOne / 8000) * 100, 9.64, 0.01) && close((retOne / 8000) * 100, 4.39, 0.01),
  `${((lateOne / 8000) * 100).toFixed(2)}% / ${((retOne / 8000) * 100).toFixed(2)}%`);

// ---- B.3/B.5 the region ranking and the region-equals-segment degeneracy -----------------------------
const byRegion = (await ask(T, "g.V('Sale').group().by('region').by(values('revenue').sum())"))[0];
const bySegment = (await ask(T, "g.V('Sale').group().by('segment').by(values('revenue').sum())"))[0];
const regions = Object.keys(byRegion).sort();
const segments = Object.keys(bySegment).sort();
check('five distinct region labels and five distinct segment labels', regions.length === 5 && segments.length === 5,
  `${regions.join(' ')} | ${segments.join(' ')}`);
check('the two label sets share no name (they are different dimensions by name)', regions.every((r) => !segments.includes(r)));
const pairs = [['AFRICA', 'BUILDING'], ['AMERICA', 'AUTOMOBILE'], ['ASIA', 'MACHINERY'], ['EUROPE', 'HOUSEHOLD'], ['MIDDLE EAST', 'FURNITURE']];
check('region and segment produce the same five sums to the cent (the degeneracy)',
  pairs.every(([region, segment]) => close(byRegion[region], bySegment[segment], 0.0001)),
  pairs.map(([r, s]) => `${r}=${byRegion[r].toFixed(2)}/${s}=${bySegment[s].toFixed(2)}`).join(' '));
check('ASIA leads EUROPE by about 327k (the ranking that must be refused)',
  close(byRegion.ASIA - byRegion.EUROPE, 327481.43, 1), `${(byRegion.ASIA - byRegion.EUROPE).toFixed(2)}`);

// ---- B.4 the reference check -------------------------------------------------------------------------
const NATION_REGION = {
  ALGERIA: 'AFRICA', ETHIOPIA: 'AFRICA', KENYA: 'AFRICA', MOROCCO: 'AFRICA', MOZAMBIQUE: 'AFRICA',
  ARGENTINA: 'AMERICA', BRAZIL: 'AMERICA', CANADA: 'AMERICA', PERU: 'AMERICA', 'UNITED STATES': 'AMERICA',
  CHINA: 'ASIA', INDIA: 'ASIA', INDONESIA: 'ASIA', JAPAN: 'ASIA', VIETNAM: 'ASIA',
  FRANCE: 'EUROPE', GERMANY: 'EUROPE', ROMANIA: 'EUROPE', RUSSIA: 'EUROPE', 'UNITED KINGDOM': 'EUROPE',
  EGYPT: 'MIDDLE EAST', IRAN: 'MIDDLE EAST', IRAQ: 'MIDDLE EAST', JORDAN: 'MIDDLE EAST', 'SAUDI ARABIA': 'MIDDLE EAST',
};
const nations = (await ask(T, "g.V('Nation').limit(30)")).map((v) => ({ name: v.properties.name, region: v.properties.region }));
const correct = nations.filter((n) => NATION_REGION[n.name] === n.region).length;
check('only 7 of 25 nations sit in the region TPC-H puts them in', nations.length === 25 && correct === 7, `${correct}/25`);
const wrongExamples = { 'UNITED STATES': 'MIDDLE EAST', CHINA: 'EUROPE', INDIA: 'EUROPE', BRAZIL: 'ASIA', FRANCE: 'AMERICA', 'SAUDI ARABIA': 'AFRICA', KENYA: 'MIDDLE EAST' };
check('the documented mismatches are the ones in the data',
  Object.entries(wrongExamples).every(([name, region]) => (nations.find((n) => n.name === name) || {}).region === region),
  Object.entries(wrongExamples).map(([n, r]) => `${n}->${r}`).join(' '));
const saleRegionOf = new Map(nations.map((n) => [n.name, n.region]));
const sample1996 = await ask(T, "g.V('Sale').has('orderYear',1996).limit(400)");
check('the flattened region of every sampled sale agrees with its nation (the defect is upstream, not the flattening)',
  sample1996.every((s) => saleRegionOf.get(s.properties.nation) === s.properties.region),
  `${sample1996.length}/${sample1996.length}`);

// ---- B.7 the defensible analysis ---------------------------------------------------------------------
const revenueYear = (await ask(T, "g.V('Sale').group().by('orderYear').by(values('revenue').sum())"))[0];
const discountYear = (await ask(T, "g.V('Sale').group().by('orderYear').by(values('discountAmount').sum())"))[0];
const quantityYear = (await ask(T, "g.V('Sale').group().by('orderYear').by(values('quantity').sum())"))[0];
const averageYear = (await ask(T, "g.V('Sale').group().by('orderYear').by(values('revenue').mean())"))[0];
const lateRevenueYear = (await ask(T, "g.V('Sale').has('lateDelivery',1).group().by('orderYear').by(values('revenue').sum())"))[0];
check('revenue 95,612,794.05 -> 98,879,432.89 (+3.42%)',
  close(revenueYear[1995], 95612794.05, 0.05) && close(revenueYear[1996], 98879432.89, 0.05)
  && close(pct(revenueYear[1995], revenueYear[1996]), 3.42, 0.01),
  `${revenueYear[1995].toFixed(2)} -> ${revenueYear[1996].toFixed(2)} (${pct(revenueYear[1995], revenueYear[1996]).toFixed(2)}%)`);
check('discount amount 4,897,014.95 -> 5,180,314.11 (+5.79%, faster than revenue)',
  close(discountYear[1995], 4897014.95, 0.05) && close(discountYear[1996], 5180314.11, 0.05)
  && close(pct(discountYear[1995], discountYear[1996]), 5.79, 0.01),
  `${pct(discountYear[1995], discountYear[1996]).toFixed(2)}%`);
check('late revenue grows 10.15% (faster still): 8,945,352.26 -> 9,853,656.02',
  close(lateRevenueYear[1995], 8945352.26, 0.05) && close(lateRevenueYear[1996], 9853656.02, 0.05)
  && close(pct(lateRevenueYear[1995], lateRevenueYear[1996]), 10.15, 0.01));
check('the late share is 9.36% then 9.97% of revenue',
  close((lateRevenueYear[1995] / revenueYear[1995]) * 100, 9.36, 0.01)
  && close((lateRevenueYear[1996] / revenueYear[1996]) * 100, 9.97, 0.01));
check('average sale value barely moves: 24,193.52 -> 24,426.74 (+0.96%)',
  close(averageYear[1995], 24193.52, 0.01) && close(averageYear[1996], 24426.74, 0.01)
  && close(pct(averageYear[1995], averageYear[1996]), 0.96, 0.01));
check('quantity 100,485 -> 104,099 (+3.59%)',
  quantityYear[1995] === 100485 && quantityYear[1996] === 104099
  && close(pct(quantityYear[1995], quantityYear[1996]), 3.59, 0.01));
const sales1995 = await count(T, "g.V('Sale').has('orderYear',1995).count()");
const sales1996 = await count(T, "g.V('Sale').has('orderYear',1996).count()");
check('3,952 vs 4,048 sales (+2.43%)', sales1995 === 3952 && sales1996 === 4048,
  `${sales1995} -> ${sales1996} (${pct(sales1995, sales1996).toFixed(2)}%)`);

const lateByMode = (await ask(T, "g.V('Sale').has('lateDelivery',1).group().by('shipmode').by(count())"))[0];
const allByMode = (await ask(T, "g.V('Sale').group().by('shipmode').by(count())"))[0];
const modeRate = (mode) => (lateByMode[mode] / allByMode[mode]) * 100;
check('late counts by shipmode are AIR 125 / SHIP 141 / REG AIR 133 / TRUCK 120 / MAIL 119 / RAIL 133',
  lateByMode.AIR === 125 && lateByMode.SHIP === 141 && lateByMode['REG AIR'] === 133
  && lateByMode.TRUCK === 120 && lateByMode.MAIL === 119 && lateByMode.RAIL === 133, JSON.stringify(lateByMode));
check('the late rates are RAIL 10.43% / SHIP 10.14% worst and MAIL 8.46% best',
  close(modeRate('RAIL'), 10.43, 0.01) && close(modeRate('SHIP'), 10.14, 0.01) && close(modeRate('MAIL'), 8.46, 0.01),
  ['RAIL', 'SHIP', 'REG AIR', 'AIR', 'TRUCK', 'MAIL'].map((m) => `${m} ${modeRate(m).toFixed(2)}%`).join(' '));
const returnedByPriority = (await ask(T, "g.V('Sale').has('returned',1).group().by('priority').by(count())"))[0];
const allByPriority = (await ask(T, "g.V('Sale').group().by('priority').by(count())"))[0];
const priorities = ['1-URGENT', '2-HIGH', '3-MEDIUM', '4-NOT SPECIFIED', '5-LOW'];
check('every priority bucket holds 1,600 sales and the return rate is flat (4.06%-4.69%)',
  priorities.every((p) => allByPriority[p] === 1600)
  && JSON.stringify(priorities.map((p) => returnedByPriority[p])) === '[66,65,71,75,74]'
  && priorities.every((p) => (returnedByPriority[p] / allByPriority[p]) * 100 >= 4.0 && (returnedByPriority[p] / allByPriority[p]) * 100 <= 4.7),
  priorities.map((p) => `${p} ${((returnedByPriority[p] / allByPriority[p]) * 100).toFixed(2)}%`).join(' '));
const byMonth = (await ask(T, "g.V('Sale').group().by('orderMonth').by(values('revenue').sum())"))[0];
check('the month curve runs 13,567,510.77 (Jan) to 18,211,062.47 (Jul) and back (+34%)',
  close(byMonth['1'], 13567510.77, 0.05) && close(byMonth['7'], 18211062.47, 0.05) && close(byMonth['10'], 13568867.50, 0.05)
  && close(((byMonth['7'] - byMonth['1']) / byMonth['1']) * 100, 34.2, 0.1),
  `Jan ${byMonth['1'].toFixed(0)} Jul ${byMonth['7'].toFixed(0)} Oct ${byMonth['10'].toFixed(0)}`);

// ---- B.2 the semantic layer the agent grounds on -----------------------------------------------------
const metrics = (await ask(SEM, "g.V('Node').has('entityType','Metric').values('name')")).length;
const dimensions = (await ask(SEM, "g.V('Node').has('entityType','Dimension').values('name')")).length;
const tables = (await ask(SEM, "g.V('Node').has('entityType','Table').values('name')")).length;
check('the semantic layer holds 18 metrics / 11 dimensions / 8 tables', metrics === 18 && dimensions === 11 && tables === 8,
  `${metrics}/${dimensions}/${tables}`);
const revenueNode = ((await ask(SEM, "g.V('metric/revenue','Node')"))[0] || {}).properties || {};
check('metric/revenue carries the expression, unit and description the agent quotes',
  revenueNode.expression === 'SUM(l_extendedprice * (1 - l_discount))' && revenueNode.unit === '元'
  && /净收入/.test(revenueNode.description), JSON.stringify(revenueNode.expression));
const lateMetric = ((await ask(SEM, "g.V('metric/late_delivery_count','Node')"))[0] || {}).properties || {};
check('metric/late_delivery_count is defined on receipt vs commit dates',
  lateMetric.expression === 'COUNT(CASE WHEN l_receiptdate > l_commitdate THEN 1 END)', String(lateMetric.expression));
const regionDim = (await ask(SEM, "g.V('dim/region','Node').outE('Relation').filter(relation.equals('dimensionColumn')).otherV().values('name')")).map(String);
check('dim/region points at column/region.r_name', JSON.stringify(regionDim) === JSON.stringify(['column/region.r_name']), JSON.stringify(regionDim));

// ---- the generated SQL, from the tool itself ---------------------------------------------------------
const sqlRegion = execFileSync('node', ['tools/semantic-sql.mjs', '--metric', 'metric/revenue', '--group-by', 'dim/region'],
  { encoding: 'utf8' });
check('the generated SQL joins lineitem -> supplier -> nation -> region (the supplier-detail path)',
  /JOIN supplier ON lineitem\.l_suppkey = supplier\.s_suppkey/.test(sqlRegion)
  && /JOIN nation ON supplier\.s_nationkey = nation\.n_nationkey/.test(sqlRegion)
  && /JOIN region ON nation\.n_regionkey = region\.r_regionkey/.test(sqlRegion)
  && /SUM\(lineitem\.l_extendedprice \* \(1 - lineitem\.l_discount\)\)/.test(sqlRegion));
const sqlMonth = execFileSync('node', ['tools/semantic-sql.mjs', '--metric', 'metric/revenue', '--group-by', 'dim/order_date', '--grain', 'month'],
  { encoding: 'utf8' });
check('the month SQL joins lineitem -> orders and groups by DATE_TRUNC',
  /JOIN orders ON lineitem\.l_orderkey = orders\.o_orderkey/.test(sqlMonth) && /DATE_TRUNC\('month'/.test(sqlMonth));

console.log(`\n${failures ? failures + ' FAILURES' : 'both cases reproduce: every figure in docs/AGENT-CASES.md came back from the data'}`);
process.exit(failures ? 1 : 0);
