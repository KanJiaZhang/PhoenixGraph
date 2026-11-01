/**
 * Database metadata without a database driver.
 *
 * The data agent is given what a DBA would hand over: DDL, a `SHOW CREATE TABLE` dump, a `SHOW TABLES`
 * result, a `DESCRIBE`, or an `information_schema` export. This module turns those into the same shape
 * (`tables`, `columns`, `foreignKeys`, `queries`) and keeps the exact statement/row as evidence. It never
 * connects anywhere: reading a database is the operator's job, and a parser that cannot lie about what it
 * saw is the point.
 */
import { quote, slug, isDateType, isNumericType } from '../util.mjs';

const IDENT = '(?:`[^`]+`|"[^"]+"|\\[[^\\]]+\\]|[A-Za-z_][\\w$]*)';
const QUALIFIED = `(${IDENT}(?:\\s*\\.\\s*${IDENT})?)`;

export function unquoteIdentifier(text) {
  return String(text ?? '').trim()
    .replace(/^[`"[]/, '')
    .replace(/[`"\]]$/, '')
    .split('.').map((part) => part.trim().replace(/^[`"[]/, '').replace(/[`"\]]$/, '')).join('.');
}

function plainName(text) {
  const unquoted = unquoteIdentifier(text);
  return unquoted.includes('.') ? unquoted.slice(unquoted.lastIndexOf('.') + 1) : unquoted;
}

/** Splits a SQL script on semicolons that are outside strings, identifiers and comments. */
export function splitStatements(sql) {
  const text = String(sql ?? '');
  const statements = [];
  let start = 0;
  let quoteChar = null;
  let lineComment = false;
  let blockComment = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    const next = text[index + 1];
    if (lineComment) {
      if (char === '\n') lineComment = false;
      continue;
    }
    if (blockComment) {
      if (char === '*' && next === '/') { blockComment = false; index += 1; }
      continue;
    }
    if (quoteChar) {
      if (char === '\\' && quoteChar !== '`') { index += 1; continue; }
      if (char === quoteChar) {
        if (next === quoteChar) { index += 1; continue; }
        quoteChar = null;
      }
      continue;
    }
    if (char === '-' && next === '-') { lineComment = true; index += 1; continue; }
    if (char === '#') { lineComment = true; continue; }
    if (char === '/' && next === '*') { blockComment = true; index += 1; continue; }
    if (char === "'" || char === '"' || char === '`') { quoteChar = char; continue; }
    if (char === ';') {
      const statement = text.slice(start, index + 1).trim();
      if (statement) statements.push(statement);
      start = index + 1;
    }
  }
  const tail = text.slice(start).trim();
  if (tail) statements.push(tail);
  return statements;
}

/** Comma split at depth 0, respecting quotes. Used for a column list and a select list. */
export function splitTopLevel(text, separator = ',') {
  const parts = [];
  let depth = 0;
  let quoteChar = null;
  let start = 0;
  const source = String(text ?? '');
  for (let index = 0; index < source.length; index += 1) {
    const char = source[index];
    if (quoteChar) {
      if (char === quoteChar && source[index + 1] === quoteChar) { index += 1; continue; }
      if (char === quoteChar) quoteChar = null;
      continue;
    }
    if (char === "'" || char === '"' || char === '`') { quoteChar = char; continue; }
    if (char === '(' || char === '[') { depth += 1; continue; }
    if (char === ')' || char === ']') { depth = Math.max(0, depth - 1); continue; }
    if (char === separator && depth === 0) {
      parts.push(source.slice(start, index).trim());
      start = index + 1;
    }
  }
  const tail = source.slice(start).trim();
  if (tail) parts.push(tail);
  return parts.filter(Boolean);
}

function stripSqlComments(text) {
  return String(text ?? '')
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/(^|\s)--[^\n]*/g, '$1')
    .replace(/(^|\s)#[^\n]*/g, '$1');
}

function precedingComments(statement) {
  const lines = String(statement ?? '').split('\n');
  const comments = [];
  for (const line of lines) {
    const match = /^\s*(?:--|#)\s*(.+)$/.exec(line);
    if (match) comments.push(match[1].trim());
    else if (line.trim()) break;
  }
  return comments;
}

function commentDescription(comments, names) {
  for (const comment of comments) {
    for (const name of names) {
      const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const patterns = [
        new RegExp(`(?:表|table)\\s*[:：]?\\s*${escaped}\\s*[:：,，\\s]+(.+)`, 'i'),
        new RegExp(`^${escaped}\\s*[:：]\\s*(.+)`, 'i'),
      ];
      for (const pattern of patterns) {
        const match = pattern.exec(comment);
        if (match) return match[1].trim();
      }
    }
  }
  return null;
}

function parseType(text) {
  const source = String(text ?? '').trim();
  if (!source) return '';
  const match = new RegExp(`^((?:${IDENT}|DOUBLE\\s+PRECISION|CHARACTER\\s+VARYING|TIMESTAMP(?:\\s+WITH(?:OUT)?\\s+TIME\\s+ZONE)?|TIME(?:\\s+WITH(?:OUT)?\\s+TIME\\s+ZONE)?)\\s*(?:\\([^)]*\\))?(?:\\s+UNSIGNED)?(?:\\s+ZEROFILL)?)`, 'i').exec(source);
  return match ? match[1].trim() : source.split(/\s+/)[0];
}

function columnRoleOf(name, type, isPk, isFk) {
  if (isPk) return 'PK';
  if (isFk) return 'FK';
  if (isDateType(type)) return 'attribute';
  if (isNumericType(type)) return 'measure';
  return 'attribute';
}

/**
 * Parses one `CREATE TABLE` statement (including a `SHOW CREATE TABLE` wrapper).
 * Returns `{ table, columns, foreignKeys, warnings }` or null when the statement is not a CREATE TABLE.
 */
export function parseCreateTable(statement) {
  const text = String(statement ?? '');
  const match = new RegExp(`CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:TEMP(?:ORARY)?\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?${QUALIFIED}\\s*\\(`, 'i').exec(text);
  if (!match) return null;
  const tableName = plainName(match[1]);
  const open = match.index + match[0].length - 1;
  let depth = 0;
  let quoteChar = null;
  let close = -1;
  for (let index = open; index < text.length; index += 1) {
    const char = text[index];
    if (quoteChar) {
      if (char === quoteChar && text[index + 1] === quoteChar) { index += 1; continue; }
      if (char === quoteChar) quoteChar = null;
      continue;
    }
    if (char === "'" || char === '"' || char === '`') { quoteChar = char; continue; }
    if (char === '(') depth += 1;
    if (char === ')') {
      depth -= 1;
      if (depth === 0) { close = index; break; }
    }
  }
  if (close < 0) return { table: { name: tableName }, columns: [], foreignKeys: [], warnings: ['unbalanced parentheses in CREATE TABLE'] };

  const body = text.slice(open + 1, close);
  const tail = text.slice(close + 1);
  const comments = precedingComments(text.slice(0, match.index));
  const tableComment = /COMMENT\s*=\s*'((?:[^']|'')*)'/i.exec(tail)?.[1]
    ?? /COMMENT\s+'((?:[^']|'')*)'/i.exec(tail)?.[1]
    ?? commentDescription(comments, [tableName]);
  const columns = [];
  const foreignKeys = [];
  const primary = new Set();
  const inlineForeign = new Map();
  const warnings = [];

  for (const part of splitTopLevel(body)) {
    const clean = stripSqlComments(part).trim();
    if (!clean) continue;

    const pk = new RegExp(`^(?:CONSTRAINT\\s+${IDENT}\\s+)?PRIMARY\\s+KEY\\s*\\(([^)]*)\\)`, 'i').exec(clean);
    if (pk) {
      for (const name of splitTopLevel(pk[1])) primary.add(plainName(name));
      continue;
    }
    const fk = new RegExp(`^(?:CONSTRAINT\\s+${IDENT}\\s+)?FOREIGN\\s+KEY\\s*\\(([^)]*)\\)\\s*REFERENCES\\s+${QUALIFIED}\\s*\\(([^)]*)\\)`, 'i').exec(clean);
    if (fk) {
      const from = splitTopLevel(fk[1]).map(plainName);
      const to = splitTopLevel(fk[3]).map(plainName);
      from.forEach((column, index) => {
        foreignKeys.push({ table: tableName, column, referencesTable: plainName(fk[2]), referencesColumn: to[index] ?? to[0] ?? null, evidence: quote(clean) });
      });
      continue;
    }
    if (/^(?:UNIQUE\s+)?(?:KEY|INDEX|CHECK|CONSTRAINT)\\b/i.test(clean)) continue;

    const nameMatch = new RegExp(`^${IDENT}`).exec(clean);
    if (!nameMatch) { warnings.push(`cannot read a column from: ${clean.slice(0, 80)}`); continue; }
    const columnName = plainName(nameMatch[0]);
    const rest = clean.slice(nameMatch[0].length).trim();
    const dataType = parseType(rest);
    const comment = /COMMENT\s+'((?:[^']|'')*)'/i.exec(rest)?.[1] ?? null;
    const inlinePk = /\bPRIMARY\s+KEY\b/i.test(rest);
    const inlineRef = new RegExp(`\\bREFERENCES\\s+${QUALIFIED}\\s*(?:\\(([^)]*)\\))?`, 'i').exec(rest);
    if (inlinePk) primary.add(columnName);
    if (inlineRef) {
      inlineForeign.set(columnName, { referencesTable: plainName(inlineRef[1]), referencesColumn: inlineRef[3] ? plainName(inlineRef[3].split(',')[0]) : null });
      foreignKeys.push({ table: tableName, column: columnName, referencesTable: plainName(inlineRef[1]), referencesColumn: inlineRef[3] ? plainName(inlineRef[3].split(',')[0]) : null, evidence: quote(clean) });
    }
    columns.push({
      table: tableName,
      name: columnName,
      dataType,
      description: comment,
      nullable: !/\bNOT\s+NULL\b/i.test(rest),
      default: /DEFAULT\s+('(?:[^']|'')*'|[\w.()+-]+)/i.exec(rest)?.[1] ?? null,
      evidence: quote(clean),
    });
  }

  for (const column of columns) {
    const isPk = primary.has(column.name);
    const isFk = inlineForeign.has(column.name) || foreignKeys.some((key) => key.column === column.name);
    column.columnRole = columnRoleOf(column.name, column.dataType, isPk, isFk);
  }
  return {
    table: { name: tableName, description: tableComment ?? null, evidence: quote(comments.join(' ') || `CREATE TABLE ${tableName}`) },
    columns,
    foreignKeys,
    warnings,
  };
}

