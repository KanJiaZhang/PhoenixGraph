import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { htmlToText, titleOfHtml, wikiMarkupToText } from '../src/readers/html.mjs';
import { docxToText, documentXmlToText } from '../src/readers/docx.mjs';
import { extractPdfText } from '../src/readers/pdf.mjs';
import { parseCreateTable, parseDdl, parseDescribe, parseInformationSchema, parseShowTables, parseSqlQueries, splitStatements } from '../src/readers/sql.mjs';
import { readSources } from '../src/readers/index.mjs';

function storedZip(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, value] of Object.entries(entries)) {
    const nameBytes = Buffer.from(name, 'utf8');
    const data = Buffer.from(value, 'utf8');
    const local = Buffer.alloc(30 + nameBytes.length + data.length);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(0, 12);
    local.writeUInt32LE(0, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBytes.length, 26);
    local.writeUInt16LE(0, 28);
    nameBytes.copy(local, 30);
    data.copy(local, 30 + nameBytes.length);
    locals.push(local);

    const central = Buffer.alloc(46 + nameBytes.length);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0, 8);
    central.writeUInt16LE(0, 10);
    central.writeUInt16LE(0, 12);
    central.writeUInt16LE(0, 14);
    central.writeUInt32LE(0, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBytes.length, 28);
    central.writeUInt16LE(0, 30);
    central.writeUInt16LE(0, 32);
    central.writeUInt16LE(0, 34);
    central.writeUInt16LE(0, 36);
    central.writeUInt32LE(0, 38);
    central.writeUInt32LE(offset, 42);
    nameBytes.copy(central, 46);
    centrals.push(central);
    offset += local.length;
  }
  const directory = Buffer.concat(centrals);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(0, 4);
  end.writeUInt16LE(0, 6);
  end.writeUInt16LE(Object.keys(entries).length, 8);
  end.writeUInt16LE(Object.keys(entries).length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  end.writeUInt16LE(0, 20);
  return Buffer.concat([...locals, directory, end]);
}

