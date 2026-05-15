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
