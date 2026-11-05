/**
 * A plan, before anything runs.
 *
 * The layer is precise but it decides a lot silently: which metric a word meant, which dimension it will break
 * down by, which property the sum reads, which definition (before or after a dated change) answers, and which rows
 * the period filter keeps. In a terminal that is fine when the question was explicit and wrong when it was not - so
 * the read products are built as a *plan* first: every decision with its source (`explicit`, `resolved`, `default`),
 * the traversal it will run, and the refusals it already knows about. An interactive caller shows the plan and waits
 * for a yes; a script passes the plan through.
 *
 * Nothing here talks to the service: a plan is a value, which is what makes it testable and what makes "confirm
 * before executing" a property of the code path rather than a convention.
 */
import { bindingOf, dimensionNode, metricNode, yearProperty, dateDimension } from './execute.mjs';
import { resolveDimension, resolveMetric } from './resolve.mjs';
import { isValidAt, windowOf, windowText } from './validity.mjs';
import { parseQuestion } from './ask.mjs';

const explicit = (value) => ({ source: 'explicit', value });
const resolved = (value, how) => ({ source: 'resolved', value, how });
const fallback = (value, why) => ({ source: 'default', value, why });

function metricDecision(model, reference) {
  const found = resolveMetric(model, reference);
  if (!found) return { key: 'metric', label: '指标', value: String(reference), source: 'missing', note: 'not in the model' };
  const node = found.node;
  const properties = node.properties || {};
  return {
    key: 'metric',
    label: '指标',
    value: `${node.id}（${properties.name || node.id}）`,
    detail: [
      properties.expression ? `表达式 ${properties.expression}` : '没有表达式（term，不能计算）',
      properties.graphProperty ? `绑定 ${properties.graphProperty}` : '未绑定（只能导出 SQL）',
      properties.unit ? `单位 ${properties.unit}` : null,
    ].filter(Boolean).join(' · '),
    // "explicit" means the caller wrote the id itself; a name, alias, binding or expression was resolved to it
    how: String(reference) === node.id ? null : found.how,
    source: String(reference) === node.id ? 'explicit' : 'resolved',
    node: node.id,
  };
}

function dimensionDecision(model, reference, source) {
  const found = resolveDimension(model, reference);
  if (!found) return { key: 'dimension', label: '维度', value: String(reference), source: 'missing', note: 'not in the model' };
  return {
    key: 'dimension',
    label: '维度',
    value: `${found.node.id}（${found.node.properties?.name || found.node.id}）`,
    detail: found.node.properties?.graphProperty ? `按 ${found.node.properties.graphProperty} 分组` : '未绑定，不能分组',
    how: String(reference) === found.node.id ? null : found.how,
    source: String(reference) === found.node.id ? 'explicit' : source,
    node: found.node.id,
  };
}

