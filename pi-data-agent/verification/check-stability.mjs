#!/usr/bin/env node
/**
 * The stability check: one question, several spellings, one answer.
 *
 * A semantic layer is a user-facing grammar. A person says `营收`, another says `metric/营收`, a third says
 * `revenue` (the column), and a report says `2025` while a script says `2025` the number. If those produce
 * different numbers - or worse, a silently shorter dashboard - the layer is not answering the question, it is
 * answering the spelling. This script runs the same dashboard/attribution questions in pairs of forms against a
 * live instance and compares the *whole* result fingerprint (tiles, totals, refusals, contributions, limits), not
 * just one number, then runs negative controls that must differ (otherwise the check proves nothing).
 *
 *   node verification/check-stability.mjs                 # print the table
 *   node verification/check-stability.mjs --doc docs/STABILITY.md
 *   node verification/check-stability.mjs --json
 */
import process from 'node:process';
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { clientFor, dashboardFor, attributeFor, loadModel } from '../src/service.mjs';
import { parseQuestion, renderQuestion } from '../src/ask.mjs';

const PACKAGE = fileURLToPath(new URL('..', import.meta.url));
const args = process.argv.slice(2);
const flag = (name, fallback) => {
  const index = args.indexOf(`--${name}`);
  return index >= 0 ? args[index + 1] : fallback;
};
const ENDPOINT = process.env.PHOENIXGRAPH_API || flag('endpoint', 'http://localhost:8081');
const GRAPH = flag('graph', 'sales-model');
const json = args.includes('--json');
const doc = flag('doc', null);

const client = clientFor({ endpoint: ENDPOINT });

/** A dashboard fingerprint: every tile (id, executed, total/refusal) plus the counters. */
async function dashboardFingerprint(request) {
  const result = await dashboardFor({ endpoint: ENDPOINT, client, graph: GRAPH, write: false, ...request });
  // tile order is presentation: compare the set of tiles, sorted by id
  const tiles = result.dashboard.tiles.map((tile) => ({
    id: tile.id,
    executed: tile.executed === true,
    total: tile.total ?? null,
    rows: tile.rows ? tile.rows.map((row) => `${row.value}=${row.measure}`).join(',') : null,
    reason: tile.executed ? null : (tile.reason || 'refused'),
  })).sort((left, right) => left.id.localeCompare(right.id));
  return {
    value: result.dashboard.tiles.find((tile) => tile.id.endsWith('|total'))?.total ?? null,
    fingerprint: JSON.stringify({
      executed: result.dashboard.executed,
      refused: result.dashboard.refused,
      tiles,
      warnings: (result.dashboard.warnings || []).length,
    }),
  };
}

/** An attribution fingerprint: the arithmetic and the order of the contributions. */
async function attributionFingerprint(request) {
  const result = await attributeFor({ endpoint: ENDPOINT, client, graph: GRAPH, ...request });
  const attribution = result.result.attribution || null;
  return {
    value: attribution ? `${attribution.delta}（top ${attribution.topMover?.value ?? '-'}）` : `refused: ${result.result.reason}`,
    fingerprint: JSON.stringify({
      executed: result.result.executed,
      reason: result.result.executed ? null : result.result.reason,
      totalBefore: attribution?.totalBefore ?? null,
      totalAfter: attribution?.totalAfter ?? null,
      delta: attribution?.delta ?? null,
      contributions: (attribution?.contributions || []).map((item) => `${item.value}:${item.delta}:${item.shareOfChange}`),
      sums: attribution ? `${attribution.contributionsSum}/${attribution.decompositionAddsUp}` : null,
      top: attribution?.topMover?.value ?? null,
      limits: (attribution?.limits || []).length,
    }),
  };
}

const dash = (label, request) => ({ label, kind: 'dashboard', request });
const attr = (label, request) => ({ label, kind: 'attribution', request });

