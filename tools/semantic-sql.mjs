#!/usr/bin/env node
/*
 * Semantic layer -> SQL, by walking the graph (nothing about TPC-H is hard-coded here).
 *
 *   node tools/semantic-sql.mjs --metric metric/revenue
 *   node tools/semantic-sql.mjs --metric metric/revenue --group-by dim/order_date --grain year
 *   node tools/semantic-sql.mjs --metric metric/revenue --group-by dim/region --json
 *   node tools/semantic-sql.mjs --metric metric/revenue --group-by dim/order_date --grain year \
 *        --expect "SELECT EXTRACT(YEAR FROM orders.o_orderdate) AS order_date_year, ..."
 *
 * What it reads, and what it therefore cannot invent:
 *
 *   metric.expression / aggFunction / unit      the measure and its unit          (metric node)
 *   metric --computedFrom--> table              the tables in the FROM             (lineage)
 *   metric --usesColumn--> column(+tableName)   the identifiers to qualify         (lineage)
 *   metric --groupedBy--> dim --dimensionColumn--> column(+dataType)   the GROUP BY (dimension)
 *   column --references--> column               the join keys                      (structure)
 *
 * The join path is a breadth-first walk over `references` from the table the measure lives in to the
 * table the dimension lives in, so a snowflake chain (lineitem -> orders -> customer -> nation -> region)
 * comes out as four joins with no one writing them down. Everything the generator could not ground in the
 * graph is reported as a warning instead of being guessed: an unresolved join is a comment in the SQL and
 * a field in --json.
 *
 * The graph is the one the service serves (default http://localhost:8081, graph tpc-h-analytics); the SQL
 * it prints is meant for a real warehouse (PostgreSQL / DuckDB / MySQL), because this repository has no
 * SQL database - the equivalent graph query, which this repository *can* run, is the other half of the
 * story (see docs/USE-CASES.md, "semantic / execution / analysis layers").
 */
import process from 'node:process';

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const at = args.indexOf('--' + name);
  return at >= 0 && args[at + 1] && !args[at + 1].startsWith('--') ? args[at + 1] : fallback;
};
const flag = (name) => args.includes('--' + name);

const URL_BASE = option('url', 'http://localhost:8081');
const GRAPH = option('graph', 'tpc-h-analytics');
const METRIC = option('metric', null);
const GROUP_BY = option('group-by', null);
const GRAIN = option('grain', 'raw'); // raw | year | quarter | month
const DIALECT = option('dialect', 'postgres'); // postgres | duckdb | mysql
const EXPECT = option('expect', null);

if (!METRIC) {
  console.error('usage: node tools/semantic-sql.mjs --metric metric/revenue [--group-by dim/order_date]'
    + ' [--grain year|quarter|month|raw] [--dialect postgres|duckdb|mysql] [--json] [--expect "SQL"]');
  process.exit(2);
}

/** One traversal against the served graph. */
const ask = async (query) => {
  const response = await fetch(`${URL_BASE}/api/v1/query`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ graphName: GRAPH, query, includeResult: true, returnSubgraph: false }),
  });
  const body = await response.json();
  if (body && body.error) throw new Error(`${query}\n  -> ${body.error}`);
  return Array.isArray(body) ? body : (body.result || []);
};

