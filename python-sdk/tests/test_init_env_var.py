"""Behavior of ``arguslog.init()`` with respect to the ARGUSLOG_DSN env var.

The convention is shared across every server-side SDK (node, python, java) — using a
different name silently produces zero-event-flow, which is exactly the typo class the
fallback eliminates. Tests guard the three call shapes documented in the docstring.
"""

from __future__ import annotations

import pytest

import arguslog


@pytest.fixture(autouse=True)
def _isolate_singleton(monkeypatch: pytest.MonkeyPatch) -> None:
    # Reset the module-level client + scrub the env so tests don't leak into each other.
    monkeypatch.setattr(arguslog, "_client", None, raising=False)
    monkeypatch.delenv("ARGUSLOG_DSN", raising=False)


def test_init_with_no_args_reads_argus_dsn_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ARGUSLOG_DSN", "arguslog://k@localhost/api/1")
    client = arguslog.init()
    assert client is not None
    assert arguslog.get_client() is client


def test_init_with_no_args_raises_when_env_is_unset() -> None:
    with pytest.raises(ValueError) as exc:
        arguslog.init()
    assert "ARGUSLOG_DSN" in str(exc.value)


def test_init_with_no_args_raises_when_env_is_whitespace(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ARGUSLOG_DSN", "   ")
    with pytest.raises(ValueError):
        arguslog.init()


def test_explicit_dsn_string_wins_over_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ARGUSLOG_DSN", "arguslog://env@host/api/1")
    client = arguslog.init("arguslog://explicit@host/api/2")
    assert client._options.dsn == "arguslog://explicit@host/api/2"


def test_explicit_options_object_wins_over_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ARGUSLOG_DSN", "arguslog://env@host/api/1")
    client = arguslog.init(arguslog.ArguslogOptions(dsn="arguslog://explicit@host/api/3"))
    assert client._options.dsn == "arguslog://explicit@host/api/3"
