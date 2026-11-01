/**
 * Risk-control agent: a risk case ledger plus the entity/ring graph.
 *
 * The ledger answers *what moved* (a metric decomposed by a dimension); the payment graph answers *who is
 * behind the slice* (Account / FuzzyLink / payments / collection receiver / centrality). The two are joined
 * by the manifest's `rings` mapping. Every claim keeps a row or a query behind it, and every hypothesis says
 * what would refute it.
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { attribute } from './attribution.mjs';
import { projectRoot } from './config.mjs';
import { escapeHtml, gremlinString, pathSegment, round, unique } from './util.mjs';

/** RFC-4180-ish CSV: quoted fields, embedded commas/newlines, CRLF. */
export function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = '';
  let quoted = false;
  const source = String(text ?? '').replace(/^\uFEFF/, '');
  for (let index = 0; index < source.length; index += 1) {
    const char = source[index];
    const next = source[index + 1];
    if (quoted) {
      if (char === '"' && next === '"') { field += '"'; index += 1; continue; }
      if (char === '"') { quoted = false; continue; }
      field += char;
      continue;
    }
    if (char === '"') { quoted = true; continue; }
    if (char === ',') { row.push(field); field = ''; continue; }
    if (char === '\n') {
      row.push(field.replace(/\r$/, ''));
      if (row.some((cell) => cell !== '')) rows.push(row);
      row = [];
      field = '';
      continue;
    }
    field += char;
  }
  row.push(field.replace(/\r$/, ''));
  if (row.some((cell) => cell !== '')) rows.push(row);
  if (!rows.length) return { header: [], rows: [] };
  const header = rows.shift().map((cell) => cell.trim());
  return { header, rows: rows.map((cells) => Object.fromEntries(header.map((key, index) => [key, cells[index] ?? '']))) };
}

function numberOrText(value) {
  if (value == null || value === '') return value;
  const text = String(value).trim();
  return /^-?\d+(?:\.\d+)?$/.test(text) ? Number(text) : text;
}

function normaliseRow(row) {
  return Object.fromEntries(Object.entries(row || {}).map(([key, value]) => [key, numberOrText(value)]));
}

/** Reads a ledger from an array, JSON, CSV or a path. */
export function readRiskCases(source) {
  if (Array.isArray(source)) return { rows: source.map(normaliseRow), columns: Object.keys(source[0] || {}) };
  const text = String(source ?? '').trim();
  if (!text) throw new Error('risk cases: empty input');
  if (text.startsWith('[') || text.startsWith('{')) {
    const parsed = JSON.parse(text);
    const rows = Array.isArray(parsed) ? parsed : parsed.rows;
    if (!Array.isArray(rows)) throw new Error('risk cases: JSON must be an array or {rows:[...]}');
    return { rows: rows.map(normaliseRow), columns: Object.keys(rows[0] || {}) };
  }
  if (text.includes('\n') || text.startsWith('period,')) {
    const parsed = parseCsv(text);
    return { rows: parsed.rows.map(normaliseRow), columns: parsed.header };
  }
  if (!existsSync(text)) throw new Error(`risk cases: '${text}' is neither a file nor inline CSV/JSON`);
  const raw = readFileSync(text, 'utf8');
  if (text.endsWith('.json')) return readRiskCases(raw);
  const parsed = parseCsv(raw);
  return { rows: parsed.rows.map(normaliseRow), columns: parsed.header };
}

/** The manifest names metrics, dimensions, rings and the entity graph. */
export function loadRiskManifest(source) {
  if (source == null) throw new Error('risk manifest is required');
  let manifest;
  if (typeof source === 'object') manifest = source;
  else {
    const text = String(source);
    if (text.trim().startsWith('{')) manifest = JSON.parse(text);
    else if (existsSync(text)) manifest = JSON.parse(readFileSync(text, 'utf8'));
    else throw new Error(`risk manifest: '${text}' is not a file or JSON`);
  }
  if (!manifest.metrics || !Object.keys(manifest.metrics).length) throw new Error('risk manifest: metrics are required');
  manifest.dimensions = manifest.dimensions || {};
  manifest.rings = manifest.rings || {};
  manifest.graph = manifest.graph || {};
  manifest.metrics = Object.fromEntries(Object.entries(manifest.metrics).map(([key, def]) => [key, { agg: 'SUM', ...def }]));
  return manifest;
}

export function metricValue(rows, definition) {
  const agg = String(definition.agg || 'SUM').toUpperCase();
  if (agg === 'COUNT') return rows.length;
  if (agg === 'RATIO') {
    const numerator = rows.reduce((sum, row) => sum + Number(row[definition.numerator] || 0), 0);
    const denominator = rows.reduce((sum, row) => sum + Number(row[definition.denominator] || 0), 0);
    return denominator === 0 ? null : (100 * numerator) / denominator;
  }
  if (agg === 'AVG') {
    const values = rows.map((row) => Number(row[definition.column])).filter((value) => Number.isFinite(value));
    return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null;
  }
  return rows.reduce((sum, row) => sum + Number(row[definition.column] || 0), 0);
}

