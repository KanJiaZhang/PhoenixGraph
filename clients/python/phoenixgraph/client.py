"""A zero-dependency Python client for the PhoenixGraph HTTP API.

The client mirrors the API rather than hiding it: every method has a ``request`` escape hatch, and the
answers are the JSON documents the service returns. That is deliberate - an agent or a notebook should be
able to print the same citation and coverage fields the service produced.
"""
from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Union

from .errors import PhoenixGraphConnectionError, PhoenixGraphError

__all__ = ["Document", "PhoenixGraphClient"]


@dataclass(frozen=True)
class Document:
    """One document to ingest."""

    id: str
    text: str
    title: str = ""

    def to_dict(self) -> Dict[str, str]:
        return {"id": self.id, "title": self.title, "text": self.text}


DocumentLike = Union[Document, Mapping[str, Any]]


def _normalise_documents(documents: Sequence[DocumentLike]) -> List[Dict[str, str]]:
    if not documents:
        raise ValueError("ingest needs at least one document")
    normalised: List[Dict[str, str]] = []
    for document in documents:
        if isinstance(document, Document):
            item = document.to_dict()
        elif isinstance(document, Mapping):
            item = dict(document)
        else:
            raise TypeError("a document must be a Document or a mapping with id/title/text")
        if not item.get("id"):
            raise ValueError("a document needs an id")
        if not item.get("text"):
            raise ValueError(f"document {item.get('id')!r} has no text")
        normalised.append(
            {
                "id": str(item["id"]),
                "title": str(item.get("title") or ""),
                "text": str(item["text"]),
            }
        )
    return normalised


