/**
 * Business attribution: how much of a change each slice accounts for.
 *
 * The arithmetic is plain and testable: union the slices of the two periods, subtract, divide by the total
 * change, and report the shares on both sides. It says "descriptive, not causal" with every answer because a
 * slice explaining most of a delta is not necessarily its cause. A slice that exists in only one period is
 * still reported, with the missing side at zero and a warning naming it: dropping it would make the
 * contributions stop adding up to the change, which is the one property this answer promises.
 */
import { round } from './util.mjs';
import { execute, bindingOf, dimensionNode, metricNode } from './execute.mjs';
import { choices } from './resolve.mjs';

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  if (sorted.length === 0) return 0;
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

function concentration(shares) {
  const total = shares.reduce((sum, value) => sum + value, 0);
  if (!total) return { hhi: null, top1: null };
  const normalised = shares.map((value) => value / total);
  return {
    hhi: round(normalised.reduce((sum, value) => sum + value * value, 0), 4),
    top1: round(Math.max(...normalised) * 100, 2),
  };
}

/**
 * @param {{beforeRows:Array<{value:string,measure:number}>, afterRows:Array<{value:string,measure:number}>,
 *          from:number|string, to:number|string, metric?:string, dimension?:string, warnings?:string[]}} input
 */
export function attribute(input) {
  const before = new Map((input.beforeRows || []).map((row) => [String(row.value), Number(row.measure) || 0]));
  const after = new Map((input.afterRows || []).map((row) => [String(row.value), Number(row.measure) || 0]));
  const values = [...new Set([...before.keys(), ...after.keys()])].sort();
  const totalBefore = [...before.values()].reduce((sum, value) => sum + value, 0);
  const totalAfter = [...after.values()].reduce((sum, value) => sum + value, 0);
  const delta = totalAfter - totalBefore;
  const contributions = values.map((value) => {
    const beforeValue = before.get(value) ?? 0;
    const afterValue = after.get(value) ?? 0;
    const change = afterValue - beforeValue;
    return {
      value,
      before: round(beforeValue, 4),
      after: round(afterValue, 4),
      delta: round(change, 4),
      shareOfChange: delta === 0 ? null : round((change / delta) * 100, 2),
      growthPercent: beforeValue === 0 ? null : round((change / beforeValue) * 100, 2),
      shareBefore: totalBefore === 0 ? null : round((beforeValue / totalBefore) * 100, 2),
      shareAfter: totalAfter === 0 ? null : round((afterValue / totalAfter) * 100, 2),
      presence: before.has(value) && after.has(value) ? 'both' : before.has(value) ? 'before_only' : 'after_only',
    };
  }).sort((left, right) => Math.abs(right.delta) - Math.abs(left.delta));

  const deltas = contributions.map((item) => item.delta);
  const middle = median(deltas);
  const mad = median(deltas.map((value) => Math.abs(value - middle)));
  const outlierScore = mad === 0 ? null : 1.4826 * mad;
  for (const item of contributions) {
    item.outlierScore = outlierScore == null ? null : round((item.delta - middle) / outlierScore, 2);
  }
  const contributionsSum = contributions.reduce((sum, item) => sum + item.delta, 0);
  const tolerance = Math.max(1e-6, Math.abs(delta) * 1e-9);
  const warnings = [...(input.warnings || [])];
  for (const item of contributions) {
    if (item.presence === 'before_only') warnings.push(`${item.value} has no value in ${input.to}; its contribution is the whole disappearance`);
    if (item.presence === 'after_only') warnings.push(`${item.value} has no value in ${input.from}; its contribution is the whole appearance`);
  }
  const concentrationBefore = concentration(contributions.map((item) => item.before));
  const concentrationAfter = concentration(contributions.map((item) => item.after));

  return {
    metric: input.metric || null,
    dimension: input.dimension || null,
    from: input.from,
    to: input.to,
    totalBefore: round(totalBefore, 4),
    totalAfter: round(totalAfter, 4),
    delta: round(delta, 4),
    deltaPercent: totalBefore === 0 ? null : round((delta / totalBefore) * 100, 2),
    direction: delta > tolerance ? 'up' : delta < -tolerance ? 'down' : 'flat',
    contributions,
    contributionsSum: round(contributionsSum, 4),
    decompositionAddsUp: Math.abs(contributionsSum - delta) <= tolerance,
    topMover: contributions[0] || null,
    concentrationBefore,
    concentrationAfter,
    warnings,
    limits: [
      'Descriptive, not causal: this says which slice moved and by how much, not why.',
      'A slice with a large shareOfChange is not necessarily the cause of the change.',
      'No rows for a slice mean the data does not cover it, not that its value was zero.',
    ],
  };
}