export function groupMetric(rows, definition, dimension) {
  const column = dimension.column ?? dimension;
  const groups = new Map();
  for (const row of rows) {
    const value = String(row[column] ?? '');
    if (!groups.has(value)) groups.set(value, []);
    groups.get(value).push(row);
  }
  return [...groups.entries()]
    .map(([value, groupRows]) => ({ value, measure: metricValue(groupRows, definition) ?? 0, rows: groupRows.length }))
    .sort((left, right) => right.measure - left.measure);
}

export function periodsOf(rows) {
  return unique(rows.map((row) => String(row.period))).sort();
}

function rowsFor(rows, period) {
  return rows.filter((row) => String(row.period) === String(period));
}

function metricLabel(manifest, metric) {
  const def = manifest.metrics[metric];
  return def?.label || def?.description || metric;
}

export function riskSummary(rows, manifest, period) {
  const periodRows = rowsFor(rows, period);
  const value = (metric) => metricValue(periodRows, manifest.metrics[metric]);
  const exposure = value('exposure');
  const blocked = value('blocked');
  const cases = value('cases');
  const fp = value('fp_cases');
  return {
    period,
    rows: periodRows.length,
    exposure: round(exposure, 2),
    loss: round(value('loss'), 2),
    blocked: round(blocked, 2),
    cases: Math.round(cases || 0),
    fp_cases: Math.round(fp || 0),
    block_rate: exposure ? round((blocked / exposure) * 100, 2) : null,
    fp_rate: cases ? round((fp / cases) * 100, 2) : null,
    review_hours: round(value('review_hours'), 2),
  };
}

function citation(graph, id) {
  return id ? `${graph}/${id}` : null;
}

/**
 * One ring's entity evidence: members, fuzzy link, payments and the collection receiver's centrality.
 * A query that the graph cannot answer becomes a warning and a failed check, never a silent zero.
 */
