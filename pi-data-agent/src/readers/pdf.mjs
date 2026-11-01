/**
 * PDF text with an explicit fallback chain: `pdftotext` first, then (when asked) `pdftoppm` + `tesseract`.
 *
 * There is no pure-JavaScript PDF parser here on purpose: a hand-rolled one would silently return partial
 * text for the scanned PDFs that matter most, and a partial metric definition is worse than a refusal.
 * The command runner is injectable so the fallback is testable without either binary installed.
 */
import { mkdtempSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

export function extractPdfText(path, options = {}) {
  const ocr = options.ocr === true;
  const lang = options.lang || 'eng';
  const timeout = options.timeout ?? 60_000;
  const run = options.run || ((command, args) => spawnSync(command, args, { encoding: 'utf8', timeout, maxBuffer: 64 * 1024 * 1024 }));
  const plain = run('pdftotext', ['-layout', path, '-']);
  if (!plain.error && plain.status === 0 && plain.stdout && plain.stdout.trim()) {
    return { text: plain.stdout, method: 'pdftotext', reason: null };
  }
  if (!ocr) {
    const reason = plain.error && plain.error.code === 'ENOENT'
      ? 'pdftotext is not installed (install poppler, or export the PDF to text/markdown)'
      : 'the PDF has no extractable text; rerun with ocr=true to try OCR';
    return { text: null, method: null, reason };
  }
  return ocrPdf(path, { lang, run });
}

function ocrPdf(path, { lang, run }) {
  const directory = mkdtempSync(join(tmpdir(), 'pi-data-agent-ocr-'));
  const prefix = join(directory, 'page');
  try {
    const render = run('pdftoppm', ['-r', '300', '-png', path, prefix]);
    if (render.error && render.error.code === 'ENOENT') {
      return { text: null, method: null, reason: 'pdftoppm is not installed (install poppler)' };
    }
    if (render.status !== 0) {
      return { text: null, method: null, reason: (render.stderr || 'pdftoppm failed').trim().split('\n')[0] };
    }
    const pages = readdirSync(directory)
      .filter((name) => name.endsWith('.png'))
      .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
    if (pages.length === 0) return { text: null, method: null, reason: 'pdftoppm produced no pages' };
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
    if (!text) return { text: null, method: null, reason: 'OCR produced no text' };
    return { text, method: 'ocr', reason: null };
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}