/** Pairs that must agree: the same question written two ways. */
const pairs = [
  {
    name: '指标：名字 vs id',
    a: dash('营收', { metric: '营收', year: '2025' }),
    b: dash('metric/营收', { metric: 'metric/营收', year: '2025' }),
  },
  {
    name: '指标：单数 metric vs 数组 metrics',
    a: dash('metric:"营收"', { metric: '营收', year: '2025' }),
    b: dash('metrics:["营收"]', { metrics: ['营收'], year: '2025' }),
  },
  {
    name: '指标：名字 vs 绑定列（cost）',
    a: dash('成本', { metric: '成本', year: '2025' }),
    b: dash('cost', { metric: 'cost', year: '2025' }),
  },
  {
    name: '指标：一个有歧义的绑定（net_revenue，两个指标都绑它）重复执行',
    a: dash('net_revenue #1', { metric: 'net_revenue', year: '2025' }),
    b: dash('net_revenue #2', { metric: 'net_revenue', year: '2025' }),
  },
  {
    name: '指标：别名（收入）vs id',
    a: dash('收入', { metric: '收入', year: '2025' }),
    b: dash('metric/营收', { metric: 'metric/营收', year: '2025' }),
  },
  {
    name: '期间：字符串 vs 数字',
    a: dash('"2025"', { metric: '营收', year: '2025' }),
    b: dash('2025', { metric: '营收', year: 2025 }),
  },
  {
    name: '期间：带空白',
    a: dash('"2025"', { metric: '营收', year: '2025' }),
    b: dash('" 2025 "', { metric: '营收', year: ' 2025 ' }),
  },
  {
    name: '维度：名字 vs id',
    a: dash('dimensions:["渠道"]', { metric: '营收', year: '2025', dimensions: ['渠道'] }),
    b: dash('dimensions:["dim/渠道"]', { metric: '营收', year: '2025', dimensions: ['dim/渠道'] }),
  },
  {
    name: '维度：绑定列（channel）vs id',
    a: dash('dimensions:["channel"]', { metric: '营收', year: '2025', dimensions: ['channel'] }),
    b: dash('dimensions:["dim/渠道"]', { metric: '营收', year: '2025', dimensions: ['dim/渠道'] }),
  },
  {
    name: 'topN：数字 vs 字符串',
    a: dash('topN:3', { metric: '营收', year: '2025', topN: 3 }),
    b: dash('topN:"3"', { metric: '营收', year: '2025', topN: '3' }),
  },
  {
    name: 'asOf：ISO 时刻 vs 日期 vs epoch 毫秒',
    a: dash('asOf:"2026-10-01T00:00:00Z"', { metric: '营收', year: '2026', asOf: '2026-10-01T00:00:00Z' }),
    b: dash('asOf:"2026-10-01"', { metric: '营收', year: '2026', asOf: '2026-10-01' }),
    c: dash('asOf:epoch', { metric: '营收', year: '2026', asOf: Date.parse('2026-10-01T00:00:00Z') }),
  },
  {
    name: '事实图绑定：显式 vs 取自模型',
    a: dash('不传 dataGraph/vertexType', { metric: '营收', year: '2025' }),
    b: dash('显式传', { metric: '营收', year: '2025', dataGraph: 'sales-facts', vertexType: 'SalesDay' }),
  },
  {
    name: '看板范围：不选指标 vs 列出全部指标',
    a: dash('默认（全部指标）', { year: '2025' }),
    b: dash('显式 metrics（名字）', { year: '2025', metrics: ['营收', '净营收', '订单数', '客户数', '成本', '毛利率'] }),
  },
  {
    name: '归因：指标名字 vs id（维度同步换写法）',
    a: attr('营收 / 渠道', { metric: '营收', by: '渠道', from: '2024', to: '2025' }),
    b: attr('metric/营收 / dim/渠道', { metric: 'metric/营收', by: 'dim/渠道', from: '2024', to: '2025' }),
  },
  {
    name: '归因：维度绑定列（channel）vs id',
    a: attr('by:"channel"', { metric: '营收', by: 'channel', from: '2024', to: '2025' }),
    b: attr('by:"dim/渠道"', { metric: '营收', by: 'dim/渠道', from: '2024', to: '2025' }),
  },
  {
    name: '归因：期间字符串 vs 数字',
    a: attr('from/to 字符串', { metric: '营收', by: '渠道', from: '2024', to: '2025' }),
    b: attr('from/to 数字', { metric: '营收', by: '渠道', from: 2024, to: 2025 }),
  },
  {
    name: '归因：asOf ISO vs 毫秒',
    a: attr('asOf ISO', { metric: '营收', by: '渠道', from: '2025', to: '2026', asOf: '2026-10-01' }),
    b: attr('asOf 毫秒', { metric: '营收', by: '渠道', from: '2025', to: '2026', asOf: Date.parse('2026-10-01') }),
  },
];