export async function enrichRing(client, graph, ringId, ring, options = {}) {
  const accounts = Array.isArray(ring.accounts) ? ring.accounts.filter(Boolean) : [];
  const label = ring.label || ringId;
  if (!accounts.length) return { ring: ringId, label, executed: false, reason: 'the manifest maps no accounts to this ring', limits: [] };
  if (!client) return { ring: ringId, label, executed: false, reason: 'no PhoenixGraph client', limits: [] };
  const vertexType = ring.vertexType || graph.vertexType || 'Account';
  const ids = accounts.map(gremlinString).join(',');
  const queries = {
    members: `g.V(${ids},${gremlinString(vertexType)}).elementMap()`,
    links: ring.patternId == null
      ? `g.V(${ids},${gremlinString(vertexType)}).inE('FuzzyLinkedTo').otherV().elementMap()`
      : `g.V(${ids},${gremlinString(vertexType)}).fuzzyLink(${Number(ring.patternId)}).elementMap()`,
    payments: `g.V(${ids},${gremlinString(vertexType)}).outE('Payment').elementMap()`,
    receivers: `g.V(${ids},${gremlinString(vertexType)}).outE('Payment').otherV().dedup().elementMap()`,
  };
  const result = {
    ring: ringId, label, graph: graph.name, executed: false, warnings: [], evidence: [], checks: [],
    limits: ['FuzzyLink 是模式相似性候选，不是欺诈事实；账户与资金路径仍需人工复核。'],
  };
  const answers = {};
  for (const [key, query] of Object.entries(queries)) {
    try { answers[key] = await client.query(graph.name, query); }
    catch (error) { result.warnings.push(`${key}: ${error.message}`); answers[key] = []; }
  }
  const members = answers.members || [];
  const links = answers.links || [];
  const payments = answers.payments || [];
  const receivers = answers.receivers || [];
  const receiverIds = unique(receivers.map((receiver) => String(receiver.id)));
  const paymentAmount = payments.reduce((sum, payment) => sum + Number(payment.amount || 0), 0);
  let payers = [];
  let receiverDegree = null;
  if (receiverIds.length) {
    const receiverList = receiverIds.map(gremlinString).join(',');
    try {
      payers = await client.query(graph.name, `g.V(${receiverList},${gremlinString(vertexType)}).bothE('Payment').otherV().dedup().elementMap()`);
    } catch (error) { result.warnings.push(`payers: ${error.message}`); }
    try {
      const counts = await client.query(graph.name, `g.V(${receiverList},${gremlinString(vertexType)}).bothE('Payment').count()`);
      receiverDegree = Number(Array.isArray(counts) ? counts[0] : counts) || 0;
    } catch (error) { result.warnings.push(`receiver degree: ${error.message}`); }
  }
  const payerIds = unique(payers.map((payer) => String(payer.id)));
  const payerCount = payerIds.length;
  const link = links[0] || null;
  const receiverTop = receiverIds.length ? { id: receiverIds[0], degree: Number(receiverDegree || 0) } : null;
  const averagePayerDegree = payerCount ? round((receiverDegree || 0) / payerCount, 2) : null;
  const receiverDominance = receiverDegree && payerCount ? round(receiverDegree / payerCount, 2) : null;
  const checks = [
    { name: 'ring size', pass: members.length >= (options.minMembers || 3), detail: `${members.length} account(s) resolved; at least ${options.minMembers || 3} expected` },
    { name: 'shared fuzzy link', pass: Boolean(link?.key), detail: link?.key ? `${link.key} (pattern ${link.patternId})` : 'no shared FuzzyLink key was returned' },
    { name: 'single collection receiver', pass: receiverIds.length === 1, detail: `${receiverIds.length} receiver(s): ${receiverIds.join(', ') || 'none'}` },
    { name: 'receiver degree dominates', pass: (receiverDegree || 0) >= Math.max(20, payerCount * 2), detail: receiverTop ? `${receiverTop.id} degree=${receiverDegree}, payers=${payerCount}, average payer degree=${averagePayerDegree}` : 'no receiver degree' },
    { name: 'payments present', pass: payments.length > 0 && paymentAmount > 0, detail: `${payments.length} payment(s), amount=${round(paymentAmount, 2)}` },
  ];
  result.executed = members.length > 0;
  // the queries ran and found nothing: say which ids were asked for. An empty ring is a finding only when the
  // reader can tell "these accounts are not in this graph" from "nobody could read the accounts".
  if (!result.executed) {
    result.reason = `the graph returned no ${vertexType} vertex for the ${accounts.length} account id(s) this ring`
      + ' maps to (the payment graph may be empty, or the manifest may name accounts that were never loaded)';
  }
  result.members = members.map((member) => ({ id: String(member.id), name: member.firstName || member.username || member.name, email: member.email, phone: member.phoneNumber, ip: member.ipAddress, card: member.cardNumber }));
  result.memberCount = members.length;
  result.link = link ? { id: link.id, key: link.key, patternId: link.patternId, pattern: link.pattern } : null;
  result.payments = { count: payments.length, amount: round(paymentAmount, 2), statuses: unique(payments.map((payment) => payment.status)) };
  result.receivers = receiverIds.map((id) => ({ id, degree: Number(receiverDegree || 0) }));
  result.centrality = {
    receiverDegree: receiverDegree ? Number(receiverDegree) : null,
    averagePayerDegree,
    payerCount,
    networkAccounts: payerCount + receiverIds.length,
    topReceiver: receiverTop?.id || null,
    receiverDominance,
  };
  result.checks = checks;
  result.verdict = checks.every((check) => check.pass) ? 'confirmed' : 'candidate';
  result.failedChecks = checks.filter((check) => !check.pass).map((check) => check.name);
  result.evidence = [
    { kind: 'members', value: `${members.length} accounts`, citation: citation(graph.name, vertexType), query: queries.members, meaning: 'the accounts the manifest maps to this ring, read by id' },
    { kind: 'fuzzy link', value: link?.key || null, citation: link ? citation(graph.name, link.id) : null, query: queries.links, meaning: 'the pattern key that put the accounts on one link node' },
    { kind: 'payments', value: `${payments.length} payments / ${round(paymentAmount, 2)}`, citation: citation(graph.name, 'Payment'), query: queries.payments, meaning: 'the money that left the ring members' },
    { kind: 'receiver', value: receiverIds.join(', ') || null, citation: receiverIds.map((id) => citation(graph.name, id)).join(', ') || null, query: queries.receivers, meaning: 'where the ring members payments end' },
    { kind: 'centrality', value: receiverTop ? `degree=${receiverDegree}, payers=${payerCount}, dominance=${receiverDominance}` : null, citation: receiverIds.map((id) => citation(graph.name, id)).join(', ') || null, query: 'g.V(...).bothE(\'Payment\').count()', meaning: 'whether one collection account dominates the payment network around the ring (degree vs payer count)' },
  ].filter((item) => item.value != null);
  return result;
}

function contributionOf(attribution, value) {
  return attribution?.contributions?.find((item) => item.value === value) || null;
}

function makeHypothesis(id, title, score, support, checks, evidence, status) {
  const passed = checks.filter((check) => check.pass).length;
  const resolved = status || (passed === checks.length ? 'supported' : passed > 0 ? 'weak' : 'refuted');
  return { id, title, score: round(score, 2), status: resolved, support, checks, evidence, refutations: checks.filter((check) => !check.pass).map((check) => check.detail) };
}

