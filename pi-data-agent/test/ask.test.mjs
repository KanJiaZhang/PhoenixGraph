import test from 'node:test';
import assert from 'node:assert/strict';
import { parseQuestion, renderQuestion, topNIn, yearsIn, dimensionIn } from '../src/ask.mjs';

const model = {
  graph: 'g',
  nodes: [
    { id: 'metric/营收', entityType: 'Metric', properties: { name: '营收', alias: ['收入', '销售额', 'revenue'], expression: 'SUM(revenue)', graphProperty: 'revenue' } },
    { id: 'metric/净营收', entityType: 'Metric', properties: { name: '净营收', alias: ['净收入'], expression: 'SUM(net_revenue)', graphProperty: 'net_revenue' } },
    { id: 'metric/成本', entityType: 'Metric', properties: { name: '成本', expression: 'SUM(cost)', graphProperty: 'cost' } },
    { id: 'dim/渠道', entityType: 'Dimension', properties: { name: '渠道', graphProperty: 'channel' } },
    { id: 'dim/地区', entityType: 'Dimension', properties: { name: '地区', graphProperty: 'region' } },
    { id: 'column/sales_day.channel', entityType: 'Column', properties: { name: 'channel', tableName: 'sales_day' } },
  ],
  edges: [
    { id: 'dim/渠道|dimensionColumn|column/sales_day.channel', head: 'dim/渠道', relation: 'dimensionColumn', tail: 'column/sales_day.channel', properties: {} },
  ],
};

const ask = (text) => parseQuestion(model, text);

test('one dashboard question, seven phrasings, one request', () => {
  const expected = { metric: 'metric/营收', year: '2025', dimensions: ['dim/渠道'], topN: 3 };
  const phrasings = [
    '2025年营收按渠道拆分，前3',
    '营收 2025 by 渠道 top3',
    '按渠道看下 2025 年的收入前三',
    '给我 2025 年 metric/营收 按 dim/渠道 前 3 名',
    '2025 年销售额按 channel 拆解 top 3',
    '2025年营收按 sales_day.channel 拆分，前3名',
    '营收 2025 按渠道 前三',
  ];
  for (const phrase of phrasings) {
    const parsed = ask(phrase);
    assert.equal(parsed.ok, true, phrase);
    assert.equal(parsed.kind, 'dashboard', phrase);
    assert.deepEqual(parsed.request, expected, phrase);
  }
  // and the understanding says which spelling it read, so a report can show it
  assert.match(ask('按渠道看下 2025 年的收入前三').understood.metric, /by alias: 收入/);
  assert.match(ask('2025 年销售额按 channel 拆解 top 3').understood.dimension, /by binding/);
  assert.match(ask('2025年营收按 sales_day.channel 拆分，前3名').understood.dimension, /by column/);
});

test('one attribution question, five phrasings, one request', () => {
  const expected = { metric: 'metric/营收', by: 'dim/渠道', from: '2024', to: '2025' };
  const phrasings = [
    '2024 到 2025 营收按渠道为什么增长',
    '营收 2024 vs 2025 按渠道贡献',
    '按渠道归因：营收从 2024 到 2025 的变化',
    '2025 比 2024，营收按渠道增长了多少',
    'metric/营收 2024 -> 2025 by dim/渠道 归因',
  ];
  for (const phrase of phrasings) {
    const parsed = ask(phrase);
    assert.equal(parsed.kind, 'attribution', phrase);
    assert.deepEqual(parsed.request, expected, phrase);
  }
  // the years are ordered, not taken in the order of the sentence
  assert.deepEqual(ask('2025 比 2024 营收按渠道变化').request, expected);
  // one year and no change word is a dashboard, not an attribution
  assert.equal(ask('2025 营收 按渠道').kind, 'dashboard');
});

test('topN understands digits, spaces and Chinese numerals, and caps itself', () => {
  assert.equal(topNIn('前3'), 3);
  assert.equal(topNIn('top 3'), 3);
  assert.equal(topNIn('top-5'), 5);
  assert.equal(topNIn('前三名'), 3);
  assert.equal(topNIn('前五'), 5);
  assert.equal(topNIn('前50'), 20, 'no question needs more than twenty rows');
  assert.equal(topNIn('营收按渠道'), null);
});

test('the years a question mentions, and the dimension it names', () => {
  assert.deepEqual(yearsIn('2024 到 2025'), [2024, 2025]);
  assert.deepEqual(yearsIn('2025年'), [2025]);
  assert.deepEqual(yearsIn('metric-2025-10'), [2025]);
  assert.deepEqual(yearsIn('没有年份'), []);
  assert.equal(dimensionIn(model, '按渠道拆分').node.id, 'dim/渠道');
  assert.equal(dimensionIn(model, '按 channel 看').node.id, 'dim/渠道');
  assert.equal(dimensionIn(model, '按地区').node.id, 'dim/地区');
  assert.equal(dimensionIn(model, '营收 2025'), null);
});

test('the longest reference wins, so 净营收 is not read as 营收', () => {
  const parsed = ask('2025 年净营收按渠道');
  assert.equal(parsed.request.metric, 'metric/净营收');
  assert.equal(ask('2025 年营收按渠道').request.metric, 'metric/营收');
});

test('a question naming no metric is refused with the alternatives, and an empty one asks nothing', () => {
  const unknown = ask('2025 年按渠道看一下那个东西');
  assert.equal(unknown.ok, false);
  assert.match(unknown.reason, /no metric/);
  assert.match(unknown.reason, /净营收/);
  const empty = ask('   ');
  assert.equal(empty.ok, false);
  assert.match(empty.reason, /empty question/);
  assert.match(renderQuestion(unknown), /^not understood:/);
  assert.match(renderQuestion(ask('2025 营收 按渠道')), /^understood as dashboard: metric=metric\/营收/);
});