/**
 * Natural language: one dashboard question and one attribution question, asked the way a person would.
 * Each phrasing is parsed to the structured request, then compared with the structured baseline.
 */
const model = await loadModel(client, GRAPH);

const dashboardQuestion = { metric: '营收', year: '2025', dimensions: ['渠道'], topN: 3 };
const attributionQuestion = { metric: '营收', by: '渠道', from: '2024', to: '2025' };

const phrasings = [
  {
    name: '看板问句（同一问题 7 种说法）',
    baseline: dash('结构化请求', dashboardQuestion),
    variants: [
      '2025年营收按渠道拆分，前3',
      '营收 2025 by 渠道 top3',
      '按渠道看下 2025 年的收入前三',
      '给我 2025 年 metric/营收 按 dim/渠道 前 3 名',
      '2025 年销售额按 channel 拆解 top 3',
      '2025年营收按 sales_day.channel 拆分，前3名',
      '营收 2025 按渠道 前三',
    ],
    kind: 'dashboard',
  },
  {
    name: '归因问句（同一问题 5 种说法）',
    baseline: attr('结构化请求', attributionQuestion),
    variants: [
      '2024 到 2025 营收按渠道为什么增长',
      '营收 2024 vs 2025 按渠道贡献',
      '按渠道归因：营收从 2024 到 2025 的变化',
      '2025 比 2024，营收按渠道增长了多少',
      'metric/营收 2024 -> 2025 by dim/渠道 归因',
    ],
    kind: 'attribution',
  },
];

/**
 * Identities that must hold between *different* questions: the same fact reached another way.
 * These are not spelling pairs - they are arithmetic and grain relations the layer promises.
 */
const identities = [];

async function evaluatePhrasings() {
  const rows = [];
  for (const group of phrasings) {
    const baseline = group.kind === 'dashboard' ? await dashboardFingerprint(group.baseline.request) : await attributionFingerprint(group.baseline.request);
    const results = [];
    for (const phrase of group.variants) {
      const parsed = parseQuestion(model, phrase);
      const request = parsed.ok ? (parsed.kind === 'dashboard' ? { ...parsed.request, ...(parsed.request.topN ? {} : {}) } : parsed.request) : null;
      let result = null;
      if (parsed.ok) {
        result = parsed.kind === 'dashboard' ? await dashboardFingerprint(request) : await attributionFingerprint(request);
      }
      results.push({
        label: phrase,
        understood: renderQuestion(parsed),
        same: Boolean(result) && result.fingerprint === baseline.fingerprint,
        kind: parsed.kind,
        value: result?.value ?? null,
        reason: parsed.ok ? null : parsed.reason,
      });
    }
    rows.push({ name: group.name, baseline: baseline.value, rows: results, same: results.every((row) => row.same) });
  }
  return rows;
}

