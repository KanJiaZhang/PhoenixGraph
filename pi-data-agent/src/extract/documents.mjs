/**
 * Deterministic extraction of a data model from documents.
 *
 * The extractor reads four shapes and nothing else: labelled prose (`指标：GMV = SUM(amount)`), Markdown/wiki
 * tables whose header says metric/dimension/term, SQL aggregates with an alias, and DDL columns. Every
 * observation carries the exact sentence or row it was read from, plus a confidence that says *how* it was
 * read. A definition with no expression is still recorded - as a term needing review - because inventing a
 * formula is the one mistake this layer must not make.
 */
import { quote, slug, splitList, isDateType, isNumericType } from '../util.mjs';
import { parseDbShow, parseSqlQueries, parseShowTables } from '../readers/sql.mjs';

const HEADING = /^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$/;

const HEADER_ALIASES = [
  ['metric', ['指标', '度量', '指标名称', '度量名称', 'metric', 'measure', 'kpi']],
  ['dimension', ['维度', '维度名称', 'dimension', '维']],
  ['term', ['术语', '业务术语', 'term', 'glossary']],
  ['expression', ['表达式', '公式', '定义', '口径', '计算口径', '计算逻辑', '计算方式', '计算方法', 'expression', 'formula', 'definition', 'calculation', 'sql']],
  ['unit', ['单位', 'unit']],
  ['alias', ['别名', '同义词', '又称', '别称', 'synonym', 'synonyms', 'alias', 'aliases']],
  ['graphProperty', ['图属性', '绑定', '事实属性', '图属性绑定', 'graphproperty', 'graph_property', 'factproperty', 'fact_property', 'binding']],
  ['table', ['表', '表名', '所属表', '来源表', 'table', 'source table']],
  ['column', ['字段', '列', '列名', '字段名', 'column', 'field']],
  ['dataType', ['类型', '数据类型', '字段类型', 'datatype', 'data_type', 'type']],
  ['role', ['角色', '字段角色', 'columnrole', 'column_role', 'role']],
  ['owner', ['负责人', 'owner', '数据负责人']],
  ['domain', ['业务域', '主题域', 'domain', '主题']],
  ['description', ['说明', '描述', '备注', '业务含义', '含义', 'description', 'note', 'comment', 'meaning']],
  ['name', ['名称', 'name']],
];

function headerKey(cell) {
  return String(cell ?? '').toLowerCase().replace(/[\s_*\-()（）:：]/g, '');
}

function columnKind(headerCell) {
  const key = headerKey(headerCell);
  for (const [kind, aliases] of HEADER_ALIASES) {
    if (aliases.some((alias) => headerKey(alias) === key)) return kind;
  }
  // a longer header such as "指标定义" still belongs to the metric column
  for (const [kind, aliases] of HEADER_ALIASES) {
    if (aliases.some((alias) => key.startsWith(headerKey(alias)) || footerMatches(key, headerKey(alias)))) return kind;
  }
  return null;
}

function footerMatches(key, alias) {
  if (!alias) return false;
  return key.endsWith(alias) && key.length <= alias.length + 6;
}

function mapHeader(cells) {
  return cells.map((cell) => columnKind(cell));
}

/** Markdown/wiki pipe tables, with the section they live in and their exact raw lines. */
export function markdownTables(lines, sectionOf = () => '') {
  const tables = [];
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (!/^\s*\|.*\|\s*$/.test(line)) continue;
    const header = line.trim().slice(1, -1).split('|').map((cell) => cell.trim());
    if (index + 1 >= lines.length) continue;
    const divider = lines[index + 1];
    if (!/^\s*\|[\s:|-]+\|\s*$/.test(divider)) continue;
    const rows = [];
    const raw = [line, divider];
    let at = index + 2;
    for (; at < lines.length; at += 1) {
      if (!/^\s*\|.*\|\s*$/.test(lines[at])) break;
      const cells = lines[at].trim().slice(1, -1).split('|').map((cell) => cell.trim());
      rows.push(cells);
      raw.push(lines[at]);
    }
    tables.push({ header, rows, raw, start: index + 1, section: sectionOf(index + 1) });
    index = at - 1;
  }
  return tables;
}

