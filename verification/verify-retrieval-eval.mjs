// needs: service
// what: an answer-level evaluation of the retrieval tools - entity recall, relation recall and citation
//       coverage against a hand-checked gold set. It fails when the answers get worse, instead of printing
//       a green summary that means nothing.
// run:  node verification/run.mjs --only retrieval-eval
const API = (process.env.PHOENIXGRAPH_API || 'http://localhost:8081').replace(/\/+$/, '');
const GRAPH = process.env.AGENT_EVAL_GRAPH || 'agent-demo';
const K = Number(process.env.AGENT_EVAL_K || 4);

let failures = 0;
const check = (name, ok, detail) => {
  if (!ok) failures += 1;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};

const post = async (tool, body) => {
  const response = await fetch(`${API}/api/v1/agent/${tool}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    // reported by the caller when the shape is needed
  }
  if (!response.ok) {
    throw new Error(`${tool} -> ${response.status}: ${json?.error || text}`);
  }
  return json;
};

// The gold set: the entities a good answer must contain, the relations it must find, and a source fragment
// a citation should carry. Kept small on purpose - every row is checked by hand against the served corpus.
const cases = [
  {
    question: '诸葛亮和司马懿是什么关系',
    entities: ['诸葛亮', '司马懿'],
    relations: [['诸葛亮', 'fought', '司马懿']],
    citations: ['诸葛亮传'],
  },
  {
    question: '关羽斩杀了谁',
    entities: ['关羽', '颜良'],
    relations: [['关羽', 'killed', '颜良']],
    citations: ['关羽传'],
  },
  {
    question: '张飞和刘备是什么关系',
    entities: ['张飞', '刘备'],
    relations: [['张飞', 'sibling_of', '刘备']],
    citations: [],
  },
  {
    question: '诸葛亮驻守在哪里',
    entities: ['诸葛亮', '汉中'],
    relations: [['诸葛亮', 'stationed_at', '汉中']],
    citations: [],
  },
  {
    question: '谁教了诸葛亮',
    entities: ['水镜先生', '诸葛亮'],
    relations: [['水镜先生', 'teaches', '诸葛亮']],
    citations: [],
  },
];

const stats = [];
for (const item of cases) {
  const answer = await post('context', { graph: GRAPH, question: item.question, k: K, hops: 1 });
  const entityIds = new Set((answer.entities || []).map((entity) => entity.id));
  const relations = new Set((answer.relations || []).map(
    (relation) => `${relation.head}|${relation.relation}|${relation.tail}`,
  ));
  const cited = JSON.stringify(answer.citations || []);

  const entityHits = item.entities.filter((name) => entityIds.has(name));
  const relationHits = item.relations.filter(([head, relation, tail]) => relations.has(`${head}|${relation}|${tail}`));
  const citationHits = item.citations.filter((fragment) => cited.includes(fragment));
  const entityRecall = entityHits.length / item.entities.length;
  const relationRecall = item.relations.length === 0 ? 1 : relationHits.length / item.relations.length;
  const citationCoverage = item.citations.length === 0 ? 1 : citationHits.length / item.citations.length;

  stats.push({ entityRecall, relationRecall, citationCoverage });
  console.log(`\n  ${item.question}`);
  console.log(`    entities  ${entityHits.length}/${item.entities.length}  ${entityHits.join(', ')}`);
  console.log(`    relations ${relationHits.length}/${item.relations.length}  ${relationHits.map((r) => r.join(' ')).join('; ')}`);
  console.log(`    citations ${citationHits.length}/${item.citations.length}  ${(answer.coverage || {}).citations ?? 0} returned`);
}

const average = (key) => stats.reduce((sum, row) => sum + row[key], 0) / stats.length;
const entityRecall = average('entityRecall');
const relationRecall = average('relationRecall');
const citationCoverage = average('citationCoverage');

console.log(`\n  graph: ${GRAPH}  cases: ${cases.length}  k: ${K}`);
check('entity recall >= 0.9', entityRecall >= 0.9, entityRecall.toFixed(3));
check('relation recall >= 0.8', relationRecall >= 0.8, relationRecall.toFixed(3));
check('citation coverage = 1.0', citationCoverage >= 0.999, citationCoverage.toFixed(3));

console.log(failures
  ? `\n${failures} retrieval evaluation threshold(s) missed`
  : '\nthe retrieval answers still contain the entities, relations and citations the gold set expects');
process.exit(failures ? 1 : 0);