/**
 * The full risk attribution bundle: the change by three dimensions, entity evidence for the rings, and
 * ranked hypotheses with the checks that would overturn them.
 */
export async function riskAttribution(client, rows, manifest, options = {}) {
  const periods = periodsOf(rows);
  const from = String(options.from || periods[0]);
  const to = String(options.to || periods[periods.length - 1]);
  const metric = options.metric || 'exposure';
  if (!manifest.metrics[metric]) throw new Error(`risk attribution: metric '${metric}' is not in the manifest`);
  const by = options.by || 'ring';
  const attributions = {};
  for (const dimension of unique([by, 'ring', 'rule', 'channel'])) {
    if (!manifest.dimensions[dimension]) continue;
    const beforeRows = groupMetric(rowsFor(rows, from), manifest.metrics[metric], manifest.dimensions[dimension]);
    const afterRows = groupMetric(rowsFor(rows, to), manifest.metrics[metric], manifest.dimensions[dimension]);
    attributions[dimension] = attribute({ beforeRows, afterRows, from, to, metric, dimension, warnings: [] });
  }
  const primary = attributions[by] || attributions.ring;
  const summaryBefore = riskSummary(rows, manifest, from);
  const summaryAfter = riskSummary(rows, manifest, to);
  const graph = { name: options.riskGraph || manifest.graph?.name, vertexType: manifest.graph?.vertexType || 'Account', ...manifest.graph };
  const ringEvidence = [];
  const ringsToEnrich = unique([
    ...(options.enrichRings || []),
    ...Object.keys(manifest.rings || {}).filter((ring) => ring !== 'none' && manifest.rings[ring]?.accounts?.length),
    ...(attributions.ring?.contributions || []).map((item) => item.value).filter((value) => value !== 'none'),
  ]);
  for (const ringId of ringsToEnrich) {
    const ring = manifest.rings[ringId];
    if (!ring) continue;
    ringEvidence.push(await enrichRing(client, graph, ringId, ring, { minMembers: manifest.minMembers || 3 }));
  }
  const ringContribution = (attributions.ring?.contributions || []).filter((item) => item.value !== 'none');
  const topRing = ringContribution[0] || null;
  const topRingEvidence = ringEvidence.find((item) => item.ring === topRing?.value) || null;
  const ringShare = topRing?.shareOfChange ?? 0;
  const ringChecks = [
    { name: 'ring contributes at least 20% of the delta', pass: ringShare >= 20, detail: `${topRing?.value || 'none'} shareOfChange=${ringShare}%` },
    { name: 'the ring has a shared FuzzyLink key', pass: Boolean(topRingEvidence?.link?.key), detail: topRingEvidence?.link?.key || 'no shared key' },
    { name: 'the ring pays one collection receiver', pass: (topRingEvidence?.receivers?.length || 0) === 1, detail: `${topRingEvidence?.receivers?.length || 0} receiver(s)` },
    { name: 'the receiver dominates the network', pass: (topRingEvidence?.centrality?.receiverDominance ?? 0) >= 2, detail: `receiver degree=${topRingEvidence?.centrality?.receiverDegree}, payers=${topRingEvidence?.centrality?.payerCount}, dominance=${topRingEvidence?.centrality?.receiverDominance}` },
  ];
  const ringScore = Math.min(100, Math.abs(ringShare)) * 0.5 + ringChecks.filter((check) => check.pass).length * 10;
  const hypotheses = [];
  hypotheses.push(makeHypothesis('ring-reentry', `团伙 ${topRing?.value || '(none)'} 的敞口回升`, ringScore,
    [`${topRing?.value || 'no ring'} 贡献了敞口变化的 ${ringShare}%`, `团伙证据：${topRingEvidence?.memberCount || 0} 个账户、${topRingEvidence?.payments?.count || 0} 笔付款、收款账户 ${topRingEvidence?.receivers?.map((receiver) => receiver.id).join(', ') || '(none)'}`],
    ringChecks, topRingEvidence?.evidence || []));

  const sensitive = manifest.rulesSensitive || [];
  const positiveRules = (attributions.rule?.contributions || []).filter((item) => item.delta > 0);
  // the hypothesis is about the sensitivity-prone rule when the ledger has one, not merely the largest rule
  const topRule = positiveRules.find((item) => sensitive.includes(item.value)) || positiveRules[0] || null;
  const mini = contributionOf(attributions.channel, 'MINI');
  const fpDelta = (summaryAfter.fp_rate ?? 0) - (summaryBefore.fp_rate ?? 0);
  const ruleChecks = [
    { name: 'a rule contributed most of the increase', pass: Boolean(topRule) && Math.abs(topRule?.shareOfChange ?? 0) >= 20, detail: `${topRule?.value || 'none'} shareOfChange=${topRule?.shareOfChange ?? 0}%` },
    { name: 'the rule is marked sensitivity-prone', pass: Boolean(topRule && sensitive.includes(topRule.value)), detail: `${topRule?.value || 'none'} in ${sensitive.join(', ') || '(none)'}` },
    { name: 'MINI channel moved', pass: Boolean(mini && mini.shareOfChange >= 15), detail: `MINI delta=${mini?.delta ?? 0}, share=${mini?.shareOfChange ?? 0}%` },
    { name: 'the false-positive rate rose', pass: fpDelta >= 5, detail: `FP rate ${summaryBefore.fp_rate}% -> ${summaryAfter.fp_rate}% (delta ${round(fpDelta, 2)}pp)` },
  ];
  const ruleScore = ruleChecks.filter((check) => check.pass).length * 20 + Math.min(100, Math.abs(topRule?.shareOfChange ?? 0)) * 0.2;
  hypotheses.push(makeHypothesis('rule-sensitivity', `规则 ${topRule?.value || '(none)'} 的敏感度上升`, ruleScore,
    [`规则 ${topRule?.value || 'none'} 贡献 ${topRule?.shareOfChange ?? 0}% 的敞口变化`, `MINI 渠道贡献 ${mini?.shareOfChange ?? 0}%`, `FP 率 ${summaryBefore.fp_rate}% -> ${summaryAfter.fp_rate}%`],
    ruleChecks,
    [{ kind: 'case ledger', value: `${summaryAfter.cases} cases, ${summaryAfter.fp_cases} false positives`, citation: options.casesPath || 'risk-cases.csv', query: null, meaning: 'the ledger rows behind this slice' }]));

  const lossDelta = (summaryAfter.loss ?? 0) - (summaryBefore.loss ?? 0);
  const blockedDelta = (summaryAfter.blocked ?? 0) - (summaryBefore.blocked ?? 0);
  const lossChecks = [
    { name: 'confirmed loss fell', pass: lossDelta < 0, detail: `${summaryBefore.loss} -> ${summaryAfter.loss} (${round(lossDelta, 2)})` },
    { name: 'blocked amount rose', pass: blockedDelta > 0, detail: `${summaryBefore.blocked} -> ${summaryAfter.blocked} (${round(blockedDelta, 2)})` },
    { name: 'exposure rose', pass: (summaryAfter.exposure ?? 0) > (summaryBefore.exposure ?? 0), detail: `${summaryBefore.exposure} -> ${summaryAfter.exposure}` },
  ];
  hypotheses.push(makeHypothesis('loss-improvement', '敞口上升但确认损失下降：拦截前置，而不是风险恶化', lossChecks.filter((check) => check.pass).length * 25,
    [`敞口 +${round((summaryAfter.exposure ?? 0) - (summaryBefore.exposure ?? 0), 2)}`, `确认损失 ${round(lossDelta, 2)}`, `拦截金额 +${round(blockedDelta, 2)}`],
    lossChecks,
    [{ kind: 'metric ledger', value: `loss ${summaryBefore.loss} -> ${summaryAfter.loss}; blocked ${summaryBefore.blocked} -> ${summaryAfter.blocked}`, citation: options.casesPath || 'risk-cases.csv', query: null, meaning: 'the same ledger, read as loss and blocked amount' }]));

  hypotheses.sort((left, right) => right.score - left.score);
  return {
    ok: true,
    graph: graph.name,
    metric,
    metricLabel: metricLabel(manifest, metric),
    unit: manifest.metrics[metric].unit || '',
    by,
    from,
    to,
    periods,
    primary,
    attributions,
    summaryBefore,
    summaryAfter,
    deltas: {
      exposure: round((summaryAfter.exposure ?? 0) - (summaryBefore.exposure ?? 0), 2),
      loss: round(lossDelta, 2),
      blocked: round(blockedDelta, 2),
      cases: (summaryAfter.cases ?? 0) - (summaryBefore.cases ?? 0),
      blockRate: round((summaryAfter.block_rate ?? 0) - (summaryBefore.block_rate ?? 0), 2),
      fpRate: round(fpDelta, 2),
    },
    ringEvidence,
    hypotheses,
    limits: [
      '归因是描述性的：它说明哪个切片贡献了变化，不证明因果。',
      'FuzzyLink 是模式相似性候选，不是欺诈事实；最终仍要人工复核账户与资金路径。',
      '账本来自风控案件系统；未进入账本的案件不在这个答案里。',
    ],
    warnings: unique([...(primary?.warnings || []), ...ringEvidence.flatMap((item) => item.warnings || [])]),
  };
}

