/**
 * Source discovery and reading: a file, a directory, an HTTP(S) page (a wiki), or inline text.
 *
 * Every reader answers one shape - `{ documents, skipped, warnings }` - so the extractor never knows
 * whether a sentence came from a PDF, a DOCX, a Markdown page or a `SHOW CREATE TABLE`. A source that
 * cannot be read is a named skip with the reason, never an empty document.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { basename, extname, join, relative, resolve } from 'node:path';
import { documentId, quote } from '../util.mjs';
import { extractPdfText } from './pdf.mjs';
import { docxToText } from './docx.mjs';
import { htmlToText, titleOfHtml, wikiMarkupToText } from './html.mjs';

const TEXT_EXTENSIONS = new Set(['.md', '.markdown', '.mdx', '.txt', '.text', '.rst', '.adoc', '.asciidoc', '.log', '.yaml', '.yml', '.json', '.csv', '.tsv']);
const SQL_EXTENSIONS = new Set(['.sql', '.ddl', '.dml', '.psql', '.mysql']);
const HTML_EXTENSIONS = new Set(['.html', '.htm', '.xhtml']);
const WIKI_EXTENSIONS = new Set(['.wiki', '.mediawiki', '.confluence']);
const BINARY_EXTENSIONS = new Set(['.pdf', '.docx']);

function titleOfText(text, fallback) {
  const heading = /^\s*#\s+(.+)$/m.exec(String(text ?? ''));
  return heading ? heading[1].trim() : fallback;
}

function formatOf(path) {
  const extension = extname(path).toLowerCase();
  if (SQL_EXTENSIONS.has(extension)) return 'sql';
  if (HTML_EXTENSIONS.has(extension)) return 'html';
  if (WIKI_EXTENSIONS.has(extension)) return 'wiki';
  if (extension === '.pdf') return 'pdf';
  if (extension === '.docx') return 'docx';
  if (extension === '.doc') return 'doc';
  if (extension === '.json') return 'json';
  if (extension === '.csv' || extension === '.tsv') return 'csv';
  if (TEXT_EXTENSIONS.has(extension)) return 'text';
  return null;
}

function looksLikeDbMetadata(text) {
  return /(?:^|\n)\s*CREATE\s+(?:OR\s+REPLACE\s+)?(?:TEMP(?:ORARY)?\s+)?TABLE\b/i.test(text)
    || /(?:^|\n)\s*(?:DESCRIBE|DESC|SHOW\s+(?:FULL\s+)?(?:TABLES|COLUMNS|CREATE\s+TABLE))/i.test(text)
    || /"COLUMN_NAME"\s*:|"column_name"\s*:|Tables_in_/i.test(text);
}

function documentFromText({ id, name, path, url, format, text, title, meta = {} }) {
  const kind = format === 'sql' || (format !== 'pdf' && format !== 'docx' && looksLikeDbMetadata(text)) ? 'sql' : format;
  return {
    id: id || documentId(name || path || url, text),
    name: name || basename(path || url || 'inline'),
    path: path || null,
    url: url || null,
    title: title || titleOfText(text, basename(name || path || 'inline', extname(name || path || ''))),
    kind,
    format,
    text: String(text ?? '').trim(),
    meta,
  };
}

function readLocalFile(path, options) {
  const format = formatOf(path);
  if (!format) return { skip: `unsupported extension ${extname(path) || '(none)'}` };
  if (format === 'doc') return { skip: 'legacy .doc is not supported; save it as .docx' };
  if (format === 'pdf') {
    const pdf = extractPdfText(path, { ocr: options.ocr, lang: options.ocrLang, run: options.runPdf });
    if (!pdf.text) return { skip: pdf.reason || 'the PDF has no readable text', warning: true };
    return { document: documentFromText({ name: basename(path), path: resolve(path), format: 'pdf', text: pdf.text, meta: { method: pdf.method } }) };
  }
  if (format === 'docx') {
    try {
      const parsed = docxToText(readFileSync(path));
      if (!parsed.text) return { skip: 'the DOCX contains no body text', warning: true };
      return { document: documentFromText({ name: basename(path), path: resolve(path), format: 'docx', text: parsed.text, title: parsed.title }) };
    } catch (error) {
      return { skip: `cannot read DOCX: ${error.message}`, warning: true };
    }
  }
  const raw = readFileSync(path, 'utf8');
  if (!raw.trim()) return { skip: 'the file is empty' };
  if (format === 'html') {
    const title = titleOfHtml(raw);
    return { document: documentFromText({ name: basename(path), path: resolve(path), format: 'html', text: htmlToText(raw), title }) };
  }
  if (format === 'wiki') {
    return { document: documentFromText({ name: basename(path), path: resolve(path), format: 'wiki', text: wikiMarkupToText(raw) }) };
  }
  return { document: documentFromText({ name: basename(path), path: resolve(path), format, text: raw }) };
}

function detectRemoteFormat(url, contentType) {
  const extension = extname(new URL(url).pathname).toLowerCase();
  if (extension === '.pdf') return 'pdf';
  if (extension === '.docx') return 'docx';
  if (SQL_EXTENSIONS.has(extension)) return 'sql';
  if (HTML_EXTENSIONS.has(extension)) return 'html';
  if (WIKI_EXTENSIONS.has(extension)) return 'wiki';
  if (TEXT_EXTENSIONS.has(extension)) return 'text';
  if (/html/i.test(contentType)) return 'html';
  if (/pdf/i.test(contentType)) return 'pdf';
  return 'html';
}

async function readRemote(url, options) {
  const format = detectRemoteFormat(url, options.contentType || '');
  if (format === 'pdf' || format === 'docx') {
    return { skip: `${format} over HTTP is not read: download it and pass the local path` };
  }
  const headers = {
    accept: 'text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5',
    ...(options.headers || {}),
  };
  const response = await (options.fetchImpl || fetch)(url, { headers, signal: AbortSignal.timeout(options.timeout ?? 30_000) });
  if (!response.ok) return { skip: `HTTP ${response.status} from ${url}` };
  const contentType = response.headers.get('content-type') || '';
  const raw = await response.text();
  if (!raw.trim()) return { skip: `${url} returned an empty body` };
  if (format === 'html' || /html/i.test(contentType)) {
    return { document: documentFromText({ name: basename(new URL(url).pathname) || 'wiki', url, format: 'html', text: htmlToText(raw), title: titleOfHtml(raw), meta: { contentType } }) };
  }
  if (format === 'wiki') {
    return { document: documentFromText({ name: basename(new URL(url).pathname) || 'wiki', url, format: 'wiki', text: wikiMarkupToText(raw), meta: { contentType } }) };
  }
  return { document: documentFromText({ name: basename(new URL(url).pathname) || 'remote', url, format, text: raw, meta: { contentType } }) };
}

function walk(path, options, documents, skipped, warnings) {
  const info = statSync(path);
  if (info.isDirectory()) {
    for (const name of readdirSync(path).sort()) {
      if (name.startsWith('.') || ['node_modules', 'target', 'dist'].includes(name)) continue;
      walk(join(path, name), options, documents, skipped, warnings);
    }
    return;
  }
  const result = readLocalFile(path, options);
  if (result.document) documents.push(result.document);
  else {
    skipped.push({ path: relative(process.cwd(), resolve(path)) || path, reason: result.skip });
    if (result.warning) warnings.push(`${path}: ${result.skip}`);
  }
}

/**
 * @param {Array<string|{name?:string,text:string,format?:string,title?:string}>} sources
 * @param {{ocr?:boolean,ocrLang?:string,runPdf?:Function,fetchImpl?:Function,headers?:object,timeout?:number,kind?:string}} options
 */