async function evaluateIdentities() {
  const checks = [];

  // 1. a year's total is the sum of its months (same fact, two grains)
  const board = await dashboardFor({ endpoint: ENDPOINT, client, graph: GRAPH, write: false, ...dashboardQuestion });
  const trend = board.dashboard.tiles.find((tile) => tile.id.endsWith('|trend|month'));
  const total = board.dashboard.tiles.find((tile) => tile.id.endsWith('|total'));
  const monthSum = (trend?.rows || []).reduce((sum, row) => sum + Number(row.measure || 0), 0);
  checks.push({
    name: '按年一次问 = 按月问再相加',
    detail: `趋势 12 个月合计 ${Math.round(monthSum)} vs 年度总额 ${Math.round(total?.total)}（差 ${Math.abs(monthSum - total?.total).toFixed(4)}）`,
    pass: Math.abs(monthSum - Number(total?.total)) < 0.01,
    a: Math.round(monthSum), b: Math.round(total?.total),
  });

  // 2. the attribution delta is the difference of the two dashboards (change vs two points)
  const from = await dashboardFor({ endpoint: ENDPOINT, client, graph: GRAPH, write: false, metric: '营收', year: '2024' });
  const to = await dashboardFor({ endpoint: ENDPOINT, client, graph: GRAPH, write: false, metric: '营收', year: '2025' });
  const deltaFromBoards = to.dashboard.tiles.find((tile) => tile.id.endsWith('|total'))?.total
    - from.dashboard.tiles.find((tile) => tile.id.endsWith('|total'))?.total;
  const attribution = await attributeFor({ endpoint: ENDPOINT, client, graph: GRAPH, ...attributionQuestion });
  const delta = attribution.result.attribution?.delta;
  checks.push({
    name: '归因 delta = 两个年度看板之差',
    detail: `归因 ${Math.round(delta)} vs 2025-2024 ${Math.round(deltaFromBoards)}`,
    pass: Math.abs(Number(delta) - Number(deltaFromBoards)) < 0.01,
    a: Math.round(Number(delta)), b: Math.round(Number(deltaFromBoards)),
  });

  // 3. asking the change backwards negates it, and keeps the same slices
  const reversed = await attributeFor({ endpoint: ENDPOINT, client, graph: GRAPH, ...attributionQuestion, from: '2025', to: '2024' });
  const reversedDelta = reversed.result.attribution?.delta;
  const sameSlices = JSON.stringify((attribution.result.attribution?.contributions || []).map((item) => item.value).sort())
    === JSON.stringify((reversed.result.attribution?.contributions || []).map((item) => item.value).sort());
  checks.push({
    name: '2024→2025 与 2025→2024：delta 取反，切片不变',
    detail: `${Math.round(Number(delta))} vs ${Math.round(Number(reversedDelta))}；切片相同=${sameSlices}；和校验=${reversed.result.attribution?.decompositionAddsUp}`,
    pass: Math.abs(Number(delta) + Number(reversedDelta)) < 0.01 && sameSlices && reversed.result.attribution?.decompositionAddsUp === true,
    a: Math.round(Number(delta)), b: Math.round(Number(reversedDelta)),
  });

  // 4. a metric named by its expression is the same metric
  const expressionMetric = await dashboardFor({ endpoint: ENDPOINT, client, graph: GRAPH, write: false, metric: 'SUM(cost)', year: '2025' });
  const expressionId = expressionMetric.dashboard.tiles.find((tile) => tile.id.endsWith('|total'))?.id;
  checks.push({
    name: '按表达式引用指标（SUM(cost)）= 按 id（metric/成本）',
    detail: `解析到 ${expressionId || '未解析'}`,
    pass: expressionId === 'metric/成本|total',
    a: expressionId || '(none)', b: 'metric/成本|total',
  });

  // 5. a dimension named by a table-qualified column is the same dimension
  const qualified = await dashboardFor({ endpoint: ENDPOINT, client, graph: GRAPH, write: false, metric: '营收', year: '2025', dimensions: ['sales_day.channel'] });
  const breakdown = qualified.dashboard.tiles.find((tile) => tile.id.includes('|breakdown|'));
  checks.push({
    name: '按表限定列引用维度（sales_day.channel）= dim/渠道',
    detail: `解析到 ${breakdown?.id || '未解析'}`,
    pass: breakdown?.id === 'metric/营收|breakdown|dim/渠道',
    a: breakdown?.id || '(none)', b: 'metric/营收|breakdown|dim/渠道',
  });

  return checks;
}

/** Controls: different questions, which must NOT agree (or the check above proves nothing). */
const controls = [
  {
    name: '对照：不同年份',
    a: dash('2024', { metric: '营收', year: '2024' }),
    b: dash('2025', { metric: '营收', year: '2025' }),
  },
  {
    name: '对照：不同口径时点',
    a: dash('asOf 2026-10-01', { metric: '营收', year: '2026', asOf: '2026-10-01' }),
    b: dash('现在', { metric: '营收', year: '2026' }),
  },
  {
    name: '对照：不同维度',
    a: attr('by 渠道', { metric: '营收', by: '渠道', from: '2024', to: '2025' }),
    b: attr('by 地区', { metric: '营收', by: '地区', from: '2024', to: '2025' }),
  },
  {
    name: '对照：不同期间',
    a: attr('2024→2025', { metric: '营收', by: '渠道', from: '2024', to: '2025' }),
    b: attr('2025→2026', { metric: '营收', by: '渠道', from: '2025', to: '2026' }),
  },
];

