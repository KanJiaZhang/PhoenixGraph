import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { readSources } from '../src/readers/index.mjs';
import { extract, extractDocument } from '../src/extract/documents.mjs';
import { buildModel, factProperties, metricSql } from '../src/extract/model.mjs';

// The example files are read relative to the package, not to the shell: `npm test` runs in the package and
// a CI step or `node --test pi-data-agent/test/*.test.mjs` runs in the repository root. A test that only
// passes from one directory is a test that fails in the other.
const ROOT = fileURLToPath(new URL('..', import.meta.url));
const example = (path) => ROOT + path;

const factSchema = {
  name: 'demo-data',
  vertexSchemas: [{
    name: 'MarketingDay',
    properties: [
      'name', 'revenue', 'spend', 'impressions', 'clicks', 'newCustomers', 'channel', 'region', 'campaign', 'year', 'month',
    ].map((name) => ({ name, type: 'STRING' })),
  }],
};

async function modelOf(files = [example('examples/retail/docs/metrics.md'), example('examples/retail/schema.sql')]) {
  const read = await readSources(files);
  const observations = extract(read.documents);
  return buildModel({
    graph: 'demo', dataGraph: 'demo-data', vertexType: 'MarketingDay', documents: read.documents, observations, factSchema,
    now: '2026-10-30T00:00:00.000Z',
  });
}

test('prose definitions carry unit, aliases, evidence and a section', async () => {
  const read = await readSources([{ name: 'defs.md', text: '# 指标\n\n指标：GMV = SUM(order_amount)\n单位：元\n别名：成交额、收入\n口径：SUM(order_amount)\n\n# 维度\n维度：渠道=channel、地区=region\n' }]);
  const doc = read.documents[0];
  const extracted = extractDocument(doc);
  const gmv = extracted.metrics.find((metric) => metric.name === 'GMV');
  assert.ok(gmv);
  assert.equal(gmv.expression, 'SUM(order_amount)');
  assert.equal(gmv.unit, '元');
  assert.deepEqual(gmv.synonyms, ['成交额', '收入']);
  assert.match(gmv.evidence, /指标：GMV/);
  assert.equal(extracted.dimensions.length, 2);
  assert.equal(extracted.dimensions[0].column, 'channel');
});

test('the retail example extracts metrics, dimensions, terms and evidence', async () => {
  const model = await modelOf();
  const revenue = model.nodes.find((node) => node.id === 'metric/营收');
  assert.equal(revenue.properties.expression, 'SUM(revenue)');
  assert.equal(revenue.properties.unit, '元');
  assert.equal(revenue.properties.graphProperty, 'revenue');
  assert.match(revenue.properties.evidence, /SUM\(revenue\)/);
  assert.ok(revenue.properties.alias.includes('收入'));
  const ratio = model.nodes.find((node) => node.id === 'metric/点击率');
  assert.equal(ratio.properties.expression, '100 * SUM(clicks) / SUM(impressions)');
  assert.equal(ratio.properties.graphProperty, undefined);
  assert.equal(model.binding.unboundMetrics.includes('metric/点击率'), true);
  const date = model.nodes.find((node) => node.id === 'dim/日期');
  assert.equal(date.properties.graphProperty, 'year=year,month=month');
  assert.ok(model.nodes.some((node) => node.id === 'term/成交'));
  assert.ok(model.edges.some((edge) => edge.head === 'metric/营收' && edge.relation === 'computedFrom' && edge.tail === 'table/marketing'));
  assert.ok(model.edges.some((edge) => edge.head === 'dim/日期' && edge.relation === 'dimensionColumn' && edge.tail === 'column/date'));
});

test('duplicates with the same expression and binding merge, with a warning and aliases', async () => {
  const model = await modelOf([example('examples/retail/docs/metrics.md'), example('examples/retail/wiki/indicator.html'),
    example('examples/retail/schema.sql')]);
  const metricIds = model.nodes.filter((node) => node.entityType === 'Metric').map((node) => node.id);
  assert.equal(metricIds.includes('metric/revenue'), false);
  assert.equal(metricIds.includes('metric/营收'), true);
  assert.ok(model.warnings.some((warning) => /same definition\/binding/.test(warning)));
  const dimensionIds = model.nodes.filter((node) => node.entityType === 'Dimension').map((node) => node.id);
  assert.equal(dimensionIds.includes('dim/channel'), false);
  assert.ok(model.nodes.find((node) => node.id === 'dim/渠道').properties.alias.includes('channel'));
});

test('the Model node is self-describing and lists its catalog', async () => {
  const model = await modelOf();
  const modelNode = model.nodes.find((node) => node.entityType === 'Model');
  assert.equal(modelNode.properties.dataGraph, 'demo-data');
  assert.equal(modelNode.properties.vertexType, 'MarketingDay');
  assert.ok(modelNode.properties.nodeIds.includes('metric/营收'));
  assert.ok(modelNode.properties.edgeIds.some((id) => id.startsWith('metric/营收|computedFrom|')));
});

test('factProperties names the vertex type and refuses an unknown one', () => {
  const facts = factProperties(factSchema, null);
  assert.equal(facts.vertexType, 'MarketingDay');
  assert.ok(facts.properties.has('revenue'));
  assert.throws(() => factProperties(factSchema, 'NoSuchType'), /has no vertex type 'NoSuchType'/);
});

test('metricSql qualifies columns and applies a grain', async () => {
  const model = await modelOf();
  const total = metricSql(model, 'metric/营收');
  assert.match(total, /SELECT SUM\(marketing\.revenue\) AS 营收 FROM marketing;/);
  const monthly = metricSql(model, 'metric/营收', 'dim/日期', 'month');
  assert.match(monthly, /DATE_TRUNC\('month', date\)/);
  assert.match(monthly, /GROUP BY DATE_TRUNC\('month', date\)/);
});

test('a metric with no expression is kept as needs_review, never given a formula', () => {
  const model = buildModel({
    graph: 'x',
    observations: {
      metrics: [{ id: 'metric/转化率', name: '转化率', expression: null, description: '下单/访问', sourceId: 's', evidence: '转化率：下单/访问', confidence: 0.7 }],
      dimensions: [], terms: [], tables: [], columns: [], foreignKeys: [], queries: [], warnings: [],
    },
    documents: [{ id: 's', name: 's.md', title: 's', kind: 'text' }],
  });
  const node = model.nodes.find((candidate) => candidate.id === 'metric/转化率');
  assert.equal(node.properties.expression, undefined);
  assert.equal(node.properties.status, 'needs_review');
  assert.ok(model.warnings.some((warning) => /has no expression/.test(warning)));
});