function algorithmDecision(metric, dimension) {
  const properties = metric?.properties || {};
  const measure = properties.graphProperty;
  const expression = properties.expression || null;
  if (!expression) return { key: 'algorithm', label: '算法', value: '不执行', detail: '指标没有表达式，只能导出 SQL', source: 'missing' };
  if (!measure) return { key: 'algorithm', label: '算法', value: '不执行', detail: '指标未绑定到事实属性，只能导出 SQL', source: 'missing' };
  const reduction = /^\s*count\s*\(/i.test(expression) || measure === 'count'
    ? `count()`
    : `values('${measure}').sum()`;
  return {
    key: 'algorithm',
    label: '算法',
    value: dimension ? `按维度分组后 ${reduction}` : reduction,
    detail: `表达式 ${expression} · 事实属性 ${measure}`,
    source: 'resolved',
  };
}

function bindingDecisions(model, options) {
  const binding = bindingOf(model, options);
  const decisions = [];
  if (!binding.dataGraph || !binding.vertexType) {
    decisions.push({ key: 'binding', label: '事实图', value: '未绑定', source: 'missing', note: '模型没有写 dataGraph/vertexType，无法执行' });
  } else {
    decisions.push({
      key: 'binding', label: '事实图',
      value: `${binding.dataGraph}/${binding.vertexType}${binding.factIds?.length ? `（${binding.factIds.length} 个事实 id）` : ''}`,
      source: options.dataGraph || options.vertexType ? 'explicit' : 'resolved',
    });
  }
  return decisions;
}

function asOfDecision(model, asOf) {
  if (asOf == null) return null;
  const instant = new Date(model.asOf || Date.now());
  return {
    key: 'asOf', label: '口径时点', value: model.asOf || String(asOf),
    detail: `按 ${instant.toISOString()} 生效的定义计算`,
    source: 'explicit',
  };
}

/**
 * The plan for a structured request.
 *
 * @param {object} model the model as loaded (already resolved at `asOf`)
 * @param {{kind:'dashboard'|'attribution', request:object}} input
 */
export function planFor(model, input) {
  const kind = input.kind;
  const request = input.request || {};
  const decisions = [];
  const missing = [];

  const references = kind === 'attribution'
    ? [request.metric]
    : (request.metrics && request.metrics.length ? request.metrics : (request.metric ? [request.metric] : (model.nodes || []).filter((node) => node.entityType === 'Metric').map((node) => node.id)));

  const metrics = [];
  for (const reference of references) {
    const decision = metricDecision(model, reference);
    decisions.push(decision);
    if (decision.node) metrics.push(metricNode(model, decision.node));
    else missing.push(`指标 ${reference} 不在模型里`);
  }

  const dimensions = [];
  if (kind === 'attribution') {
    if (request.by) {
      const decision = dimensionDecision(model, request.by, 'explicit');
      decisions.push(decision);
      if (decision.node) dimensions.push(dimensionNode(model, decision.node));
      else missing.push(`维度 ${request.by} 不在模型里`);
    } else {
      decisions.push({ key: 'dimension', label: '维度', value: '未指定', source: 'missing', note: '归因必须指定一个维度' });
      missing.push('归因没有指定维度');
    }
  } else {
    const requested = request.dimensions || (request.dimension ? [request.dimension] : null);
    if (requested) {
      for (const reference of requested) {
        const decision = dimensionDecision(model, reference, 'explicit');
        decisions.push(decision);
        if (decision.node) dimensions.push(dimensionNode(model, decision.node));
        else missing.push(`维度 ${reference} 不在模型里`);
      }
    } else if (kind === 'dashboard') {
      // the dashboard's default breakdown: say that it is a default, not something the user asked for
      decisions.push({
        key: 'dimension', label: '维度', value: '未指定（默认按模型给出的第一个已绑定维度拆解）',
        source: 'default', why: '没有指定维度时看板会自己挑一个',
      });
    }
  }

  if (kind === 'dashboard') {
    decisions.push(request.year != null
      ? { key: 'filter', label: '过滤条件', value: `year = ${String(request.year).trim()}`, source: 'explicit' }
      : { key: 'filter', label: '过滤条件', value: '无（全部年份）', source: 'default', why: '没有指定期间时看板汇总所有行' });
    if (request.topN != null) decisions.push({ key: 'topN', label: '排名条数', value: `前 ${request.topN} 条`, source: 'explicit' });
  } else {
    decisions.push({ key: 'filter', label: '过滤条件', value: `from = ${request.from} → to = ${request.to}`, source: 'explicit' });
    decisions.push({
      key: 'algorithm', label: '算法',
      value: `两期总量 + 按维度切片的 delta / shareOfChange / growth + 集中度(HHI, top1) + 离群分`,
      detail: '描述性，不是因果', source: 'resolved',
    });
  }

  if (kind === 'dashboard') decisions.push(algorithmDecision(metrics[0], dimensions[0]));
  decisions.push(...bindingDecisions(model, request));
  const asOf = asOfDecision(model, request.asOf);
  if (asOf) decisions.push(asOf);

  const inferred = decisions.filter((decision) => decision.source === 'default' || decision.source === 'resolved');
  const refusals = decisions.filter((decision) => decision.source === 'missing')
    .map((decision) => `${decision.label}：${decision.value}${decision.note ? ` — ${decision.note}` : ''}`);

  return {
    ok: missing.length === 0,
    kind,
    model: model.graph,
    asOf: model.asOf || null,
    request,
    decisions,
    inferred: inferred.map((decision) => decision.label),
    missing,
    refusals,
    // a plan is confirmed when something was inferred, when a refusal is known, or when the caller asks for the plan
    needsConfirmation: inferred.length > 0 || missing.length > 0,
  };
}

/** The plan for a question in words: parse it, then plan the request it produced. */
export function planForQuestion(model, question) {
  const parsed = parseQuestion(model, question);
  if (!parsed.ok) {
    return {
      ok: false, kind: parsed.kind || 'dashboard', model: model.graph, question,
      reason: parsed.reason, decisions: [], inferred: [], missing: [parsed.reason], refusals: [parsed.reason],
      needsConfirmation: false, request: null,
    };
  }
  return { ...planFor(model, { kind: parsed.kind, request: parsed.request }), question, understood: parsed.understood };
}

/**
 * What an interactive caller shows. The refusals come first: a plan that will refuse is better declined than run.
 */
export function renderPlan(plan) {
  const lines = [];
  if (plan.question) lines.push(`问题：${plan.question}`);
  if (!plan.ok && plan.reason) return `不能执行：${plan.reason}`;
  lines.push(`将执行：${plan.kind === 'attribution' ? '归因（两期变化拆解）' : '看板（指标 × 维度 × 期间）'} · 模型 ${plan.model}${plan.asOf ? ` · 时点 ${plan.asOf}` : ''}`);
  const icon = { explicit: '·', resolved: '~', default: '?', missing: '!' };
  for (const decision of plan.decisions) {
    const mark = icon[decision.source] || '·';
    const suffix = decision.detail ? ` — ${decision.detail}` : (decision.note ? ` — ${decision.note}` : '');
    const how = decision.how ? ` [按${decision.how}解析]` : '';
    const why = decision.why ? ` [默认：${decision.why}]` : '';
    lines.push(`  ${mark} ${decision.label}：${decision.value}${how}${suffix}${why}`);
  }
  if (plan.inferred.length) lines.push(`~ 解析得到 / ? 默认值：${plan.inferred.join('、')}`);
  if (plan.refusals.length) lines.push(`! 会拒答：${plan.refusals.join('；')}`);
  lines.push('（· 明确指定 · ~ 由模型解析 · ? 默认值 · ! 会拒答）');
  return lines.join('\n');
}

/** One line for a log, e.g. `dashboard: metric/营收 by dim/渠道 year=2025`. */
export function summarisePlan(plan) {
  if (!plan.ok && plan.reason) return `${plan.kind}: refused (${plan.reason})`;
  const metric = plan.decisions.filter((decision) => decision.key === 'metric').map((decision) => decision.node || decision.value).join(', ');
  const dimension = plan.decisions.filter((decision) => decision.key === 'dimension').map((decision) => decision.node).filter(Boolean).join(', ');
  const filter = plan.decisions.find((decision) => decision.key === 'filter');
  return `${plan.kind}: ${metric}${dimension ? ` by ${dimension}` : ''}${filter ? ` ${filter.value}` : ''}`;
}

export { isValidAt, windowOf, windowText, yearProperty, dateDimension };
