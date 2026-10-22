"""Errors raised by the PhoenixGraph client."""
from __future__ import annotations

from typing import Any, Optional


class PhoenixGraphError(Exception):
    """The service answered with an error, or the client could not use the answer."""

    def __init__(
        self,
        message: str,
        *,
        status: Optional[int] = None,
        body: Any = None,
        request_id: Optional[str] = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status = status
        self.body = body
        self.request_id = request_id

    def __str__(self) -> str:
        details = []
        if self.status is not None:
            details.append(f"HTTP {self.status}")
        if self.request_id:
            details.append(f"request id {self.request_id}")
        return f"{self.message} [{', '.join(details)}]" if details else self.message


class PhoenixGraphConnectionError(PhoenixGraphError):
    """The service could not be reached at all (DNS, connection refused, timeout)."""
