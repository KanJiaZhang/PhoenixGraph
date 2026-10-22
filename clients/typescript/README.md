# @phoenixgraph/client

A zero-dependency TypeScript client for the PhoenixGraph HTTP API. It mirrors the API instead of hiding
it, so the JSON documents with citations, coverage and quality are returned unchanged.

## Install from this repository

The package is not published to npm yet. Build it from the repository, then install the local path (or
import `dist/index.js` directly):

```bash
cd clients/typescript
npm install
npm run build
npm install /path/to/PhoenixGraph/clients/typescript
```

Node 18+ is required (it uses the built-in `fetch`). A browser or another runtime can pass its own
implementation with `fetch`.

## Use

```ts
import { PhoenixGraphClient } from '@phoenixgraph/client';

const client = new PhoenixGraphClient({
  baseUrl: 'http://localhost:8081',
  apiKey: process.env.PHOENIXGRAPH_API_KEY, // only when security is enabled
});

const answer = await client.context('agent-demo', '诸葛亮和司马懿是什么关系');
console.log(answer.coverage);

const found = await client.search('agent-demo', '街亭', 5);
const sql = await client.semanticTranslate('tpc-h-analytics', 'metric/revenue', {
  dimensions: ['dim/region'],
});
```

Every method has an escape hatch:

```ts
const raw = await client.request('GET', '/api/v1/agent/quality/agent-demo');
```

Errors are typed: `PhoenixGraphError` carries `status`, `body` and `requestId`;
`PhoenixGraphConnectionError` means the service could not be reached or timed out.

## Build and test

```bash
npm install
npm run build
npm test
```

`npm test` runs against a local mock HTTP server; no running PhoenixGraph instance is required.