function splitSections(text) {
  const lines = String(text ?? '').split('\n');
  const headings = new Array(lines.length).fill('');
  let current = '';
  for (let index = 0; index < lines.length; index += 1) {
    const match = HEADING.exec(lines[index]);
    if (match) current = match[2].trim();
    headings[index] = current;
  }
  return { lines, headings };
}

function normaliseExpression(text) {
  const raw = String(text ?? '').trim();
  if (!raw) return null;
  const aggregate = /(?:SUM|COUNT|AVG|MIN|MAX|RATIO|MEDIAN|PERCENTILE(?:_CONT)?)\s*\(/i.exec(raw);
  if (!aggregate) return null;
  const start = aggregate.index;
  let depth = 0;
  let quoteChar = null;
  let left = start;
  while (left > 0 && /[0-9*+/().\s]/.test(raw[left - 1])) left -= 1;
  if (/\/\s*(?:SUM|COUNT|AVG|MIN|MAX)/i.test(raw.slice(left))) {
    // a ratio keeps the whole arithmetic: `100 * SUM(a) / SUM(b)` is one definition, not two
    return raw.slice(left).replace(/[。；;].*$/, '').trim().replace(/\s+/g, ' ');
  }
  for (let index = start; index < raw.length; index += 1) {
    const char = raw[index];
    if (quoteChar) {
      if (char === quoteChar) quoteChar = null;
      continue;
    }
    if (char === "'" || char === '"' || char === '`') { quoteChar = char; continue; }
    if (char === '(') depth += 1;
    else if (char === ')') {
      depth -= 1;
      if (depth === 0) {
        // a ratio such as `100 * SUM(a) / COUNT(*)` keeps its arithmetic around the aggregate
        let left2 = start;
        while (left2 > 0 && /[0-9*+/().\s]/.test(raw[left2 - 1])) left2 -= 1;
        let right = index + 1;
        while (right < raw.length && /[0-9*+/().\s]/.test(raw[right])) right += 1;
        return raw.slice(left2, right).trim().replace(/\s+/g, ' ');
      }
    }
  }
  return raw.slice(start).trim();
}

function aggOf(expression) {
  const match = /^\s*(?:100\s*\*\s*)?(SUM|COUNT|AVG|MIN|MAX|RATIO|MEDIAN)/i.exec(String(expression ?? ''));
  if (!match) return null;
  const name = match[1].toUpperCase();
  if (/\/\s*(?:SUM|COUNT|AVG)/i.test(expression)) return 'RATIO';
  return name;
}

function identifiers(expression) {
  const ignored = new Set(['sum', 'count', 'avg', 'min', 'max', 'ratio', 'median', 'distinct', 'case', 'when', 'then', 'else', 'end', 'and', 'or', 'not', 'null', 'as', 'cast', 'integer', 'decimal', 'date', 'coalesce']);
  const found = [];
  for (const match of String(expression ?? '').matchAll(/\b[A-Za-z_][\w$]*\b/g)) {
    const name = match[0];
    if (!ignored.has(name.toLowerCase())) found.push(name);
  }
  return [...new Set(found)];
}

function bindingOf(value) {
  const text = String(value ?? '').trim();
  if (!text || /^(?:-|—|n\/a|none|null|无)$/i.test(text)) return null;
  if (/^[A-Za-z_][\w$]*(?:\s*=\s*[A-Za-z_][\w$]*)?(?:\s*,\s*[A-Za-z_][\w$]*\s*=\s*[A-Za-z_][\w$]*)*$/.test(text)) return text.replace(/\s+/g, '');
  // a prose cell such as `绑定到 revenue` still names the property
  const match = /(?:绑定|bind|property|属性)\s*(?:到|为|to|:)?\s*([A-Za-z_][\w$]*)/i.exec(text);
  return match ? match[1] : null;
}

function reasonOf(confidence) {
  if (confidence >= 0.95) return 'explicit definition';
  if (confidence >= 0.8) return 'structured table or DDL';
  if (confidence >= 0.65) return 'SQL alias';
  return 'prose heuristic';
}

function observationBase(document, evidence, confidence, line, extra = {}) {
  return {
    sourceId: document.id,
    sourceTitle: document.title || document.name,
    evidence: quote(evidence),
    confidence,
    reason: reasonOf(confidence),
    locator: line ? `${document.name}:${line}` : document.name,
    ...extra,
  };
}

function metricsFromTable(document, table, mapped) {
  const observations = [];
  const nameAt = mapped.findIndex((kind) => kind === 'metric');
  const expressionAt = mapped.findIndex((kind) => kind === 'expression');
  if (nameAt < 0 || expressionAt < 0) return observations;
  const unitAt = mapped.indexOf('unit');
  const aliasAt = mapped.indexOf('alias');
  const descriptionAt = mapped.findIndex((kind, index) => kind === 'description' && index !== expressionAt);
  const tableAt = mapped.indexOf('table');
  const bindingAt = mapped.indexOf('graphProperty');
  const ownerAt = mapped.indexOf('owner');
  for (let index = 0; index < table.rows.length; index += 1) {
    const row = table.rows[index];
    const name = row[nameAt]?.replace(/^\*\*|\*\*$/g, '').trim();
    if (!name) continue;
    const rawDefinition = row[expressionAt] ?? '';
    const expression = normaliseExpression(rawDefinition);
    const evidence = table.raw[index + 2] || row.join(' | ');
    const aliases = aliasAt >= 0 ? splitList(row[aliasAt]) : [];
    const columns = expression ? identifiers(expression).filter((identifier) => !isSqlKeyword(identifier)) : [];
    observations.push(observationBase(document, evidence, expression ? 0.95 : 0.85, table.start + index + 1, {
      id: `metric/${slug(name)}`,
      name,
      description: descriptionAt >= 0 ? row[descriptionAt] : (expression ? null : rawDefinition) || null,
      expression,
      aggFunction: aggOf(expression),
      unit: unitAt >= 0 ? (row[unitAt] || null) : unitFromText(rawDefinition),
      synonyms: aliases,
      tables: tableAt >= 0 && row[tableAt] ? splitList(row[tableAt]) : [],
      columns,
      graphProperty: bindingAt >= 0 ? bindingOf(row[bindingAt]) : null,
      owner: ownerAt >= 0 ? row[ownerAt] : null,
      needsReview: !expression,
      proposedBy: 'doc-table',
    }));
  }
  return observations;
}

function isSqlKeyword(word) {
  return /^(?:and|or|not|null|case|when|then|else|end|as|distinct|true|false)$/i.test(word);
}

function unitFromText(text) {
  const match = /(?:单位|unit)\s*[:：]\s*([^\s，,。；;|]+)/i.exec(String(text ?? ''));
  return match ? match[1] : null;
}

function dimensionsFromTable(document, table, mapped) {
  const observations = [];
  const nameAt = mapped.findIndex((kind) => kind === 'dimension');
  if (nameAt < 0) return observations;
  const columnAt = mapped.indexOf('column');
  const tableAt = mapped.indexOf('table');
  const aliasAt = mapped.indexOf('alias');
  const descriptionAt = mapped.indexOf('description');
  const typeAt = mapped.indexOf('dataType');
  const bindingAt = mapped.indexOf('graphProperty');
  for (let index = 0; index < table.rows.length; index += 1) {
    const row = table.rows[index];
    const name = row[nameAt]?.trim();
    if (!name) continue;
    const evidence = table.raw[index + 2] || row.join(' | ');
    observations.push(observationBase(document, evidence, 0.9, table.start + index + 1, {
      id: `dim/${slug(name)}`,
      name,
      column: columnAt >= 0 ? row[columnAt] : name,
      table: tableAt >= 0 ? row[tableAt] : null,
      dataType: typeAt >= 0 ? row[typeAt] : null,
      description: descriptionAt >= 0 ? row[descriptionAt] : null,
      synonyms: aliasAt >= 0 ? splitList(row[aliasAt]) : [],
      graphProperty: bindingAt >= 0 ? bindingOf(row[bindingAt]) : null,
      proposedBy: 'doc-table',
    }));
  }
  return observations;
}

function termsFromTable(document, table, mapped) {
  const observations = [];
  const nameAt = mapped.findIndex((kind) => kind === 'term');
  if (nameAt < 0) return observations;
  const descriptionAt = mapped.indexOf('description');
  const definitionAt = mapped.indexOf('expression');
  const aliasAt = mapped.indexOf('alias');
  for (let index = 0; index < table.rows.length; index += 1) {
    const row = table.rows[index];
    const name = row[nameAt]?.trim();
    if (!name) continue;
    const evidence = table.raw[index + 2] || row.join(' | ');
    observations.push(observationBase(document, evidence, 0.85, table.start + index + 1, {
      id: `term/${slug(name)}`,
      name,
      description: (descriptionAt >= 0 ? row[descriptionAt] : null) || (definitionAt >= 0 ? row[definitionAt] : null) || null,
      synonyms: aliasAt >= 0 ? splitList(row[aliasAt]) : [],
      proposedBy: 'doc-table',
    }));
  }
  return observations;
}

function columnsFromTable(document, table, mapped) {
  const observations = [];
  const columnAt = mapped.indexOf('column');
  if (columnAt < 0) return observations;
  const tableAt = mapped.indexOf('table');
  const typeAt = mapped.indexOf('dataType');
  const descriptionAt = mapped.indexOf('description');
  const roleAt = mapped.indexOf('role');
  for (let index = 0; index < table.rows.length; index += 1) {
    const row = table.rows[index];
    const name = row[columnAt]?.trim();
    if (!name) continue;
    const dataType = typeAt >= 0 ? row[typeAt] : null;
    const evidence = table.raw[index + 2] || row.join(' | ');
    observations.push(observationBase(document, evidence, 0.8, table.start + index + 1, {
      table: tableAt >= 0 ? row[tableAt] : null,
      name,
      dataType,
      description: descriptionAt >= 0 ? row[descriptionAt] : null,
      columnRole: roleAt >= 0 ? row[roleAt] : (isDateType(dataType) ? 'attribute' : isNumericType(dataType) ? 'measure' : 'attribute'),
      proposedBy: 'doc-table',
    }));
  }
  return observations;
}

function tablesFromTable(document, table, mapped) {
  const observations = [];
  const nameAt = mapped.findIndex((kind) => kind === 'table');
  const descriptionAt = mapped.indexOf('description');
  if (nameAt < 0 || descriptionAt < 0) return observations;
  if (mapped.includes('metric') || mapped.includes('dimension') || mapped.includes('column')) return observations;
  for (let index = 0; index < table.rows.length; index += 1) {
    const name = table.rows[index][nameAt]?.trim();
    if (!name) continue;
    observations.push(observationBase(document, table.raw[index + 2] || table.rows[index].join(' | '), 0.8, table.start + index + 1, {
      name,
      description: table.rows[index][descriptionAt] || null,
      proposedBy: 'doc-table',
    }));
  }
  return observations;
}

/** Labeled prose: `指标：GMV = SUM(order_amount)`, `口径/单位/别名` on following lines, `维度：渠道`. */
function proseObservations(document) {
  const { lines, headings } = splitSections(document.text);
  const metrics = [];
  const dimensions = [];
  const terms = [];
  const tables = [];
  let lastMetric = null;

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index].trim();
    if (!line || HEADING.test(lines[index]) || /^\s*\|/.test(line)) continue;
    const section = headings[index].toLowerCase();
    const evidenceLine = index + 1;

    const metricLabel = /(?:^|[-*]\s*)(?:指标|度量|measure|metric|kpi)\s*[:：]?\s*(?:\*\*)?\s*([^*:：=，,（(\n]{1,40}?)(?:\*\*)?\s*(?:=|:|：)\s*(.+)$/i.exec(line);
    if (metricLabel) {
      const name = metricLabel[1].trim();
      const rest = metricLabel[2].trim();
      const expression = normaliseExpression(rest);
      const aliases = /(?:别名|同义词|synonym|alias)\s*[:：]\s*([^；;\n]+)/i.exec(rest)?.[1];
      const unit = unitFromText(rest);
      lastMetric = observationBase(document, line, expression ? 0.95 : 0.8, evidenceLine, {
        id: `metric/${slug(name)}`,
        name,
        description: expression ? null : rest,
        expression,
        aggFunction: aggOf(expression),
        unit,
        synonyms: aliases ? splitList(aliases) : [],
        columns: expression ? identifiers(expression).filter((word) => !isSqlKeyword(word)) : [],
        needsReview: !expression,
        proposedBy: 'doc-prose',
      });
      metrics.push(lastMetric);
      continue;
    }

    const annotation = /^(?:单位|unit|别名|同义词|又称|synonym|alias|口径|定义|计算|表达式|definition|formula|绑定|图属性|graph.?property|fact.?property)\s*[:：]/i.test(line);
    const aggregateLine = /(?:^|[-*]\s*)(?:\*\*)?([A-Za-z_\u4e00-\u9fff][^*:：=，,（(\n]{0,40}?)(?:\*\*)?\s*(?:=|:|：)\s*((?:100\s*\*\s*)?(?:SUM|COUNT|AVG|MIN|MAX)\s*\(.+)$/i.exec(line);
    if (!annotation && aggregateLine && !/^https?:/i.test(line)) {
      const name = aggregateLine[1].trim();
      const expression = normaliseExpression(aggregateLine[2]);
      if (expression) {
        lastMetric = observationBase(document, line, 0.8, evidenceLine, {
          id: `metric/${slug(name)}`,
          name,
          description: null,
          expression,
          aggFunction: aggOf(expression),
          unit: unitFromText(aggregateLine[2]),
          synonyms: [],
          columns: identifiers(expression).filter((word) => !isSqlKeyword(word)),
          needsReview: false,
          proposedBy: 'doc-prose',
        });
        metrics.push(lastMetric);
      }
      continue;
    }

    // inside a Metric section, `- GMV：...` is a metric definition too
    if (/指标|度量|metric|kpi/.test(section) && /^[-*]\s+/.test(line)) {
      const bullet = /^[-*]\s+(?:\*\*)?([^*:：=，,（(\n]{1,40}?)(?:\*\*)?\s*[:：=]\s*(.+)$/.exec(line);
      if (bullet) {
        const expression = normaliseExpression(bullet[2]);
        const name = bullet[1].trim();
        lastMetric = observationBase(document, line, expression ? 0.9 : 0.7, evidenceLine, {
          id: `metric/${slug(name)}`,
          name,
          description: expression ? null : bullet[2].trim(),
          expression,
          aggFunction: aggOf(expression),
          unit: unitFromText(bullet[2]),
          synonyms: [],
          columns: expression ? identifiers(expression).filter((word) => !isSqlKeyword(word)) : [],
          needsReview: !expression,
          proposedBy: 'doc-prose',
        });
        metrics.push(lastMetric);
        continue;
      }
    }

    if (lastMetric) {
      const unit = /^(?:单位|unit)\s*[:：]\s*([^\s，,。；;]+)/i.exec(line);
      if (unit) { lastMetric.unit = lastMetric.unit || unit[1]; continue; }
      const aliases = /^(?:别名|同义词|又称|synonym|alias)\s*[:：]\s*(.+)$/i.exec(line);
      if (aliases) { lastMetric.synonyms = [...new Set([...lastMetric.synonyms, ...splitList(aliases[1])])]; continue; }
      const definition = /^(?:口径|定义|计算|表达式|definition|formula)\s*[:：]\s*(.+)$/i.exec(line);
      if (definition) {
        const expression = normaliseExpression(definition[1]);
        if (expression) {
          lastMetric.expression = expression;
          lastMetric.aggFunction = aggOf(expression);
          lastMetric.columns = identifiers(expression).filter((word) => !isSqlKeyword(word));
          lastMetric.evidence = quote(`${lastMetric.evidence} / ${line}`);
          lastMetric.needsReview = false;
        } else {
          lastMetric.description = definition[1].trim();
        }
        continue;
      }
      const binding = /(?:绑定|图属性|graph.?property|fact.?property)\s*[:：]\s*([A-Za-z_][\w$=,]*(?:\s*,\s*[A-Za-z_][\w$=]*)*)/i.exec(line);
      if (binding) { lastMetric.graphProperty = bindingOf(binding[1]); continue; }
    }

    const dimension = /(?:^|[-*]\s*)(?:维度|dimension)\s*[:：]\s*(.+)$/i.exec(line);
    if (dimension) {
      for (const part of splitList(dimension[1])) {
        const [name, column, binding] = part.split(/[=:：]/).map((piece) => piece.trim());
        dimensions.push(observationBase(document, line, 0.9, evidenceLine, {
          id: `dim/${slug(name)}`,
          name,
          column: column || name,
          table: null,
          dataType: null,
          synonyms: [],
          graphProperty: binding ? bindingOf(binding) : null,
          proposedBy: 'doc-prose',
        }));
      }
      continue;
    }

    const term = /(?:^|[-*]\s*)(?:术语|业务术语|term)\s*[:：]\s*([^*:：=，,（(\n]{1,40}?)\s*(?:=|:|：)\s*(.+)$/i.exec(line);
    if (term) {
      terms.push(observationBase(document, line, 0.85, evidenceLine, {
        id: `term/${slug(term[1])}`,
        name: term[1].trim(),
        description: term[2].trim(),
        synonyms: [],
        proposedBy: 'doc-prose',
      }));
      continue;
    }

    const table = /(?:^|[-*]\s*)(?:表|table)\s*[:：]\s*([`"'\w.]+)\s*[-—:：]?\s*(.+)$/i.exec(line);
    if (table) {
      tables.push(observationBase(document, line, 0.7, evidenceLine, {
        name: table[1].replace(/[`"']/g, '').trim(),
        description: table[2].trim(),
        proposedBy: 'doc-prose',
      }));
    }
  }
  return { metrics, dimensions, terms, tables };
}

/** SQL/DDL/show output: tables, columns, keys, reference queries, metrics and dimensions. */
function sqlObservations(document) {
  const metrics = [];
  const dimensions = [];
  const tables = [];
  const columns = [];
  const foreignKeys = [];
  const queries = [];
  const warnings = [];

  const parsed = parseDbShow(document.text, {});
  warnings.push(...(parsed.warnings || []));
  for (const table of parsed.tables || []) {
    tables.push(observationBase(document, table.evidence || table.name, 0.9, null, {
      name: table.name,
      description: table.description || null,
      proposedBy: 'sql-schema',
    }));
  }
  for (const column of parsed.columns || []) {
    columns.push(observationBase(document, column.evidence || `${column.table}.${column.name}`, 0.9, null, { ...column, proposedBy: 'sql-schema' }));
  }
  for (const key of parsed.foreignKeys || []) {
    foreignKeys.push(observationBase(document, key.evidence || `${key.table}.${key.column}`, 0.9, null, { ...key, proposedBy: 'sql-schema' }));
  }

  const parsedQueries = parseSqlQueries(document.text);
  warnings.push(...parsedQueries.warnings);
  for (const query of parsedQueries.queries) {
    queries.push(observationBase(document, query.evidence || query.sqlText, 0.85, null, {
      ...query, proposedBy: 'sql-query',
    }));
  }
  for (const metric of parsedQueries.metrics) {
    metrics.push(observationBase(document, metric.evidence || metric.expression, 0.75, null, {
      ...metric,
      graphProperty: null,
      synonyms: [],
      unit: null,
      needsReview: false,
      proposedBy: 'sql-query',
    }));
  }
  for (const dimension of parsedQueries.dimensions) {
    dimensions.push(observationBase(document, dimension.evidence || `GROUP BY ${dimension.column}`, 0.75, null, {
      ...dimension, graphProperty: null, synonyms: [], proposedBy: 'sql-query',
    }));
  }

  // `SHOW TABLES` alone: names are still part of the metadata layer
  if (tables.length === 0) {
    for (const name of parseShowTables(document.text)) {
      tables.push(observationBase(document, name, 0.8, null, { name, description: null, proposedBy: 'show-tables' }));
    }
  }
  return { metrics, dimensions, terms: [], tables, columns, foreignKeys, queries, warnings };
}

/** All observations from one document. */
export function extractDocument(document) {
  const sqlLike = document.kind === 'sql' || document.format === 'sql'
    || /(?:^|\n)\s*CREATE\s+(?:OR\s+REPLACE\s+)?(?:TEMP(?:ORARY)?\s+)?TABLE\b/i.test(document.text)
    || /(?:^|\n)\s*(?:DESCRIBE|SHOW\s+(?:FULL\s+)?(?:TABLES|COLUMNS|CREATE\s+TABLE))/i.test(document.text);

  const result = {
    metrics: [], dimensions: [], terms: [], tables: [], columns: [], foreignKeys: [], queries: [], warnings: [],
  };

  const prose = proseObservations(document);
  for (const key of ['metrics', 'dimensions', 'terms', 'tables']) result[key].push(...prose[key]);

  if (sqlLike) {
    const sql = sqlObservations(document);
    for (const key of ['metrics', 'dimensions', 'terms', 'tables', 'columns', 'foreignKeys', 'queries']) result[key].push(...sql[key]);
    result.warnings.push(...sql.warnings);
  }

  const { lines, headings } = splitSections(document.text);
  for (const table of markdownTables(lines, (line) => headings[line - 1] || '')) {
    let mapped = mapHeader(table.header);
    const section = table.section || headings[table.start - 1] || '';
    if (!mapped.includes('metric') && /指标|度量|metric|kpi/i.test(section)) {
      mapped = mapped.map((kind) => (kind === 'name' ? 'metric' : kind));
    }
    if (!mapped.includes('dimension') && /维度|dimension/i.test(section)) {
      mapped = mapped.map((kind) => (kind === 'name' ? 'dimension' : kind));
    }
    if (!mapped.includes('term') && /术语|glossary|term/i.test(section)) {
      mapped = mapped.map((kind) => (kind === 'name' ? 'term' : kind));
    }
    result.metrics.push(...metricsFromTable(document, table, mapped));
    result.dimensions.push(...dimensionsFromTable(document, table, mapped));
    result.terms.push(...termsFromTable(document, table, mapped));
    result.columns.push(...columnsFromTable(document, table, mapped));
    result.tables.push(...tablesFromTable(document, table, mapped));
  }
  return result;
}

/** All documents to one observation set, with per-document warnings. */
export function extract(documents, options = {}) {
  const merged = {
    metrics: [], dimensions: [], terms: [], tables: [], columns: [], foreignKeys: [], queries: [], warnings: [],
  };
  for (const document of documents) {
    const extracted = extractDocument(document);
    for (const key of ['metrics', 'dimensions', 'terms', 'tables', 'columns', 'foreignKeys', 'queries']) {
      merged[key].push(...extracted[key]);
    }
    merged.warnings.push(...extracted.warnings.map((warning) => `${document.name}: ${warning}`));
  }
  if (options.inferDimensions !== false) {
    // a date/attribute column that a reference query groups by is already a dimension; this adds nothing.
    // Columns with no dimension are left alone: turning every VARCHAR into a dimension is the guess this
    // package refuses to make.
  }
  return merged;
}
