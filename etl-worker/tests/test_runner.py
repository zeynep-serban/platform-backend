"""Unit tests for :func:`etl_worker.runner.run_fetch`.

Adım 12 PR-2b1 — runner retry foundation. PR-2b2a extends this module
with audit-emission coverage. The runner is exercised via a fake
:class:`SchemaServiceClient` so no live HTTP fires.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from etl_worker.audit import AuditEvent
from etl_worker.contracts import ColumnSpec, SchemaSnapshot, TableSpec
from etl_worker.retry import RetryPolicy
from etl_worker.runner import RunResult, run_fetch
from etl_worker.schema_service_client import (
    SchemaContractVersionMismatch,
    SchemaServiceMalformedResponse,
    SchemaServiceUnavailable,
)


class _RecordingAuditWriter:
    """In-memory ``AuditWriter`` used by tests to capture event sequences."""

    def __init__(self) -> None:
        self.events: list[AuditEvent] = []

    def write(self, event: AuditEvent) -> None:
        self.events.append(event)

    def names(self) -> list[str]:
        return [event.event for event in self.events]


class _FakeSleeper:
    """Records each requested delay; never blocks."""

    def __init__(self) -> None:
        self.delays: list[float] = []

    def sleep(self, seconds: float) -> None:
        self.delays.append(seconds)


class _FakeClient:
    """Scripted client returning a snapshot or raising scripted errors."""

    def __init__(
        self,
        *,
        responses: list[SchemaSnapshot | Exception] | None = None,
    ) -> None:
        # Default: one success response
        self._responses: list[SchemaSnapshot | Exception] = (
            responses if responses is not None else [_good_snapshot()]
        )
        self.fetch_calls: list[str | None] = []

    def fetch_snapshot(self, *, schema: str | None = None) -> SchemaSnapshot:
        self.fetch_calls.append(schema)
        if not self._responses:
            raise RuntimeError("No more scripted responses")
        next_value = self._responses.pop(0)
        if isinstance(next_value, Exception):
            raise next_value
        return next_value


def _good_snapshot() -> SchemaSnapshot:
    return SchemaSnapshot(
        contract_version="1",
        allowlist_name="ReportingAllowlist",
        allowlist_version="V1",
        tables=(
            TableSpec(
                schema="dbo",
                name="EMPLOYEES",
                columns=(
                    ColumnSpec(name="EMPLOYEE_ID", type="int", nullable=False),
                    ColumnSpec(name="NAME", type="nvarchar(255)", nullable=True),
                ),
            ),
            TableSpec(
                schema="dbo",
                name="COMPANY",
                columns=(ColumnSpec(name="COMPANY_ID", type="int", nullable=False),),
            ),
        ),
    )


# ---- success paths -------------------------------------------------------


def test_first_attempt_success_summary_includes_attempts_one() -> None:
    client = _FakeClient(responses=[_good_snapshot()])
    sleeper = _FakeSleeper()

    result = run_fetch(
        client=client,  # type: ignore[arg-type]
        schema=None,
        policy=RetryPolicy(max_attempts=3),
        sleeper=sleeper,
    )

    assert isinstance(result, RunResult)
    assert result.attempts == 1
    assert result.summary == {
        "contract_version": "1",
        "allowlist_name": "ReportingAllowlist",
        "allowlist_version": "V1",
        "table_count": 2,
        "column_count": 3,
    }
    assert sleeper.delays == []
    assert client.fetch_calls == [None]


def test_schema_arg_is_forwarded_to_client() -> None:
    client = _FakeClient(responses=[_good_snapshot()])

    run_fetch(
        client=client,  # type: ignore[arg-type]
        schema="workcube_mikrolink_2025",
        policy=RetryPolicy(max_attempts=1),
        sleeper=_FakeSleeper(),
    )

    assert client.fetch_calls == ["workcube_mikrolink_2025"]


def test_transient_then_success_records_two_attempts_and_one_sleep() -> None:
    client = _FakeClient(
        responses=[
            SchemaServiceUnavailable("upstream 503"),
            _good_snapshot(),
        ]
    )
    sleeper = _FakeSleeper()

    result = run_fetch(
        client=client,  # type: ignore[arg-type]
        schema=None,
        policy=RetryPolicy(max_attempts=3, initial_seconds=0.5, multiplier=2.0),
        sleeper=sleeper,
    )

    assert result.attempts == 2
    assert sleeper.delays == [0.5]
    assert len(client.fetch_calls) == 2


# ---- failure paths -------------------------------------------------------


def test_transient_exhausted_raises_unavailable() -> None:
    client = _FakeClient(
        responses=[
            SchemaServiceUnavailable("attempt 1"),
            SchemaServiceUnavailable("attempt 2"),
            SchemaServiceUnavailable("attempt 3"),
        ]
    )
    sleeper = _FakeSleeper()

    with pytest.raises(SchemaServiceUnavailable, match="attempt 3"):
        run_fetch(
            client=client,  # type: ignore[arg-type]
            schema=None,
            policy=RetryPolicy(max_attempts=3, initial_seconds=0.1),
            sleeper=sleeper,
        )

    assert len(client.fetch_calls) == 3
    assert len(sleeper.delays) == 2  # sleeps before attempts 2 and 3


def test_malformed_response_is_not_retried() -> None:
    client = _FakeClient(
        responses=[SchemaServiceMalformedResponse("4xx body")]
    )
    sleeper = _FakeSleeper()

    with pytest.raises(SchemaServiceMalformedResponse):
        run_fetch(
            client=client,  # type: ignore[arg-type]
            schema=None,
            policy=RetryPolicy(max_attempts=5),
            sleeper=sleeper,
        )

    assert len(client.fetch_calls) == 1
    assert sleeper.delays == []


def test_contract_version_mismatch_is_not_retried() -> None:
    client = _FakeClient(
        responses=[SchemaContractVersionMismatch("'2' not in ('1',)")]
    )
    sleeper = _FakeSleeper()

    with pytest.raises(SchemaContractVersionMismatch):
        run_fetch(
            client=client,  # type: ignore[arg-type]
            schema=None,
            policy=RetryPolicy(max_attempts=5),
            sleeper=sleeper,
        )

    assert len(client.fetch_calls) == 1
    assert sleeper.delays == []


def test_max_attempts_one_means_single_try_and_propagates() -> None:
    """``max_attempts=1`` collapses runner to a single try; first transient failure propagates."""
    client = _FakeClient(responses=[SchemaServiceUnavailable("503")])
    sleeper = _FakeSleeper()

    with pytest.raises(SchemaServiceUnavailable):
        run_fetch(
            client=client,  # type: ignore[arg-type]
            schema=None,
            policy=RetryPolicy(max_attempts=1, initial_seconds=99.0, cap_seconds=99.0),
            sleeper=sleeper,
        )

    assert len(client.fetch_calls) == 1
    assert sleeper.delays == []


# ---- run_id propagation --------------------------------------------------


def test_run_id_caller_supplied_propagates_to_result() -> None:
    client = _FakeClient(responses=[_good_snapshot()])

    result = run_fetch(
        client=client,  # type: ignore[arg-type]
        schema=None,
        policy=RetryPolicy(max_attempts=1),
        sleeper=_FakeSleeper(),
        run_id="caller-supplied-id-42",
    )

    assert result.run_id == "caller-supplied-id-42"


def test_run_id_autogenerated_when_omitted() -> None:
    client = _FakeClient(responses=[_good_snapshot()])

    result = run_fetch(
        client=client,  # type: ignore[arg-type]
        schema=None,
        policy=RetryPolicy(max_attempts=1),
        sleeper=_FakeSleeper(),
    )

    assert result.run_id  # non-empty
    assert len(result.run_id) >= 16  # uuid4 hex is 32 chars


# ---- audit emission ------------------------------------------------------


def test_audit_no_op_when_writer_absent() -> None:
    """Backward compat: PR-2b1 callers (no ``audit`` arg) see no observable change."""
    client = _FakeClient(responses=[_good_snapshot()])

    result = run_fetch(
        client=client,  # type: ignore[arg-type]
        schema=None,
        policy=RetryPolicy(max_attempts=3),
        sleeper=_FakeSleeper(),
        # audit=None (default)
    )

    assert isinstance(result, RunResult)
    # No audit writer = no side effect; the only observable is the result.


def test_audit_success_event_sequence() -> None:
    client = _FakeClient(responses=[_good_snapshot()])
    audit = _RecordingAuditWriter()

    run_fetch(
        client=client,  # type: ignore[arg-type]
        schema=None,
        policy=RetryPolicy(max_attempts=3),
        sleeper=_FakeSleeper(),
        audit=audit,
        run_id="run-success-1",
    )

    assert audit.names() == [
        "run_started",
        "attempt_started",
        "attempt_succeeded",
        "run_succeeded",
    ]
    assert all(event.run_id == "run-success-1" for event in audit.events)
    # The final run_succeeded carries the summary + attempts.
    final = audit.events[-1]
    assert final.outcome == "success"
    assert final.summary is not None
    assert final.summary["attempts"] == 1
    assert final.summary["table_count"] == 2


def test_audit_transient_then_success_event_sequence() -> None:
    client = _FakeClient(
        responses=[
            SchemaServiceUnavailable("503-1"),
            _good_snapshot(),
        ]
    )
    audit = _RecordingAuditWriter()

    run_fetch(
        client=client,  # type: ignore[arg-type]
        schema=None,
        policy=RetryPolicy(max_attempts=3, initial_seconds=0.1),
        sleeper=_FakeSleeper(),
        audit=audit,
    )

    assert audit.names() == [
        "run_started",
        "attempt_started",
        "attempt_failed",
        "attempt_started",
        "attempt_succeeded",
        "run_succeeded",
    ]
    # The retryable failure event records the error class for grouping.
    failure_event = audit.events[2]
    assert failure_event.outcome == "retryable_failure"
    assert failure_event.error_class == "SchemaServiceUnavailable"
    assert failure_event.attempt == 1


def test_audit_exhausted_retry_event_sequence() -> None:
    client = _FakeClient(
        responses=[
            SchemaServiceUnavailable("503-1"),
            SchemaServiceUnavailable("503-2"),
        ]
    )
    audit = _RecordingAuditWriter()

    with pytest.raises(SchemaServiceUnavailable):
        run_fetch(
            client=client,  # type: ignore[arg-type]
            schema=None,
            policy=RetryPolicy(max_attempts=2, initial_seconds=0.1),
            sleeper=_FakeSleeper(),
            audit=audit,
        )

    # Two attempts; the second is the final and surfaces as
    # retryable_exhausted on both the attempt_failed and run_failed
    # events.
    assert audit.names() == [
        "run_started",
        "attempt_started",
        "attempt_failed",
        "attempt_started",
        "attempt_failed",
        "run_failed",
    ]
    run_failed = audit.events[-1]
    assert run_failed.outcome == "retryable_exhausted"
    assert run_failed.error_class == "SchemaServiceUnavailable"


def test_audit_malformed_response_event_sequence() -> None:
    client = _FakeClient(
        responses=[SchemaServiceMalformedResponse("4xx body")]
    )
    audit = _RecordingAuditWriter()

    with pytest.raises(SchemaServiceMalformedResponse):
        run_fetch(
            client=client,  # type: ignore[arg-type]
            schema=None,
            policy=RetryPolicy(max_attempts=5),
            sleeper=_FakeSleeper(),
            audit=audit,
        )

    # Terminal failure on attempt 1 → no retry → run_failed
    # immediately.
    assert audit.names() == [
        "run_started",
        "attempt_started",
        "attempt_failed",
        "run_failed",
    ]
    run_failed = audit.events[-1]
    assert run_failed.outcome == "terminal_failure"
    assert run_failed.error_class == "SchemaServiceMalformedResponse"


def test_audit_contract_mismatch_event_sequence() -> None:
    client = _FakeClient(
        responses=[SchemaContractVersionMismatch("'2' not in ('1',)")]
    )
    audit = _RecordingAuditWriter()

    with pytest.raises(SchemaContractVersionMismatch):
        run_fetch(
            client=client,  # type: ignore[arg-type]
            schema=None,
            policy=RetryPolicy(max_attempts=5),
            sleeper=_FakeSleeper(),
            audit=audit,
        )

    assert audit.names() == [
        "run_started",
        "attempt_started",
        "attempt_failed",
        "run_failed",
    ]
    run_failed = audit.events[-1]
    assert run_failed.outcome == "contract_drift"
    assert run_failed.error_class == "SchemaContractVersionMismatch"


# ---- PR-2b2b/2b3 — db_writer + checkpoint integration -------------------


def test_audit_with_db_writer_emits_full_success_sequence(tmp_path: Path) -> None:
    """Codex 019e2a5c REVISE absorb: pin the success sequence
    ``... attempt_succeeded -> db_upsert_started -> db_upsert_completed
    -> checkpoint_written -> run_succeeded`` with checkpoint persisted."""
    from etl_worker.checkpoint import CheckpointFile
    from etl_worker.db import NoopReportsDbWriter

    client = _FakeClient(responses=[_good_snapshot()])
    audit = _RecordingAuditWriter()
    writer = NoopReportsDbWriter()
    cp_file = CheckpointFile(tmp_path / "state.json")

    result = run_fetch(
        client=client,  # type: ignore[arg-type]
        schema=None,
        policy=RetryPolicy(max_attempts=1),
        sleeper=_FakeSleeper(),
        audit=audit,
        run_id="success-r1",
        db_writer=writer,
        checkpoint=cp_file,
    )

    assert isinstance(result, RunResult)
    assert audit.names() == [
        "run_started",
        "attempt_started",
        "attempt_succeeded",
        "db_upsert_started",
        "db_upsert_completed",
        "checkpoint_written",
        "run_succeeded",
    ]
    # DB writer saw the summary + signature
    assert len(writer.calls) == 1
    assert "snapshot_signature" in writer.calls[0]
    # Checkpoint persisted
    loaded = cp_file.load()
    assert loaded is not None
    assert loaded.run_id == "success-r1"


def test_db_writer_failure_emits_failed_events_and_skips_checkpoint(tmp_path: Path) -> None:
    """Codex 019e2a5c REVISE absorb: ``ReportsDbWriteError`` surfaces
    ``db_upsert_failed`` + ``run_failed``, and the checkpoint is **not**
    persisted (transaction boundary contract)."""
    from etl_worker.checkpoint import CheckpointFile
    from etl_worker.db import ReportsDbWriteError, ReportsDbWriteResult

    class _BoomWriter:
        def upsert(self, _summary: dict[str, object]) -> ReportsDbWriteResult:
            raise ReportsDbWriteError("db connection lost")

    client = _FakeClient(responses=[_good_snapshot()])
    audit = _RecordingAuditWriter()
    cp_file = CheckpointFile(tmp_path / "state.json")

    with pytest.raises(ReportsDbWriteError):
        run_fetch(
            client=client,  # type: ignore[arg-type]
            schema=None,
            policy=RetryPolicy(max_attempts=1),
            sleeper=_FakeSleeper(),
            audit=audit,
            run_id="db-fail-r1",
            db_writer=_BoomWriter(),
            checkpoint=cp_file,
        )

    names = audit.names()
    assert "db_upsert_failed" in names
    assert names[-1] == "run_failed"
    # Checkpoint NEVER written when DB upsert fails — transaction
    # boundary contract.
    assert not cp_file.exists()


def test_resume_loads_checkpoint_even_without_audit(tmp_path: Path) -> None:
    """Codex 019e2a5c REVISE absorb: ``resume=True`` is independent of
    audit. A corrupt checkpoint at load time raises ``CheckpointError``
    even when no audit writer is configured."""
    from etl_worker.checkpoint import CheckpointError, CheckpointFile

    cp_path = tmp_path / "corrupt.json"
    cp_path.write_text("<<<not json>>>", encoding="utf-8")
    cp_file = CheckpointFile(cp_path)

    client = _FakeClient(responses=[_good_snapshot()])

    with pytest.raises(CheckpointError):
        run_fetch(
            client=client,  # type: ignore[arg-type]
            schema=None,
            policy=RetryPolicy(max_attempts=1),
            sleeper=_FakeSleeper(),
            # audit=None — corrupt checkpoint must still surface
            run_id="resume-r1",
            checkpoint=cp_file,
            resume=True,
        )
