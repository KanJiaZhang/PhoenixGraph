/**
 * HTML / wiki markup to plain, structured text.
 *
 * Wiki exports (Confluence, Notion, MediaWiki, XWiki) are HTML in practice. The reader is deliberately a
 * small state machine rather than a DOM dependency: it has to keep the *tables* and *headings* that a
 * semantic-layer extractor reads, and it must not execute or fetch anything. Everything else is dropped.
 */

const ENTITIES = new Map([
  ['amp', '&'], ['lt', '<'], ['gt', '>'], ['quot', '"'], ['apos', "'"], ['nbsp', ' '],
  ['hellip', '…'], ['mdash', '—'], ['ndash', '–'], ['times', '×'], ['divide', '÷'],
  ['yen', '¥'], ['euro', '€'], ['pound', '£'], ['copy', '©'], ['reg', '®'], ['trade', '™'],
  ['ldquo', '“'], ['rdquo', '”'], ['lsquo', '‘'], ['rsquo', '’'],
]);

export function decodeEntities(text) {
  return String(text ?? '').replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]+);/g, (whole, body) => {
    if (body[0] === '#') {
      const hex = body[1] === 'x' || body[1] === 'X';
      const code = Number.parseInt(body.slice(hex ? 2 : 1), hex ? 16 : 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : whole;
    }
    const named = ENTITIES.get(body.toLowerCase());
    return named == null ? whole : named;
  });
}

function stripTags(html) {
  return decodeEntities(String(html ?? '')
    .replace(/<br\s*\/?>/gi, ' ')
    .replace(/<[^>]+>/g, ''));
}

/** One HTML table as rows of plain cells. Kept separate so a caller can read the table structurally. */
export function tablesOf(html) {
  const tables = [];
  const tablePattern = /<table\b[\s\S]*?<\/table>/gi;
  for (const match of String(html ?? '').matchAll(tablePattern)) {
    const rows = [];
    for (const row of match[0].matchAll(/<tr\b[\s\S]*?<\/tr>/gi)) {
      const cells = [];
      for (const cell of row[0].matchAll(/<t([dh])\b[^>]*>([\s\S]*?)<\/t\1>/gi)) {
        cells.push(stripTags(cell[2]).replace(/\s+/g, ' ').trim());
      }
      if (cells.length) rows.push(cells);
    }
    if (rows.length) tables.push(rows);
  }
  return tables;
}

/**
 * Converts HTML to text that keeps the shapes the extractor knows:
 * `# heading`, `| a | b |` table rows, `- list item`, blank lines between blocks.
 */
export function htmlToText(html) {
  let text = String(html ?? '');
  text = text.replace(/<!--[\s\S]*?-->/g, ' ');
  text = text.replace(/<(script|style|noscript|svg|head)\b[\s\S]*?<\/\1>/gi, ' ');

  // tables first: a cell boundary becomes ` | ` so a row stays one line
  text = text.replace(/<table\b[\s\S]*?<\/table>/gi, (table) => {
    const rows = [];
    for (const row of table.matchAll(/<tr\b[\s\S]*?<\/tr>/gi)) {
      const cells = [];
      for (const cell of row[0].matchAll(/<t([dh])\b[^>]*>([\s\S]*?)<\/t\1>/gi)) {
        cells.push(stripTags(cell[2]).replace(/\s+/g, ' ').trim());
      }
      if (cells.length) rows.push(`| ${cells.join(' | ')} |`);
    }
    if (rows.length > 1) rows.splice(1, 0, `|${rows[0].split('|').slice(1, -1).map(() => '---').join('|')}|`);
    return `\n\n${rows.join('\n')}\n\n`;
  });

  for (let level = 1; level <= 6; level += 1) {
    const pattern = new RegExp(`<h${level}\\b[^>]*>([\\s\\S]*?)<\\/h${level}>`, 'gi');
    text = text.replace(pattern, (_whole, inner) => {
      const label = stripTags(inner).replace(/\s+/g, ' ').trim();
      return `\n\n${'#'.repeat(level)} ${label}\n`;
    });
  }

  text = text.replace(/<pre\b[^>]*>([\s\S]*?)<\/pre>/gi, (_whole, inner) => `\n\n\`\`\`\n${stripTags(inner)}\n\`\`\`\n\n`);
  text = text.replace(/<li\b[^>]*>/gi, '\n- ');
  text = text.replace(/<\/li>/gi, '\n');
  text = text.replace(/<\/(p|div|section|article|tr|ul|ol|dl|blockquote)>/gi, '\n\n');
  text = text.replace(/<br\s*\/?>/gi, '\n');
  text = text.replace(/<[^>]+>/g, ' ');

  text = decodeEntities(text);
  return text
    .replace(/[ \t\f\v]+/g, ' ')
    .replace(/ *\n */g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** The `<title>` of a page, or null. */
export function titleOfHtml(html) {
  const match = /<title\b[^>]*>([\s\S]*?)<\/title>/i.exec(String(html ?? ''));
  if (match) {
    const title = decodeEntities(stripTags(match[1])).replace(/\s+/g, ' ').trim();
    if (title) return title;
  }
  const h1 = /<h1\b[^>]*>([\s\S]*?)<\/h1>/i.exec(String(html ?? ''));
  return h1 ? decodeEntities(stripTags(h1[1])).replace(/\s+/g, ' ').trim() : null;
}

/** MediaWiki / XWiki source markup: enough to keep headings, tables and list items. */
export function wikiMarkupToText(markup) {
  let text = String(markup ?? '');
  text = text.replace(/^\s*\{\|[\s\S]*?^\s*\|\}/gm, (table) => {
    const rows = [];
    let row = null;
    for (const line of table.split('\n')) {
      if (/^\s*\|[-+]/.test(line)) {
        if (row && row.length) rows.push(`| ${row.join(' | ')} |`);
        row = [];
      } else if (/^\s*[!|]/.test(line)) {
        row = row || [];
        for (const cell of line.replace(/^\s*[!|]/, '').split(/\s*[!|]{2}\s*/)) {
          row.push(cell.replace(/^\s*[!|]\s*/, '').trim());
        }
      }
    }
    if (row && row.length) rows.push(`| ${row.join(' | ')} |`);
    return `\n\n${rows.join('\n')}\n\n`;
  });
  text = text.replace(/^======\s*(.+?)\s*======$/gm, '# $1');
  text = text.replace(/^=====\s*(.+?)\s*=====$/gm, '## $1');
  text = text.replace(/^====\s*(.+?)\s*====$/gm, '### $1');
  text = text.replace(/^===\s*(.+?)\s*===$/gm, '#### $1');
  text = text.replace(/^==\s*(.+?)\s*==$/gm, '##### $1');
  text = text.replace(/^=\s*(.+?)\s*=$/gm, '###### $1');
  text = text.replace(/'''?([^'\n]+)'{2,3}/g, '$1');
  text = text.replace(/^\s*\*+\s*/gm, '- ');
  text = text.replace(/<\/?[a-zA-Z][^>]*>/g, ' ');
  return decodeEntities(text).replace(/\n{3,}/g, '\n\n').trim();
}
