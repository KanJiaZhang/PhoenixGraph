/**
 * A dashboard over the semantic layer, computed from the fact graph.
 *
 * A tile is a semantic query with its provenance: the metric, the dimension, the traversal, the SQL a
 * warehouse would run, and the rows it produced. A tile that cannot execute is shown as a refusal with the
 * reason and the SQL - an empty chart would look like a zero, which is the failure this package exists to
 * avoid.
 */
import { escapeHtml, round, unique } from './util.mjs';
import { execute, bindingOf, dimensionNode, dateDimension, yearProperty, metricNode } from './execute.mjs';
import { attribute } from './attribution.mjs';

function metricOf(model, id) {
  return metricNode(model, id);
}

function metricIds(model) {
  return (model.nodes || []).filter((node) => node.entityType === 'Metric').map((node) => node.id).sort();
}

function dimensionIds(model) {
  return (model.nodes || []).filter((node) => node.entityType === 'Dimension').map((node) => node.id).sort();
}

function dimensionsOf(model, metricId) {
  const dims = [];
  const seen = new Set();
  for (const edge of model.edges || []) {
    if (edge.head === metricId && edge.relation === 'groupedBy') {
      const node = dimensionNode(model, edge.tail);
      if (node && !seen.has(node.id)) { seen.add(node.id); dims.push(node); }
    }
  }
  if (dims.length === 0) {
    for (const node of model.nodes || []) {
      if (node.entityType === 'Dimension' && node.properties?.graphProperty && !seen.has(node.id)) {
        seen.add(node.id);
        dims.push(node);
      }
    }
  }
  return dims;
}

function isDateDimension(node) {
  return /(?:^|,)year=/.test(String(node?.properties?.graphProperty || ''))
    || /date|time/i.test(String(node?.properties?.dataType || ''));
}

function sortTrend(rows) {
  return [...rows].sort((left, right) => {
    const leftNumber = Number(left.value);
    const rightNumber = Number(right.value);
    if (Number.isFinite(leftNumber) && Number.isFinite(rightNumber)) return leftNumber - rightNumber;
    return String(left.value).localeCompare(String(right.value));
  });
}

/**
 * @param {object} client PhoenixClient
 * @param {object} model  the model read from the graph (or built and not yet written)
 * @param {{year?:number|string, topN?:number, dimensions?:string[], metrics?:string[], grain?:string,
 *          dataGraph?:string, vertexType?:string, now?:Date|string, title?:string}} options
 */
