#!/usr/bin/env node
/**
 * The end-to-end case: own data in, metadata layer built from documents, relations, vector + graph retrieval,
 * reviewer edits, a definition change with a validity window, and the scheduled dashboard + attribution.
 *
 * Every step prints what it did (the sources, the operations, the traversals, the SQL) and what came back, and the
 * same transcript is written to a Markdown document so the run can be checked afterwards:
 *
 *   node examples/sales-case/run-case.mjs                          # print, and write docs/CASE-DATA-AGENT.md
 *   node examples/sales-case/run-case.mjs --dry-run                # print only
 *   PHOENIXGRAPH_API=http://localhost:8081 node examples/sales-case/run-case.mjs
 */
import process from 'node:process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { clientFor, scanSources, buildDataAgent, loadModel, editDataAgent, dashboardFor, attributeFor } from '../../src/service.mjs';
import { similarWithGraph } from '../../src/similar.mjs';
import { runSchedule, renderSchedule } from '../../src/schedule.mjs';
import { baseId, windowText, isVersion } from '../../src/validity.mjs';
import { importFacts } from './generate.mjs';

const PACKAGE = fileURLToPath(new URL('../../', import.meta.url));
const CASE_DIR = join(PACKAGE, 'examples', 'sales-case');
const DOC = join(PACKAGE, 'docs', 'CASE-DATA-AGENT.md');
const GRAPH = 'sales-model';
const FACT_GRAPH = 'sales-facts';
const VERTEX_TYPE = 'SalesDay';
const CHANGE = '2026-10-15T00:00:00Z';

const args = process.argv.slice(2);
const dryRun = args.includes('--dry-run');
const steps = [];
let step = null;

function start(title, purpose) {
  step = { title, purpose, facts: [], blocks: [], notes: [], conclusion: '' };
  steps.push(step);
  console.log(`\n=== ${step.number = steps.length}. ${title}`);
  if (purpose) console.log(purpose);
}

function fact(line) {
  if (!step) return;
  step.facts.push(line);
  console.log(`  · ${line}`);
}

function block(title, code) {
  if (!step) return;
  step.blocks.push({ title, code: String(code).trim() });
}

function note(line) {
  if (!step) return;
  step.notes.push(line);
  console.log(`  → ${line}`);
}

function conclude(line) {
  if (!step) return;
  step.conclusion = line;
  console.log(`  ✓ ${line}`);
}

/**
 * The case is re-runnable: empty the model graph before rebuilding it.
 *
 * Edges are read from the vertices (outE) rather than from the catalog, because a catalog is itself a vertex: a
 * previous run's edges are not in today's catalog and would survive as dangling relations.
 */
async function resetModel(client, graph) {
  let read = null;
  try { read = await client.readModel(graph); } catch { /* no model graph yet */ }
  let catalog = null;
  try { catalog = await client.catalog(graph); } catch { /* no catalog */ }
  const nodes = [...new Set([...(read?.nodes || []).map((node) => node.id), ...((catalog?.nodeIds || []).map(String))])];
  if (!nodes.length) return { vertices: 0, edges: 0 };
  let edges = 0;
  for (const edge of read?.edges || []) {
    try { await client.deleteEdge(graph, { edgeId: edge.id, edgeSchemaName: 'Relation', source: edge.head, destination: edge.tail }); edges += 1; } catch { /* already gone */ }
  }
  let vertices = 0;
  for (const id of nodes) {
    try { await client.deleteVertex(graph, id); vertices += 1; } catch { /* already gone */ }
  }
  return { vertices, edges };
}

function graphOf(client) {
  return client.graphs().then((graphs) => (graphs || []).find((candidate) => candidate.name === FACT_GRAPH) || null);
}

