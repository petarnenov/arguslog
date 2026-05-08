# arguslog (Python SDK)

Arguslog SDK for Python 3.9+. Zero runtime dependencies — uses stdlib
`urllib` for transport and `threading.Thread` for the background sender.

## Install

```bash
pip install arguslog
```

## Quick start

```python
import arguslog

arguslog.init(
    "arguslog://<key>@<host>/api/<projectId>",
    environment="production",
    release="1.2.3",
)

try:
    do_something_risky()
except Exception as exc:
    arguslog.capture_exception(exc)

# Always flush before the process exits — short-lived scripts and CLIs
# will exit before the background sender drains otherwise.
arguslog.flush()
```

## Manual capture

```python
arguslog.capture_message("user signup completed", level="info")
arguslog.set_user({"id": "u-1234", "email": "alice@example.com"})  # email auto-scrubbed
arguslog.set_tag("region", "eu-west")
arguslog.set_context("order", {"id": 42, "total_cents": 9900})
arguslog.add_breadcrumb({"category": "nav", "message": "/cart"})
```

## Integrations

### Uncaught exceptions

```python
from arguslog.integrations.excepthook import install_excepthook

install_excepthook(arguslog.get_client())
```

The wrapper preserves the previous `sys.excepthook` (so debuggers,
IPython, etc. still work) and skips `KeyboardInterrupt`.

### Standard `logging` module

```python
import logging
from arguslog.integrations.logging import install_logging_handler

install_logging_handler(arguslog.get_client(), level=logging.ERROR)

logging.getLogger(__name__).error("payment failed", exc_info=True)
```

`ERROR`-or-higher records with `exc_info` become `capture_exception`;
everything else at or above the handler's level becomes
`capture_message`.

## CLI / script lifecycle

```python
try:
    main()
finally:
    arguslog.flush()   # or arguslog.close() if you want to tear down
```

Without a flush, a CLI that exits in <1s will likely terminate before
the background sender's first request leaves the socket.

## Wire format

The on-the-wire JSON shape is identical to `@arguslog/sdk-node` and
`org.arguslog:arguslog-java-sdk` so all events from a polyglot stack
land in the same Arguslog issue groups.
