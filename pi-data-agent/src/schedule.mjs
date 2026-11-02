/**
 * A schedule: the two read products, produced on a timer for a list of periods.
 *
 * A dashboard and an attribution are answers that go stale as data lands, so the case runs them *repeatedly* -
 * one cycle per period, waiting `everyMs` between cycles - and records what each cycle produced (the files, the
 * executed/refused tiles, the delta and the top mover). That is what "定时生成看板和归因" means here: the same
 * two calls a cron job would make, with the run log kept so the output can be inspected afterwards instead of
 * remembered.
 *
 * The clock is injectable (`now`, `sleep`), so the test can run a schedule in milliseconds and assert the order.
 */
import { dashboardFor, attributeFor } from './service.mjs';

/** The period before `period` in a yearly series, so an attribution always has a base. */
export function previousOf(period) {
  const year = Number(period);
  if (Number.isFinite(year)) return String(year - 1);
  return null;
}

/**
 * Runs the schedule.
 *
 * @param {object} options
 * @param {string[]} options.periods     the periods to produce, in order (e.g. ['2024','2025','2026'])
 * @param {number} [options.everyMs]     wait between cycles (0 in tests)
 * @param {string} [options.metric]      metric for the attribution (default: the dashboard's first metric)
 * @param {string} [options.by]          dimension for the attribution
 * @param {Function} [options.onRun]     called with each cycle's result, so a caller can print it live
 */
export async function runSchedule(options = {}) {
  const periods = (options.periods || []).map(String).filter(Boolean);
  if (!periods.length) throw new Error('a schedule needs at least one period (e.g. --periods 2024,2025,2026)');
  const sleep = options.sleep || ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
  // both calls are injectable so the schedule itself can be tested without a service
  const produceDashboard = options.dashboardFor || dashboardFor;
  const produceAttribution = options.attributeFor || attributeFor;
  const everyMs = Math.max(0, Number(options.everyMs) || 0);
  const common = {
    endpoint: options.endpoint, apiKey: options.apiKey, graph: options.graph,
    dataGraph: options.dataGraph, vertexType: options.vertexType, client: options.client,
    asOf: options.asOf, now: options.now, fetchImpl: options.fetchImpl,
  };
  const runs = [];
  for (const [index, period] of periods.entries()) {
    if (index > 0 && everyMs > 0) await sleep(everyMs);
    const startedAt = new Date(options.now ?? Date.now()).toISOString();
    const dashboard = await produceDashboard({
      ...common, year: period, topN: options.topN, dimensions: options.dimensions,
      metrics: options.metrics, write: options.write !== false, outDir: options.outDir,
    });
    const from = options.from?.[index] ?? previousOf(period);
    const by = options.by || (dashboard.dashboard.tiles.find((tile) => tile.kind === 'breakdown')?.dimension || null);
    const metric = options.metric || (dashboard.dashboard.tiles.find((tile) => tile.kind === 'total')?.metric || null);
    let attribution = null;
    if (from && metric && by) {
      const result = await produceAttribution({ ...common, metric, by, from, to: period });
      // an attribution against a period with no rows would report the whole total as the change
      const empty = result.result.executed && !(Number(result.result.attribution?.totalBefore) > 0);
      attribution = {
        executed: result.result.executed && !empty,
        reason: empty ? `period ${from} has no rows, so there is no change to attribute` : (result.result.reason || null),
        from,
        to: period,
        totalBefore: result.result.attribution?.totalBefore ?? null,
        totalAfter: result.result.attribution?.totalAfter ?? null,
        delta: result.result.attribution?.delta ?? null,
        topMover: result.result.attribution?.topMover ?? null,
        file: result.files?.json || null,
      };
    }
    const run = {
      index,
      period,
      from,
      startedAt,
      dashboard: {
        executed: dashboard.dashboard.executed,
        refused: dashboard.dashboard.refused,
        tiles: dashboard.dashboard.tiles.length,
        files: dashboard.files,
      },
      attribution,
    };
    runs.push(run);
    if (options.onRun) options.onRun(run);
  }
  return {
    ok: true,
    graph: options.graph,
    periods,
    everyMs,
    cycles: runs.length,
    executed: runs.reduce((sum, run) => sum + run.dashboard.executed, 0),
    refused: runs.reduce((sum, run) => sum + run.dashboard.refused, 0),
    runs,
  };
}

/** The run log as text, for the terminal and for the report. */
export function renderSchedule(schedule) {
  const lines = [];
  lines.push(`schedule: graph ${schedule.graph || '(session)'} · ${schedule.cycles} cycle(s) · every ${schedule.everyMs} ms`);
  lines.push('period | dashboard (executed/refused) | attribution | files');
  lines.push('---|---|---|---');
  for (const run of schedule.runs) {
    const attribution = run.attribution
      ? (run.attribution.executed ? `${run.attribution.from} -> ${run.attribution.to}: delta ${Math.round(run.attribution.delta)}` : `refused: ${run.attribution.reason}`)
      : 'not requested';
    lines.push(`${run.period} | ${run.dashboard.executed}/${run.dashboard.refused} tiles | ${attribution} | ${run.dashboard.files?.html || '(not written)'}`);
  }
  lines.push('');
  lines.push(`total: ${schedule.executed} executed, ${schedule.refused} refused (a refusal is an answer: it carries the SQL)`);
  return lines.join('\n');
}