class PhoenixGraphClient:
    """Talk to a running PhoenixGraph service.

    >>> client = PhoenixGraphClient("http://localhost:8081")
    >>> answer = client.context("agent-demo", "诸葛亮和司马懿是什么关系")
    >>> answer["coverage"]["relations"]
    6
    """

    _TOOL_PATHS = {
        "find_entities": "/api/v1/agent/find-entities",
        "search": "/api/v1/agent/search",
        "related": "/api/v1/agent/related",
        "similar": "/api/v1/agent/similar",
        "related_similar": "/api/v1/agent/related-similar",
        "similar_related": "/api/v1/agent/similar-related",
        "path_between": "/api/v1/agent/path",
        "context": "/api/v1/agent/context",
    }

    def __init__(
        self,
        base_url: str = "http://localhost:8081",
        *,
        api_key: Optional[str] = None,
        timeout: float = 30.0,
        headers: Optional[Mapping[str, str]] = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout
        self._headers: Dict[str, str] = {
            "Accept": "application/json",
            "User-Agent": "phoenixgraph-python/0.1.0",
        }
        if headers:
            self._headers.update(headers)

    # -- transport ---------------------------------------------------------------

    def _url(self, path: str, params: Optional[Mapping[str, Any]] = None) -> str:
        if not path.startswith("/"):
            path = "/" + path
        if params:
            clean: Dict[str, Any] = {}
            for key, value in params.items():
                if value is None:
                    continue
                clean[key] = str(value).lower() if isinstance(value, bool) else value
            if clean:
                path = path + "?" + urllib.parse.urlencode(clean)
        return self.base_url + path

    def _request(
        self,
        method: str,
        path: str,
        body: Optional[Mapping[str, Any]] = None,
        params: Optional[Mapping[str, Any]] = None,
    ) -> Any:
        url = self._url(path, params)
        headers = dict(self._headers)
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(url, data=data, headers=headers, method=method.upper())
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                return self._decode(response.read())
        except urllib.error.HTTPError as error:
            raw = error.read()
            payload = self._decode(raw)
            message = f"PhoenixGraph answered HTTP {error.code}"
            hint = None
            if isinstance(payload, Mapping):
                message = str(payload.get("error") or payload.get("message") or message)
                hint = payload.get("hint")
            elif raw:
                message = raw.decode("utf-8", "replace").strip() or message
            if hint:
                message = f"{message} ({hint})"
            raise PhoenixGraphError(
                message,
                status=error.code,
                body=payload,
                request_id=error.headers.get("X-Request-Id") if error.headers else None,
            ) from None
        except urllib.error.URLError as error:
            raise PhoenixGraphConnectionError(
                f"Cannot reach PhoenixGraph at {self.base_url}: {error.reason}"
            ) from error
        except TimeoutError as error:
            raise PhoenixGraphConnectionError(
                f"PhoenixGraph at {self.base_url} did not answer within {self.timeout:g}s"
            ) from error

    @staticmethod
    def _decode(raw: bytes) -> Any:
        if not raw:
            return None
        try:
            return json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return raw.decode("utf-8", "replace")

    def request(
        self,
        method: str,
        path: str,
        body: Optional[Mapping[str, Any]] = None,
        params: Optional[Mapping[str, Any]] = None,
    ) -> Any:
        """Call any endpoint. The escape hatch for endpoints not wrapped below."""
        return self._request(method, path, body, params)

    # -- service -----------------------------------------------------------------

    def health(self) -> Any:
        return self.request("GET", "/api/v1/health")

    def graphs(self) -> Any:
        return self.request("GET", "/api/v1/graphs")

    def metrics(self, graph: Optional[str] = None) -> Any:
        path = "/api/v1/metrics" if graph is None else f"/api/v1/metrics/{urllib.parse.quote(graph, safe='')}"
        return self.request("GET", path)

    def prometheus(self) -> str:
        return str(self.request("GET", "/api/v1/metrics/prometheus"))

    def query(
        self,
        graph: str,
        query: str,
        *,
        language: str = "gremlin",
        params: Optional[Mapping[str, str]] = None,
        include_result: bool = True,
        return_subgraph: bool = False,
        source: Optional[str] = None,
        script: Optional[bool] = None,
    ) -> Any:
        body: Dict[str, Any] = {
            "graphName": graph,
            "query": query,
            "language": language,
            "includeResult": include_result,
            "returnSubgraph": return_subgraph,
        }
        if params:
            body["params"] = dict(params)
        if source is not None:
            body["source"] = source
        if script is not None:
            body["script"] = script
        return self.request("POST", "/api/v1/query", body)

    # -- ingestion and tools -----------------------------------------------------

    def ingest(
        self,
        graph: str,
        documents: Sequence[DocumentLike],
        *,
        dictionary: Optional[Mapping[str, str]] = None,
        aliases: Optional[Mapping[str, str]] = None,
        gold: Optional[Sequence[str]] = None,
        apply: bool = True,
        replace: bool = False,
        description: Optional[str] = None,
        rule_pack: Optional[str] = None,
        dedupe: bool = True,
    ) -> Any:
        body: Dict[str, Any] = {
            "graph": graph,
            "documents": _normalise_documents(documents),
            "apply": apply,
            "replace": replace,
            "dedupe": dedupe,
        }
        if description is not None:
            body["description"] = description
        if rule_pack is not None:
            body["rulePack"] = rule_pack
        if dictionary:
            body["dictionary"] = dict(dictionary)
        if aliases:
            body["aliases"] = dict(aliases)
        if gold:
            body["gold"] = list(gold)
        return self.request("POST", "/api/v1/agent/ingest", body)

    def quality(self, graph: str) -> Any:
        return self.request("GET", f"/api/v1/agent/quality/{urllib.parse.quote(graph, safe='')}")

    def index(self, graph: str, *, vectors: bool = False) -> Any:
        return self.request(
            "GET",
            f"/api/v1/agent/index/{urllib.parse.quote(graph, safe='')}",
            params={"vectors": vectors},
        )

    def tools(self) -> Any:
        return self.request("GET", "/api/v1/agent/tools")

    def _tool(self, tool: str, graph: str, **fields: Any) -> Any:
        if not graph:
            raise ValueError("a tool call needs a graph")
        body: Dict[str, Any] = {"graph": graph}
        body.update({key: value for key, value in fields.items() if value is not None})
        return self.request("POST", self._TOOL_PATHS[tool], body)

    def find_entities(self, graph: str, question: str, *, k: int = 5) -> Any:
        return self._tool("find_entities", graph, question=question, k=k)

    def search(self, graph: str, question: str, *, k: int = 5) -> Any:
        """Hybrid recall: name, description terms and vector in one ranking."""
        return self._tool("search", graph, question=question, k=k)

    def related(
        self,
        graph: str,
        entity: str,
        *,
        relation: Optional[str] = None,
        hops: int = 1,
    ) -> Any:
        return self._tool("related", graph, entity=entity, relation=relation, hops=hops)

    def similar(self, graph: str, entity: str, *, k: int = 5) -> Any:
        return self._tool("similar", graph, entity=entity, k=k)

    def related_similar(self, graph: str, entity: str, *, k: int = 3) -> Any:
        return self._tool("related_similar", graph, entity=entity, k=k)

    def similar_related(self, graph: str, entity: str, *, k: int = 3) -> Any:
        return self._tool("similar_related", graph, entity=entity, k=k)

    def path_between(
        self,
        graph: str,
        source_entity: str,
        target_entity: str,
        *,
        max_hops: int = 3,
    ) -> Any:
        return self._tool(
            "path_between",
            graph,
            **{"from": source_entity, "to": target_entity, "maxHops": max_hops},
        )

    def context(self, graph: str, question: str, *, k: int = 5, hops: int = 1) -> Any:
        return self._tool("context", graph, question=question, k=k, hops=hops)

    # -- semantic layer and data tools ------------------------------------------

    def semantic_model(self, graph: str, *, data_graph: Optional[str] = None) -> Any:
        return self.request(
            "GET",
            f"/api/v1/semantic/{urllib.parse.quote(graph, safe='')}/model",
            params={"dataGraph": data_graph},
        )

    @staticmethod
    def _semantic_body(
        metric: str,
        dimensions: Optional[Sequence[str]] = None,
        grain: Optional[str] = None,
        dialect: Optional[str] = None,
        filters: Optional[Sequence[Mapping[str, Any]]] = None,
        order: Optional[Sequence[Mapping[str, Any]]] = None,
        limit: Optional[int] = None,
        data_graph: Optional[str] = None,
    ) -> Dict[str, Any]:
        body: Dict[str, Any] = {"metric": metric}
        if dimensions is not None:
            body["dimensions"] = list(dimensions)
        if grain is not None:
            body["grain"] = grain
        if dialect is not None:
            body["dialect"] = dialect
        if filters is not None:
            body["filters"] = list(filters)
        if order is not None:
            body["order"] = list(order)
        if limit is not None:
            body["limit"] = limit
        if data_graph is not None:
            body["dataGraph"] = data_graph
        return body

    def semantic_translate(
        self,
        graph: str,
        metric: str,
        *,
        dimensions: Optional[Sequence[str]] = None,
        grain: Optional[str] = None,
        dialect: Optional[str] = None,
        filters: Optional[Sequence[Mapping[str, Any]]] = None,
        order: Optional[Sequence[Mapping[str, Any]]] = None,
        limit: Optional[int] = None,
        data_graph: Optional[str] = None,
    ) -> Any:
        body = self._semantic_body(metric, dimensions, grain, dialect, filters, order, limit, data_graph)
        return self.request("POST", f"/api/v1/semantic/{urllib.parse.quote(graph, safe='')}/translate", body)

    def semantic_validate(
        self,
        graph: str,
        metric: str,
        *,
        dimensions: Optional[Sequence[str]] = None,
        grain: Optional[str] = None,
        dialect: Optional[str] = None,
        filters: Optional[Sequence[Mapping[str, Any]]] = None,
        order: Optional[Sequence[Mapping[str, Any]]] = None,
        limit: Optional[int] = None,
        data_graph: Optional[str] = None,
    ) -> Any:
        body = self._semantic_body(metric, dimensions, grain, dialect, filters, order, limit, data_graph)
        return self.request("POST", f"/api/v1/semantic/{urllib.parse.quote(graph, safe='')}/validate", body)

    def ask_data(
        self,
        graph: str,
        question: str,
        *,
        mode: str = "auto",
        confirm_token: Optional[str] = None,
        data_graph: Optional[str] = None,
    ) -> Any:
        body: Dict[str, Any] = {"question": question, "mode": mode}
        if confirm_token is not None:
            body["confirmToken"] = confirm_token
        if data_graph is not None:
            body["dataGraph"] = data_graph
        return self.request("POST", f"/api/v1/semantic/{urllib.parse.quote(graph, safe='')}/ask", body)

    def analyze_business(
        self,
        graph: str,
        *,
        question: Optional[str] = None,
        metric: Optional[str] = None,
        dimensions: Optional[Sequence[str]] = None,
        periods: Optional[Sequence[str]] = None,
        mode: str = "auto",
        confirm_token: Optional[str] = None,
        data_graph: Optional[str] = None,
    ) -> Any:
        body: Dict[str, Any] = {"mode": mode}
        if question is not None:
            body["question"] = question
        if metric is not None:
            body["metric"] = metric
        if dimensions is not None:
            body["dimensions"] = list(dimensions)
        if periods is not None:
            body["periods"] = list(periods)
        if confirm_token is not None:
            body["confirmToken"] = confirm_token
        if data_graph is not None:
            body["dataGraph"] = data_graph
        return self.request("POST", f"/api/v1/analysis/{urllib.parse.quote(graph, safe='')}/analyze", body)

    def attribute_change(
        self,
        graph: str,
        *,
        question: Optional[str] = None,
        metric: Optional[str] = None,
        by: Optional[Sequence[str]] = None,
        from_period: Optional[str] = None,
        to_period: Optional[str] = None,
        mode: str = "auto",
        confirm_token: Optional[str] = None,
        data_graph: Optional[str] = None,
    ) -> Any:
        body: Dict[str, Any] = {"mode": mode}
        if question is not None:
            body["question"] = question
        if metric is not None:
            body["metric"] = metric
        if by is not None:
            body["by"] = list(by)
        if from_period is not None:
            body["from"] = from_period
        if to_period is not None:
            body["to"] = to_period
        if confirm_token is not None:
            body["confirmToken"] = confirm_token
        if data_graph is not None:
            body["dataGraph"] = data_graph
        return self.request("POST", f"/api/v1/analysis/{urllib.parse.quote(graph, safe='')}/attribute", body)

    # -- lifecycle ----------------------------------------------------------------

    def close(self) -> None:
        """There is no persistent connection; this exists for context-manager symmetry."""

    def __enter__(self) -> "PhoenixGraphClient":
        return self

    def __exit__(self, *_: Any) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"PhoenixGraphClient(base_url={self.base_url!r})"
