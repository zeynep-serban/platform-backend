"""Unit tests for :func:`etl_worker.runner.run_fetch`.

Adım 12 PR-2b1 — runner retry foundation. The runner is exercised via
a fake :class:`SchemaServiceClient` so no live HTTP fires.
"""

from __future__ import annotations

import pytest

from etl_worker.contracts import ColumnSpec, SchemaSnapshot, TableSpec
from etl_worker.retry import RetryPolicy
from etl_worker.runner import RunResult, run_fetch
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
