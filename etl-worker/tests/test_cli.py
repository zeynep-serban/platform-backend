"""Unit tests for :func:`etl_worker.cli.main`.

The CLI signature accepts injectable ``argv``, ``env``, ``stdout``,
``stderr`` and ``client_factory`` so tests do not depend on
``sys.argv`` / ``os.environ`` / live HTTP. Adım 12 PR-2a scope.
"""

from __future__ import annotations

import io
import json

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


class _FakeClient:
    """In-memory schema-service client substitute.

    ``snapshot`` is returned by ``fetch_snapshot``; if ``raise_exc`` is
    set the call raises that exception instead. Captures the constructor
    + ``fetch_snapshot`` arguments so tests can assert the wiring
    contract between :func:`cli.main` and
    :class:`SchemaServiceClient`.
    """

    def __init__(
        self,
        base_url: str,
        *,
        timeout: float,
        supported_versions: tuple[str, ...],
        internal_api_key: str | None,
        snapshot: SchemaSnapshot | None = None,
        raise_exc: Exception | None = None,
    ) -> None:
        self.base_url = base_url
        self.timeout = timeout
        self.supported_versions = supported_versions
        self.internal_api_key = internal_api_key
        self._snapshot = snapshot
        self._raise = raise_exc
        self.last_schema: str | None = None

    def fetch_snapshot(self, *, schema: str | None = None) -> SchemaSnapshot:
        self.last_schema = schema
        if self._raise is not None:
            raise self._raise
        assert self._snapshot is not None
        return self._snapshot


def _factory(
    snapshot: SchemaSnapshot | None = None,
    raise_exc: Exception | None = None,
) -> tuple[list[_FakeClient], object]:
    """Build a client factory + capture list so tests can inspect the constructed client."""
    captured: list[_FakeClient] = []

    def factory(
        base_url: str,
        *,
        timeout: float,
        supported_versions: tuple[str, ...],
        internal_api_key: str | None,
    ) -> _FakeClient:
        client = _FakeClient(
            base_url,
            timeout=timeout,
            supported_versions=supported_versions,
            internal_api_key=internal_api_key,
            snapshot=snapshot,
            raise_exc=raise_exc,
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
            TableSpec(
                schema="dbo",
                name="COMPANY",
                columns=(ColumnSpec(name="COMPANY_ID", type="int", nullable=False),),
            ),
        ),
    )


def _good_env(**overrides: str) -> dict[str, str]:
    base: dict[str, str] = {"SCHEMA_SERVICE_URL": "http://schema-service.test:8096"}
    base.update(overrides)
    return base


# ---- success path --------------------------------------------------------


def test_fetch_snapshot_success_prints_json_summary_to_stdout() -> None:
    captured, factory = _factory(snapshot=_good_snapshot())
    out, err = io.StringIO(), io.StringIO()

    code = main(
        ["fetch-snapshot"],
        env=_good_env(),
        stdout=out,
        stderr=err,
        client_factory=factory,
    )

    assert code == EX_OK
    summary = json.loads(out.getvalue())
    assert summary == {
        "contract_version": "1",
        "allowlist_name": "ReportingAllowlist",
        "allowlist_version": "V1",
        "table_count": 2,
        "column_count": 3,
    }
    assert err.getvalue() == ""
    # Only one line on stdout (JSON + trailing newline)
    assert out.getvalue().count("\n") == 1
    # Constructor wiring
    assert len(captured) == 1
    client = captured[0]
    assert client.base_url == "http://schema-service.test:8096"
    assert client.supported_versions == ("1",)
    assert client.internal_api_key is None
    assert client.timeout == 10.0


def test_env_internal_api_key_is_forwarded_to_client() -> None:
    captured, factory = _factory(snapshot=_good_snapshot())

    code = main(
        ["fetch-snapshot"],
        env=_good_env(SCHEMA_SERVICE_INTERNAL_API_KEY="route-test-key"),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
    )

    assert code == EX_OK
    assert captured[0].internal_api_key == "route-test-key"


def test_env_contract_versions_csv_is_propagated() -> None:
    captured, factory = _factory(snapshot=_good_snapshot())

    main(
        ["fetch-snapshot"],
        env=_good_env(SCHEMA_SERVICE_CONTRACT_VERSIONS="1,2"),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
    )

    assert captured[0].supported_versions == ("1", "2")


# ---- override precedence -------------------------------------------------


def test_cli_schema_overrides_env_schema() -> None:
    captured, factory = _factory(snapshot=_good_snapshot())

    main(
        ["fetch-snapshot", "--schema", "workcube_mikrolink_2025"],
        env=_good_env(SCHEMA_SERVICE_SCHEMA="workcube_mikrolink"),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
    )

    # ``fetch_snapshot`` got the CLI value, not the env value
    assert captured[0].last_schema == "workcube_mikrolink_2025"


def test_env_schema_used_when_no_cli_override() -> None:
    captured, factory = _factory(snapshot=_good_snapshot())

    main(
        ["fetch-snapshot"],
        env=_good_env(SCHEMA_SERVICE_SCHEMA="workcube_mikrolink"),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
    )

    assert captured[0].last_schema == "workcube_mikrolink"