/** DDL (and `SHOW CREATE TABLE` dumps) to tables, columns and foreign keys. */
export function parseDdl(sql) {
  const tables = [];
  const columns = [];
  const foreignKeys = [];
  const warnings = [];
  for (const statement of splitStatements(sql)) {
    const parsed = parseCreateTable(statement);
    if (!parsed) continue;
    tables.push(parsed.table);
    columns.push(...parsed.columns);
    foreignKeys.push(...parsed.foreignKeys);
    warnings.push(...parsed.warnings);
  }
  return { tables, columns, foreignKeys, warnings };
}

/** One ASCII/boxed result set as rows of cells. */
export function boxedRows(text) {
  const rows = [];
  for (const line of String(text ?? '').split('\n')) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('|') || !trimmed.endsWith('|')) continue;
    const inner = trimmed.slice(1, -1);
    if (/^[\s:+-]+$/.test(inner)) continue;
    rows.push(inner.split('|').map((cell) => cell.trim()));
  }
  return rows;
}

function normaliseHeader(header) {
  return String(header ?? '').toLowerCase().replace(/[\s_-]+/g, '');
}

/** `SHOW TABLES` in boxed, tab-separated or single-column form. */
export function parseShowTables(text) {
  const rows = boxedRows(text);
  const names = [];
  if (rows.length) {
    const first = rows[0].map(normaliseHeader);
    const start = first.some((cell) => cell.startsWith('tablesin') || cell === 'table' || cell === 'tables' || cell === 'name') ? 1 : 0;
    for (const row of rows.slice(start)) if (row[0]) names.push(unquoteIdentifier(row[0]));
  } else {
    for (const line of String(text ?? '').split('\n')) {
      const trimmed = line.trim();
      if (!trimmed || /^tables?_?in/i.test(trimmed) || /^table$/i.test(trimmed)) continue;
      if (trimmed.includes('\t')) {
        const cell = trimmed.split('\t')[0].trim();
        if (cell && !/^tables?_?in/i.test(cell)) names.push(unquoteIdentifier(cell));
      } else if (/^[A-Za-z_][\w$.]*$/.test(trimmed)) {
        names.push(unquoteIdentifier(trimmed));
      }
    }
  }
  return [...new Set(names)];
}

