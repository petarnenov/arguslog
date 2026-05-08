"""DSN parser kept in lockstep with packages/sdk-browser/src/dsn.ts and
java-sdk/src/main/java/org/arguslog/sdk/Dsn.java.

User-facing DSN format: arguslog://<publicKey>@<host>/api/<projectId>
"""

from __future__ import annotations

import re
from dataclasses import dataclass

_DSN_RE = re.compile(r"^arguslog://([^@]+)@([^/]+)/api/([^/?#]+)$")
_LOOPBACK_HOSTS = {"localhost", "0.0.0.0", "[::1]", "::1"}


class InvalidDsnError(ValueError):
    """Raised when a DSN string fails to match the expected shape."""


@dataclass(frozen=True)
class ParsedDsn:
    public_key: str
    host: str
    scheme: str
    project_id: str
    ingest_url: str


def parse_dsn(raw: str) -> ParsedDsn:
    if raw is None:
        raise InvalidDsnError("dsn is required")
    match = _DSN_RE.match(raw)
    if not match:
        raise InvalidDsnError(f"Invalid DSN: {raw}")
    public_key, host, project_id = match.group(1), match.group(2), match.group(3)
    if not public_key or not host or not project_id:
        raise InvalidDsnError(f"Invalid DSN: {raw}")
    scheme = "http" if _is_loopback(host) else "https"
    ingest_url = f"{scheme}://{host}/api/{project_id}/events"
    return ParsedDsn(public_key, host, scheme, project_id, ingest_url)


def _is_loopback(host: str) -> bool:
    bare = host[: host.index("]") + 1] if host.startswith("[") else host.split(":", 1)[0]
    return bare in _LOOPBACK_HOSTS or bare.startswith("127.")
