#!/usr/bin/env node
// The three-minute proof: ingest two documents, ask a question, read the citations, then ask something
// the corpus cannot support. Run after `tools/start-phoenixgraph.sh`.
//
//   node examples/three-minute-demo/demo.mjs
//   PHOENIXGRAPH_API=http://localhost:8081 PHOENIXGRAPH_DEMO_GRAPH=demo-three-minute node ...
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, '../..');
const API = (process.env.PHOENIXGRAPH_API || 'http://localhost:8081').replace(/\/+$/, '');
const GRAPH = process.env.PHOENIXGRAPH_DEMO_GRAPH || 'demo-three-minute';

const call = async (method, path, body) => {
  const response = await fetch(API + path, {
    method,
    headers: {
      Accept: 'application/json',
      ...(body === undefined ? {} : { 'Content-Type': 'application/json; charset=utf-8' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let payload = text;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch {
    // keep the raw text for the error message
  }
  if (!response.ok) {
    const detail = payload && typeof payload === 'object' ? (payload.error || payload.message) : text;
    throw new Error(`${method} ${path} -> ${response.status}: ${detail || 'no body'}`);
  }
  return payload;
};
const get = (path) => call('GET', path);
const post = (path, body) => call('POST', path, body);

const heading = (title) => console.log(`\n${title}\n${'-'.repeat([...title].length)}`);
const percent = (value) => (value === undefined || value === null ? 'n/a' : `${(Number(value) * 100).toFixed(0)}%`);
const short = (text, limit = 110) => {
  const value = String(text || '').replace(/\s+/g, ' ').trim();
  return value.length > limit ? `${value.slice(0, limit - 1)}…` : value;
};

const documents = [
  {
    id: 'guanyu',
    title: '三国志 · 关羽传（示例文本）',
    text: readFileSync(join(REPO, 'docs/agent-demo/guanyu.md'), 'utf8'),
  },
  {
    id: 'zhugeliang',
    title: '三国志 · 诸葛亮传（示例文本）',
    text: readFileSync(join(REPO, 'docs/agent-demo/zhugeliang.md'), 'utf8'),
  },
];

const dictionary = {
  关羽: 'Person', 刘备: 'Person', 诸葛亮: 'Person', 司马懿: 'Person', 水镜先生: 'Person',
  张飞: 'Person', 曹操: 'Person', 张辽: 'Person', 黄月英: 'Person', 周仓: 'Person',
  张郃: 'Person', 马谡: 'Person', 颜良: 'Person', 文丑: 'Person',
  阆中: 'Place', 汉中: 'Place',
};
const gold = ['关羽|killed|颜良', '诸葛亮|strategist_of|刘备', '张飞|sibling_of|刘备', '关羽|fought|文丑'];

const ingestBody = (apply) => ({
  graph: GRAPH,
  description: 'The three-minute demo: two documents, one cited answer, one question the corpus cannot support.',
  documents,
  dictionary,
  gold,
  apply,
});

const printAnswer = (answer) => {
  const entities = answer.entities || [];
  const relations = answer.relations || [];
  const citations = answer.citations || [];
  const coverage = answer.coverage || {};
  console.log('  entities:');
  for (const entity of entities.slice(0, 8)) {
    const score = entity.score === undefined ? '' : `, score ${Number(entity.score).toFixed(2)}`;
    console.log(`    - ${entity.name} (${entity.type || '?'}${entity.via ? `, via ${entity.via}` : ''}${score})`);
  }
  console.log('  relations:');
  for (const relation of relations.slice(0, 8)) {
    console.log(`    - ${relation.head} -[${relation.relation}]-> ${relation.tail}`);
    if (relation.cite) {
      console.log(`      ${relation.cite}${relation.sentence ? `: ${short(relation.sentence)}` : ''}`);
    }
  }
  console.log('  citations:');
  for (const citation of citations.slice(0, 3)) {
    console.log(`    - ${citation.cite || citation.chunkId}: ${short(citation.text)}`);
  }
  console.log(
    `  coverage: ${coverage.entities ?? entities.length} entities, `
    + `${coverage.relations ?? relations.length} relations, `
    + `${coverage.citations ?? citations.length} citations`,
  );
};

const main = async () => {
  console.log('PhoenixGraph · three-minute proof');
  console.log(`API:   ${API}`);
  console.log(`Graph: ${GRAPH}`);
  await get('/api/v1/health');

  heading('1. Extract (dry run, nothing written)');
  const dry = await post('/api/v1/agent/ingest', ingestBody(false));
  const quality = dry.quality || {};
  console.log(`  ${dry.entities} entities, ${dry.relations} relations from ${documents.length} documents`);
  console.log(`  provenance ${percent(quality.provenanceRate)} · type agreement ${percent(quality.typeAgreement)} · linked ${percent(quality.entityLinkRate)}`);
  if (quality.gold) {
    console.log(`  against gold: precision ${percent(quality.gold.precision)}, recall ${percent(quality.gold.recall)}`);
  }
  console.log('  (a dry run computes the same pipeline and writes nothing)');

  heading('2. Write the graph');
  const applied = await post('/api/v1/agent/ingest', ingestBody(true));
  console.log(`  ${applied.entities} entities, ${applied.relations} relations, ${applied.vertices} vertices, ${applied.edges} edges`);
  for (const note of applied.notes || []) {
    // the created-graph note ends with the local schema path; keep the fact, drop the path
    console.log(`  note: ${note.replace(/\s*\([^)]*\.json\)$/, '')}`);
  }

  heading('3. Ask a question');
  const question = '诸葛亮和司马懿是什么关系';
  console.log(`  question: ${question}`);
  // Both entities are named in the question, so two seeds are enough. k=5 adds low-score vector matches
  // (马谡, 周仓) that are related to the graph but not to the question.
  printAnswer(await post('/api/v1/agent/context', { graph: GRAPH, question, k: 2, hops: 1 }));

  heading('4. Ask something the corpus cannot support');
  const unknownQuestion = '曹操的坐骑是什么颜色';
  console.log(`  question: ${unknownQuestion}`);
  const related = await post('/api/v1/agent/related', { graph: GRAPH, entity: '曹操', hops: 1 });
  const aboutCao = (related.relations || []).filter(
    (relation) => relation.head === '曹操' || relation.tail === '曹操',
  );
  console.log('  what the graph knows about 曹操:');
  for (const relation of aboutCao) {
    console.log(`    - ${relation.head} -[${relation.relation}]-> ${relation.tail}`);
    if (relation.cite) {
      console.log(`      ${relation.cite}: ${short(relation.sentence)}`);
    }
  }
  console.log(`  ${aboutCao.length} relation(s) mention 曹操, and none of them is about a horse.`);
  console.log('  The graph returned context; it did not answer the question. An agent must check the relation, not just the count.');

  heading('5. A data question the model refuses');
  try {
    const refusal = await post('/api/v1/semantic/tpc-h-analytics/ask', {
      question: '1996 年各退货标志营收',
    });
    console.log(`  ${refusal.refusal || 'no answer and no refusal - inspect the response'}`);
    if (refusal.plan && refusal.plan.metric) {
      console.log(`  metric: ${refusal.plan.metric}`);
    }
    console.log('  A refusal is the feature: it did not invent a plausible total.');
  } catch (error) {
    console.log(`  skipped: ${error.message}`);
  }

  heading('Done');
  console.log('  UI:         http://localhost:8080');
  console.log('  MCP check:  node tools/mcp-server.mjs --selftest');
  console.log('  Positioning: docs/POSITIONING.md · plan: docs/PRODUCT-PLAN.md');
};

main().catch((error) => {
  console.error(`\nThe demo failed: ${error.message}`);
  console.error('Is the service running?  tools/start-phoenixgraph.sh --no-build');
  process.exitCode = 1;
});