test('html becomes headings, tables with a divider row and list items', () => {
  const html = '<html><head><title>口径</title></head><body><h2>指标</h2><table><tr><th>指标</th><th>定义</th></tr><tr><td>营收</td><td>SUM(revenue)</td></tr></table><ul><li>a</li><li>b</li></ul><p>&amp; 结束</p></body></html>';
  const text = htmlToText(html);
  assert.match(text, /^## 指标/m);
  assert.match(text, /\| 指标 \| 定义 \|/);
  assert.match(text, /^\|---\|---\|$/m);
  assert.match(text, /- a/);
  assert.equal(titleOfHtml(html), '口径');
  assert.match(text, /& 结束/);
});

test('wiki markup keeps headings and a table', () => {
  const text = wikiMarkupToText('== 指标 ==\n{|\n! 指标 !! 定义\n|-\n| 营收 || SUM(revenue)\n|}\n* 说明');
  assert.match(text, /## 指标/);
  assert.match(text, /\| 指标 \| 定义 \|/);
  assert.match(text, /- 说明/);
});

test('docx reads paragraphs, tabs and table cells', () => {
  const xml = '<w:document><w:body><w:p><w:r><w:t>营收</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>SUM(revenue)</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>渠道</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>channel</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>';
  const text = documentXmlToText(xml);
  assert.match(text, /营收\tSUM\(revenue\)/);
  assert.match(text, /渠道 \| channel/);
  const archive = storedZip({
    'word/document.xml': xml,
    'docProps/core.xml': '<cp:coreProperties><dc:title>指标手册</dc:title></cp:coreProperties>',
  });
  const parsed = docxToText(archive);
  assert.equal(parsed.title, '指标手册');
  assert.match(parsed.text, /营收/);
});

test('pdf uses pdftotext and reports a missing binary', () => {
  const ok = extractPdfText('x.pdf', { run: () => ({ status: 0, stdout: '营收 = SUM(revenue)', stderr: '' }) });
  assert.equal(ok.method, 'pdftotext');
  assert.match(ok.text, /SUM/);
  const missing = extractPdfText('x.pdf', { run: () => ({ error: { code: 'ENOENT' }, status: null, stdout: '' }) });
  assert.equal(missing.text, null);
  assert.match(missing.reason, /pdftotext is not installed/);
  const empty = extractPdfText('x.pdf', { run: () => ({ status: 0, stdout: '   ', stderr: '' }) });
  assert.match(empty.reason, /no extractable text/);
});

test('DDL parses table, columns, keys, comments and foreign keys', () => {
  const ddl = `-- 表：orders 订单\nCREATE TABLE orders (\n  id INT PRIMARY KEY,\n  customer_id INT NOT NULL,\n  amount DECIMAL(15,2) COMMENT '金额',\n  created_at DATE,\n  CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES customer(id) COMMENT '客户'\n) COMMENT='订单表';`;
  const parsed = parseCreateTable(ddl);
  assert.equal(parsed.table.name, 'orders');
  assert.equal(parsed.table.description, '订单表');
  assert.equal(parsed.columns.find((c) => c.name === 'id').columnRole, 'PK');
  assert.equal(parsed.columns.find((c) => c.name === 'customer_id').columnRole, 'FK');
  assert.equal(parsed.columns.find((c) => c.name === 'amount').dataType, 'DECIMAL(15,2)');
  assert.equal(parsed.columns.find((c) => c.name === 'amount').description, '金额');
  assert.equal(parsed.foreignKeys[0].referencesTable, 'customer');
});

test('SHOW TABLES, DESCRIBE and information_schema answer the same shape', () => {
  const show = `+------------------+\n| Tables_in_demo   |\n+------------------+\n| marketing        |\n| orders           |\n+------------------+`;
  assert.deepEqual(parseShowTables(show), ['marketing', 'orders']);
  const describe = `DESCRIBE marketing\n+--------+--------------+------+-----+---------+-------+\n| Field  | Type         | Null | Key | Default | Extra |\n+--------+--------------+------+-----+---------+-------+\n| id     | int          | NO   | PRI | NULL    |       |\n| channel| varchar(20)  | YES  |     | NULL    |       |\n+--------+--------------+------+-----+---------+-------+`;
  const described = parseDescribe(describe);
  assert.equal(described.table, 'marketing');
  assert.equal(described.columns[1].name, 'channel');
  assert.equal(described.columns[0].columnRole, 'PK');
  const json = JSON.stringify([{ TABLE_NAME: 'marketing', COLUMN_NAME: 'revenue', DATA_TYPE: 'decimal', COLUMN_TYPE: 'decimal(15,2)', COLUMN_KEY: '', COLUMN_COMMENT: '营收' }]);
  const information = parseInformationSchema(json);
  assert.equal(information.columns[0].name, 'revenue');
  assert.equal(information.columns[0].description, '营收');
  assert.equal(parseDdl('select 1;').tables.length, 0);
});

test('SQL reference queries become named metrics and dimensions', () => {
  const sql = `-- name: 各渠道营收\nSELECT channel, SUM(revenue) AS revenue\nFROM marketing\nGROUP BY channel;`;
  const parsed = parseSqlQueries(sql);
  assert.equal(parsed.metrics[0].id, 'metric/revenue');
  assert.equal(parsed.metrics[0].expression, 'SUM(revenue)');
  assert.equal(parsed.dimensions[0].column, 'channel');
  assert.equal(parsed.queries[0].tables[0], 'marketing');
  assert.equal(splitStatements('select 1; select 2;').length, 2);
});

test('readSources walks a directory, reads inline text and skips unsupported files', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'pda-'));
  try {
    mkdirSync(join(dir, 'docs'));
    writeFileSync(join(dir, 'docs', 'metrics.md'), '# 指标\n\n指标：营收 = SUM(revenue)\n');
    writeFileSync(join(dir, 'schema.sql'), 'CREATE TABLE t (id INT);');
    writeFileSync(join(dir, 'image.png'), 'not really');
    const read = await readSources([dir, { name: 'inline', text: '维度：渠道' }]);
    assert.equal(read.documents.length, 3);
    assert.ok(read.skipped.some((item) => item.path.endsWith('image.png')));
    assert.ok(read.documents.some((doc) => doc.kind === 'sql'));
    assert.ok(read.documents.some((doc) => doc.name === 'inline'));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
