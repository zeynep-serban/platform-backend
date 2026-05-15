"""Unit tests for :mod:`etl_worker.db`.

Adım 12 PR-2b2b/2b3 — checkpoint + DB writer interface combined slice.
The DB writer is Protocol-only in this PR; tests use the in-process
:class:`NoopReportsDbWriter` and a hand-rolled fake that raises
:class:`ReportsDbWriteError` to exercise the failure contract.
"""

from __future__ import annotations

import pytest

from etl_worker.db import (
    NoopReportsDbWriter,
    ReportsDbWriteError,
    ReportsDbWriter,
    ReportsDbWriteResult,
)


def test_noop_writer_records_each_call() -> None:
    writer = NoopReportsDbWriter()

    result1 = writer.upsert({"table_count": 1})
    result2 = writer.upsert({"table_count": 2})

    assert isinstance(result1, ReportsDbWriteResult)
    assert result1.rows_written == 1
    assert result2.rows_written == 2
    assert writer.calls == [{"table_count": 1}, {"table_count": 2}]


def test_noop_writer_captures_shallow_copy() -> None:
    """The writer must record what was passed, not a mutating reference."""
    writer = NoopReportsDbWriter()
    summary = {"table_count": 1}

    writer.upsert(summary)
    summary["table_count"] = 99

    assert writer.calls[0] == {"table_count": 1}


def test_protocol_runtime_shape() -> None:
    """A class implementing ``upsert`` satisfies the Protocol at call time.

    Protocols are structural in Python so this is a runtime sanity
    check, not a static-type test.
    """

    class _MyWriter:
        def upsert(self, summary: dict[str, object]) -> ReportsDbWriteResult:
            assert "table_count" in summary
            return ReportsDbWriteResult(rows_written=0)

    writer: ReportsDbWriter = _MyWriter()
    result = writer.upsert({"table_count": 1})
    assert result.rows_written == 0


def test_write_error_is_an_exception() -> None:
    """``ReportsDbWriteError`` is a normal :class:`Exception` subclass."""

    def boom() -> None:
        raise ReportsDbWriteError("connection lost")

    with pytest.raises(ReportsDbWriteError, match="connection lost"):
        boom()


def test_write_result_is_immutable_dataclass() -> None:
    result = ReportsDbWriteResult(rows_written=42)
    with pytest.raises(AttributeError):
        result.rows_written = 100  # type: ignore[misc]
