import test from 'node:test';
import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { extractPdfText } from './pdf.mjs';

test('pdftotext is used when it has text', () => {
  const calls = [];
  const run = (command, args) => {
    calls.push([command, args]);
    return { status: 0, stdout: 'the annual report', stderr: '' };
  };
  const answer = extractPdfText('report.pdf', { run });
  assert.equal(answer.method, 'pdftotext');
  assert.equal(answer.text, 'the annual report');
  assert.deepEqual(calls.map(([command]) => command), ['pdftotext']);
});

test('a scanned PDF falls back to pdftoppm and tesseract', () => {
  const calls = [];
  const run = (command, args) => {
    calls.push([command, args]);
    if (command === 'pdftotext') return { status: 0, stdout: '  \n', stderr: '' };
    if (command === 'pdftoppm') {
      const prefix = args[args.length - 1];
      writeFileSync(`${prefix}-2.png`, '');
      writeFileSync(`${prefix}-1.png`, '');
      return { status: 0, stdout: '', stderr: '' };
    }
    if (command === 'tesseract') {
      return { status: 0, stdout: args[0].includes('page-1') ? 'page one' : 'page two', stderr: '' };
    }
    throw new Error(`unexpected command ${command}`);
  };
  const answer = extractPdfText('scanned.pdf', { ocr: true, lang: 'eng', run });
  assert.equal(answer.method, 'ocr');
  assert.equal(answer.text, 'page one\n\npage two');
  assert.ok(calls.some(([command, args]) => command === 'tesseract' && args.includes('eng')));
  assert.ok(calls.some(([command, args]) => command === 'pdftoppm' && args.includes('300')));
});

test('a missing OCR binary is reported, not guessed', () => {
  const missing = (command) => ({ error: { code: 'ENOENT' }, status: null, stdout: '', stderr: '' });
  const noPoppler = extractPdfText('scanned.pdf', { ocr: true, run: missing });
  assert.match(noPoppler.reason, /pdftoppm is not installed/);
  const noText = extractPdfText('scanned.pdf', { run: (command) => ({ status: 0, stdout: '', stderr: '' }) });
  assert.match(noText.reason, /no extractable text/);
});
