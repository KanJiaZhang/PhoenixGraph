/**
 * A deterministic, offline embedder: feature hashing over word and character n-grams.
 *
 * The case needs *vector* similarity over the metadata layer, and the numbers have to be reproducible on a
 * machine with no API key - so this is the same idea PhoenixGraph's default agent embedder uses (hashing), in
 * ~40 lines: tokens are hashed into a fixed number of dimensions with a sign, weighted by sublinear term
 * frequency, and the vector is L2-normalised. It is a real bag-of-ngrams vector space (character trigrams carry
 * Chinese, word tokens carry English), not a random projection, so "收入" lands near "营收" and "revenue".
 *
 * Swap it for a model embedder by replacing this function; nothing else changes, because the vector is only
 * ever compared with another vector produced by the same function (cosine), never mixed with another space.
 */

export const DIMENSIONS = 768;

/** Words (latin/number runs) plus single CJK characters and their bigrams, which is what carries Chinese. */
export function tokens(text) {
  const normalised = String(text ?? '')
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[\s_]+/g, ' ');
  const out = [];
  for (const word of normalised.match(/[a-z0-9][a-z0-9.+#-]*/g) || []) {
    out.push(word);
    if (word.length > 4) for (let index = 0; index <= word.length - 3; index += 1) out.push(`#${word.slice(index, index + 3)}`);
  }
  const cjk = normalised.replace(/[^\p{Script=Han}]/gu, ' ');
  for (const run of cjk.split(/\s+/).filter(Boolean)) {
    for (const char of run) out.push(char);
    for (let index = 0; index + 1 < run.length; index += 1) out.push(run.slice(index, index + 2));
  }
  return out;
}

/** A 32-bit FNV-1a hash: stable across processes and platforms, which a Map iteration order is not. */
function hash(text) {
  let value = 0x811c9dc5;
  for (let index = 0; index < text.length; index += 1) {
    value ^= text.charCodeAt(index);
    value = Math.imul(value, 0x01000193) >>> 0;
  }
  return value;
}

/** The embedding of a text: an array of `dimensions` floats, unit length (or all zeros for empty text). */
export function embed(text, dimensions = DIMENSIONS) {
  const vector = new Float64Array(dimensions);
  const counts = new Map();
  for (const token of tokens(text)) counts.set(token, (counts.get(token) || 0) + 1);
  for (const [token, count] of counts) {
    const value = hash(token);
    const index = value % dimensions;
    const sign = (hash(`${token}:sign`) & 1) === 1 ? -1 : 1;
    vector[index] += sign * (1 + Math.log(count));
  }
  let norm = 0;
  for (const value of vector) norm += value * value;
  norm = Math.sqrt(norm);
  const out = new Array(dimensions);
  for (let index = 0; index < dimensions; index += 1) out[index] = norm ? Number((vector[index] / norm).toFixed(6)) : 0;
  return out;
}

/** Cosine similarity of two vectors of the same length (both are normalised here, so this is a dot product). */
export function cosine(left, right) {
  if (!Array.isArray(left) || !Array.isArray(right) || left.length !== right.length) return 0;
  let dot = 0;
  let normLeft = 0;
  let normRight = 0;
  for (let index = 0; index < left.length; index += 1) {
    dot += left[index] * right[index];
    normLeft += left[index] * left[index];
    normRight += right[index] * right[index];
  }
  if (!normLeft || !normRight) return 0;
  return Number((dot / Math.sqrt(normLeft * normRight)).toFixed(4));
}