export async function readSources(sources, options = {}) {
  const documents = [];
  const skipped = [];
  const warnings = [];
  for (const source of sources) {
    if (source == null) continue;
    if (typeof source === 'object') {
      if (!source.text || !String(source.text).trim()) {
        skipped.push({ path: source.name || '(inline)', reason: 'no text' });
        continue;
      }
      const format = source.format || options.kind || 'text';
      documents.push(documentFromText({
        id: source.id, name: source.name || 'inline', format, text: source.text, title: source.title, meta: source.meta || {},
      }));
      continue;
    }
    const text = String(source);
    if (/^https?:\/\//i.test(text)) {
      try {
        const result = await readRemote(text, { ...options, contentType: options.contentType });
        if (result.document) documents.push(result.document);
        else { skipped.push({ path: text, reason: result.skip }); warnings.push(`${text}: ${result.skip}`); }
      } catch (error) {
        skipped.push({ path: text, reason: `fetch failed: ${error.message}` });
        warnings.push(`${text}: fetch failed (${error.message})`);
      }
      continue;
    }
    let info;
    try {
      info = statSync(text);
    } catch {
      skipped.push({ path: text, reason: 'not found (and not an http(s) URL)' });
      continue;
    }
    if (info.isDirectory()) walk(text, options, documents, skipped, warnings);
    else {
      const result = readLocalFile(text, options);
      if (result.document) documents.push(result.document);
      else { skipped.push({ path: text, reason: result.skip }); if (result.warning) warnings.push(`${text}: ${result.skip}`); }
    }
  }
  // stable ids: two documents with the same name and body are the same source
  const seen = new Map();
  for (const document of documents) {
    let id = document.id;
    let copy = 2;
    while (seen.has(id) && seen.get(id) !== document.text) id = `${document.id}-${copy++}`;
    document.id = id;
    seen.set(id, document.text);
  }
  return { documents, skipped, warnings };
}

/** The first `n` non-empty lines of a document, for a prompt-sized preview. */
export function preview(document, limit = 5) {
  const lines = String(document.text ?? '').split('\n').map((line) => line.trim()).filter(Boolean);
  return `### ${document.title || document.name} (${document.kind})\n${lines.slice(0, limit).map((line) => quote(line, 160)).join('\n')}`;
}

export { looksLikeDbMetadata };
