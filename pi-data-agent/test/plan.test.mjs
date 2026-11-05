import test from 'node:test';
import assert from 'node:assert/strict';
import { planFor, planForQuestion, renderPlan, summarisePlan } from '../src/plan.mjs';
import { askFor, runPlanned } from '../src/service.mjs';

const model = {
  graph: 'sales-model',
  asOf: '2026-11-05T00:00:00.000Z',
  nodes: [
    { id: 'model/sales-model', entityType: 'Model', properties: { dataGraph: 'sales-facts', vertexType: 'SalesDay', factIds: ['sd-1', 'sd-2'] } },
    { id: 'metric/营收', entityType: 'Metric', properties: { name: '营收', alias: ['收入'], expression: 'SUM(net_revenue)', graphProperty: 'net_revenue', unit: '元' } },
    { id: 'metric/毛利率', entityType: 'Metric', properties: { name: '毛利率' } },
    { id: 'dim/渠道', entityType: 'Dimension', properties: { name: '渠道', graphProperty: 'channel' } },
    { id: 'dim/地区', entityType: 'Dimension', properties: { name: '地区', graphProperty: 'region' } },
    { id: 'dim/日期', entityType: 'Dimension', properties: { name: '日期', graphProperty: 'year=year,month=month', dataType: 'DATE' } },
  ],
  edges: [
    { id: 'metric/营收|groupedBy|dim/渠道', head: 'metric/营收', relation: 'groupedBy', tail: 'dim/渠道', properties: {} },
  ],
};

const types = (decisions) => decisions.map((decision) => decision.key);

test('the plan names every decision, and says which ones it made up', () => {
  const plan = planFor(model, { kind: 'dashboard', request: { metric: '营收', year: '2025', dimensions: ['channel'], topN: 3 } });
  assert.equal(plan.ok, true);
  assert.equal(plan.kind, 'dashboard');
  assert.deepEqual(types(plan.decisions), ['metric', 'dimension', 'filter', 'topN', 'algorithm', 'binding']);
  const metric = plan.decisions[0];
  assert.equal(metric.node, 'metric/营收');
  assert.equal(metric.source, 'resolved', 'a name is resolved, not explicit');
  assert.equal(metric.how, 'id', 'a bare name is also the id suffix, so it resolves at the id level');
  assert.match(metric.detail, /SUM\(net_revenue\)/);
  assert.equal(plan.decisions[1].source, 'explicit', 'the dimension was asked for');
  assert.equal(plan.decisions[2].value, 'year = 2025');
  assert.equal(plan.decisions[4].key, 'algorithm');
  assert.match(plan.decisions[4].value, /values\('net_revenue'\)\.sum\(\)/);
  assert.match(plan.decisions[5].value, /sales-facts\/SalesDay/);
  assert.deepEqual(plan.inferred, ['指标', '算法', '事实图']);
  assert.equal(plan.needsConfirmation, true);
});

test('a metric given by id is explicit; an unnamed dimension and an unnamed period are defaults', () => {
  const plan = planFor(model, { kind: 'dashboard', request: { metric: 'metric/营收' } });
  assert.equal(plan.decisions[0].source, 'explicit');
  const dimension = plan.decisions.find((decision) => decision.key === 'dimension');
  assert.equal(dimension.source, 'default');
  assert.match(dimension.value, /默认/);
  const filter = plan.decisions.find((decision) => decision.key === 'filter');
  assert.equal(filter.source, 'default');
  assert.match(filter.value, /全部年份/);
  assert.ok(plan.inferred.includes('维度'));
  assert.equal(plan.needsConfirmation, true);
});

test('a plan that will refuse says so before anything runs', () => {
  const plan = planFor(model, { kind: 'dashboard', request: { metric: '毛利率', year: '2025' } });
  assert.equal(plan.ok, true, 'the graph is fine; the metric cannot answer');
  assert.equal(plan.decisions[0].source, 'resolved');
  assert.match(plan.decisions[0].detail, /没有表达式/);
  const algorithm = plan.decisions.find((decision) => decision.key === 'algorithm');
  assert.equal(algorithm.source, 'missing');
  assert.equal(algorithm.value, '不执行');
  assert.ok(plan.refusals.length >= 1);

  const unknown = planFor(model, { kind: 'attribution', request: { metric: '营收', by: 'nope', from: '2024', to: '2025' } });
  assert.equal(unknown.ok, false);
  assert.ok(unknown.refusals.some((line) => /nope/.test(line)));
  assert.equal(unknown.needsConfirmation, true);
});

