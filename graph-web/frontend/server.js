import express from 'express';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const app = express();

const PORT = Number(process.env.PORT || 8082);
const GRAPH_HTTP_URL = (process.env.GRAPH_HTTP_URL || 'http://localhost:8081').replace(/\/+$/, '');

app.use(express.json({ limit: '20mb' }));
app.use(express.static(path.join(__dirname, 'public')));
// Serve d3 locally (installed via npm) so the UI works without a CDN.
app.use('/vendor/d3', express.static(path.join(__dirname, 'node_modules', 'd3', 'dist')));

// Proxy the two graph-http endpoints the UI needs. This keeps the browser on a
// single origin (no CORS) and lets the UI be pointed at any graph-http node.
async function proxy(req, res, upstreamPath) {
  try {
    const upstream = await fetch(GRAPH_HTTP_URL + upstreamPath, {
      method: req.method,
      headers: { 'content-type': 'application/json' },
      body: req.method === 'GET' ? undefined : JSON.stringify(req.body),
    });
    const text = await upstream.text();
    res.status(upstream.status);
    res.set('content-type', upstream.headers.get('content-type') || 'application/json');
    res.send(text);
  } catch (err) {
    res.status(502).json({ error: 'graph-http unreachable', detail: String(err) });
  }
}

app.get('/api/graphs', (req, res) => proxy(req, res, '/api/v1/graphs'));
app.post('/api/query', (req, res) => proxy(req, res, '/api/v1/query'));

app.listen(PORT, () => {
  console.log(`PhoenixGraph web UI on http://localhost:${PORT}  (backend ${GRAPH_HTTP_URL})`);
});
