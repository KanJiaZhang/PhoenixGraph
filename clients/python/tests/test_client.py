"""Tests for the Python client. They use a local HTTP server, so no service is needed."""
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from phoenixgraph import Document, PhoenixGraphClient, PhoenixGraphError

SEEN = []


class _Handler(BaseHTTPRequestHandler):
    server_version = "PhoenixGraphTest/1.0"

    def log_message(self, format, *args):  # keep the test output clean
        pass

    def _record(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        entry = {
            "method": self.command,
            "path": self.path,
            "headers": dict(self.headers.items()),
            "raw": raw,
            "body": json.loads(raw.decode("utf-8")) if raw else None,
        }
        SEEN.append(entry)
        return entry

    def _json(self, status, payload, headers=None):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _text(self, status, text):
        raw = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        self._record()
        if self.path == "/api/v1/graphs":
            self._json(200, [{"name": "agent-demo", "storage": "Aerospike"}])
        elif self.path == "/api/v1/health":
            self._json(200, {"status": "UP"})
        elif self.path == "/api/v1/metrics/prometheus":
            self._text(200, "phoenixgraph_up 1\n")
        elif self.path == "/api/v1/forbidden":
            self._json(403, {"error": "role says no"}, {"X-Request-Id": "req-42"})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        entry = self._record()
        if self.path == "/api/v1/agent/context":
            self._json(
                200,
                {
                    "tool": "context",
                    "graph": entry["body"]["graph"],
                    "entities": [],
                    "relations": [],
                    "citations": [],
                    "coverage": {"entities": 0, "relations": 0},
                },
            )
        elif self.path == "/api/v1/query":
            self._json(200, {"result": [], "subgraph": {"vertices": [], "edges": []}})
        elif self.path == "/api/v1/agent/ingest":
            self._json(
                200,
                {
                    "graph": entry["body"]["graph"],
                    "applied": entry["body"].get("apply", True),
                    "quality": {"provenanceRate": 1.0},
                    "entities": 2,
                    "relations": 1,
                },
            )
        elif self.path == "/api/v1/agent/search":
            self._json(200, {"tool": "search", "entities": [], "relations": [], "citations": []})
        elif self.path == "/api/v1/agent/path":
            self._json(200, {"tool": "path_between", "paths": []})
        elif self.path == "/api/v1/semantic/tpc-h-analytics/ask":
            self._json(200, {"mode": "auto", "refusal": "not answerable"})
        else:
            self._json(404, {"error": "not found"})


class ClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base_url = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def setUp(self):
        SEEN.clear()
        self.client = PhoenixGraphClient(self.base_url)

    def last(self, path):
        matches = [entry for entry in SEEN if entry["path"] == path]
        self.assertTrue(matches, f"no request to {path}; saw {[e['path'] for e in SEEN]}")
        return matches[-1]

    def test_graphs(self):
        graphs = self.client.graphs()
        self.assertEqual(graphs[0]["name"], "agent-demo")
        entry = self.last("/api/v1/graphs")
        self.assertEqual(entry["method"], "GET")

    def test_context_sends_bearer_and_utf8(self):
        client = PhoenixGraphClient(self.base_url, api_key="secret")
        answer = client.context("agent-demo", "诸葛亮和司马懿是什么关系")
        self.assertEqual(answer["tool"], "context")
        entry = self.last("/api/v1/agent/context")
        self.assertEqual(entry["headers"].get("Authorization"), "Bearer secret")
        self.assertEqual(
            entry["body"],
            {"graph": "agent-demo", "question": "诸葛亮和司马懿是什么关系", "k": 5, "hops": 1},
        )

    def test_query_flags(self):
        self.client.query("payment-graph", "g.V(135,'Account')", return_subgraph=True)
        body = self.last("/api/v1/query")["body"]
        self.assertEqual(body["graphName"], "payment-graph")
        self.assertEqual(body["language"], "gremlin")
        self.assertTrue(body["returnSubgraph"])
        self.assertTrue(body["includeResult"])

    def test_ingest_accepts_documents(self):
        result = self.client.ingest(
            "my-graph",
            [Document(id="doc-1", text="hello", title="Doc")],
            apply=False,
            rule_pack="incident",
        )
        self.assertEqual(result["graph"], "my-graph")
        body = self.last("/api/v1/agent/ingest")["body"]
        self.assertEqual(body["documents"], [{"id": "doc-1", "title": "Doc", "text": "hello"}])
        self.assertFalse(body["apply"])
        self.assertEqual(body["rulePack"], "incident")

    def test_search_uses_the_hybrid_tool(self):
        answer = self.client.search("agent-demo", "why did the outage happen", k=3)
        self.assertEqual(answer["tool"], "search")
        body = self.last("/api/v1/agent/search")["body"]
        self.assertEqual(body, {"graph": "agent-demo", "question": "why did the outage happen", "k": 3})

    def test_path_between_uses_from_and_to(self):
        self.client.path_between("agent-demo", "A", "B", max_hops=2)
        body = self.last("/api/v1/agent/path")["body"]
        self.assertEqual(body["from"], "A")
        self.assertEqual(body["to"], "B")
        self.assertEqual(body["maxHops"], 2)

    def test_error_carries_status_and_request_id(self):
        with self.assertRaises(PhoenixGraphError) as raised:
            self.client.request("GET", "/api/v1/forbidden")
        error = raised.exception
        self.assertEqual(error.status, 403)
        self.assertEqual(error.request_id, "req-42")
        self.assertIn("role", str(error))

    def test_prometheus_returns_text(self):
        self.assertIn("phoenixgraph_up", self.client.prometheus())

    def test_ask_data_returns_refusal(self):
        answer = self.client.ask_data("tpc-h-analytics", "1996 年各退货标志营收")
        self.assertIn("refusal", answer)

    def test_document_requires_text(self):
        with self.assertRaises(ValueError):
            self.client.ingest("g", [Document(id="x", text="")])


if __name__ == "__main__":
    unittest.main()
