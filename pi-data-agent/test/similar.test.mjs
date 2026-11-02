import test from 'node:test';
import assert from 'node:assert/strict';
import { DIMENSIONS, embed, cosine, tokens } from '../src/embed.mjs';
import { nodeText, similarNodes, neighbourhood, similarWithGraph, renderSimilar } from '../src/similar.mjs';

const model = {
  graph: 'sales-model',
  nodes: [
    { id: 'metric/营收', entityType: 'Metric', properties: { name: '营收', alias: ['收入', 'revenue'], expression: 'SUM(revenue)', description: '成交金额（毛）' } },
    { id: 'metric/净营收', entityType: 'Metric', properties: { name: '净营收', alias: ['净收入'], expression: 'SUM(net_revenue)' } },
    { id: 'metric/订单数', entityType: 'Metric', properties: { name: '订单数', expression: 'SUM(orders)' } },
    { id: 'table/sales_day', entityType: 'Table', properties: { name: 'sales_day' } },
    { id: 'dim/渠道', entityType: 'Dimension', properties: { name: '渠道', graphProperty: 'channel' } },
    { id: 'model/sales-model', entityType: 'Model', properties: { name: 'model/sales-model' } },
  ],
  edges: [
    { id: 'metric/营收|computedFrom|table/sales_day', head: 'metric/营收', relation: 'computedFrom', tail: 'table/sales_day', properties: {} },
    { id: 'metric/营收|groupedBy|dim/渠道', head: 'metric/营收', relation: 'groupedBy', tail: 'dim/渠道', properties: {} },
    { id: 'metric/订单数|computedFrom|table/sales_day', head: 'metric/订单数', relation: 'computedFrom', tail: 'table/sales_day', properties: {} },
  ],
};

test('the embedding is deterministic, unit length and built from words and character n-grams', () => {
  const a = embed('营收 revenue');
  const b = embed('营收 revenue');
  assert.deepEqual(a, b);
  assert.equal(a.length, DIMENSIONS);
  const norm = Math.sqrt(a.reduce((sum, value) => sum + value * value, 0));
  assert.ok(Math.abs(norm - 1) < 0.01, `norm ${norm}`);
  assert.deepEqual(embed(''), new Array(DIMENSIONS).fill(0));
  assert.equal(cosine(embed('收入'), embed('营收')) > 0.3, true, 'synonyms share character bigrams and the alias text');
  assert.ok(tokens('营收=SUM(revenue)').includes('营收'));
  assert.ok(tokens('revenue').includes('#rev'), 'a long word contributes character trigrams');
});

test('the nearest definitions to a phrase are ranked by cosine, and the model node is never a candidate', () => {
  const asked = similarNodes(model, '收入怎么算', { k: 3 });
  assert.equal(asked.matches.length, 2, 'only definitions that share something with the phrase are candidates');
  // 营收 wins because its alias 收入 is in the question, not because it shares a character with 净营收
  assert.deepEqual(asked.matches.map((match) => match.id), ['metric/营收', 'metric/净营收']);
  assert.equal(asked.matches[0].exact, true);
  assert.ok(asked.matches[0].score >= asked.matches[1].score);
  assert.equal(asked.matches.some((match) => match.id === 'model/sales-model'), false);
  const onlyTables = similarNodes(model, 'sales', { k: 5, entityTypes: ['Table'] });
  assert.deepEqual(onlyTables.matches.map((match) => match.id), ['table/sales_day']);
  assert.equal(similarNodes(model, 'zzzzqqqq', { k: 3 }).matches.length, 0);
  assert.equal(nodeText(model.nodes[0]).includes('SUM(revenue)'), true);
});

test('the neighbourhood walks the relations out from a match and reports what it could not find', () => {
  const graph = neighbourhood(model, ['metric/营收'], { hops: 1 });
  assert.deepEqual(graph.nodes.map((node) => node.id).sort(), ['dim/渠道', 'metric/营收', 'table/sales_day']);
  assert.equal(graph.edges.length, 2);
  // hop 0 is the node itself; hop 2 reaches the sibling metric through the shared table
  assert.deepEqual(neighbourhood(model, ['metric/营收'], { hops: 0 }).nodes.map((node) => node.id), ['metric/营收']);
  const two = neighbourhood(model, ['metric/营收'], { hops: 2 });
  assert.ok(two.nodes.some((node) => node.id === 'metric/订单数'), 'two hops reach the sibling metric through the table');
  assert.deepEqual(neighbourhood(model, ['metric/nope']).missing, ['metric/nope']);
  assert.deepEqual(neighbourhood(model, ['metric/营收'], { relations: ['groupedBy'] }).edges.map((edge) => edge.relation), ['groupedBy']);
});

test('vector + graph: a match carries the relations behind it, and an isolated match says so', () => {
  const bundle = similarWithGraph(model, '收入 按渠道 拆分', { k: 2, hops: 1 });
  assert.equal(bundle.ok, true);
  const revenue = bundle.matches.find((match) => match.id === 'metric/营收');
  assert.equal(revenue.relations.length, 2);
  assert.deepEqual(revenue.relations.map((relation) => `${relation.direction}:${relation.relation}:${relation.other}`).sort(), [
    'out:computedFrom:table/sales_day', 'out:groupedBy:dim/渠道',
  ]);
  assert.ok(bundle.nodes.length >= 3);
  const text = renderSimilar(bundle);
  assert.match(text, /metric\/营收/);
  assert.match(text, /-> computedFrom table\/sales_day/);

  const lonely = similarWithGraph({ ...model, edges: [] }, '净营收', { k: 1 });
  assert.deepEqual(lonely.isolated, ['metric/净营收']);
  assert.match(renderSimilar(lonely), /isolated: metric\/净营收/);
  assert.match(renderSimilar(similarWithGraph(model, 'zzzzqqqq', { k: 1 })), /no node in the model is close/);
});
