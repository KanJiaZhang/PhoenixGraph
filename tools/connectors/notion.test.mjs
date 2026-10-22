import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { notionDocuments } from './notion.mjs';

test('notion pages and blocks become documents', async () => {
  const server = createServer((request, response) => {
    if (request.url === '/v1/search') {
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({
        results: [{
          id: 'page-1',
          properties: { Name: { type: 'title', title: [{ plain_text: 'Checkout incident' }] } },
        }],
        has_more: false,
        next_cursor: null,
      }));
      return;
    }
    if (request.url.startsWith('/v1/blocks/page-1/children')) {
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({
        results: [{
          id: 'block-1',
          type: 'paragraph',
          paragraph: { rich_text: [{ plain_text: 'Deploy 42 caused the outage.' }] },
          has_children: false,
        }],
        has_more: false,
        next_cursor: null,
      }));
      return;
    }
    response.writeHead(404);
    response.end();
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const documents = await notionDocuments({
      token: 'secret',
      baseUrl: `http://127.0.0.1:${server.address().port}/v1`,
    });
    assert.equal(documents.length, 1);
    assert.equal(documents[0].title, 'Checkout incident');
    assert.match(documents[0].text, /Deploy 42 caused the outage/);
  } finally {
    server.close();
  }
});