export function renderRiskAttribution(bundle) {
  const lines = [];
  const before = bundle.summaryBefore;
  const after = bundle.summaryAfter;
  lines.push(`# 风控归因：${bundle.metricLabel} by ${bundle.by}, ${bundle.from} -> ${bundle.to}`);
  lines.push('');
  lines.push(`风险图：${bundle.graph}`);
  lines.push(`敞口 ${before.exposure} -> ${after.exposure} (${bundle.deltas.exposure >= 0 ? '+' : ''}${bundle.deltas.exposure})`);
  lines.push(`确认损失 ${before.loss} -> ${after.loss} (${bundle.deltas.loss >= 0 ? '+' : ''}${bundle.deltas.loss}) · 拦截 ${before.blocked} -> ${after.blocked} (${bundle.deltas.blocked >= 0 ? '+' : ''}${bundle.deltas.blocked})`);
  lines.push(`案件 ${before.cases} -> ${after.cases} · 拦截率 ${before.block_rate}% -> ${after.block_rate}% · FP 率 ${before.fp_rate}% -> ${after.fp_rate}%`);
  lines.push('');
  lines.push('## 贡献');
  lines.push('value | before | after | delta | shareOfChange');
  lines.push('---|---:|---:|---:|---:');
  for (const item of bundle.primary.contributions.slice(0, 10)) {
    lines.push(`${item.value} | ${item.before} | ${item.after} | ${item.delta >= 0 ? '+' : ''}${item.delta} | ${item.shareOfChange == null ? 'n/a' : `${item.shareOfChange}%`}`);
  }
  lines.push(`contributions sum = ${bundle.primary.contributionsSum}; decomposition adds up = ${bundle.primary.decompositionAddsUp}`);
  lines.push('');
  lines.push('## 团伙证据（PhoenixGraph）');
  for (const ring of bundle.ringEvidence) {
    lines.push(`### ${ring.label} (${ring.ring})`);
    if (!ring.executed) { lines.push(`- 未执行：${ring.reason}`); continue; }
    lines.push(`- ${ring.memberCount} 个账户；FuzzyLink key: ${ring.link?.key || '(none)'}；pattern ${ring.link?.patternId ?? '(none)'}`);
    lines.push(`- 付款 ${ring.payments.count} 笔，金额 ${ring.payments.amount}；收款账户 ${ring.receivers.map((receiver) => receiver.id).join(', ') || '(none)'}`);
    lines.push(`- receiver degree ${ring.centrality.receiverDegree}, payers ${ring.centrality.payerCount}, dominance ${ring.centrality.receiverDominance}, network accounts ${ring.centrality.networkAccounts}`);
    lines.push(`- checks: ${ring.checks.map((check) => `${check.pass ? 'ok' : 'FAIL'} ${check.name}`).join('；')}`);
    lines.push(`- verdict: ${ring.verdict}${ring.failedChecks.length ? ` (failed: ${ring.failedChecks.join(', ')})` : ''}`);
    for (const evidence of ring.evidence) lines.push(`  - ${evidence.kind}: ${evidence.value} [${evidence.citation || 'ledger'}] via \`${evidence.query || 'ledger'}\``);
  }
  lines.push('');
  lines.push('## 候选解释（带推翻条件）');
  for (const hypothesis of bundle.hypotheses) {
    lines.push(`### ${hypothesis.title}`);
    lines.push(`- score ${hypothesis.score} · ${hypothesis.status}`);
    for (const support of hypothesis.support) lines.push(`- 支持：${support}`);
    for (const check of hypothesis.checks) lines.push(`- ${check.pass ? 'ok' : 'FAIL'} ${check.name}: ${check.detail}`);
    for (const refutation of hypothesis.refutations) lines.push(`- 推翻条件：${refutation}`);
    lines.push('');
  }
  for (const warning of bundle.warnings) lines.push(`> warning: ${warning}`);
  for (const limit of bundle.limits) lines.push(`> limit: ${limit}`);
  return lines.join('\n');
}