def test_cli_timeout_overrides_env_timeout() -> None:
    captured, factory = _factory(snapshot=_good_snapshot())

    main(
        ["fetch-snapshot", "--timeout", "2.5"],
        env=_good_env(SCHEMA_SERVICE_TIMEOUT_SECONDS="20"),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
    )

    assert captured[0].timeout == 2.5


# ---- exit-code matrix ----------------------------------------------------


def test_5xx_unavailable_maps_to_75_tempfail() -> None:
    _, factory = _factory(raise_exc=SchemaServiceUnavailable("upstream 503"))
    err = io.StringIO()

    code = main(
        ["fetch-snapshot"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
    )

    assert code == EX_TEMPFAIL
    assert "upstream unavailable" in err.getvalue()


def test_malformed_response_maps_to_70_software() -> None:
    _, factory = _factory(raise_exc=SchemaServiceMalformedResponse("4xx body"))
    err = io.StringIO()

    code = main(
        ["fetch-snapshot"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
    )

    assert code == EX_SOFTWARE
    assert "malformed response" in err.getvalue()


def test_contract_version_mismatch_maps_to_76_protocol() -> None:
    _, factory = _factory(raise_exc=SchemaContractVersionMismatch("'2' not in ('1',)"))
    err = io.StringIO()

    code = main(
        ["fetch-snapshot"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
    )

    assert code == EX_PROTOCOL
    assert "contract version mismatch" in err.getvalue()


# ---- usage failures ------------------------------------------------------


def test_missing_url_env_maps_to_64_usage() -> None:
    _, factory = _factory(snapshot=_good_snapshot())
    err = io.StringIO()

    code = main(
        ["fetch-snapshot"],
        env={},
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
    )

    assert code == EX_USAGE
    assert "config error" in err.getvalue()
    assert "SCHEMA_SERVICE_URL is required" in err.getvalue()


def test_unknown_subcommand_maps_to_64_usage() -> None:
    _, factory = _factory(snapshot=_good_snapshot())
    err = io.StringIO()

    code = main(
        ["bogus-subcommand"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
    )

    assert code == EX_USAGE


def test_missing_subcommand_maps_to_64_usage() -> None:
    _, factory = _factory(snapshot=_good_snapshot())
    err = io.StringIO()

    code = main(
        [],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
    )

    assert code == EX_USAGE


def test_unknown_flag_maps_to_64_usage() -> None:
    _, factory = _factory(snapshot=_good_snapshot())
    err = io.StringIO()

    code = main(
        ["fetch-snapshot", "--no-such-flag"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
    )

    assert code == EX_USAGE


def test_bad_timeout_flag_maps_to_64_usage() -> None:
    _, factory = _factory(snapshot=_good_snapshot())
    err = io.StringIO()

    code = main(
        ["fetch-snapshot", "--timeout", "abc"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
    )

    assert code == EX_USAGE


def test_malformed_env_url_maps_to_64_usage_without_stack_trace() -> None:
    """Codex 019e2a5c REVISE absorb: malformed URLs that ``urlparse`` /
    ``.port`` rejects with ``ValueError`` flow through ``ConfigError``
    and surface as ``EX_USAGE``, with stderr carrying only the safe
    error message (no Python traceback)."""
    _, factory = _factory(snapshot=_good_snapshot())
    err = io.StringIO()

    code = main(
        ["fetch-snapshot"],
        env={"SCHEMA_SERVICE_URL": "http://schema-service:badport"},
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
    )

    assert code == EX_USAGE
    assert "config error" in err.getvalue()
    # Never leak the underlying stdlib stack trace into stderr
    assert "Traceback" not in err.getvalue()
    assert "ValueError" not in err.getvalue()


def test_bad_env_timeout_maps_to_64_usage() -> None:
    _, factory = _factory(snapshot=_good_snapshot())
    err = io.StringIO()

    code = main(
        ["fetch-snapshot"],
        env=_good_env(SCHEMA_SERVICE_TIMEOUT_SECONDS="-1"),
        stdout=io.StringIO(),
        stderr=err,
        client_factory=factory,
    )

    assert code == EX_USAGE
    assert "config error" in err.getvalue()


# ---- help -----------------------------------------------------------------


def test_help_flag_returns_ok_exit_code() -> None:
    """``--help`` returns ``EX_OK`` (0).

    argparse internally raises ``SystemExit(0)`` after printing help to
    its own configured output (``sys.stdout`` by default — argparse
    does not route through the injected ``stdout`` parameter we pass
    to :func:`cli.main`). :func:`cli.main` traps the ``SystemExit(0)``
    and funnels through the normalised exit-code contract so the
    console-script caller gets a clean ``0`` rather than the
    sysexit-style ``2`` argparse would otherwise emit.
    """
    _, factory = _factory(snapshot=_good_snapshot())

    code = main(
        ["--help"],
        env=_good_env(),
        stdout=io.StringIO(),
        stderr=io.StringIO(),
        client_factory=factory,
    )

    assert code == EX_OK
