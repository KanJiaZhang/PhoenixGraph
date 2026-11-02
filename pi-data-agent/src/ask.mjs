/**
 * A question, in the words people actually use.
 *
 * The structured request (`metric`, `dimensions`, `year`, `from`/`to`, `topN`) is precise but it is not how the
 * question arrives: "2025年营收按渠道拆分，前3", "营收 2025 by 渠道 top3", "按渠道看下 2025 年的收入前三" are one
 * question written three ways. This module turns any of them into the same request, so the answer cannot depend on
 * which phrasing the person happened to type - and every phrase it does not understand becomes a refusal with the
 * alternatives, never a silent half-answer.
 *
 * It is deliberately a *grammar over the model*, not a language model: it looks for the metric (by name, alias, id,
 * bound property or expression), the dimensions (after 按/by, or anywhere a dimension is named), the years (two years
 * plus a change word = an attribution), and `topN` (前3 / top 3 / 前三名). Everything is resolved against the model,
 * so a phrase naming something the model does not hold is reported as such.
 */
import { resolveDimension, resolveMetric } from './resolve.mjs';

const YEAR = /(?:^|[^\d])((?:19|20)\d{2})(?![\d])/g;
const TOP = /(?:前|top[\s-]?)\s*([1-9]\d?|[一二三四五六七八九十])\s*(?:名|个|条)?/i;
const NUMERALS = { 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 七: 7, 八: 8, 九: 9, 十: 10 };
const CHANGE = /归因|贡献|变化|增长|下降|减少|上升|涨|跌|对比|相比|比较|差多少|vs\.?|versus|attribut|contribut|change|grew|grow|drop/i;
const BY = /(?:按|根据|by)\s*([^,，。；;\d]{1,24}?)\s*(?:拆分|拆解|分组|切分|看|来|整理|汇总|breakdown|group)?(?=$|[,，。；;\s]|前|top)/i;

const normalise = (text) => String(text ?? '').replace(/\s+/g, ' ').trim();

/** Every year the text mentions, in the order it mentions them. */
export function yearsIn(text) {
  return [...String(text ?? '').matchAll(YEAR)].map((match) => Number(match[1]));
}

/** `前3` / `top 3` / `前三名` / `top-3` as a number. */
export function topNIn(text) {
  const match = TOP.exec(String(text ?? ''));
  if (!match) return null;
  const value = NUMERALS[match[1]] ?? Number(match[1]);
  return Number.isFinite(value) && value > 0 ? Math.min(20, value) : null;
}

/** The references in the text that resolve to a node of this type, longest match first. */
function referencesIn(model, text, entityType) {
  const haystack = normalise(text).toLowerCase();
  const found = [];
  for (const node of model.nodes || []) {
    if (node.entityType !== entityType) continue;
    const alias = Array.isArray(node.properties?.alias) ? node.properties.alias : [];
    const candidates = [
      ['id', String(node.id)],
      ['id', String(node.id).replace(/^[a-z]+\//i, '')],
      ['name', node.properties?.name],
      ...alias.map((value) => ['alias', value]),
      ['binding', node.properties?.graphProperty],
      ['expression', node.properties?.expression],
      ['column', node.properties?.column],
    ];
    for (const [how, value] of candidates) {
      const needle = normalise(value).toLowerCase();
      if (needle.length < 1) continue;
      if (how === 'binding' && needle.includes('=')) continue;
      if (!haystack.includes(needle)) continue;
      found.push({ node, how, needle });
    }
  }
  // the longest reference wins: a phrase mentioning 净营收 must not be read as 营收
  found.sort((left, right) => right.needle.length - left.needle.length || left.node.id.localeCompare(right.node.id));
  return found;
}

/** The dimension a phrase names after 按/by, or anywhere a dimension is named. */
export function dimensionIn(model, text) {
  const match = BY.exec(normalise(text));
  if (match) {
    const resolved = resolveDimension(model, match[1].trim());
    if (resolved) return { node: resolved.node, how: resolved.how, phrase: match[1].trim() };
  }
  const found = referencesIn(model, text, 'Dimension');
  if (!found.length) return null;
  return { node: found[0].node, how: found[0].how, phrase: found[0].needle };
}

/**
 * The request a question means.
 *
 * @returns {{ok:boolean, kind:'dashboard'|'attribution', request:object, understood:object, reason?:string}}
 */
export function parseQuestion(model, text) {
  const question = normalise(text);
  if (!question) return { ok: false, kind: 'dashboard', reason: 'an empty question asks nothing', understood: {} };
  const metrics = referencesIn(model, question, 'Metric');
  if (!metrics.length) {
    const available = (model.nodes || []).filter((node) => node.entityType === 'Metric').map((node) => node.properties?.name || node.id);
    return {
      ok: false, kind: 'dashboard', understood: {},
      reason: `no metric in this model is named in the question; available: ${available.sort().join(', ') || 'none'}`,
    };
  }
  const metric = metrics[0];
  const dimension = dimensionIn(model, question);
  const years = yearsIn(question);
  const topN = topNIn(question);
  const change = CHANGE.test(question);

  if (years.length >= 2 && change) {
    const from = String(Math.min(...years));
    const to = String(Math.max(...years));
    return {
      ok: true, kind: 'attribution',
      request: { metric: metric.node.id, by: dimension?.node.id || null, from, to },
      understood: { metric: `${metric.node.id} (by ${metric.how}: ${metric.needle})`, by: dimension ? `${dimension.node.id} (by ${dimension.how})` : null, from, to, changeWord: true },
    };
  }
  const request = { metric: metric.node.id };
  if (years.length) request.year = String(years[years.length - 1]);
  if (dimension) request.dimensions = [dimension.node.id];
  if (topN != null) request.topN = topN;
  return {
    ok: true, kind: 'dashboard', request,
    understood: {
      metric: `${metric.node.id} (by ${metric.how}: ${metric.needle})`,
      dimension: dimension ? `${dimension.node.id} (by ${dimension.how})` : null,
      year: request.year || null, topN: request.topN ?? null,
    },
  };
}

/** The parsed question as a line of text, for a report that has to show it understood. */
export function renderQuestion(parsed) {
  if (!parsed.ok) return `not understood: ${parsed.reason}`;
  const understood = parsed.understood || {};
  return `understood as ${parsed.kind}: ${Object.entries(understood).filter(([, value]) => value != null && value !== false).map(([key, value]) => `${key}=${value}`).join(' · ')}`;
}
