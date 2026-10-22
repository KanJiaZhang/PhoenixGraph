// S3 connector: list a bucket/prefix and read the text objects as documents.
//
//   const docs = await s3Documents({ bucket, prefix, region, accessKey, secretKey });
//
// The connector signs its own ListObjectsV2/GetObject requests with SigV4, so it needs no AWS SDK. It reads
// Markdown, text and HTML; PDF objects are listed as skipped (use the CLI with pdftotext for those).
import { createHash, createHmac } from 'node:crypto';
import { stripHtml } from './common.mjs';

const EMPTY_SHA256 = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';
const TEXT_SUFFIXES = ['.md', '.markdown', '.txt', '.html', '.htm'];

const hex = (buffer) => Buffer.from(buffer).toString('hex');
const sha256 = (value) => createHash('sha256').update(value).digest();
const hmac = (key, value) => createHmac('sha256', key).update(value).digest();
const encode = (value) => encodeURIComponent(value).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * One signed request. `path` is the canonical path the service sees (for AWS virtual-host style it is
 * `/key`; for a path-style endpoint it is `/bucket/key`). `query` is an array of [key, value] pairs.
 */
async function signedRequest({ method, url, path, query = [], region, accessKey, secretKey, sessionToken }) {
  const now = new Date();
  const amzDate = now.toISOString().replace(/[:-]|\.\d{3}/g, '');
  const date = amzDate.slice(0, 8);
  const target = new URL(url);
  const payloadHash = EMPTY_SHA256;
  const canonicalQuery = [...query]
    .map(([key, value]) => [encode(key), encode(value)])
    .sort(([a, av], [b, bv]) => (a === b ? (av < bv ? -1 : 1) : (a < b ? -1 : 1)))
    .map(([key, value]) => `${key}=${value}`)
    .join('&');
  const headers = {
    host: target.host,
    'x-amz-content-sha256': payloadHash,
    'x-amz-date': amzDate,
  };
  if (sessionToken) headers['x-amz-security-token'] = sessionToken;
  const signedHeaderNames = Object.keys(headers).sort();
  const canonicalHeaders = signedHeaderNames.map((key) => `${key}:${headers[key]}\n`).join('');
  const canonicalRequest = [
    method,
    path,
    canonicalQuery,
    canonicalHeaders,
    signedHeaderNames.join(';'),
    payloadHash,
  ].join('\n');
  const scope = `${date}/${region}/s3/aws4_request`;
  const stringToSign = ['AWS4-HMAC-SHA256', amzDate, scope, hex(sha256(canonicalRequest))].join('\n');
  let signingKey = hmac(`AWS4${secretKey}`, date);
  signingKey = hmac(signingKey, region);
  signingKey = hmac(signingKey, 's3');
  signingKey = hmac(signingKey, 'aws4_request');
  const signature = hex(hmac(signingKey, stringToSign));
  const authorization = `AWS4-HMAC-SHA256 Credential=${accessKey}/${scope}, SignedHeaders=${signedHeaderNames.join(';')}, Signature=${signature}`;
  const requestHeaders = { ...headers, Authorization: authorization };
  delete requestHeaders.host;
  const endpoint = `${target.origin}${path}${canonicalQuery ? `?${canonicalQuery}` : ''}`;
  const response = await fetch(endpoint, { method, headers: requestHeaders });
  const text = await response.text();
  return { status: response.status, ok: response.ok, text };
}

const tag = (xml, name) => {
  const match = new RegExp(`<${name}>([\\s\\S]*?)</${name}>`).exec(xml);
  return match ? match[1] : null;
};

const decodeXml = (value) => String(value || '')
  .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
  .replace(/&quot;/g, '"').replace(/&#39;/g, "'");

function parseList(xml) {
  const objects = [];
  for (const block of xml.split('<Contents>').slice(1)) {
    const end = block.indexOf('</Contents>');
    const body = end < 0 ? block : block.slice(0, end);
    const key = tag(body, 'Key');
    if (key != null) {
      objects.push({ key: decodeXml(key), size: Number(tag(body, 'Size') || 0) });
    }
  }
  return {
    objects,
    truncated: tag(xml, 'IsTruncated') === 'true',
    next: decodeXml(tag(xml, 'NextContinuationToken') || ''),
  };
}

/** The bucket endpoint and the canonical path for one key (virtual-host on AWS, path-style otherwise). */
function location({ bucket, region, endpoint, key = '' }) {
  const suffix = key ? `/${key.split('/').map(encode).join('/')}` : '/';
  if (endpoint) {
    const base = endpoint.replace(/\/+$/, '');
    return {
      url: `${base}/${encode(bucket)}${suffix === '/' ? '/' : suffix}`,
      path: `/${encode(bucket)}${suffix}`,
    };
  }
  return {
    url: `https://${bucket}.s3.${region}.amazonaws.com${suffix}`,
    path: suffix,
  };
}

export async function s3Documents({
  bucket,
  prefix = '',
  region = process.env.AWS_REGION || 'us-east-1',
  endpoint = '',
  accessKey = process.env.AWS_ACCESS_KEY_ID || '',
  secretKey = process.env.AWS_SECRET_ACCESS_KEY || '',
  sessionToken = process.env.AWS_SESSION_TOKEN || '',
  limit = 100,
  timeoutMs = 30000,
} = {}) {
  if (!bucket) throw new Error('S3 needs a bucket (--bucket or S3_BUCKET)');
  if (!accessKey || !secretKey) throw new Error('S3 needs AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY');
  const documents = [];
  const skipped = [];
  let token = '';
  while (documents.length < limit) {
    const locationForList = location({ bucket, region, endpoint });
    const query = [['list-type', '2'], ['max-keys', '100']];
    if (prefix) query.push(['prefix', prefix]);
    if (token) query.push(['continuation-token', token]);
    const listed = await signedRequest({
      method: 'GET', url: locationForList.url, path: locationForList.path, query, region,
      accessKey, secretKey, sessionToken,
    });
    if (!listed.ok) {
      throw new Error(`S3 list answered ${listed.status}: ${listed.text.slice(0, 200)}`);
    }
    const page = parseList(listed.text);
    for (const object of page.objects) {
      if (documents.length >= limit) break;
      const lower = object.key.toLowerCase();
      const suffix = TEXT_SUFFIXES.find((candidate) => lower.endsWith(candidate));
      if (!suffix) {
        if (lower.endsWith('.pdf')) skipped.push({ key: object.key, reason: 'PDF is a CLI/pdftotext path' });
        continue;
      }
      const objectLocation = location({ bucket, region, endpoint, key: object.key });
      const fetched = await signedRequest({
        method: 'GET', url: objectLocation.url, path: objectLocation.path, query: [], region,
        accessKey, secretKey, sessionToken,
      });
      if (!fetched.ok) {
        skipped.push({ key: object.key, reason: `HTTP ${fetched.status}` });
        continue;
      }
      const text = suffix === '.html' || suffix === '.htm'
        ? stripHtml(fetched.text)
        : fetched.text;
      if (!text.trim()) {
        skipped.push({ key: object.key, reason: 'no readable text' });
        continue;
      }
      documents.push({
        id: `s3:${bucket}/${object.key}`,
        title: object.key.split('/').pop() || object.key,
        text,
      });
    }
    if (!page.truncated || !page.next) break;
    token = page.next;
    await sleep(0);
  }
  return { documents, skipped };
}
