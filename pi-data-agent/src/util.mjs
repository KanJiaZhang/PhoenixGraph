import { createHash } from 'node:crypto';

/** Trim, collapse whitespace and cap a piece of evidence so a graph property stays a sentence. */
export function quote(text, limit = 320) {
  const collapsed = String(text ?? '')
    .replace(/\r\n?/g, '\n')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  if (collapsed.length <= limit) return collapsed;
  return `${collapsed.slice(0, limit - 1).trimEnd()}…`;
}

/** A one-line value (ids, names): everything that is not a letter or digit becomes `_`. */
export function slug(name) {
  const text = String(name ?? '').normalize('NFKC').trim();
  const replaced = text
    .replace(/['"`]/g, '')
    .replace(/[^\p{L}\p{N}]+/gu, '_')
    .replace(/^_+|_+$/g, '');
  return replaced || 'unnamed';
}

/**
 * A name that is safe as ONE path segment. A graph name or a ledger period comes from a parameter or a file, and
 * `../x` in it would write outside the state directory - but letters, digits, `.`, `-` and `_` are what graph names
 * already look like, so they stay: sanitising must not turn `pi-data-agent-verify` into a second schema file (two
 * files carrying one schema id are refused at start-up).
 */
export function pathSegment(name, fallback = 'unnamed') {
  const text = String(name ?? '').normalize('NFKC').trim();
  const cleaned = text
    .replace(/[^\p{L}\p{N}._-]+/gu, '_')
    .replace(/^[._-]+|[._-]+$/g, '');
  return cleaned || fallback;
}

/** A stable short id for a source document, independent of its absolute path on disk. */
export function documentId(name, text = '') {
  const base = slug(String(name ?? '').replace(/^.*[\\/]/, '').replace(/\.[^.]+$/, ''));
  const digest = createHash('sha1').update(String(text ?? '')).digest('hex').slice(0, 8);
  return `${base || 'document'}-${digest}`;
}

export function shortId(id) {
  if (id == null) return null;
  const text = String(id);
  const slash = text.indexOf('/');
  return slash < 0 ? text : text.slice(slash + 1);
}

export function tableOfColumn(columnId) {
  const name = String(columnId ?? '').replace(/^column\//, '');
  const dot = name.indexOf('.');
  return dot < 0 ? name : name.slice(0, dot);
}

export function columnOfId(columnId) {
  return String(columnId ?? '').replace(/^column\//, '');
}

export function unique(values) {
  const seen = new Set();
  const result = [];
  for (const value of values) {
    if (value == null) continue;
    const key = typeof value === 'string' ? value.trim() : JSON.stringify(value);
    if (!key || seen.has(key)) continue;
    seen.add(key);
    result.push(typeof value === 'string' ? value.trim() : value);
  }
  return result;
}

export function splitList(text) {
  return unique(String(text ?? '')
    .split(/[、,，;；/|]+/)
    .map((part) => part.replace(/^(?:和|与|及|以及)\s*/, '').trim())
    .filter(Boolean));
}

/** Turns `1,234.50 元` / `12%` / `3 天` into `{value, unit}` without inventing a unit. */
export function numberAndUnit(text) {
  const raw = String(text ?? '').trim();
  const match = /^([-+]?\d[\d,]*(?:\.\d+)?)\s*(.*)$/.exec(raw);
  if (!match) return { value: null, unit: raw || null };
  return { value: Number(match[1].replace(/,/g, '')), unit: match[2].trim() || null };
}

export function round(value, digits = 2) {
  if (!Number.isFinite(value)) return 0;
  const factor = 10 ** digits;
  return Math.round((value + Number.EPSILON) * factor) / factor;
}

export function escapeHtml(text) {
  return String(text ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** A Gremlin string literal: single quotes and backslashes escaped. */
export function gremlinString(value) {
  return `'${String(value ?? '').replace(/\\/g, '\\\\').replace(/'/g, "\\'")}'`;
}

export function gremlinNumber(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) throw new Error(`Not a number: ${value}`);
  return String(number);
}

export function isDateType(dataType) {
  return /date|time|timestamp/i.test(String(dataType ?? ''));
}

export function isNumericType(dataType) {
  return /int|decimal|numeric|number|float|double|real|money|long|short|byte/i.test(String(dataType ?? ''));
}

export function isCategoricalType(dataType) {
  return /char|text|string|varchar|enum|bool|uuid|json/i.test(String(dataType ?? ''));
}

/** Deterministic JSON: object keys sorted, so a model diff is readable. */
export function stableJson(value) {
  const walk = (item) => {
    if (Array.isArray(item)) return item.map(walk);
    if (item && typeof item === 'object') {
      return Object.fromEntries(Object.keys(item).sort().map((key) => [key, walk(item[key])]));
    }
    return item;
  };
  return JSON.stringify(walk(value), null, 2);
}

/** `2025-03-04T...` from a Date or a number, without a locale. */
export function iso(value) {
  if (value == null) return new Date().toISOString();
  if (value instanceof Date) return value.toISOString();
  if (typeof value === 'number') return new Date(value).toISOString();
  const parsed = Date.parse(String(value));
  return Number.isNaN(parsed) ? String(value) : new Date(parsed).toISOString();
}

/** The prose an agent should read: one line per fact, no JSON wall. */
export function bullet(lines, limit = 40) {
  return lines.slice(0, limit).join('\n');
}
