/**
 * Time on a definition.
 *
 * PhoenixGraph already reads a window off an element: `validFrom`/`validTo`, epoch millis, `from <= instant < to`,
 * absent meaning "since the beginning" / "for ever" (see the server's `Validity`). The ingestion writes it for
 * documents; a metadata layer needs it for the same reason, because a metric's formula is true for a period and
 * then it is *replaced*: "revenue = SUM(amount)" stopping on 2026-10-15 and "revenue = SUM(net_amount)" starting
 * that day are two definitions of one metric, and a question about September must be answered with the first.
 *
 * The trick a keyed store forces: a vertex id is unique, so a second definition cannot be the same vertex. A
 * superseded definition is therefore kept as a *version vertex* `metric/revenue#v<ISO instant>` with the old
 * values and the closed window, linked to the live vertex by a `supersededBy` edge. Readers collapse the group
 * back to one node per base id ({@link resolveAsOf}), so everything above this module - the dashboard, the
 * attribution, the review - keeps addressing `metric/revenue` and gets the version that was true then.
 */

/** What separates a version vertex's id from the id of the definition it versioned. */
export const VERSION_SEPARATOR = '#v';

/**
 * An instant as epoch millis. Accepts a Date, a number (millis or seconds) and an ISO-8601 or date-only string;
 * `null`/`''`/`undefined` mean "no bound".
 */
export function instantMs(value) {
  if (value == null || value === '') return null;
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value.getTime();
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) return null;
    return value < 1e11 ? Math.round(value * 1000) : Math.round(value);
  }
  const text = String(value).trim();
  if (!text) return null;
  if (/^\d+$/.test(text)) return instantMs(Number(text));
  const parsed = Date.parse(text);
  return Number.isNaN(parsed) ? null : parsed;
}

/** The window an element's properties declare (`null` ends are open). */
export function windowOf(properties = {}) {
  return { from: instantMs(properties.validFrom), to: instantMs(properties.validTo) };
}

/** Whether the element holds at `instant` (epoch millis); no instant means "does not filter". */
export function isValidAt(properties, instant) {
  if (instant == null) return true;
  const { from, to } = windowOf(properties);
  return (from == null || instant >= from) && (to == null || instant < to);
}

/** Whether the element carries a window at all. */
export function isVersioned(properties = {}) {
  return properties.validFrom != null || properties.validTo != null;
}

/** The version id for a definition that starts at `from` (deterministic, so re-running is idempotent). */
export function versionId(id, from) {
  const instant = instantMs(from) ?? 0;
  return `${baseId(id)}${VERSION_SEPARATOR}${new Date(instant).toISOString()}`;
}

/** The id a version vertex versions. */
export function baseId(id) {
  const text = String(id ?? '');
  const index = text.indexOf(VERSION_SEPARATOR);
  return index < 0 ? text : text.slice(0, index);
}

/** Whether this id names a superseded definition rather than the live one. */
export function isVersion(id) {
  return String(id ?? '').includes(VERSION_SEPARATOR);
}

/**
 * The model as it was at an instant: one node per base id, every element whose window excludes the instant gone.
 *
 * A group of versions is collapsed by preference: a node valid at the instant wins; if several are (an
 * unbounded live node and a closed version overlap while the live one has no `validFrom`), the live node wins,
 * then the one that started last. Edges are filtered by their own windows and re-pointed at the base ids, so a
 * relation written for a version is readable as a relation of the definition it belonged to.
 */
export function resolveAsOf(model, instant) {
  const asOf = instantMs(instant);
  if (asOf == null) return model;

  const groups = new Map();
  for (const node of model.nodes || []) {
    const base = baseId(node.id);
    if (!groups.has(base)) groups.set(base, []);
    groups.get(base).push(node);
  }
  const chosen = new Map();
  const alias = new Map();
  for (const [base, versions] of groups) {
    const valid = versions.filter((node) => isValidAt(node.properties, asOf));
    if (!valid.length) continue;
    const best = valid.sort((left, right) => {
      const liveLeft = baseId(left.id) === left.id ? 1 : 0;
      const liveRight = baseId(right.id) === right.id ? 1 : 0;
      if (liveLeft !== liveRight) return liveRight - liveLeft;
      return (windowOf(right.properties).from ?? 0) - (windowOf(left.properties).from ?? 0);
    })[0];
    chosen.set(base, best.id === base ? best : { ...best, id: base, properties: { ...best.properties, definitionId: best.id } });
    if (best.id !== base) alias.set(best.id, base);
  }

  const nodes = [...chosen.values()];
  const present = new Set(nodes.map((node) => node.id));
  const edges = [];
  const seen = new Set();
  for (const edge of model.edges || []) {
    if (!isValidAt(edge.properties, asOf)) continue;
    const head = alias.get(edge.head) || edge.head;
    const tail = alias.get(edge.tail) || edge.tail;
    if (!present.has(head) || !present.has(tail)) continue;
    const id = head === edge.head && tail === edge.tail ? edge.id : `${head}|${edge.relation}|${tail}`;
    if (seen.has(id)) continue;
    seen.add(id);
    edges.push(id === edge.id ? edge : { ...edge, id, head, tail });
  }

  return { ...model, asOf, nodes, edges, resolvedFrom: (model.nodes || []).length };
}

/** A one-line description of a window, for reports. */
export function windowText(properties = {}) {
  const { from, to } = windowOf(properties);
  if (from == null && to == null) return 'always';
  const iso = (ms) => new Date(ms).toISOString().replace(/\.\d+Z$/, 'Z');
  return `${from == null ? 'beginning' : iso(from)} -> ${to == null ? 'now' : iso(to)}`;
}