export async function buildDashboard(client, model, options = {}) {
  const binding = bindingOf(model, options);
  const now = options.now ?? new Date();
  const topN = Math.max(1, Math.min(20, Number(options.topN) || 5));
  // `metrics: ['营收']`, `metric: '营收'` and no selection at all are one question with three spellings.
  const requested = options.metrics && options.metrics.length ? options.metrics
    : (options.metric ? [options.metric] : null);
  const wanted = requested
    ? requested.map((id) => String(id))
    : (model.nodes || []).filter((node) => node.entityType === 'Metric').map((node) => node.id);
  const tiles = [];
  const warnings = [...(model.warnings || [])];

  for (const reference of wanted) {
    const metric = metricOf(model, reference);
    // tiles and traversals speak the resolved id, so the same question spelled two ways produces the same tiles
    const metricId = metric ? metric.id : reference;
    if (!metric) {
      // a requested metric nobody can resolve is an answer to nothing: say so, and keep the tile count stable
      const reason = `no metric '${reference}' in this model (by id, name, alias or bound property); available: ${metricIds(model).join(', ') || 'none'}`;
      warnings.push(reason);
      tiles.push({ id: `${reference}|unknown`, kind: 'refusal', metric: reference, title: reference, reason });
      continue;
    }
    const expression = metric.properties?.expression;
    const metricLabel = metric.properties?.name || metricId.replace(/^metric\//, '');
    if (!expression) {
      tiles.push({
        id: `${metricId}|definition`, kind: 'refusal', metric: metricId, title: metricLabel,
        reason: `${metricId} has no expression; it is a term needing review, not a metric that can answer`,
      });
      continue;
    }
    if (!binding.dataGraph || !binding.vertexType) {
      tiles.push({
        id: `${metricId}|binding`, kind: 'refusal', metric: metricId, title: metricLabel,
        reason: 'the model does not name a fact graph/vertex type; the dashboard cannot execute anything',
      });
      continue;
    }

    // 1. the total for the requested period (no period = every row)
    const total = await execute(client, model, { ...options, metric: metricId, year: options.year });
    tiles.push({
      id: `${metricId}|total`, kind: 'total', title: metricLabel, metric: metricId,
      unit: metric.properties?.unit || null, year: options.year ?? null,
      ...(total.executed ? { rows: total.rows, total: total.total, gremlin: total.gremlin, sql: total.sql, warnings: total.warnings } : { reason: total.reason, sql: total.sql }),
      executed: total.executed,
    });

    // 2. the trend over the date dimension: by month inside a year, by year across the data
    const date = dateDimension(model);
    if (date) {
      const grain = options.year != null ? (dimensionPropertyGrain(date, 'month') ? 'month' : 'year') : (options.grain || 'year');
      const trend = await execute(client, model, { ...options, metric: metricId, dimension: date.id, grain, year: options.year });
      tiles.push({
        id: `${metricId}|trend|${grain}`, kind: 'trend', title: `${metricLabel} · ${grain}`, metric: metricId,
        dimension: date.id, grain, unit: metric.properties?.unit || null,
        ...(trend.executed ? { rows: sortTrend(trend.rows), total: trend.total, gremlin: trend.gremlin, sql: trend.sql, warnings: trend.warnings } : { reason: trend.reason, sql: trend.sql }),
        executed: trend.executed,
      });
    }

    // 3. the breakdown: the dimensions the model says may slice this metric, bound first
    const available = dimensionsOf(model, metricId).filter((node) => !isDateDimension(node));
    // a dimension can be named by id, name, alias, bound property or column; one that resolves to nothing is
    // reported as a refusal tile instead of being dropped (dropping it silently answered a different question)
    // an explicit list is honoured (every dimension the caller named, up to five); the default selection of a
    // metric is the one breakdown the dashboard promises (`breakdowns`)
    const explicit = Boolean(options.dimensions && options.dimensions.length);
    const requestedDimensions = explicit
      ? options.dimensions.map((id) => ({ reference: String(id), node: dimensionNode(model, id) }))
      : available.filter((node) => node.properties?.graphProperty).map((node) => ({ reference: node.id, node }));
    const chosen = requestedDimensions.slice(0, explicit ? 5 : Math.max(1, Math.min(3, Number(options.breakdowns) || 1)));
    for (const unresolved of chosen.filter((candidate) => !candidate.node)) {
      const reason = `no dimension '${unresolved.reference}' in this model (by id, name, alias, bound property or column); available: ${dimensionIds(model).join(', ') || 'none'}`;
      warnings.push(reason);
      tiles.push({ id: `${metricId}|dimension|${unresolved.reference}`, kind: 'refusal', metric: metricId, dimension: unresolved.reference, title: `${metricLabel} · by ${unresolved.reference}`, reason });
    }
    for (const { node: dimension } of chosen.filter((candidate) => candidate.node)) {
      const breakdown = await execute(client, model, { ...options, metric: metricId, dimension: dimension.id, year: options.year, grain: 'raw' });
      tiles.push({
        id: `${metricId}|breakdown|${dimension.id}`, kind: 'breakdown', title: `${metricLabel} · by ${dimension.properties?.name || dimension.id}`,
        metric: metricId, dimension: dimension.id, unit: metric.properties?.unit || null, topN,
        ...(breakdown.executed
          ? { rows: breakdown.rows.slice(0, topN), total: breakdown.total, rowCount: breakdown.rows.length, gremlin: breakdown.gremlin, sql: breakdown.sql, warnings: breakdown.warnings }
          : { reason: breakdown.reason, sql: breakdown.sql }),
        executed: breakdown.executed,
      });
    }
  }

  const executed = tiles.filter((tile) => tile.executed).length;
  const refused = tiles.filter((tile) => !tile.executed).length;
  const dashboard = {
    graph: model.graph,
    dataGraph: binding.dataGraph,
    vertexType: binding.vertexType,
    title: options.title || `${model.graph} · data dashboard`,
    generatedAt: new Date(now).toISOString(),
    model: model.stats || null,
    tiles,
    executed,
    refused,
    warnings: unique(warnings),
    sources: model.sources || [],
    limits: [
      'Every number is computed by a traversal whose rows and traversal are shown with the tile.',
      'A tile without a graphProperty is a refusal with the warehouse SQL; it is not an empty chart.',
      'The dashboard is descriptive; attribution is a separate answer and says it is not causal.',
    ],
  };
  return dashboard;
}

function dimensionPropertyGrain(dimension, grain) {
  const text = String(dimension?.properties?.graphProperty || '');
  return new RegExp(`(?:^|,)${grain}=`).test(text);
}

export function renderDashboardMarkdown(dashboard) {
  const lines = [];
  lines.push(`# ${dashboard.title}`);
  lines.push('');
  lines.push(`graph: ${dashboard.graph} · facts: ${dashboard.dataGraph || '(none)'}/${dashboard.vertexType || '(none)'} · generated: ${dashboard.generatedAt}`);
  lines.push(`tiles: ${dashboard.tiles.length} (executed ${dashboard.executed}, refused ${dashboard.refused})`);
  if (dashboard.model) {
    lines.push(`model: ${dashboard.model.metrics} metrics, ${dashboard.model.dimensions} dimensions, ${dashboard.model.tables} tables, ${dashboard.model.columns} columns`);
  }
  lines.push('');
  for (const tile of dashboard.tiles) {
    lines.push(`## ${tile.title}`);
    if (!tile.executed) {
      lines.push(`- refused: ${tile.reason}`);
      if (tile.sql) lines.push(`- SQL: \`${tile.sql.replace(/\n/g, ' ')}\``);
      lines.push('');
      continue;
    }
    lines.push(`- metric: ${tile.metric}${tile.dimension ? ` · dimension: ${tile.dimension}` : ''}${tile.year != null ? ` · year: ${tile.year}` : ''}`);
    lines.push(`- total: ${tile.total}${tile.unit ? ` ${tile.unit}` : ''}`);
    if (tile.rows?.length) {
      lines.push('');
      lines.push('| value | measure |');
      lines.push('|---|---:|');
      for (const row of tile.rows.slice(0, 12)) lines.push(`| ${row.value} | ${round(row.measure, 2)} |`);
    }
    lines.push('');
    lines.push(`<details><summary>provenance</summary>`);
    lines.push('');
    lines.push(`traversal: \`${tile.gremlin}\``);
    if (tile.sql) lines.push(`sql: \`${tile.sql.replace(/\n/g, ' ')}\``);
    for (const warning of tile.warnings || []) lines.push(`warning: ${warning}`);
    lines.push('</details>');
    lines.push('');
  }
  for (const warning of dashboard.warnings) lines.push(`> warning: ${warning}`);
  for (const limit of dashboard.limits) lines.push(`> limit: ${limit}`);
  return lines.join('\n');
}

function bar(value, max, width = 160) {
  if (!Number.isFinite(value) || !Number.isFinite(max) || max <= 0) return '';
  const length = Math.max(1, Math.round((Math.abs(value) / max) * width));
  return `<span class="bar" style="width:${length}px"></span>`;
}

function renderTileHtml(tile) {
  const heading = `<h2>${escapeHtml(tile.title)}</h2>`;
  if (!tile.executed) {
    return `<section class="tile refusal">${heading}
      <p class="bad">refused: ${escapeHtml(tile.reason)}</p>
      ${tile.sql ? `<pre>${escapeHtml(tile.sql)}</pre>` : ''}
    </section>`;
  }
  const rows = tile.rows || [];
  const max = Math.max(0, ...rows.map((row) => Math.abs(Number(row.measure) || 0)));
  const table = rows.length ? `<table><thead><tr><th>value</th><th class="num">measure</th><th></th></tr></thead><tbody>${rows.map((row) => `<tr><td>${escapeHtml(row.value)}</td><td class="num">${round(row.measure, 2)}</td><td>${bar(Number(row.measure), max)}</td></tr>`).join('')}</tbody></table>` : '<p class="bad">no rows: the data does not cover this filter</p>';
  return `<section class="tile">
    ${heading}
    <p class="total">${tile.total == null ? '' : round(tile.total, 2)} ${escapeHtml(tile.unit || '')}</p>
    ${table}
    <details><summary>provenance</summary><code>${escapeHtml(tile.gremlin || '')}</code>${tile.sql ? `<pre>${escapeHtml(tile.sql)}</pre>` : ''}${(tile.warnings || []).map((warning) => `<p class="warn">${escapeHtml(warning)}</p>`).join('')}</details>
  </section>`;
}

export function renderDashboardHtml(dashboard) {
  const tiles = dashboard.tiles.map(renderTileHtml).join('\n');
  const warnings = dashboard.warnings.map((warning) => `<li>${escapeHtml(warning)}</li>`).join('');
  const limits = dashboard.limits.map((limit) => `<li>${escapeHtml(limit)}</li>`).join('');
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${escapeHtml(dashboard.title)}</title>
<style>
  :root { color-scheme: light dark; --ink:#1d2433; --muted:#667085; --line:#dfe3ea; --accent:#2f6fed; --bad:#b42318; --warn:#b54708; }
  * { box-sizing: border-box; }
  body { margin: 0; padding: 32px; font: 14px/1.5 ui-sans-serif, system-ui, "Segoe UI", "Microsoft YaHei", sans-serif; color: var(--ink); background: #f7f8fa; }
  header { max-width: 1180px; margin: 0 auto 24px; }
  h1 { margin: 0 0 4px; font-size: 24px; }
  .meta { color: var(--muted); }
  main { max-width: 1180px; margin: 0 auto; display: grid; grid-template-columns: repeat(auto-fit, minmax(420px, 1fr)); gap: 16px; }
  .tile { background: #fff; border: 1px solid var(--line); border-radius: 10px; padding: 16px; overflow: auto; }
  .tile.refusal { border-color: #f3c7c2; background: #fff7f6; }
  h2 { margin: 0 0 8px; font-size: 16px; }
  .total { margin: 0 0 12px; font-size: 28px; font-weight: 650; letter-spacing: -0.02em; }
  table { width: 100%; border-collapse: collapse; }
  th, td { text-align: left; border-bottom: 1px solid var(--line); padding: 5px 6px; font-size: 13px; }
  th { color: var(--muted); font-weight: 550; }
  .num { text-align: right; font-variant-numeric: tabular-nums; }
  .bar { display: inline-block; height: 8px; border-radius: 4px; background: var(--accent); vertical-align: middle; }
  .bad { color: var(--bad); }
  .warn { color: var(--warn); }
  details { margin-top: 10px; color: var(--muted); }
  code, pre { font: 12px/1.45 ui-monospace, SFMono-Regular, Consolas, monospace; white-space: pre-wrap; word-break: break-word; }
  footer { max-width: 1180px; margin: 24px auto 0; color: var(--muted); }
  ul { margin: 6px 0 12px; padding-left: 20px; }
</style></head>
<body>
<header>
  <h1>${escapeHtml(dashboard.title)}</h1>
  <div class="meta">graph ${escapeHtml(dashboard.graph)} · facts ${escapeHtml(dashboard.dataGraph || '(none)')}/${escapeHtml(dashboard.vertexType || '(none)')} · generated ${escapeHtml(dashboard.generatedAt)}</div>
  <div class="meta">${dashboard.tiles.length} tiles · ${dashboard.executed} executed · ${dashboard.refused} refused${dashboard.model ? ` · ${dashboard.model.metrics} metrics / ${dashboard.model.dimensions} dimensions` : ''}</div>
</header>
<main>
${tiles}
</main>
<footer>
  ${warnings ? `<h2>warnings</h2><ul>${warnings}</ul>` : ''}
  <h2>limits</h2><ul>${limits}</ul>
</footer>
</body></html>`;
}

/** Attribution as a dashboard tile (used by the extension's dashboard answer). */
export function attributionTile(result) {
  if (!result.executed) return { id: 'attribution|refusal', kind: 'refusal', executed: false, reason: result.reason, sql: result.before?.sql || result.after?.sql };
  const a = result.attribution;
  return {
    id: `attribution|${a.metric}|${a.dimension}|${a.from}|${a.to}`,
    kind: 'attribution', executed: true, title: `${a.metric} · ${a.dimension} · ${a.from}→${a.to}`,
    metric: a.metric, dimension: a.dimension,
    rows: a.contributions.map((item) => ({ value: item.value, measure: item.delta, before: item.before, after: item.after, shareOfChange: item.shareOfChange })),
    total: a.delta, attribution: a,
  };
}

export { attribute };