/** `DESCRIBE table` / `SHOW FULL COLUMNS FROM table`, in boxed or tab-separated form. */
export function parseDescribe(text, defaultTable = null) {
  const source = String(text ?? '');
  const table = defaultTable
    ?? /(?:DESCRIBE|DESC|SHOW\s+(?:FULL\s+)?COLUMNS\s+FROM)\s+([`"\w.]+)/i.exec(source)?.[1]
    ?? null;
  const rows = boxedRows(source);
  const columns = [];
  const foreignKeys = [];
  if (rows.length) {
    const header = rows[0].map(normaliseHeader);
    const indexOf = (...names) => header.findIndex((cell) => names.includes(cell));
    const fieldAt = indexOf('field', 'column', 'columnname', '字段', '列名');
    const typeAt = indexOf('type', 'columntype', 'datatype', '类型');
    const keyAt = indexOf('key', 'columnkey', '键');
    const commentAt = indexOf('comment', 'columncomment', '说明', '备注');
    const nullAt = indexOf('null', 'isnullable', '可空');
    for (const row of rows.slice(1)) {
      const name = fieldAt >= 0 ? row[fieldAt] : row[0];
      if (!name) continue;
      const dataType = typeAt >= 0 ? row[typeAt] : '';
      const key = keyAt >= 0 ? row[keyAt] : '';
      columns.push({
        table: table ? plainName(table) : null,
        name: unquoteIdentifier(name),
        dataType,
        description: commentAt >= 0 ? (row[commentAt] || null) : null,
        nullable: nullAt < 0 ? true : !/^no$/i.test(row[nullAt] || ''),
        columnRole: /pri/i.test(key) ? 'PK' : /mul|for/i.test(key) ? 'FK' : columnRoleOf(name, dataType, false, false),
        evidence: quote(row.join(' | ')),
      });
    }
  } else {
    for (const line of source.split('\n')) {
      if (!line.trim() || /^(?:field|column|字段)/i.test(line.trim())) continue;
      const cells = line.split('\t').map((cell) => cell.trim());
      if (cells.length < 2) continue;
      columns.push({
        table: table ? plainName(table) : null,
        name: unquoteIdentifier(cells[0]),
        dataType: cells[1],
        description: cells[5] || null,
        nullable: !/^no$/i.test(cells[2] || ''),
        columnRole: /pri/i.test(cells[3] || '') ? 'PK' : /mul|for/i.test(cells[3] || '') ? 'FK' : columnRoleOf(cells[0], cells[1], false, false),
        evidence: quote(line),
      });
    }
  }
  return { table: table ? plainName(table) : null, columns, foreignKeys };
}

function pick(row, ...names) {
  for (const name of names) {
    const wanted = normaliseHeader(name);
    for (const [key, value] of Object.entries(row)) {
      if (normaliseHeader(key) === wanted && value != null && value !== '') return value;
    }
  }
  return null;
}

/** An `information_schema` export: JSON array, or a header row of CSV/TSV values. */
export function parseInformationSchema(text) {
  const source = String(text ?? '').trim();
  let records = null;
  if (source.startsWith('[') || source.startsWith('{')) {
    const parsed = JSON.parse(source);
    if (Array.isArray(parsed)) records = parsed;
    else if (Array.isArray(parsed.rows)) records = parsed.rows;
    else records = [parsed];
  } else {
    const lines = source.split('\n').filter((line) => line.trim());
    if (lines.length < 2) return null;
    const delimiter = lines[0].includes('\t') ? '\t' : ',';
    const header = lines[0].split(delimiter).map((cell) => cell.trim().replace(/^"|"$/g, ''));
    if (!header.some((cell) => /table/i.test(cell)) || !header.some((cell) => /column/i.test(cell))) return null;
    records = lines.slice(1).map((line) => {
      const cells = line.split(delimiter).map((cell) => cell.trim().replace(/^"|"$/g, ''));
      return Object.fromEntries(header.map((key, index) => [key, cells[index] ?? null]));
    });
  }
  const tables = new Map();
  const columns = [];
  const foreignKeys = [];
  for (const record of records) {
    const tableName = pick(record, 'TABLE_NAME', 'table', 'table_name', '表名');
    const columnName = pick(record, 'COLUMN_NAME', 'column', 'column_name', '字段', '列名');
    if (!tableName) continue;
    const comment = pick(record, 'TABLE_COMMENT', 'table_comment');
    if (!tables.has(tableName)) {
      tables.set(tableName, { name: tableName, description: comment ?? null, evidence: quote(JSON.stringify(record)) });
    }
    if (!columnName) continue;
    const dataType = pick(record, 'COLUMN_TYPE', 'DATA_TYPE', 'data_type', 'type', '类型') ?? '';
    const key = pick(record, 'COLUMN_KEY', 'column_key', 'key') ?? '';
    columns.push({
      table: tableName,
      name: columnName,
      dataType,
      description: pick(record, 'COLUMN_COMMENT', 'column_comment', 'comment', '说明') ?? null,
      nullable: String(pick(record, 'IS_NULLABLE', 'is_nullable') ?? 'YES').toUpperCase() !== 'NO',
      default: pick(record, 'COLUMN_DEFAULT', 'column_default'),
      columnRole: /pri/i.test(key) ? 'PK' : /mul|for/i.test(key) ? 'FK' : columnRoleOf(columnName, dataType, false, false),
      evidence: quote(JSON.stringify(record)),
    });
  }
  return { tables: [...tables.values()], columns, foreignKeys, warnings: [] };
}

/**
 * Auto-detects what a DB dump is. The return shape is always the same, so the caller does not branch on
 * which `SHOW` command the operator ran.
 */
export function parseDbShow(text, options = {}) {
  const source = String(text ?? '');
  if (!source.trim()) return { tables: [], columns: [], foreignKeys: [], queries: [], warnings: ['empty database metadata'] };
  const warnings = [];
  try {
    const information = parseInformationSchema(source);
    if (information) return { ...information, queries: [], warnings };
  } catch (error) {
    warnings.push(`information_schema JSON could not be read: ${error.message}`);
  }
  const ddl = /CREATE\s+(?:OR\s+REPLACE\s+)?(?:TEMP(?:ORARY)?\s+)?TABLE\b/i.test(source);
  if (ddl) {
    const parsed = parseDdl(source);
    return { ...parsed, queries: parseSqlQueries(source).queries, warnings: [...warnings, ...parsed.warnings] };
  }
  if (/(?:DESCRIBE|DESC\s|SHOW\s+(?:FULL\s+)?COLUMNS)/i.test(source)) {
    const parsed = parseDescribe(source, options.table ?? null);
    return { tables: parsed.table ? [{ name: parsed.table, description: null, evidence: quote(source) }] : [], columns: parsed.columns, foreignKeys: parsed.foreignKeys, queries: [], warnings };
  }
  const tables = parseShowTables(source);
  return {
    tables: tables.map((name) => ({ name, description: null, evidence: quote(name) })),
    columns: [],
    foreignKeys: [],
    queries: [],
    warnings,
  };
}

function selectAndFrom(statement) {
  const clean = stripSqlComments(statement);
  const select = /\bSELECT\b/i.exec(clean);
  if (!select) return null;
  const rest = clean.slice(select.index + select[0].length);
  let depth = 0;
  let quoteChar = null;
  let fromAt = -1;
  for (let index = 0; index < rest.length; index += 1) {
    const char = rest[index];
    if (quoteChar) {
      if (char === quoteChar) quoteChar = null;
      continue;
    }
    if (char === "'" || char === '"' || char === '`') { quoteChar = char; continue; }
    if (char === '(') depth += 1;
    else if (char === ')') depth = Math.max(0, depth - 1);
    else if (depth === 0 && /^FROM\b/i.test(rest.slice(index, index + 4))) {
      fromAt = index;
      break;
    }
  }
  if (fromAt < 0) return null;
  const selected = rest.slice(0, fromAt).trim();
  const tail = rest.slice(fromAt);
  const groupBy = /\bGROUP\s+BY\s+([\s\S]*?)(?:\bORDER\s+BY\b|\bHAVING\b|\bLIMIT\b|$)/i.exec(tail)?.[1] ?? null;
  return { selected, tail, groupBy };
}

function tablesIn(text) {
  const names = [];
  const pattern = new RegExp(`\\b(?:FROM|JOIN)\\s+${QUALIFIED}`, 'gi');
  for (const match of String(text ?? '').matchAll(pattern)) names.push(plainName(match[1]));
  return [...new Set(names)];
}

function identifiersIn(expression) {
  const ignored = new Set(['sum', 'count', 'avg', 'min', 'max', 'distinct', 'case', 'when', 'then', 'else', 'end', 'and', 'or', 'not', 'null', 'as', 'cast', 'integer', 'decimal', 'date', 'coalesce']);
  const names = [];
  for (const match of String(expression ?? '').matchAll(new RegExp(`\\b${IDENT}\\b`, 'g'))) {
    const name = plainName(match[0]);
    if (!ignored.has(name.toLowerCase()) && !/^\d+$/.test(name)) names.push(name);
  }
  return [...new Set(names)];
}

/**
 * Reference SQL to metrics and dimensions: an aggregate with an alias is a metric definition, a GROUP BY
 * column is a dimension. A select item with no alias is not named: it is reported as a warning, not
 * given a made-up metric name.
 */
export function parseSqlQueries(sql) {
  const queries = [];
  const metrics = [];
  const dimensions = [];
  const warnings = [];
  let number = 0;
  for (const statement of splitStatements(sql)) {
    if (!/\bSELECT\b/i.test(statement)) continue;
    const picked = selectAndFrom(statement);
    if (!picked) continue;
    number += 1;
    const comments = precedingComments(statement);
    const tables = tablesIn(picked.tail);
    const nameComment = comments.map((comment) => /(?:^|\s)(?:name|title|查询|名称)\s*[:：]\s*(.+)$/i.exec(comment)?.[1]).find(Boolean);
    const queryId = `query/${slug(nameComment || `q${number}`)}`;
    const queryMetrics = [];
    for (const item of splitTopLevel(picked.selected)) {
      const aggregate = /\b(SUM|COUNT|AVG|MIN|MAX)\s*\(/i.exec(item);
      if (!aggregate) continue;
      const alias = /\bAS\s+([`"\w]+)\s*$/i.exec(item)?.[1]
        ?? new RegExp(`^\\s*${IDENT}\\s*$`).exec(item)?.[0];
      if (!alias) {
        warnings.push(`an aggregate has no alias, so it is not named: ${quote(item, 120)}`);
        continue;
      }
      const metricId = `metric/${slug(plainName(alias))}`;
      const columns = identifiersIn(item.replace(/\bAS\s+[`"\w]+\s*$/i, ''));
      queryMetrics.push(metricId);
      metrics.push({
        id: metricId,
        name: plainName(alias),
        expression: item.replace(/\bAS\s+[`"\w]+\s*$/i, '').trim(),
        aggFunction: aggregate[1].toUpperCase(),
        tables,
        columns,
        queryId,
        evidence: quote(comments.join(' ') || item),
      });
    }
    const queryDimensions = [];
    if (picked.groupBy) {
      for (const item of splitTopLevel(picked.groupBy)) {
        const column = plainName(item.replace(/\b(?:ASC|DESC)\b/gi, '').replace(/^[;,\s]+|[;,\s]+$/g, '').trim());
        if (!column) continue;
        const dimensionId = `dim/${slug(column)}`;
        queryDimensions.push(dimensionId);
        dimensions.push({ id: dimensionId, name: column, tables, column, queryId, evidence: quote(`GROUP BY ${item}`) });
      }
    }
    queries.push({
      id: queryId,
      name: nameComment || `Q${number}`,
      description: comments.join(' ') || null,
      sqlText: quote(statement, 4000),
      tables,
      metrics: queryMetrics,
      dimensions: queryDimensions,
      evidence: quote(statement, 240),
    });
  }
  return { queries, metrics, dimensions, warnings };
}
