import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

/**
 * PDF text with an explicit fallback: `pdftotext` first, then (when asked) render pages with `pdftoppm`
 * and OCR them with `tesseract`. The command runner is injectable so the fallback can be tested without
 * either binary installed.
 */
export const extractPdfText = (path, options = {}) => {
  const ocr = options.ocr === true;
  const lang = options.lang || 'eng';
  const run = options.run || ((command, args) => spawnSync(command, args, { encoding: 'utf8' }));
  const plain = run('pdftotext', ['-layout', path, '-']);
  if (!plain.error && plain.status === 0 && plain.stdout && plain.stdout.trim()) {
    return { text: plain.stdout, method: 'pdftotext', reason: null };
  }
  if (!ocr) {
    const reason = plain.error && plain.error.code === 'ENOENT'
      ? 'pdftotext is not installed (install poppler, or convert the PDF to text)'
      : 'the PDF has no extractable text; rerun with --ocr to try OCR';
    return { text: null, method: null, reason };
  }
  return ocrPdf(path, { lang, run });
};

const ocrPdf = (path, { lang, run }) => {
  const directory = mkdtempSync(join(tmpdir(), 'phoenixgraph-ocr-'));
  const prefix = join(directory, 'page');
  try {
    const render = run('pdftoppm', ['-r', '300', '-png', path, prefix]);
    if (render.error && render.error.code === 'ENOENT') {
      return { text: null, method: null, reason: 'pdftoppm is not installed (install poppler)' };
    }
    if (render.status !== 0) {
      return { text: null, method: null, reason: (render.stderr || 'pdftoppm failed').trim().split('\n')[0] };
    }
    const pages = readdirSync(directory).filter((name) => name.endsWith('.png')).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    if (pages.length === 0) {
      return { text: null, method: null, reason: 'pdftoppm produced no pages' };
    }
    const texts = [];
    for (const page of pages) {
      const answer = run('tesseract', [join(directory, page), 'stdout', '-l', lang]);
      if (answer.error && answer.error.code === 'ENOENT') {
        return { text: null, method: null, reason: 'tesseract is not installed' };
      }
      if (answer.status !== 0) {
        return { text: null, method: null, reason: (answer.stderr || 'tesseract failed').trim().split('\n')[0] };
      }
      texts.push((answer.stdout || '').trim());
    }
    const text = texts.filter(Boolean).join('\n\n');
    if (!text) {
      return { text: null, method: null, reason: 'OCR produced no text' };
    }
    return { text, method: 'ocr', reason: null };
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
};