/** Dashboard data for one period: KPI tiles, breakdowns and ring evidence. */
export async function riskDashboard(client, rows, manifest, options = {}) {
  const periods = periodsOf(rows);
  const period = String(options.period || periods[periods.length - 1]);
  const summary = riskSummary(rows, manifest, period);
  const previous = periods.indexOf(period) > 0 ? riskSummary(rows, manifest, periods[periods.indexOf(period) - 1]) : null;
  const breakdowns = [];
  for (const dimension of ['ring', 'rule', 'channel']) {
    if (!manifest.dimensions[dimension]) continue;
    breakdowns.push({
      dimension,
      rows: groupMetric(rowsFor(rows, period), manifest.metrics.exposure, manifest.dimensions[dimension]).slice(0, options.topN || 8),
    });
  }
  const graph = { name: options.riskGraph || manifest.graph?.name, vertexType: manifest.graph?.vertexType || 'Account', ...manifest.graph };
  const rings = Object.keys(manifest.rings || {}).filter((ring) => ring !== 'none' && manifest.rings[ring]?.accounts?.length);
  const evidence = [];
  for (const ringId of rings) evidence.push(await enrichRing(client, graph, ringId, manifest.rings[ringId], { minMembers: manifest.minMembers || 3 }));
  return {
    ok: true,
    graph: graph.name,
    period,
    periods,
    summary,
    previous,
    deltas: previous ? {
      exposure: round(summary.exposure - previous.exposure, 2),
      loss: round(summary.loss - previous.loss, 2),
      blocked: round(summary.blocked - previous.blocked, 2),
      cases: summary.cases - previous.cases,
      block_rate: round((summary.block_rate ?? 0) - (previous.block_rate ?? 0), 2),
      fp_rate: round((summary.fp_rate ?? 0) - (previous.fp_rate ?? 0), 2),
      review_hours: round((summary.review_hours ?? 0) - (previous.review_hours ?? 0), 2),
      fpRate: round((summary.fp_rate ?? 0) - (previous.fp_rate ?? 0), 2),
    } : null,
    breakdowns,
    evidence,
    warnings: unique(evidence.flatMap((item) => item.warnings || [])),
    limits: [
      '每个指标都能追到案件账本的列；每个团伙结论都能追到一条查询。',
      'FuzzyLink 是模式候选，不是事实；图上没有证据时结论会降级为 candidate。',
      '看板是描述性的；归因回答“哪个切片”，不回答“为什么”。',
    ],
  };
}

