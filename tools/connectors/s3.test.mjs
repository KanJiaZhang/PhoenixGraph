import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { s3Documents } from './s3.mjs';

test('s3 lists and reads text objects with a signed request', async () => {
  let authorization = '';
  const server = createServer((request, response) => {
    authorization = request.headers.authorization || authorization;
    if (request.url.startsWith('/bucket/') && !request.url.startsWith('/bucket/docs/')) {
      response.writeHead(200, { 'content-type': 'application/xml' });
      response.end('<?xml version="1.0"?><ListBucketResult>'
        + '<Contents><Key>docs/a.md</Key><Size>10</Size></Contents>'
        + '<IsTruncated>false</IsTruncated></ListBucketResult>');
      return;
    }
    if (request.url.startsWith('/bucket/docs/a.md')) {
      response.writeHead(200, { 'content-type': 'text/markdown' });
      response.end('Deploy 42 caused the outage.');
      return;
    }
    response.writeHead(404);
    response.end();
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const { documents } = await s3Documents({
      bucket: 'bucket',
      endpoint: `http://127.0.0.1:${server.address().port}`,
      accessKey: 'AKIDEXAMPLE',
      secretKey: 'secret',
    });
    assert.equal(documents.length, 1);
    assert.equal(documents[0].title, 'a.md');
    assert.match(documents[0].text, /Deploy 42 caused the outage/);
    assert.match(authorization, /^AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE\//);
  } finally {
    server.close();
  }
});