async function evaluate(group) {
  const rows = [];
  for (const entry of group) {
    const forms = [entry.a, entry.b, entry.c].filter(Boolean);
    const results = [];
    for (const form of forms) {
      const result = form.kind === 'dashboard' ? await dashboardFingerprint(form.request) : await attributionFingerprint(form.request);
      results.push({ label: form.label, ...result });
    }
    const same = results.every((result) => result.fingerprint === results[0].fingerprint);
    rows.push({ name: entry.name, kind: forms[0].kind, forms: results.map((result) => result.label), same, value: results[0].value, results });
  }
  return rows;
}

const main = async () => {
  const checked = await evaluate(pairs);
  const spoken = await evaluatePhrasings();
  const proven = await evaluateIdentities();
  const controlled = await evaluate(controls);
  const failures = [
    ...checked.filter((row) => !row.same).map((row) => `NOT STABLE: ${row.name}`),
    ...spoken.flatMap((group) => group.rows.filter((row) => !row.same)
      .map((row) => `PHRASING NOT STABLE: ${row.label}${row.reason ? ` (${row.reason})` : ''}`)),
    ...proven.filter((check) => !check.pass).map((check) => `IDENTITY BROKEN: ${check.name}`),
    ...controlled.filter((row) => row.same).map((row) => `CONTROL DID NOT DIFFER: ${row.name}`),
  ];

  if (json) {
    console.log(JSON.stringify({ endpoint: ENDPOINT, graph: GRAPH, pairs: checked, phrasings: spoken, identities: proven, controls: controlled, failures }, null, 2));
  } else {
    console.log(`stability: ${ENDPOINT} · model ${GRAPH}\n`);
    console.log('### A. 同一个问题的不同写法（必须一致）\n');
    console.log('| # | 问题 | 写法 A | 写法 B | 结果 | 值 |');
    console.log('|---|---|---|---|---|---|');
    checked.forEach((row, index) => {
      console.log(`| ${index + 1} | ${row.name} | ${row.forms[0]} | ${row.forms[1]}${row.forms[2] ? ` / ${row.forms[2]}` : ''} | ${row.same ? '一致 ✅' : '不一致 ❌'} | ${row.value} |`);
    });

    console.log('\n### B. 自然语言问句（不同问法必须解析成同一请求）\n');
    for (const group of spoken) {
      console.log(`**${group.name}** — 基线 ${group.baseline}\n`);
      console.log('| 问法 | 解析为 | 结果 | 值 |');
      console.log('|---|---|---|---|');
      for (const row of group.rows) {
        console.log(`| ${row.label} | ${row.understood.replace(/\|/g, '\|')} | ${row.same ? '一致 ✅' : '不一致 ❌'} | ${row.value ?? row.reason ?? '-'} |`);
      }
      console.log('');
    }

    console.log('### C. 恒等式（不同的问题之间必须成立的关系）\n');
    console.log('| 恒等式 | 结果 | A | B | 说明 |');
    console.log('|---|---|---|---|---|');
    for (const check of proven) {
      console.log(`| ${check.name} | ${check.pass ? '成立 ✅' : '不成立 ❌'} | ${check.a} | ${check.b} | ${check.detail.replace(/\|/g, '\\|')} |`);
    }

    console.log('\n### D. 对照（换一个问题，结果必须变）\n');
    console.log('| 对照 | 写法 A | 写法 B | 结果 | A / B |');
    console.log('|---|---|---|---|---|');
    controlled.forEach((row) => {
      console.log(`| ${row.name} | ${row.forms[0]} | ${row.forms[1]} | ${row.same ? '相同 ❌（对照失效）' : '不同 ✅'} | ${row.results[0].value} / ${row.results[1].value} |`);
    });
    console.log(failures.length ? `\n${failures.length} problem(s):\n- ${failures.join('\n- ')}` : `\n${checked.length} 组等价写法、${spoken.reduce((sum, group) => sum + group.rows.length, 0)} 种问法、${proven.length} 条恒等式成立，${controlled.length} 组对照不同。`);
  }

  if (doc) {
    const path = join(PACKAGE, doc);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, renderDoc({ checked, spoken, proven, controlled, failures }));
    console.log(`\nwritten to ${path}`);
  }
  process.exit(failures.length ? 1 : 0);
};