try {
  const config = { endpoint: process.env.PHOENIXGRAPH_API || 'http://localhost:8081', apiKey: process.env.PHOENIXGRAPH_API_KEY };
  const client = clientFor(config);

  // ---------------------------------------------------------------- 1. the data the case owns
  start('生成并导入案例自己的事实数据',
    'Case 不依赖任何外部数据集：自己定义事实表、自己生成（固定种子）、自己通过 HTTP 写接口导入 PhoenixGraph。');
  const facts = await importFacts({ ...config, client, graph: FACT_GRAPH, years: [2024, 2025, 2026] });
  const storage = await graphOf(client);
  const sample = await client.query(FACT_GRAPH, `g.V('${facts.rows[0].id}','${VERTEX_TYPE}').elementMap()`);
  fact(`生成 ${facts.rows.length} 行（2024–2026 × 12 月 × 4 渠道 × 3 地区），写入 ${facts.written} 行${facts.created ? '，图与 schema 由本次创建' : ''}`);
  fact(`图 ${FACT_GRAPH}：${storage?.storage || '?'}，顶点类型 ${(storage?.vertexTypes || []).join(', ')}`);
  fact(`一行样例：${JSON.stringify(sample[0] || {})}`);
  block('事实表 schema（由案例创建）', `vertex type SalesDay
  year long · month long · channel string · region string · campaign string
  orders long · customers long · revenue double · net_revenue double · cost double`);
  note('退款率在 2026 年提高到 3.5%–5%，这是后面「营收口径变更」在数字上能被看见的原因。');
  conclude(`${facts.rows.length} 行事实数据在 ${FACT_GRAPH}/${VERTEX_TYPE} 中，可被语义层绑定与遍历。`);

  // ---------------------------------------------------------------- 2. documents -> metadata
  start('从文档 / DDL / SHOW TABLES 识别元数据',
    '让包自己读案例的指标字典、DDL 与 SHOW TABLES 输出，抽取表、列、指标、维度、术语，并带上原句证据。');
  const scan = await scanSources([CASE_DIR], { ...config, client, graph: GRAPH, dataGraph: FACT_GRAPH, vertexType: VERTEX_TYPE });
  fact(`读了 ${scan.documents.length} 个来源：${scan.documents.map((document) => document.name).join(', ')}`);
  fact(`抽取：${scan.model.stats.tables} 表 / ${scan.model.stats.columns} 列 / ${scan.model.stats.metrics} 指标 / ${scan.model.stats.dimensions} 维度 / ${scan.model.stats.queries} 参考查询`);
  fact(`review：${scan.review.length} 项待确认（${scan.review.slice(0, 4).map((item) => `${item.id}:${item.kind}`).join(', ')}${scan.review.length > 4 ? ', …' : ''}）`);
  const metricLines = scan.model.nodes.filter((node) => node.entityType === 'Metric')
    .map((node) => `${node.id} | ${node.properties.expression || '(无表达式)'} | ${node.properties.graphProperty || '(未绑定)'} | ${node.properties.unit || ''}`);
  block('指标预览（表达式 | 绑定 | 单位）', metricLines.join('\n'));
  block('一条指标的原句证据', JSON.stringify(scan.model.nodes.find((node) => node.id === 'metric/营收')?.properties.evidence, null, 2));
  conclude('元数据从文档里读出来，每条都带原句；没有表达式的会成为待评审项，而不是被猜一个公式。');

  // ---------------------------------------------------------------- 3. build + auto schema
  start('写入模型图：schema 自动创建',
    `把同一批元数据写进 ${GRAPH}（新图自动建 schema），并绑定到 ${FACT_GRAPH}/${VERTEX_TYPE}。`);
  // the 2026-10 definition is a *reviewer* change (step 9), so the build reads the 2025 dictionary - but step 2
  // scanned it too, which is why the review list shows the two definitions of 营收 as a conflict to resolve.
  const reset = await resetModel(client, GRAPH);
  if (reset.vertices || reset.edges) fact(`重置已有的模型图：删除 ${reset.vertices} 个顶点 / ${reset.edges} 条边（所以本案例可重复运行）`);
  const v1 = [join(CASE_DIR, 'docs/metrics.md'), join(CASE_DIR, 'schema.sql'), join(CASE_DIR, 'show_tables.txt')];
  // a keyed fact graph cannot scan a vertex type, so the model records the fact ids it is bound to;
  // a semantic query is then g.V(ids...) - one traversal, addressed the way the store answers.
  const factIds = facts.rows.map((row) => row.id);
  const build = await buildDataAgent(v1, { ...config, client, graph: GRAPH, dataGraph: FACT_GRAPH, vertexType: VERTEX_TYPE, factIds, validFrom: '2024-01-01T00:00:00Z', snapshot: false });
  const schema = await client.schema(GRAPH);
  fact(`写入 ${build.write.vertices} 个顶点、${build.write.edges} 条边；schema ${build.write.created ? '本次创建' : '已存在'}`);
  fact(`schema 里声明的类型：${(schema?.vertexSchemas || []).map((vertex) => `${vertex.name}(${(vertex.properties || []).length} 属性)`).join(', ')} / ${(schema?.edgeSchemas || []).map((edge) => edge.name).join(', ')}`);
  fact(`catalog 写在 Model 顶点上：nodeIds=${(build.model.nodes.find((node) => node.entityType === 'Model')?.properties.nodeIds || []).length}、edgeIds=${(build.model.nodes.find((node) => node.entityType === 'Model')?.properties.edgeIds || []).length}（keyed store 只能按 id 读）`);
  fact(`事实绑定：${FACT_GRAPH}/${VERTEX_TYPE}，并记录 ${factIds.length} 个事实 id（Aerospike 不能按类型扫描，语义查询用 g.V(ids...)）`);
  block('schema 的顶点属性（节选）', (schema?.vertexSchemas?.[0]?.properties || []).slice(0, 12).map((property) => `${property.id} ${property.name} ${property.type}`).join('\n'));
  note('每个节点与边都写了 validFrom（构建时刻），这是第 6 步「口径变更后仍可回溯」的基础。');
  conclude('模型图与它的 schema 都在 PhoenixGraph 里，不依赖任何手工建 schema 的步骤。');

  // ---------------------------------------------------------------- 4. relations
  start('关系：依赖 / 贡献 / 包含 / 血缘',
    '关系有两个来源，都在上一步写进了图：SQL DDL 的外键与列归属，文档里的「属于 · 域」「指标来自表」等句子。');
  const model = await loadModel(client, GRAPH);
  const byRelation = {};
  for (const edge of model.edges) byRelation[edge.relation] = (byRelation[edge.relation] || 0) + 1;
  fact(`边：${model.edges.length} 条，${Object.entries(byRelation).map(([relation, count]) => `${relation}=${count}`).join(' ')}`);
  const examples = ['hasColumn', 'references', 'computedFrom', 'groupedBy', 'belongsTo', 'describes']
    .map((relation) => model.edges.find((edge) => edge.relation === relation)).filter(Boolean);
  for (const edge of examples) {
    const evidence = String(edge.properties?.evidence || '').replace(/\s+/g, ' ').slice(0, 120);
    fact(`${edge.head} -${edge.relation}-> ${edge.tail}${evidence ? `  证据:"${evidence}"` : ''}`);
  }
  block('关系与反向名 / 家族（schema 里声明的）', (schema?.edgeSchemas?.[0]?.properties || []).filter((property) => ['relation', 'xrelation', 'relationLabel', 'relationFamily'].includes(property.name)).map((property) => `${property.name}=${property.description}`).join('\n'));
  conclude('依赖/包含/血缘/归因关系在模型图里，每条都带证据句，关系类型由 schema 声明而不是自由字符串。');

  // ---------------------------------------------------------------- 5. vector + graph retrieval
  start('向量相似 + 图关联：把「收入」问成具体定义',
    '向量只回答「你大概指哪一个」，图回答「那个定义由什么构成」，两者组合才允许报价。');
  const similar = similarWithGraph(model, '收入 按渠道拆分 怎么算', { k: 4, hops: 1 });
  fact(`查询："${similar.query}" → ${similar.matches.length} 个候选`);
  for (const match of similar.matches) {
    fact(`${match.score.toFixed(3)} ${match.id}（${match.entityType}）${match.name}，关系 ${match.relations.length} 条`);
  }
  fact(`扩展出的子图：${similar.nodes.length} 个节点 / ${similar.edges.length} 条边；孤立候选 ${similar.isolated.length} 个`);
  block('候选与它们的图邻域', similar.matches.map((match) => [
    `${match.score.toFixed(3)} ${match.id}  ${match.text}`,
    ...match.relations.slice(0, 6).map((relation) => `    ${relation.direction === 'out' ? '->' : '<-'} ${relation.relation} ${relation.other} (${relation.otherType})`),
  ].join('\n')).join('\n\n'));
  note('向量由包内的确定性 hashing embedder 计算（768 维，词 + 字符 n-gram，L2 归一），换成本地模型只换一个函数。');
  conclude('「收入」被定位到 metric/营收，再由图拿到它的绑定、来源表、可切分维度；这就是 vector + graph 的组合检索。');

  // ---------------------------------------------------------------- 6. dashboard
  start('看板：指标 × 维度 × 期间，全部落在事实图上',
    '每个 tile 都是语义查询：遍历 + SQL 都打印出来，不能执行的 tile 给拒答理由而不是 0。');
  const board = await dashboardFor({ ...config, client, graph: GRAPH, year: '2025', topN: 3 });
  fact(`2025 年：${board.dashboard.executed} 个 tile 执行，${board.dashboard.refused} 个拒答`);
  for (const tile of board.dashboard.tiles.filter((candidate) => candidate.kind === 'total').slice(0, 5)) {
    fact(`${tile.metric}：${tile.executed ? tile.total : `拒答（${tile.reason}）`}`);
  }
  const revenueTile = board.dashboard.tiles.find((tile) => tile.kind === 'total' && tile.metric === 'metric/营收');
  block('营收总额的遍历与 SQL', `${revenueTile?.gremlin || '(none)'}\n${revenueTile?.sql || '(none)'}`);
  fact(`看板文件：${board.files?.html}`);
  conclude('看板是模型驱动的：换指标/维度/期间只改参数，遍历与 SQL 由模型生成。');

  // ---------------------------------------------------------------- 7. attribution
  start('归因：2024 → 2025 按渠道拆解',
    '贡献之和必须等于变化量；结果明说「描述性、不是因果」。');
  const attribution = await attributeFor({ ...config, client, graph: GRAPH, metric: '营收', by: '渠道', from: '2024', to: '2025' });
  const result = attribution.result;
  fact(`执行：${result.executed}${result.executed ? '' : `（${result.reason}）`}`);
  if (result.attribution) {
    fact(`${result.attribution.from} → ${result.attribution.to}：${Math.round(result.attribution.totalBefore)} → ${Math.round(result.attribution.totalAfter)}（Δ ${Math.round(result.attribution.delta)}）`);
    for (const contribution of result.attribution.contributions.slice(0, 4)) {
      fact(`${contribution.value}：Δ ${Math.round(contribution.delta)}，shareOfChange ${contribution.shareOfChange}%`);
    }
    fact(`contributionsSum=${Math.round(result.attribution.contributionsSum)}，decompositionAddsUp=${result.attribution.decompositionAddsUp}`);
    block('归因的限制声明', result.attribution.limits.join('\n'));
  }
  conclude('归因把变化拆到一个维度上，算术自证（和为 delta），并拒绝因果化。');

  // ---------------------------------------------------------------- 8. edits
  start('用户修改 / 删除：受校验、带审计',
    '改一个指标的属性、给它加别名、新增一个术语实体、删掉一条关系 —— 全部走 applyEdits 的校验、before/after 与审计。');
  const describeEdge = (await loadModel(client, GRAPH)).edges.find((edge) => edge.relation === 'describes' && edge.tail === 'term/成交');
  const operations = [
    { op: 'update-node', id: 'metric/营收', properties: { description: '毛额口径（2025 版）：成交金额未扣退款' } },
    { op: 'add-alias', id: 'metric/营收', alias: '销售额' },
    { op: 'upsert-node', node: { id: 'term/口径草案', entityType: 'Term', properties: { name: '口径草案', description: '评审中：营收是否改为净额', proposedBy: 'reviewer' } } },
  ];
  const edits = await editDataAgent(operations, { ...config, client, graph: GRAPH });
  fact(`写入：${JSON.stringify(edits.written)}；审计 ${edits.audit.length} 条`);
  for (const record of edits.audit) fact(`${record.op} ${record.id}${record.before ? '（有 before）' : ''}`);
  // a relation delete is not repeatable: the second run finds it already gone, which is the right answer too
  let deleted = null;
  if (describeEdge) {
    const removal = await editDataAgent([{ op: 'delete-edge', edge: { head: describeEdge.head, relation: describeEdge.relation, tail: describeEdge.tail }, confirm: true }], { ...config, client, graph: GRAPH });
    deleted = removal.written.deletedEdges;
    fact(`删除关系：${describeEdge.head} -describes-> ${describeEdge.tail}（deletedEdges=${deleted}）`);
  } else {
    note('关系 source -> term/成交 已在上一轮删除（重跑不会重复删除）。');
  }
  block('编辑操作（原始 JSON）', JSON.stringify([
    ...operations,
    describeEdge ? { op: 'delete-edge', edge: { head: describeEdge.head, relation: 'describes', tail: describeEdge.tail }, confirm: true } : { op: 'delete-edge', note: 'already removed in an earlier run' },
  ], null, 2));
  note(`审计文件：${edits.auditFile}；快照：${edits.snapshot}`);
  conclude('新增、修改、删除实体与关系都可做，且每次写入都留 before/after 与审计行。');

  // ---------------------------------------------------------------- 9. temporal
  start('口径变更（带时间戳）+ 回溯',
    '2026-10-15 起「营收」改为净额口径：旧定义被冻结成版本节点，新定义从该日生效；asOf 可以取回旧定义。');
  const change = await editDataAgent([{
    op: 'update-node', id: 'metric/营收', effectiveFrom: CHANGE,
    properties: { expression: 'SUM(net_revenue)', graphProperty: 'net_revenue', description: '净额口径（2026-10-15 起）：成交金额扣除退款' },
  }], { ...config, client, graph: GRAPH });
  const versionAudit = change.audit.find((record) => record.op === 'supersede');
  fact(`supersede：旧定义冻结为 ${versionAudit?.version || '?'}，窗口 ${windowText({ validFrom: versionAudit?.validFrom, validTo: versionAudit?.validTo })}`);
  const before = await loadModel(client, GRAPH, { asOf: '2026-10-01T00:00:00Z' });
  const after = await loadModel(client, GRAPH, { asOf: '2026-11-01T00:00:00Z' });
  const metricOf = (model) => model.nodes.find((node) => node.id === 'metric/营收')?.properties || {};
  fact(`asOf 2026-10-01：expression=${metricOf(before).expression}，graphProperty=${metricOf(before).graphProperty}${metricOf(before).definitionId ? `（来自版本 ${metricOf(before).definitionId}）` : ''}`);
  fact(`asOf 2026-11-01：expression=${metricOf(after).expression}，graphProperty=${metricOf(after).graphProperty}`);
  const dashboardAt = async (asOf) => dashboardFor({ ...config, client, graph: GRAPH, year: '2026', asOf, write: false });
  const oldBoard = await dashboardAt('2026-10-01T00:00:00Z');
  const newBoard = await dashboardAt('2026-11-01T00:00:00Z');
  const totalOf = (board) => board.dashboard.tiles.find((tile) => tile.kind === 'total' && tile.metric === 'metric/营收');
  const oldTile = totalOf(oldBoard);
  const newTile = totalOf(newBoard);
  fact(`2026 年营收，按 2026-10-01 的口径：${oldTile?.total}（${oldTile?.sql}）`);
  fact(`2026 年营收，按 2026-11-01 的口径：${newTile?.total}（${newTile?.sql}）`);
  block('版本节点与窗口', [
    `live  node: metric/营收        validFrom=${new Date(Number(after.nodes.find((n) => n.id === 'metric/营收')?.properties?.validFrom)).toISOString()}`,
    `version   : ${versionAudit?.version}  ${windowText({ validFrom: versionAudit?.validFrom, validTo: versionAudit?.validTo })}`,
    `relation  : ${versionAudit?.version}|supersededBy|metric/营收`,
  ].join('\n'));
  note(`模型里现在有 ${after.versions} 个版本节点；默认视图只显示当前定义，asOf 才会取回旧的。`);
  conclude('同一指标两个口径按时间戳共存：默认读当前，asOf 读历史，SQL/遍历随定义一起变化。');

  // ---------------------------------------------------------------- 10. schedule
  start('定时生成看板 + 归因（三个周期）',
    '把上面两步做成周期性任务：每个期间一个循环，产物流落盘，运行日志保留。');
  const schedule = await runSchedule({
    ...config, client, graph: GRAPH, periods: ['2024', '2025', '2026'],
    metric: '营收', by: '渠道', everyMs: 800, outDir: join(CASE_DIR, 'artifacts'),
    onRun: (run) => fact(`周期 ${run.index + 1}/${3} ${run.period}：看板 ${run.dashboard.executed} 执行 / ${run.dashboard.refused} 拒答${run.attribution?.executed ? `；归因 Δ ${Math.round(run.attribution.delta)}` : ''}`),
  });
  block('调度日志', renderSchedule(schedule));
  note(`产物目录：${join(CASE_DIR, 'artifacts')}（HTML + JSON，每周期一份）`);
  conclude(`${schedule.cycles} 个周期全部产出看板与归因，共 ${schedule.executed} 个执行 tile、${schedule.refused} 个拒答。`);

  // ---------------------------------------------------------------- 11. state of the model now
  start('收尾：模型现状',
    '把当前模型的状态记下来，便于与文档对照。');
  const finalModel = await loadModel(client, GRAPH);
  fact(`节点 ${finalModel.nodes.length}（另 ${finalModel.versions} 个历史版本节点不在默认视图中）、边 ${finalModel.edges.length}`);
  fact(`指标 ${finalModel.nodes.filter((node) => node.entityType === 'Metric').length}、维度 ${finalModel.nodes.filter((node) => node.entityType === 'Dimension').length}、表 ${finalModel.nodes.filter((node) => node.entityType === 'Table').length}、列 ${finalModel.nodes.filter((node) => node.entityType === 'Column').length}、术语 ${finalModel.nodes.filter((node) => node.entityType === 'Term').length}`);
  fact(`asOf ${finalModel.asOf}`);
  conclude('案例结束时模型可读、可回溯、可继续编辑，事实图数据独立于模型图。');

  // ---------------------------------------------------------------- write the document
  if (!dryRun) {
    const markdown = renderDoc(steps, { endpoint: config.endpoint, graph: GRAPH, factGraph: FACT_GRAPH, vertexType: VERTEX_TYPE });
    mkdirSync(dirname(DOC), { recursive: true });
    writeFileSync(DOC, markdown);
    console.log(`\ntranscript written to ${DOC}`);
  }
  console.log(`\n${steps.length} steps, ${steps.filter((item) => item.conclusion).length} conclusions.`);
} catch (error) {
  console.error(`case failed: ${error.stack || error.message}`);
  process.exit(1);
}