const short = (id) => String(id).replace(/^[a-z]+\//, '');
const tableOf = (column) => column.replace(/^column\//, '').split('.')[0];
const one = (rows, what) => {
  if (!rows.length) throw new Error(`The semantic layer has no ${what}`);
  return rows[0];
};

// ---- 1. the measure -------------------------------------------------------------------------------
const metric = one(await ask(`g.V('${METRIC}','Node').elementMap()`), METRIC);
const expression = metric.expression;
if (!expression) throw new Error(`${METRIC} has no 'expression': the semantic layer must define it`);

// ---- 2. lineage: the tables and the columns the expression is made of ------------------------------
const usedColumns = await ask(`g.V('${METRIC}','Node').outE().filter(relation.equals('usesColumn'))`
  + `.otherV().values('name','tableName')`);
const columns = [];
for (let i = 0; i < usedColumns.length; i += 2) {
  columns.push({ name: String(usedColumns[i]).replace(/^column\//, ''), table: String(usedColumns[i + 1]) });
}
const tables = (await ask(`g.V('${METRIC}','Node').outE().filter(relation.equals('computedFrom'))`
  + `.otherV().values('name')`)).map((name) => short(name));

// the base table is where the measure's columns live (lineitem for revenue: two columns against none)
const byTable = new Map();
for (const column of columns) byTable.set(column.table, (byTable.get(column.table) || 0) + 1);
const baseTable = [...byTable.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] || tables[0];

// ---- 3. the dimension to group by, when there is one ---------------------------------------------
let dimension = null;
if (GROUP_BY) {
  const dim = one(await ask(`g.V('${GROUP_BY}','Node').elementMap()`), GROUP_BY);
  // values(a, b, c) answers with the values as separate rows in this engine, so the dimension's column
  // arrives as three of them: the column, its table and its data type
  const values = await ask(`g.V('${GROUP_BY}','Node').outE().filter(relation.equals('dimensionColumn'))`
    + `.otherV().values('name','tableName','dataType')`);
  if (values.length < 2) {
    throw new Error(`${GROUP_BY} has no dimensionColumn: the semantic layer must say which column it is`);
  }
  dimension = {
    id: dim.id,
    name: dim.name || GROUP_BY,
    column: String(values[0]).replace(/^column\//, ''),
    table: String(values[1]),
    dataType: String(values[2] || ''),
  };
}

// ---- 4. the join path: breadth-first over `references`, from the base table to a target table -----
const foreignKeys = new Map(); // table -> [{ column, references: { column, table } }]
for (const fk of await ask("g.V('Node').has('columnRole','FK').values('name')")) {
  const name = String(fk).replace(/^column\//, '');
  const targets = await ask(`g.V('${`column/${name}`}','Node').outE().filter(relation.equals('references'))`
    + `.otherV().values('name')`);
  for (const target of targets) {
    const targetName = String(target).replace(/^column\//, '');
    const from = tableOf(name);
    const entry = { column: name, references: { column: targetName, table: tableOf(targetName) } };
    if (!foreignKeys.has(from)) foreignKeys.set(from, []);
    foreignKeys.get(from).push(entry);
  }
}

/** The shortest chain of joins from `from` to `to`, or null when the graph does not hold one. */
const joinPath = (from, to) => {
  if (from === to) return [];
  const seen = new Set([from]);
  const queue = [[from, []]];
  while (queue.length) {
    const [table, path] = queue.shift();
    for (const fk of foreignKeys.get(table) || []) {
      if (seen.has(fk.references.table)) continue;
      const step = [...path, { from: table, to: fk.references.table, fk: fk.column, pk: fk.references.column }];
      if (fk.references.table === to) return step;
      seen.add(fk.references.table);
      queue.push([fk.references.table, step]);
    }
  }
  return null;
};

const wanted = new Set();
if (dimension) wanted.add(dimension.table);
for (const column of columns) wanted.add(column.table);
wanted.delete(baseTable);
const joins = [];
const warnings = [];
for (const target of wanted) {
  const path = joinPath(baseTable, target);
  if (!path) {
    warnings.push(`no join path from ${baseTable} to ${target}: the query would need one`);
    continue;
  }
  for (const step of path) {
    if (joins.some((join) => join.to === step.to)) continue;
    joins.push(step);
  }
}

// ---- 5. render -------------------------------------------------------------------------------------
const qualified = expression.replace(/[A-Za-z_][A-Za-z0-9_]*/g, (token) => {
  const column = columns.find((candidate) => candidate.name.split('.').pop() === token);
  return column ? column.name : token; // functions (SUM) and operators stay as they are
});

const grainSql = (column, grain) => {
  const dialect = DIALECT.toLowerCase();
  if (grain === 'raw') return column;
  if (grain === 'year') return dialect === 'mysql' ? `YEAR(${column})` : `EXTRACT(YEAR FROM ${column})`;
  if (grain === 'quarter') return dialect === 'mysql' ? `QUARTER(${column})` : `EXTRACT(QUARTER FROM ${column})`;
  if (grain === 'month') {
    return dialect === 'mysql' ? `DATE_FORMAT(${column}, '%Y-%m')` : `DATE_TRUNC('month', ${column})`;
  }
  throw new Error(`Unknown grain '${grain}'; use raw, year, quarter or month`);
};

const alias = short(METRIC);
const select = [];
let groupBy = null;
if (dimension) {
  const grain = GRAIN === 'raw' ? dimension.column : grainSql(dimension.column, GRAIN);
  // the alias is the dimension's own name (dim/order_date -> order_date), so it never carries the table's
  // dot into an identifier
  const grainAlias = GRAIN === 'raw' ? short(dimension.id) : `${short(dimension.id)}_${GRAIN}`;
  select.push(`${grain} AS ${grainAlias}`);
  groupBy = { expression: grain, alias: grainAlias };
}
select.push(`${qualified} AS ${alias}`);

const lines = [];
lines.push(`-- metric: ${METRIC}${metric.description ? ` (${metric.description})` : ''}`);
lines.push(`-- expression from the graph: ${expression}${metric.unit ? `, unit: ${metric.unit}` : ''}`);
for (const join of joins) {
  lines.push(`-- join from references: ${join.fk} -> ${join.pk}`);
}
for (const warning of warnings) {
  lines.push(`-- WARNING: ${warning}`);
}
const sql = [
  `SELECT ${select.join(',\n       ')}`,
  `FROM ${baseTable}`,
  ...joins.map((join) => `JOIN ${join.to} ON ${join.fk} = ${join.pk}`),
  ...(groupBy ? [`GROUP BY ${groupBy.expression}`] : []),
  ...(groupBy ? [`ORDER BY ${groupBy.alias}`] : []),
];
const statement = `${sql.join('\n')};`;
const document = [...lines, ...sql].join('\n') + ';';

if (flag('json')) {
  console.log(JSON.stringify({
    graph: GRAPH,
    metric: { id: metric.id, description: metric.description, expression, aggFunction: metric.aggFunction,
      unit: metric.unit },
    tables,
    columns,
    baseTable,
    dimension,
    grain: dimension ? GRAIN : null,
    joins,
    warnings,
    sql: statement,
  }, null, 2));
} else {
  console.log(document);
}

if (EXPECT !== null) {
  // whitespace, the header comments and a trailing semicolon are not part of the statement
  const normalise = (text) => text.replace(/--[^\n]*\n/g, '').replace(/\s+/g, ' ').trim()
    .replace(/;\s*$/, '').toLowerCase();
  const ok = normalise(EXPECT) === normalise(statement);
  console.log(ok ? '\n== the generated SQL matches --expect' : '\n!! the generated SQL differs from --expect');
  if (!ok) {
    console.log(`   expected: ${normalise(EXPECT)}\n   generated: ${normalise(statement)}`);
  }
  process.exit(ok ? 0 : 1);
}
