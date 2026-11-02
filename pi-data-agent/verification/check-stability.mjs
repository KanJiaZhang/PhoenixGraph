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
import { clientFor, dashboardFor, attributeFor } from '../src/service.mjs';

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
  const controlled = await evaluate(controls);
  const failures = [
    ...checked.filter((row) => !row.same).map((row) => `NOT STABLE: ${row.name}`),
    ...controlled.filter((row) => row.same).map((row) => `CONTROL DID NOT DIFFER: ${row.name}`),
  ];

  if (json) {
    console.log(JSON.stringify({ endpoint: ENDPOINT, graph: GRAPH, pairs: checked, controls: controlled, failures }, null, 2));
  } else {
    console.log(`stability: ${ENDPOINT} · model ${GRAPH}\n`);
    console.log('| # | 问题 | 写法 A | 写法 B | 结果 | 值 |');
    console.log('|---|---|---|---|---|---|');
    checked.forEach((row, index) => {
      console.log(`| ${index + 1} | ${row.name} | ${row.forms[0]} | ${row.forms[1]}${row.forms[2] ? ` / ${row.forms[2]}` : ''} | ${row.same ? '一致 ✅' : '不一致 ❌'} | ${row.value} |`);
    });
    console.log('\n| 对照（必须不同） | 写法 A | 写法 B | 结果 | A / B |');
    console.log('|---|---|---|---|---|');
    controlled.forEach((row) => {
      console.log(`| ${row.name} | ${row.forms[0]} | ${row.forms[1]} | ${row.same ? '相同 ❌（对照失效）' : '不同 ✅'} | ${row.results[0].value} / ${row.results[1].value} |`);
    });
    console.log(failures.length ? `\n${failures.length} problem(s):\n- ${failures.join('\n- ')}` : `\n${checked.length} pairs agree, ${controlled.length} controls differ.`);
  }

  if (doc) {
    const path = join(PACKAGE, doc);
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, renderDoc(checked, controlled, failures));
    console.log(`\nwritten to ${path}`);
  }
  process.exit(failures.length ? 1 : 0);
};

function renderDoc(checked, controlled, failures) {
  const lines = [];
  lines.push('# 稳定性测试：同一个问题，两种文法，一个答案');
  lines.push('');
  lines.push('本文件由 `node verification/check-stability.mjs --doc docs/STABILITY.md` 生成（对着运行中的 PhoenixGraph 与');
  lines.push(`\`${GRAPH}\` 模型执行）。比较的是**完整结果指纹**——每个 tile 的 id、是否执行、金额/行、拒答理由，以及归因的`);
  lines.push('before/after/delta/贡献序列/和校验/限制条数——而不是只看一个数字。');
  lines.push('');
  lines.push('| # | 问题（两种写法应等价） | 写法 A | 写法 B | 结果 | 值 |');
  lines.push('|---|---|---|---|---|---|');
  checked.forEach((row, index) => {
    lines.push(`| ${index + 1} | ${row.name} | ${row.forms[0]} | ${row.forms[1]}${row.forms[2] ? ` / ${row.forms[2]}` : ''} | ${row.same ? '一致 ✅' : '不一致 ❌'} | ${row.value} |`);
  });
  lines.push('');
  lines.push('## 对照：换一个问题，结果必须变');
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
    : `${checked.length} 组等价写法全部得到同一份结果，${controlled.length} 组对照全部不同。`);
  lines.push('');
  lines.push('覆盖的等价关系：指标（id / 名字 / 别名 / 绑定列）、维度（id / 名字 / 绑定列）、期间（字符串 / 数字 / 带空白）、');
  lines.push('topN（数字 / 字符串）、`asOf`（ISO 时刻 / 日期 / epoch 毫秒）、事实图绑定（显式 / 取自模型）、指标选择（单数 /');
  lines.push('数组 / 不选）、归因（指标与维度的四种写法、期间类型、时点）。');
  lines.push('');
  lines.push('## 复现');
  lines.push('');
  lines.push('```bash');
  lines.push('node verification/check-stability.mjs              # 打印');
  lines.push('node verification/check-stability.mjs --doc docs/STABILITY.md');
  lines.push('```');
  return `${lines.join('\n')}\n`;
}

main().catch((error) => {
  console.error(`stability check failed: ${error.stack || error.message}`);
  process.exit(1);
});