function renderDoc({ checked, spoken, proven, controlled, failures }) {
  const lines = [];
  lines.push('# 稳定性测试：同一个问题，不同问法，一个答案');
  lines.push('');
  lines.push('本文件由 `node verification/check-stability.mjs --doc docs/STABILITY.md` 生成（对着运行中的 PhoenixGraph 与');
  lines.push(`\`${GRAPH}\` 模型执行）。比较的是**完整结果指纹**——每个 tile 的 id / 是否执行 / 金额或行 / 拒答理由，归因的`);
  lines.push('before / after / delta / 贡献序列 / 和校验 / 限制条数——而不是只看一个数字。四组：');
  lines.push('');
  lines.push(`- **A. 等价写法**：${checked.length} 组，同一个请求的两种（或三种）拼写；`);
  lines.push(`- **B. 自然语言问句**：${spoken.reduce((sum, group) => sum + group.rows.length, 0)} 种说法，经 \`src/ask.mjs\` 解析成结构化请求后与基线比较；`);
  lines.push(`- **C. 恒等式**：${proven.length} 条，不同问题之间必须成立的关系（年 = 月之和、归因 = 两个看板之差、反向取反、表达式/表限定列引用）；`);
  lines.push(`- **D. 对照**：${controlled.length} 组换了问题，结果必须不同（否则前面什么也没证明）。`);
  lines.push('');
  lines.push('## A. 等价写法');
  lines.push('');
  lines.push('| # | 问题 | 写法 A | 写法 B | 结果 | 值 |');
  lines.push('|---|---|---|---|---|---|');
  checked.forEach((row, index) => {
    lines.push(`| ${index + 1} | ${row.name} | ${row.forms[0]} | ${row.forms[1]}${row.forms[2] ? ` / ${row.forms[2]}` : ''} | ${row.same ? '一致 ✅' : '不一致 ❌'} | ${row.value} |`);
  });
  lines.push('');
  lines.push('## B. 自然语言问句');
  for (const group of spoken) {
    lines.push('');
    lines.push(`### ${group.name}（基线：${group.baseline}）`);
    lines.push('');
    lines.push('| 问法 | 解析为 | 结果 | 值 |');
    lines.push('|---|---|---|---|');
    for (const row of group.rows) {
      lines.push(`| ${row.label} | ${row.understood.replace(/\|/g, '\|')} | ${row.same ? '一致 ✅' : '不一致 ❌'} | ${row.value ?? row.reason ?? '-'} |`);
    }
  }
  lines.push('');
  lines.push('## C. 恒等式');
  lines.push('');
  lines.push('| 恒等式 | 结果 | A | B | 说明 |');
  lines.push('|---|---|---|---|---|');
  for (const check of proven) {
    lines.push(`| ${check.name} | ${check.pass ? '成立 ✅' : '不成立 ❌'} | ${check.a} | ${check.b} | ${check.detail.replace(/\|/g, '\|')} |`);
  }
  lines.push('');
  lines.push('## D. 对照');
  lines.push('');
  lines.push('| 对照 | 写法 A | 写法 B | 结果 | A / B |');
  lines.push('|---|---|---|---|---|');
  controlled.forEach((row) => {
    lines.push(`| ${row.name} | ${row.forms[0]} | ${row.forms[1]} | ${row.same ? '相同 ❌' : '不同 ✅'} | ${row.results[0].value} / ${row.results[1].value} |`);
  });
  lines.push('');
  lines.push('## 结论');
  lines.push('');
  lines.push(failures.length
    ? `发现 ${failures.length} 个问题：${failures.map((failure) => `\`${failure}\``).join('、')}。`
    : `A 组 ${checked.length} 组等价写法一致，B 组 ${spoken.reduce((sum, group) => sum + group.rows.length, 0)} 种问法全部解析成同一请求，C 组 ${proven.length} 条恒等式成立，D 组 ${controlled.length} 组对照全部不同。`);
  lines.push('');
  lines.push('## 复现');
  lines.push('');
  lines.push('```bash');
  lines.push('node verification/check-stability.mjs              # 打印四组');
  lines.push('node verification/check-stability.mjs --doc docs/STABILITY.md');
  lines.push('```');
  return `${lines.join('\n')}\n`;
}

main().catch((error) => {
  console.error(`stability check failed: ${error.stack || error.message}`);
  process.exit(1);
});
