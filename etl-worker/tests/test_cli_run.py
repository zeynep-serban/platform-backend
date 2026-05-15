"""CLI tests for the ``etl-worker run`` subcommand.

Separated from ``test_cli.py`` so the ``fetch-snapshot`` baseline stays
focused on the PR-2a contract. Adım 12 PR-2b1 scope.
"""

from __future__ import annotations

import io
import json

import pytest

from etl_worker.cli import (
    EX_OK,
    EX_PROTOCOL,
    EX_SOFTWARE,
    EX_TEMPFAIL,
    EX_USAGE,
    main,
)
from etl_worker.contracts import ColumnSpec, SchemaSnapshot, TableSpec
from etl_worker.schema_service_client import (
    SchemaContractVersionMismatch,
    SchemaServiceMalformedResponse,
    SchemaServiceUnavailable,
)


class _FakeSleeper:
    """Records each requested delay; never blocks."""

    def __init__(self) -> None:
        self.delays: list[float] = []

    def sleep(self, seconds: float) -> None:
        self.delays.append(seconds)


class _ScriptedClient:
    """Client that walks through a scripted response list.

    Each entry is either a :class:`SchemaSnapshot` or an exception
    instance the client raises on the next ``fetch_snapshot`` call.
    """

    def __init__(
        self,
        base_url: str,
        *,
        timeout: float,
        supported_versions: tuple[str, ...],
        internal_api_key: str | None,
        responses: list[SchemaSnapshot | Exception],
    ) -> None:
        self.base_url = base_url
        self.timeout = timeout
        self.supported_versions = supported_versions
        self.internal_api_key = internal_api_key
        self._responses = responses
        self.fetch_calls: list[str | None] = []

    def fetch_snapshot(self, *, schema: str | None = None) -> SchemaSnapshot:
        self.fetch_calls.append(schema)
        if not self._responses:
            raise RuntimeError("No more scripted responses")
        next_value = self._responses.pop(0)
        if isinstance(next_value, Exception):
            raise next_value
        return next_value


def _factory(
    responses: list[SchemaSnapshot | Exception],
) -> tuple[list[_ScriptedClient], object]:
    captured: list[_ScriptedClient] = []

    def factory(
        base_url: str,
        *,
        timeout: float,
        supported_versions: tuple[str, ...],
        internal_api_key: str | None,
    ) -> _ScriptedClient:
        client = _ScriptedClient(
            base_url,
            timeout=timeout,
            supported_versions=supported_versions,
            internal_api_key=internal_api_key,
            responses=list(responses),
        )
        captured.append(client)
        return client

    return captured, factory


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
        ),
    )


def _good_env() -> dict[str, str]:
    return {"SCHEMA_SERVICE_URL": "http://schema-service.test:8096"}


# ---- success paths -------------------------------------------------------


def test_run_happy_path_emits_attempts_one() -> None:
    _, factory = _factory([_good_snapshot()])
    sleeper = _FakeSleeper()
    out = io.StringIO()

    code = main(
        ["run"],
        env=_good_env(),
        stdout=out,
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=sleeper,
    )

    assert code == EX_OK
    summary = json.loads(out.getvalue())
    assert summary["attempts"] == 1
    assert summary["table_count"] == 1
    assert summary["contract_version"] == "1"
    assert sleeper.delays == []


def test_run_transient_then_success_absorbs_and_reports_attempts() -> None:
    captured, factory = _factory(
        [SchemaServiceUnavailable("upstream 503"), _good_snapshot()]
    )
    sleeper = _FakeSleeper()
    out = io.StringIO()

    code = main(
        [
            "run",
            "--retry-attempts",
            "3",
            "--retry-initial-seconds",
            "0.25",
            "--retry-multiplier",
            "2",
            "--retry-cap-seconds",
            "10",
        ],
        env=_good_env(),
        stdout=out,
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=sleeper,
    )

    assert code == EX_OK
    summary = json.loads(out.getvalue())
    assert summary["attempts"] == 2
    # Sleeper saw exactly the initial backoff before attempt 2
    assert sleeper.delays == [0.25]
    assert len(captured[0].fetch_calls) == 2