function renderDoc(all, context) {
  const lines = [];
  lines.push('# 数据智能体端到端案例（sales-case）');
  lines.push('');
  lines.push(`本文由 \`node examples/sales-case/run-case.mjs\` 生成：每一步的输入、调用的 PhoenixGraph 接口/遍历/SQL 和真实返回都在里面，可以直接照着复现。`);
  lines.push('');
  lines.push('| 项 | 值 |');
  lines.push('|---|---|');
  lines.push(`| 服务 | ${context.endpoint} |`);
  lines.push(`| 模型图 | ${context.graph}（Aerospike，schema 由包创建） |`);
  lines.push(`| 事实图 | ${context.factGraph} / ${context.vertexType}（案例自己生成并导入） |`);
  lines.push(`| 口径变更 | 2026-10-15：营收 SUM(revenue) → SUM(net_revenue) |`);
  lines.push(`| 生成时间 | ${new Date().toISOString()} |`);
  lines.push('');
  lines.push('## 覆盖的能力');
  lines.push('');
  for (const item of all) lines.push(`${item.number}. ${item.title} — ${item.conclusion || '（见下）'}`);
  lines.push('');
  for (const item of all) {
    lines.push(`## ${item.number}. ${item.title}`);
    lines.push('');
    if (item.purpose) { lines.push(item.purpose); lines.push(''); }
    if (item.facts.length) {
      lines.push('**做了什么 / 返回了什么**');
      lines.push('');
      for (const line of item.facts) lines.push(`- ${line}`);
      lines.push('');
    }
    for (const part of item.blocks) {
      lines.push(`**${part.title}**`);
      lines.push('');
      lines.push('```');
      lines.push(part.code);
      lines.push('```');
      lines.push('');
    }
    for (const line of item.notes) lines.push(`> ${line}`);
    if (item.notes.length) lines.push('');
    if (item.conclusion) { lines.push(`**结论**：${item.conclusion}`); lines.push(''); }
  }
  lines.push('---');
  lines.push('');
  lines.push('## 复现');
  lines.push('');
  lines.push('```bash');
  lines.push('tools/start-phoenixgraph.sh --no-build        # 8081 + 8080 + Aerospike');
  lines.push('cd pi-data-agent');
  lines.push('node examples/sales-case/run-case.mjs         # 跑完并重写本文');
  lines.push('```');
  lines.push('');
  lines.push('产物：`.pi/data-agent/dashboards/*.html`、`examples/sales-case/artifacts/*.html`、`.pi/data-agent/audit.jsonl`、`.pi/data-agent/models/sales-model.json`。');
  return `${lines.join('\n')}\n`;
}