/** Executes both periods and attributes the change; a missing binding is a refusal with the SQL. */
export async function runAttribution(client, model, options = {}) {
  const binding = bindingOf(model, options);
  // a reference is resolved once, here, and the rest of the function speaks ids: id, name, alias, bound
  // property or column are all the same dimension, and two spellings must give one answer
  if (!options.metric) return { executed: false, reason: 'an attribution needs a metric' };
  const metric = metricNode(model, options.metric);
  if (!metric) return { executed: false, reason: `no metric '${options.metric}' in this model (by id, name, alias or bound property); available: ${choices(model, 'Metric').join(', ') || 'none'}` };
  const metricId = metric.id;
  if (!options.by) {
    return { executed: false, reason: `an attribution needs a dimension to decompose by; this model has ${choices(model, 'Dimension').join(', ') || 'none'}` };
  }
  const dimension = dimensionNode(model, options.by);
  if (!dimension) return { executed: false, reason: `no dimension '${options.by}' in this model (by id, name, alias, bound property or column); available: ${choices(model, 'Dimension').join(', ') || 'none'}` };
  const dimensionId = dimension.id;
  if (options.from == null || options.to == null) return { executed: false, reason: 'an attribution needs two periods (from and to), e.g. 2024 and 2025' };
  if (String(options.from) === String(options.to)) return { executed: false, reason: 'from and to are the same period; there is no change to attribute' };

  const before = await execute(client, model, { ...options, metric: metricId, dimension: dimensionId, year: options.from, grain: options.grain || 'raw' });
  const after = await execute(client, model, { ...options, metric: metricId, dimension: dimensionId, year: options.to, grain: options.grain || 'raw' });
  if (!before.executed || !after.executed) {
    return {
      executed: false,
      reason: before.reason || after.reason,
      before: before.executed === false ? before : undefined,
      after: after.executed === false ? after : undefined,
      metric: metricId,
      dimension: dimensionId,
      dataGraph: binding.dataGraph,
      vertexType: binding.vertexType,
    };
  }
  return {
    executed: true,
    metric: metricId,
    dimension: dimensionId,
    dataGraph: binding.dataGraph,
    vertexType: binding.vertexType,
    grain: options.grain || 'raw',
    before: { rows: before.rows, total: before.total, gremlin: before.gremlin, warnings: before.warnings, sql: before.sql },
    after: { rows: after.rows, total: after.total, gremlin: after.gremlin, warnings: after.warnings, sql: after.sql },
    attribution: attribute({
      beforeRows: before.rows,
      afterRows: after.rows,
      from: options.from,
      to: options.to,
      metric: metricId,
      dimension: dimensionId,
      warnings: [...new Set([...before.warnings, ...after.warnings])],
    }),
  };
}

/** The attribution as the agent should read it: the totals, then the movers, then the limits. */
export function renderAttribution(result) {
  if (!result.executed) {
    return [
      `attribution refused: ${result.reason}`,
      result.before?.sql || result.after?.sql ? `SQL (runs on the warehouse):\n${result.before?.sql || result.after?.sql}` : null,
    ].filter(Boolean).join('\n');
  }
  const a = result.attribution;
  const lines = [];
  lines.push(`attribution: ${a.metric} by ${a.dimension}, ${a.from} -> ${a.to} on ${result.dataGraph}/${result.vertexType}`);
  lines.push(`total ${a.totalBefore} -> ${a.totalAfter} (${a.delta >= 0 ? '+' : ''}${a.delta}${a.deltaPercent == null ? '' : `, ${a.deltaPercent}%`})`);
  lines.push('value | before | after | delta | shareOfChange | growth | presence');
  for (const item of a.contributions.slice(0, 12)) {
    lines.push(`${item.value} | ${item.before} | ${item.after} | ${item.delta >= 0 ? '+' : ''}${item.delta} | ${item.shareOfChange == null ? 'n/a' : `${item.shareOfChange}%`} | ${item.growthPercent == null ? 'n/a' : `${item.growthPercent}%`} | ${item.presence}`);
  }
  lines.push(`contributions sum = ${a.contributionsSum}; decomposition adds up = ${a.decompositionAddsUp}`);
  lines.push(`top mover: ${a.topMover ? `${a.topMover.value} (${a.topMover.delta >= 0 ? '+' : ''}${a.topMover.delta})` : 'none'}; concentration (top1 after) ${a.concentrationAfter.top1 == null ? 'n/a' : `${a.concentrationAfter.top1}%`}`);
  for (const warning of a.warnings) lines.push(`warning: ${warning}`);
  for (const limit of a.limits) lines.push(`limit: ${limit}`);
  return lines.join('\n');
}
