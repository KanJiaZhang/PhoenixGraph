/**
 * Word `.docx` without a dependency.
 *
 * A `.docx` is a ZIP of OOXML parts; the only part the semantic extractor needs is
 * `word/document.xml` (and `docProps/core.xml` for the title). The ZIP reader below reads the central
 * directory (the authoritative index) and inflates the entries with `node:zlib`, so it works on a plain
 * Node install and never shells out. `.doc` (the pre-2007 binary format) is not ZIP and is refused with a
 * message rather than mis-read.
 */
import { inflateRawSync } from 'node:zlib';
import { readFileSync } from 'node:fs';

const EOCD = 0x06054b50;
const CENTRAL = 0x02014b50;
const LOCAL = 0x04034b50;

function findEndOfCentralDirectory(buffer) {
  const minimum = Math.max(0, buffer.length - 0xffff - 22);
  for (let at = buffer.length - 22; at >= minimum; at -= 1) {
    if (buffer.readUInt32LE(at) === EOCD) return at;
  }
  throw new Error('not a ZIP archive: no end-of-central-directory record (a .doc is not a .docx)');
}

/** Every entry of a ZIP buffer, decompressed, keyed by its path. */
export function unzip(buffer) {
  if (!Buffer.isBuffer(buffer)) buffer = Buffer.from(buffer);
  const eocd = findEndOfCentralDirectory(buffer);
  const count = buffer.readUInt16LE(eocd + 10);
  let offset = buffer.readUInt32LE(eocd + 16);
  const entries = new Map();
  for (let index = 0; index < count; index += 1) {
    if (offset + 46 > buffer.length || buffer.readUInt32LE(offset) !== CENTRAL) {
      throw new Error(`broken ZIP central directory at entry ${index}`);
    }
    const method = buffer.readUInt16LE(offset + 10);
    const compressedSize = buffer.readUInt32LE(offset + 20);
    const nameLength = buffer.readUInt16LE(offset + 28);
    const extraLength = buffer.readUInt16LE(offset + 30);
    const commentLength = buffer.readUInt16LE(offset + 32);
    const localOffset = buffer.readUInt32LE(offset + 42);
    const name = buffer.subarray(offset + 46, offset + 46 + nameLength).toString('utf8');
    offset += 46 + nameLength + extraLength + commentLength;

    if (name.endsWith('/')) continue; // a directory
    if (localOffset + 30 > buffer.length || buffer.readUInt32LE(localOffset) !== LOCAL) {
      throw new Error(`broken ZIP local header for ${name}`);
    }
    const localName = buffer.readUInt16LE(localOffset + 26);
    const localExtra = buffer.readUInt16LE(localOffset + 28);
    const start = localOffset + 30 + localName + localExtra;
    const raw = buffer.subarray(start, start + compressedSize);
    if (method === 0) entries.set(name, Buffer.from(raw));
    else if (method === 8) entries.set(name, inflateRawSync(raw));
    else throw new Error(`unsupported ZIP compression method ${method} for ${name}`);
  }
  return entries;
}

function decodeXml(text) {
  return String(text ?? '')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'").replace(/&#(\d+);/g, (_w, code) => String.fromCodePoint(Number(code)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_w, code) => String.fromCodePoint(Number.parseInt(code, 16)))
    .replace(/&amp;/g, '&');
}

/**
 * Body text of `word/document.xml`, with paragraphs on their own lines and table cells separated by
 * ` | `. The order of `<w:t>` runs is preserved, so a line reads the way the document reads.
 */
export function documentXmlToText(xml) {
  // a paragraph that ends a table cell must not emit its newline before the cell separator
  const normalised = String(xml ?? '').replace(/<\/w:p>\s*<\/w:tc>/g, '</w:tc>');
  const tokens = /<w:t(?:\s[^>]*)?>([\s\S]*?)<\/w:t>|<w:tab\b[^>]*\/?>|<w:br\b[^>]*\/?>|<\/w:p>|<\/w:tc>|<\/w:tr>/g;
  let out = '';
  for (const match of normalised.matchAll(tokens)) {
    if (match[1] != null) out += decodeXml(match[1].replace(/<[^>]+>/g, ''));
    else if (match[0].startsWith('<w:tab')) out += '\t';
    else if (match[0].startsWith('<w:br')) out += '\n';
    else if (match[0] === '</w:p>') out += '\n';
    else if (match[0] === '</w:tc>') out += ' | ';
    else if (match[0] === '</w:tr>') out += '\n';
  }
  return out
    .replace(/[ \t]+\|/g, ' |')
    .replace(/ *\n */g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** Reads a `.docx` buffer: `{ text, title }`; throws with a readable reason for anything else. */
export function docxToText(buffer) {
  const entries = unzip(buffer);
  const body = entries.get('word/document.xml');
  if (!body) throw new Error('the archive has no word/document.xml (is it really a .docx?)');
  const text = documentXmlToText(body.toString('utf8'));
  let title = null;
  const core = entries.get('docProps/core.xml');
  if (core) {
    const match = /<dc:title>([\s\S]*?)<\/dc:title>/i.exec(core.toString('utf8'));
    if (match) title = decodeXml(match[1]).trim() || null;
  }
  return { text, title };
}

export function readDocxFile(path) {
  return docxToText(readFileSync(path));
}