export function renderRiskDashboardMarkdown(dashboard) {
  const lines = [];
  const s = dashboard.summary;
  lines.push(`# 风控看板 · ${dashboard.period}`);
  lines.push('');
  lines.push(`风险图：${dashboard.graph} · 上一期：${dashboard.previous?.period || '(none)'}`);
  lines.push(`| KPI | 本期 | 上期 | 变化 |`);
  lines.push('|---|---:|---:|---:|');
  const row = (label, key, suffix = '') => {
    const prev = dashboard.previous?.[key];
    const delta = dashboard.deltas?.[key];
    lines.push(`| ${label} | ${s[key]}${suffix} | ${prev ?? '-'}${prev == null ? '' : suffix} | ${delta == null ? '-' : `${delta >= 0 ? '+' : ''}${delta}`} |`);
  };
  row('风险敞口', 'exposure');
  row('确认损失', 'loss');
  row('拦截金额', 'blocked');
  row('案件数', 'cases');
  row('拦截率', 'block_rate', '%');
  row('FP 率', 'fp_rate', '%');
  row('平均复核时长', 'review_hours', 'h');
  lines.push('');
  for (const breakdown of dashboard.breakdowns) {
    lines.push(`## by ${breakdown.dimension}`);
    lines.push('| value | exposure |');
    lines.push('|---|---:|');
    for (const item of breakdown.rows) lines.push(`| ${item.value} | ${round(item.measure, 2)} |`);
    lines.push('');
  }
  lines.push('## 团伙证据');
  for (const ring of dashboard.evidence) {
    lines.push(`### ${ring.label}`);
    if (!ring.executed) { lines.push(`- 未执行：${ring.reason}`); continue; }
    lines.push(`- ${ring.memberCount} 个账户，${ring.payments.count} 笔付款，金额 ${ring.payments.amount}，收款账户 ${ring.receivers.map((receiver) => receiver.id).join(', ')}`);
    lines.push(`- FuzzyLink ${ring.link?.key || '(none)'}；receiver degree ${ring.centrality.receiverDegree}, dominance ${ring.centrality.receiverDominance}；verdict ${ring.verdict}`);
    lines.push(`- ${ring.checks.map((check) => `${check.pass ? 'ok' : 'FAIL'} ${check.name}`).join('；')}`);
  }
  for (const limit of dashboard.limits) lines.push(`> limit: ${limit}`);
  return lines.join('\n');
}