test('an attribution plans its two periods and its arithmetic, and a question plans itself', () => {
  const plan = planFor(model, { kind: 'attribution', request: { metric: '营收', by: '渠道', from: '2024', to: '2025' } });
  assert.equal(plan.kind, 'attribution');
  assert.deepEqual(types(plan.decisions), ['metric', 'dimension', 'filter', 'algorithm', 'binding']);
  assert.equal(plan.decisions[2].value, 'from = 2024 → to = 2025');
  assert.match(plan.decisions[3].value, /delta/);

  const asked = planForQuestion(model, '2025年收入按渠道拆分，前3');
  assert.equal(asked.ok, true);
  assert.equal(asked.question, '2025年收入按渠道拆分，前3');
  assert.deepEqual(asked.request, { metric: 'metric/营收', year: '2025', dimensions: ['dim/渠道'], topN: 3 });
  assert.match(asked.understood.metric, /alias/);
  const notUnderstood = planForQuestion(model, '2025 年按渠道看那个东西');
  assert.equal(notUnderstood.ok, false);
  assert.match(notUnderstood.reason, /no metric/);
});

test('the rendered plan carries the markers a person needs to see', () => {
  const text = renderPlan(planForQuestion(model, '2025年营收按渠道拆分，前3'));
  assert.match(text, /^问题：/);
  assert.match(text, /· 指标：metric\/营收/);
  assert.match(text, /~ 算法：/);
  assert.match(text, /解析得到 \/ \? 默认值：/);
  assert.match(text, /· 明确指定 · ~ 由模型解析 · \? 默认值 · ! 会拒答/);

  const refusal = renderPlan(planFor(model, { kind: 'dashboard', request: { metric: '毛利率' } }));
  assert.match(refusal, /! 会拒答：.*没有表达式|! 会拒答：/);
  assert.equal(summarisePlan(planForQuestion(model, '2025年营收按渠道拆分，前3')), 'dashboard: metric/营收 by dim/渠道 year = 2025');
});

/** A client whose every query is recorded, so "declined" can be asserted as "no fact query happened". */
function fakeClient() {
  const queries = [];
  return {
    queries,
    async readModel() {
      return { nodes: model.nodes, edges: model.edges, viaCatalog: false };
    },
    async query(graph, gremlin) {
      queries.push({ graph, gremlin });
      if (graph === 'sales-facts') {
        if (gremlin.includes("group().by('channel')")) return [{ APP: 100 }];
        if (gremlin.includes("group().by('month')")) return [{ 1: 40, 2: 60 }];
        return [100];
      }
      return [];
    },
  };
}

test('a declined plan executes nothing, an accepted one runs, and no gate means it runs unconfirmed', async () => {
  const declinedClient = fakeClient();
  const declined = await runPlanned({ question: '2025年营收按渠道拆分，前3' }, {
    client: declinedClient, graph: 'sales-model', write: false,
    confirm: async () => false,
  });
  assert.equal(declined.ok, false);
  assert.equal(declined.cancelled, true);
  assert.match(declined.planText, /将执行：看板/);
  assert.deepEqual(declinedClient.queries.filter((query) => query.graph === 'sales-facts'), [], 'declining must not touch the fact graph');

  const acceptedClient = fakeClient();
  let shown = null;
  const accepted = await runPlanned({ question: '2025年营收按渠道拆分，前3' }, {
    client: acceptedClient, graph: 'sales-model', write: false,
    confirm: async (plan, planText) => { shown = planText; return true; },
  });
  assert.equal(accepted.ok, true);
  assert.equal(accepted.confirmed, true);
  assert.match(shown, /metric\/营收/);
  assert.ok(acceptedClient.queries.some((query) => query.graph === 'sales-facts'), 'accepting runs the traversal');

  const unconfirmed = await runPlanned({ question: '2025年营收按渠道拆分，前3' }, { client: fakeClient(), graph: 'sales-model', write: false });
  assert.equal(unconfirmed.ok, true);
  assert.equal(unconfirmed.confirmed, false, 'the answer says it was not confirmed');
});

test('askFor is the same gate: a decline returns the plan, an accept returns the numbers', async () => {
  const declined = await askFor('2024 到 2025 营收按渠道为什么增长', {
    client: fakeClient(), graph: 'sales-model', confirm: async () => false,
  });
  assert.equal(declined.ok, false);
  assert.equal(declined.cancelled, true);
  assert.equal(declined.kind, 'attribution');
  assert.match(declined.planText, /归因/);

  const accepted = await askFor('2024 到 2025 营收按渠道为什么增长', {
    client: fakeClient(), graph: 'sales-model', confirm: async () => true,
  });
  assert.equal(accepted.ok, true);
  assert.equal(accepted.confirmed, true);
  assert.equal(accepted.result.executed, true);
});
