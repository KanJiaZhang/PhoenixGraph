import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { confluenceDocuments } from './confluence.mjs';

test('confluence pages become documents', async () => {
  let authorization = null;
  const server = createServer((request, response) => {
    authorization = request.headers.authorization;
    if (request.url.startsWith('/rest/api/content')) {
      const payload = {
        results: [{
          id: '42',
          title: 'Checkout incident',
          body: { storage: { value: '<p>Deploy 42 caused the outage.</p><p>Alice fixed it.</p>' } },
        }],
        _links: {},
      };
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify(payload));
      return;
    }
    response.writeHead(404);
    response.end();
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const documents = await confluenceDocuments({
      baseUrl: `http://127.0.0.1:${server.address().port}`,
      space: 'ENG',
      email: 'me@example.com',
      token: 'secret',
    });
    assert.equal(documents.length, 1);
    assert.equal(documents[0].id, 'confluence:42');
    assert.equal(documents[0].title, 'Checkout incident');
    assert.match(documents[0].text, /Deploy 42 caused the outage/);
    assert.equal(authorization, `Basic ${Buffer.from('me@example.com:secret').toString('base64')}`);
  } finally {
    server.close();
  }
});