export function renderRiskDashboardHtml(dashboard) {
  const s = dashboard.summary;
  const kpis = [
    ['风险敞口', s.exposure, '元'], ['确认损失', s.loss, '元'], ['拦截金额', s.blocked, '元'],
    ['案件数', s.cases, '件'], ['拦截率', s.block_rate, '%'], ['FP 率', s.fp_rate, '%'], ['平均复核时长', s.review_hours, 'h'],
  ];
  const kpiHtml = kpis.map(([label, value, unit]) => `<div class="kpi"><span>${escapeHtml(label)}</span><strong>${value ?? '-'}<small>${escapeHtml(unit)}</small></strong></div>`).join('');
  const breakdownHtml = dashboard.breakdowns.map((breakdown) => `<section class="tile"><h2>by ${escapeHtml(breakdown.dimension)}</h2><table><thead><tr><th>value</th><th class="num">exposure</th></tr></thead><tbody>${breakdown.rows.map((row) => `<tr><td>${escapeHtml(row.value)}</td><td class="num">${round(row.measure, 2)}</td></tr>`).join('')}</tbody></table></section>`).join('');
  const evidenceHtml = dashboard.evidence.map((ring) => `<section class="tile ${ring.verdict === 'confirmed' ? 'confirmed' : 'candidate'}"><h2>${escapeHtml(ring.label)}</h2>${ring.executed ? `<p>${ring.memberCount} accounts · ${ring.payments.count} payments · ${ring.payments.amount}</p><p>FuzzyLink <code>${escapeHtml(ring.link?.key || '(none)')}</code> · receiver degree ${ring.centrality.receiverDegree} · dominance ${ring.centrality.receiverDominance}</p><p>${ring.checks.map((check) => `<span class="${check.pass ? 'ok' : 'fail'}">${check.pass ? 'ok' : 'FAIL'} ${escapeHtml(check.name)}</span>`).join(' ')}</p><pre>${escapeHtml(ring.evidence.map((item) => `${item.kind}: ${item.value} [${item.citation || 'ledger'}]`).join('\\n'))}</pre>` : `<p class="fail">not executed: ${escapeHtml(ring.reason)}</p>`}</section>`).join('');
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>风控看板 · ${escapeHtml(dashboard.period)}</title>
<style>
:root{color-scheme:light dark;--ink:#1c2333;--muted:#657087;--line:#dde2ea;--accent:#1769d6;--bad:#b42318;--ok:#067647;--bg:#f6f7f9}
*{box-sizing:border-box}body{margin:0;padding:28px;background:var(--bg);color:var(--ink);font:14px/1.5 ui-sans-serif,system-ui,"Segoe UI","Microsoft YaHei",sans-serif}
header,main{max-width:1180px;margin:0 auto}header{margin-bottom:20px}h1{margin:0 0 4px;font-size:24px}.meta{color:var(--muted)}
.kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin:18px 0}
.kpi{background:#fff;border:1px solid var(--line);border-radius:10px;padding:14px}.kpi span{display:block;color:var(--muted);font-size:12px}.kpi strong{font-size:24px;letter-spacing:-.02em}.kpi small{font-size:12px;color:var(--muted);margin-left:4px}
main{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:14px}.tile{background:#fff;border:1px solid var(--line);border-radius:10px;padding:14px;overflow:auto}.tile.confirmed{border-color:#a6e0bf}.tile.candidate{border-color:#f2c94c}
h2{font-size:15px;margin:0 0 8px}table{width:100%;border-collapse:collapse}th,td{border-bottom:1px solid var(--line);padding:5px;text-align:left;font-size:13px}.num{text-align:right;font-variant-numeric:tabular-nums}
.ok{color:var(--ok);font-weight:600}.fail{color:var(--bad);font-weight:600}pre{white-space:pre-wrap;word-break:break-word;font:12px/1.45 ui-monospace,Consolas,monospace;color:var(--muted)}
footer{max-width:1180px;margin:20px auto;color:var(--muted)}
</style></head><body>
<header><h1>风控看板 · ${escapeHtml(dashboard.period)}</h1><div class="meta">风险图 ${escapeHtml(dashboard.graph)} · 上一期 ${escapeHtml(dashboard.previous?.period || '(none)')} · 生成时间 ${escapeHtml(new Date().toISOString())}</div></header>
<main><section class="tile" style="grid-column:1/-1"><div class="kpis">${kpiHtml}</div></section>${breakdownHtml}${evidenceHtml}</main>
<footer>${dashboard.limits.map((limit) => `<p>${escapeHtml(limit)}</p>`).join('')}</footer>
</body></html>`;
}

/** Writes the risk dashboard under .pi/data-agent/risk/. */
export function saveRiskDashboard(dashboard, options = {}) {
  const directory = options.outDir || join(projectRoot(), '.pi', 'data-agent', 'risk');
  mkdirSync(directory, { recursive: true });
  const stamp = new Date(options.now ?? Date.now()).toISOString().replace(/[:.]/g, '-');
  const base = join(directory, `risk-${pathSegment(dashboard.period, 'period')}-${stamp}`);
  const html = options.html || renderRiskDashboardHtml(dashboard);
  writeFileSync(`${base}.html`, html);
  writeFileSync(`${base}.json`, `${JSON.stringify(dashboard, null, 2)}\n`);
  return { html: `${base}.html`, json: `${base}.json` };
}
