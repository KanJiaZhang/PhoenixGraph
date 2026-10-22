import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { PhoenixGraphClient, PhoenixGraphError } from '../dist/index.js';

const mock = async () => {
  const seen = [];
  const server = createServer((request, response) => {
    let body = '';
    request.on('data', (chunk) => { body += chunk; });
    request.on('end', () => {
      const parsed = body ? JSON.parse(body) : null;
      seen.push({ url: request.url, authorization: request.headers.authorization, body: parsed });
      if (request.url === '/api/v1/health') {
        response.writeHead(503, { 'content-type': 'application/json', 'x-request-id': 'req-1' });
        response.end(JSON.stringify({ error: 'not ready', hint: 'wait for the index' }));
        return;
      }
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ graph: parsed?.graph, question: parsed?.question, ok: true }));
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, seen, baseUrl: `http://127.0.0.1:${server.address().port}` };
};

test('the client sends the graph and the API key', async () => {
  const api = await mock();
  try {
    const client = new PhoenixGraphClient({ baseUrl: api.baseUrl, apiKey: 'secret' });
    const answer = await client.context('demo', 'who is related?');
    assert.equal(answer.graph, 'demo');
    assert.equal(answer.question, 'who is related?');
    assert.equal(api.seen[0].authorization, 'Bearer secret');
  } finally {
    api.server.close();
  }
});

test('HTTP errors keep the status, the body and the hint', async () => {
  const api = await mock();
  try {
    const client = new PhoenixGraphClient({ baseUrl: api.baseUrl });
    await assert.rejects(
      () => client.health(),
      (error) => {
        assert.ok(error instanceof PhoenixGraphError);
        assert.equal(error.status, 503);
        assert.equal(error.requestId, 'req-1');
        assert.match(error.message, /not ready/);
        assert.match(error.message, /wait for the index/);
        assert.deepEqual(error.body, { error: 'not ready', hint: 'wait for the index' });
        return true;
      },
    );
  } finally {
    api.server.close();
  }
});

test('ingest normalises documents and carries the rule pack', async () => {
  const api = await mock();
  try {
    const client = new PhoenixGraphClient(api.baseUrl);
    await client.ingest('demo', [{ id: 'd1', title: 'Doc', text: 'Alice fixed it.' }], {
      rulePack: 'incident',
      apply: false,
    });
    assert.deepEqual(api.seen[0].body, {
      graph: 'demo',
      documents: [{ id: 'd1', title: 'Doc', text: 'Alice fixed it.' }],
      apply: false,
      replace: false,
      dedupe: true,
      rulePack: 'incident',
    });
  } finally {
    api.server.close();
  }
});
