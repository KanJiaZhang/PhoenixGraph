"""PhoenixGraph Python client.

    from phoenixgraph import PhoenixGraphClient

    client = PhoenixGraphClient("http://localhost:8081")
    answer = client.context("agent-demo", "诸葛亮和司马懿是什么关系")
    print(answer["coverage"])

The client has no runtime dependencies; it uses the standard library only.
"""
from .client import Document, PhoenixGraphClient
from .errors import PhoenixGraphConnectionError, PhoenixGraphError

__all__ = [
    "Document",
    "PhoenixGraphClient",
    "PhoenixGraphConnectionError",
    "PhoenixGraphError",
]

__version__ = "0.1.0"