def test_run_schema_flag_forwarded_to_client() -> None:
    captured, factory = _factory([_good_snapshot()])

    main(
        ["run", "--schema", "workcube_mikrolink_2025"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert captured[0].fetch_calls == ["workcube_mikrolink_2025"]


# ---- exit-code matrix ---------------------------------------------------


def test_run_transient_exhausted_maps_to_75_tempfail() -> None:
    _, factory = _factory(
        [
            SchemaServiceUnavailable("503-1"),
            SchemaServiceUnavailable("503-2"),
        ]
    )
    sleeper = _FakeSleeper()
    err = io.StringIO()

    code = main(
        [
            "run",
            "--retry-attempts",
            "2",
            "--retry-initial-seconds",
            "0.1",
        ],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
        sleeper=sleeper,
    )

    assert code == EX_TEMPFAIL
    assert "after 2 attempts" in err.getvalue()
    # Exactly one sleep before attempt 2
    assert sleeper.delays == [0.1]


def test_run_malformed_response_is_not_retried_70_software() -> None:
    captured, factory = _factory([SchemaServiceMalformedResponse("4xx body")])
    sleeper = _FakeSleeper()
    err = io.StringIO()

    code = main(
        ["run", "--retry-attempts", "5"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
        sleeper=sleeper,
    )

    assert code == EX_SOFTWARE
    assert "malformed response" in err.getvalue()
    assert len(captured[0].fetch_calls) == 1
    assert sleeper.delays == []


def test_run_contract_mismatch_is_not_retried_76_protocol() -> None:
    captured, factory = _factory(
        [SchemaContractVersionMismatch("'2' not in ('1',)")]
    )
    sleeper = _FakeSleeper()
    err = io.StringIO()

    code = main(
        ["run", "--retry-attempts", "5"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
        sleeper=sleeper,
    )

    assert code == EX_PROTOCOL
    assert "contract version mismatch" in err.getvalue()
    assert len(captured[0].fetch_calls) == 1
    assert sleeper.delays == []


# ---- bad CLI retry config ------------------------------------------------


def test_run_zero_retry_attempts_maps_to_64_usage() -> None:
    _, factory = _factory([_good_snapshot()])

    code = main(
        ["run", "--retry-attempts", "0"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_USAGE


def test_run_negative_retry_attempts_maps_to_64_usage() -> None:
    _, factory = _factory([_good_snapshot()])

    code = main(
        ["run", "--retry-attempts", "-1"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_USAGE


def test_run_non_integer_retry_attempts_maps_to_64_usage() -> None:
    _, factory = _factory([_good_snapshot()])

    code = main(
        ["run", "--retry-attempts", "abc"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_USAGE


def test_run_sub_unit_multiplier_maps_to_64_usage() -> None:
    _, factory = _factory([_good_snapshot()])

    code = main(
        ["run", "--retry-multiplier", "0.5"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_USAGE


def test_run_negative_initial_seconds_maps_to_64_usage() -> None:
    _, factory = _factory([_good_snapshot()])

    code = main(
        ["run", "--retry-initial-seconds", "-1"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_USAGE


@pytest.mark.parametrize(
    "flag,value",
    [
        ("--timeout", "nan"),
        ("--timeout", "inf"),
        ("--retry-initial-seconds", "nan"),
        ("--retry-initial-seconds", "inf"),
        ("--retry-multiplier", "nan"),
        ("--retry-multiplier", "inf"),
        ("--retry-cap-seconds", "nan"),
        ("--retry-cap-seconds", "inf"),
    ],
)
def test_run_non_finite_numeric_flags_map_to_64_usage(flag: str, value: str) -> None:
    """Codex 019e2a5c REVISE absorb: ``nan`` / ``inf`` numeric flags must
    surface as ``EX_USAGE`` (64), not silently pass validation and then
    be misclassified as a retryable upstream failure (75)."""
    _, factory = _factory([_good_snapshot()])

    code = main(
        ["run", flag, value],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_USAGE


def test_run_cap_smaller_than_initial_maps_to_64_usage() -> None:
    """``RetryPolicy.__post_init__`` cross-field validation surfaces as EX_USAGE."""
    _, factory = _factory([_good_snapshot()])
    err = io.StringIO()

    code = main(
        [
            "run",
            "--retry-initial-seconds",
            "5",
            "--retry-cap-seconds",
            "1",
        ],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_USAGE
    assert "retry policy" in err.getvalue()
    assert "cap_seconds must be a finite number >= initial_seconds" in err.getvalue()


# ---- config errors propagate to EX_USAGE in run path ---------------------


def test_run_missing_url_env_maps_to_64_usage() -> None:
    _, factory = _factory([_good_snapshot()])

    code = main(
        ["run"],
        env={},
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_USAGE


# ---- PR-2b2a — audit trail integration -----------------------------------


def test_run_stdout_summary_includes_run_id() -> None:
    """PR-2b2a adds ``run_id`` to the stdout JSON summary even when no audit path is provided."""
    _, factory = _factory([_good_snapshot()])
    out = io.StringIO()

    code = main(
        ["run", "--run-id", "operator-supplied-1"],
        env=_good_env(),
        stdout=out,
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_OK
    summary = json.loads(out.getvalue())
    assert summary["run_id"] == "operator-supplied-1"
    assert summary["attempts"] == 1


def test_run_stdout_summary_autogenerates_run_id_when_omitted() -> None:
    _, factory = _factory([_good_snapshot()])
    out = io.StringIO()

    main(
        ["run"],
        env=_good_env(),
        stdout=out,
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    summary = json.loads(out.getvalue())
    assert isinstance(summary["run_id"], str)
    assert len(summary["run_id"]) >= 16  # uuid4 hex is 32 chars


def test_run_with_audit_path_invokes_audit_factory() -> None:
    """``--audit-path`` flag wires an :class:`AuditWriter` into the runner."""
    captured_paths: list[str] = []
    events: list[object] = []

    class _RecordingWriter:
        def __init__(self, path: str) -> None:
            captured_paths.append(path)

        def write(self, event: object) -> None:
            events.append(event)

    _, factory = _factory([_good_snapshot()])

    code = main(
        ["run", "--audit-path", "/tmp/etl-worker-test-audit.jsonl"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
        audit_factory=_RecordingWriter,
    )

    assert code == EX_OK
    # Factory was invoked exactly once with the CLI-supplied path.
    assert captured_paths == ["/tmp/etl-worker-test-audit.jsonl"]
    # The runner emitted the expected event sequence onto our writer.
    event_names = [event.event for event in events]  # type: ignore[attr-defined]
    assert event_names == [
        "run_started",
        "attempt_started",
        "attempt_succeeded",
        "run_succeeded",
    ]


def test_run_without_audit_path_does_not_invoke_audit_factory() -> None:
    """Absence of ``--audit-path`` leaves PR-2b1 behaviour intact."""
    invocations: list[str] = []

    def factory(path: str) -> object:
        invocations.append(path)
        raise AssertionError("audit_factory must NOT be called without --audit-path")

    _, client_factory = _factory([_good_snapshot()])

    code = main(
        ["run"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=client_factory,
        sleeper=_FakeSleeper(),
        audit_factory=factory,
    )

    assert code == EX_OK
    assert invocations == []


def test_run_audit_path_creates_jsonl_file_with_event_lines(tmp_path: object) -> None:
    """End-to-end smoke: CLI ``--audit-path`` flag persists JSON Lines to disk."""
    import json as _json
    from pathlib import Path as _Path

    audit_file = _Path(str(tmp_path)) / "audit.jsonl"
    _, factory = _factory([_good_snapshot()])

    code = main(
        ["run", "--audit-path", str(audit_file), "--run-id", "smoke-1"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_OK
    assert audit_file.exists()
    lines = [_json.loads(line) for line in audit_file.read_text().splitlines()]
    assert [line["event"] for line in lines] == [
        "run_started",
        "attempt_started",
        "attempt_succeeded",
        "run_succeeded",
    ]
    assert all(line["run_id"] == "smoke-1" for line in lines)


def test_run_audit_on_transient_exhausted_writes_run_failed(tmp_path: object) -> None:
    """Audit log captures the retry-exhausted run_failed event with retryable_exhausted outcome."""
    import json as _json
    from pathlib import Path as _Path

    audit_file = _Path(str(tmp_path)) / "audit.jsonl"
    _, factory = _factory(
        [SchemaServiceUnavailable("503-1"), SchemaServiceUnavailable("503-2")]
    )

    code = main(
        [
            "run",
            "--retry-attempts",
            "2",
            "--retry-initial-seconds",
            "0.1",
            "--audit-path",
            str(audit_file),
        ],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_TEMPFAIL
    lines = [_json.loads(line) for line in audit_file.read_text().splitlines()]
    names = [line["event"] for line in lines]
    assert names[-1] == "run_failed"
    assert lines[-1]["outcome"] == "retryable_exhausted"
    assert lines[-1]["error_class"] == "SchemaServiceUnavailable"


# ---- Codex 019e2a5c REVISE absorb — audit sink OSError handling ----------


def test_run_audit_factory_oserror_maps_to_70_software() -> None:
    """Codex 019e2a5c REVISE absorb: an audit sink that fails to instantiate
    must surface as ``EX_SOFTWARE`` (70) with a one-line stderr message,
    *not* a Python traceback. Pins the typed exit-code contract through
    the audit path."""

    def explosive_factory(path: str) -> object:
        raise OSError("permission denied")

    _, factory = _factory([_good_snapshot()])
    err = io.StringIO()

    code = main(
        ["run", "--audit-path", "/tmp/never-created-audit.jsonl"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
        sleeper=_FakeSleeper(),
        audit_factory=explosive_factory,
    )

    assert code == EX_SOFTWARE
    text = err.getvalue()
    assert "audit error" in text
    assert "permission denied" in text
    # Never leak the underlying Python traceback into stderr.
    assert "Traceback" not in text
    assert "OSError(" not in text


def test_run_audit_write_oserror_maps_to_70_software() -> None:
    """An ``OSError`` from a successful audit writer's ``write()`` call
    (e.g. disk full mid-run) also surfaces as ``EX_SOFTWARE`` rather
    than leaking out as a traceback."""

    class _BrokenWriter:
        def __init__(self, path: str) -> None:
            self.path = path

        def write(self, _event: object) -> None:
            raise OSError("no space left on device")

    _, factory = _factory([_good_snapshot()])
    err = io.StringIO()

    code = main(
        ["run", "--audit-path", "/tmp/disk-full-audit.jsonl"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
        sleeper=_FakeSleeper(),
        audit_factory=_BrokenWriter,
    )

    assert code == EX_SOFTWARE
    text = err.getvalue()
    # Codex 019e2a5c REVISE absorb: runtime OSError surfaces with the
    # neutral "persistence error" label so a checkpoint-write failure
    # is not mis-reported as an audit failure.
    assert "persistence error" in text
    assert "no space left on device" in text
    assert "Traceback" not in text


# ---- PR-2b2b/2b3 — CLI checkpoint + DB writer integration ---------------


def test_run_resume_without_checkpoint_path_maps_to_64_usage() -> None:
    """Codex 019e2a5c REVISE absorb: ``--resume`` without
    ``--checkpoint-path`` is a misinvocation; CLI must fail-closed."""
    _, factory = _factory([_good_snapshot()])
    err = io.StringIO()

    code = main(
        ["run", "--resume"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_USAGE
    assert "--resume requires --checkpoint-path" in err.getvalue()


def test_run_checkpoint_path_writes_file_on_success(tmp_path: object) -> None:
    """End-to-end smoke: ``--checkpoint-path`` persists a checkpoint JSON
    file after a successful fetch (without DB writer)."""
    import json as _json
    from pathlib import Path as _Path

    cp_file = _Path(str(tmp_path)) / "state.json"
    _, factory = _factory([_good_snapshot()])

    code = main(
        ["run", "--checkpoint-path", str(cp_file), "--run-id", "smoke-cp"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_OK
    assert cp_file.exists()
    payload = _json.loads(cp_file.read_text())
    assert payload["run_id"] == "smoke-cp"
    assert payload["last_successful_attempt"] == 1
    assert payload["schema_version"] == 1
    assert isinstance(payload["snapshot_signature"], str)


def test_run_resume_with_corrupt_checkpoint_maps_to_70_software(tmp_path: object) -> None:
    """``--resume`` against a malformed checkpoint surfaces
    ``CheckpointError`` as ``EX_SOFTWARE`` with no traceback leak."""
    from pathlib import Path as _Path

    cp_file = _Path(str(tmp_path)) / "corrupt.json"
    cp_file.write_text("<<<not json>>>", encoding="utf-8")
    _, factory = _factory([_good_snapshot()])
    err = io.StringIO()

    code = main(
        [
            "run",
            "--resume",
            "--checkpoint-path",
            str(cp_file),
        ],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
        sleeper=_FakeSleeper(),
    )

    assert code == EX_SOFTWARE
    text = err.getvalue()
    assert "checkpoint error" in text
    assert "Traceback" not in text


def test_run_db_writer_write_error_maps_to_75_tempfail(tmp_path: object) -> None:
    """``ReportsDbWriteError`` from the wired ``db_writer`` surfaces as
    ``EX_TEMPFAIL`` so an outer scheduler can re-queue."""
    from etl_worker.db import ReportsDbWriteError as _Err
    from etl_worker.db import ReportsDbWriteResult as _Result

    class _BoomWriter:
        def upsert(self, _summary: dict[str, object]) -> _Result:
            raise _Err("db connection lost")

    _, factory = _factory([_good_snapshot()])
    err = io.StringIO()

    code = main(
        ["run"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
        sleeper=_FakeSleeper(),
        db_writer=_BoomWriter(),
    )

    assert code == EX_TEMPFAIL
    text = err.getvalue()
    assert "db write error" in text
    assert "db connection lost" in text
    assert "Traceback" not in text
