"""Unit tests for :class:`etl_worker.schema_service_client.SchemaServiceClient`.

Adım 12 PR-1 scope: every observable failure mode is exercised through
a fake :class:`_Transport`. No live HTTP, no live schema-service, no
local network — pytest must run hermetic in CI.
"""

from __future__ import annotations

import json

import pytest

from etl_worker.contracts import ColumnSpec, SchemaSnapshot, TableSpec
from etl_worker.schema_service_client import (
    SchemaContractVersionMismatch,
    SchemaServiceClient,
    SchemaServiceMalformedResponse,
    SchemaServiceUnavailable,
)


class _FakeTransport:
    """In-memory transport returning a single fixed ``(status, body)`` tuple."""

    def __init__(self, status: int, body: bytes) -> None:
        self.status = status
        self.body = body
        self.last_url: str | None = None
        self.last_timeout: float | None = None
        self.last_headers: dict[str, str] | None = None

    def get(
        self,
        url: str,
        *,
        timeout: float,
        headers: object = None,
    ) -> tuple[int, bytes]:
        self.last_url = url
        self.last_timeout = timeout
        # Materialise the mapping eagerly so assertions can compare a
        # plain dict regardless of whether the caller passed ``None``,
        # an empty mapping or a populated dict.
        if headers is None:
            self.last_headers = None
        else:
            assert hasattr(headers, "items")
            self.last_headers = {str(k): str(v) for k, v in headers.items()}  # type: ignore[attr-defined]
        return self.status, self.body


def _snapshot_payload() -> dict[str, object]:
    return {
        "contract_version": "1",
        "allowlist_name": "ReportingAllowlist",
        "allowlist_version": "V1",
        "tables": [
            {
                "schema": "dbo",
                "name": "EMPLOYEES",
                "columns": [
                    {"name": "EMPLOYEE_ID", "type": "int", "nullable": False},
                    {"name": "EMPLOYEE_NAME", "type": "nvarchar(255)", "nullable": True},
                ],
            },
            {
                "schema": "dbo",
                "name": "COMPANY",
                "columns": [
                    {"name": "COMPANY_ID", "type": "int", "nullable": False},
                ],
            },
        ],
    }


def _client(
    transport: _FakeTransport,
    *,
    supported_versions: tuple[str, ...] = ("1",),
    internal_api_key: str | None = None,
) -> SchemaServiceClient:
    return SchemaServiceClient(
        base_url="http://schema-service.test:8096",
        transport=transport,
        timeout=2.0,
        supported_versions=supported_versions,
        internal_api_key=internal_api_key,
    )


# ---- happy path -----------------------------------------------------------


def test_fetch_snapshot_200_parses_full_payload() -> None:
    transport = _FakeTransport(200, json.dumps(_snapshot_payload()).encode("utf-8"))

    snapshot = _client(transport).fetch_snapshot()

    assert isinstance(snapshot, SchemaSnapshot)
    assert snapshot.contract_version == "1"
    assert snapshot.allowlist_name == "ReportingAllowlist"
    assert snapshot.allowlist_version == "V1"
    assert len(snapshot.tables) == 2

    employees = snapshot.tables[0]
    assert employees == TableSpec(
        schema="dbo",
        name="EMPLOYEES",
        columns=(
            ColumnSpec(name="EMPLOYEE_ID", type="int", nullable=False),
            ColumnSpec(name="EMPLOYEE_NAME", type="nvarchar(255)", nullable=True),
        ),
    )

    company = snapshot.tables[1]
    assert company.schema == "dbo"
    assert company.name == "COMPANY"
    assert len(company.columns) == 1
    assert company.columns[0].nullable is False

    # URL composed from base_url + default snapshot path
    assert transport.last_url == "http://schema-service.test:8096/api/v1/schema/snapshot"
    assert transport.last_timeout == 2.0


def test_fetch_snapshot_base_url_trailing_slash_normalised() -> None:
    transport = _FakeTransport(200, json.dumps(_snapshot_payload()).encode("utf-8"))
    client = SchemaServiceClient(
        base_url="http://schema-service.test:8096/",
        transport=transport,
    )

    client.fetch_snapshot()

    assert transport.last_url == "http://schema-service.test:8096/api/v1/schema/snapshot"


def test_fetch_snapshot_ignores_unknown_top_level_keys() -> None:
    payload = _snapshot_payload()
    payload["unknown_future_field"] = {"foo": "bar"}
    transport = _FakeTransport(200, json.dumps(payload).encode("utf-8"))

    snapshot = _client(transport).fetch_snapshot()

    assert snapshot.contract_version == "1"
    assert len(snapshot.tables) == 2


# ---- retryable upstream failures -----------------------------------------


@pytest.mark.parametrize("status", [500, 502, 503, 504, 599])
def test_fetch_snapshot_5xx_raises_unavailable(status: int) -> None:
    transport = _FakeTransport(status, b"")

    with pytest.raises(SchemaServiceUnavailable) as exc_info:
        _client(transport).fetch_snapshot()

    assert f"status={status}" in str(exc_info.value)


def test_fetch_snapshot_transport_failure_status_zero_raises_unavailable() -> None:
    """Sentinel status 0 from the transport (DNS / refused / timeout) maps to retryable."""
    transport = _FakeTransport(0, b"")

    with pytest.raises(SchemaServiceUnavailable):
        _client(transport).fetch_snapshot()


# ---- terminal failures ----------------------------------------------------


@pytest.mark.parametrize("status", [400, 401, 403, 404, 409])
def test_fetch_snapshot_4xx_raises_malformed(status: int) -> None:
    transport = _FakeTransport(status, b"")

    with pytest.raises(SchemaServiceMalformedResponse) as exc_info:
        _client(transport).fetch_snapshot()

    assert f"status {status}" in str(exc_info.value)


def test_fetch_snapshot_invalid_json_raises_malformed() -> None:
    transport = _FakeTransport(200, b"<<<not json>>>")

    with pytest.raises(SchemaServiceMalformedResponse) as exc_info:
        _client(transport).fetch_snapshot()

    assert "not valid JSON" in str(exc_info.value)


def test_fetch_snapshot_root_must_be_object() -> None:
    transport = _FakeTransport(200, b'["array", "not", "object"]')

    with pytest.raises(SchemaServiceMalformedResponse) as exc_info:
        _client(transport).fetch_snapshot()

    assert "root must be a JSON object" in str(exc_info.value)


def test_fetch_snapshot_missing_contract_version_raises_malformed() -> None:
    payload = _snapshot_payload()
    del payload["contract_version"]
    transport = _FakeTransport(200, json.dumps(payload).encode("utf-8"))

    with pytest.raises(SchemaServiceMalformedResponse) as exc_info:
        _client(transport).fetch_snapshot()

    assert "contract_version" in str(exc_info.value)


def test_fetch_snapshot_missing_allowlist_name_raises_malformed() -> None:
    payload = _snapshot_payload()
    del payload["allowlist_name"]
    transport = _FakeTransport(200, json.dumps(payload).encode("utf-8"))

    with pytest.raises(SchemaServiceMalformedResponse) as exc_info:
        _client(transport).fetch_snapshot()

    assert "allowlist_name" in str(exc_info.value)


def test_fetch_snapshot_missing_allowlist_version_raises_malformed() -> None:
    payload = _snapshot_payload()
    del payload["allowlist_version"]
    transport = _FakeTransport(200, json.dumps(payload).encode("utf-8"))

    with pytest.raises(SchemaServiceMalformedResponse) as exc_info:
        _client(transport).fetch_snapshot()

    assert "allowlist_version" in str(exc_info.value)


def test_fetch_snapshot_tables_must_be_list() -> None:
    payload = _snapshot_payload()
    payload["tables"] = "not a list"
    transport = _FakeTransport(200, json.dumps(payload).encode("utf-8"))

    with pytest.raises(SchemaServiceMalformedResponse) as exc_info:
        _client(transport).fetch_snapshot()

    assert "'tables' must be a list" in str(exc_info.value)


def test_fetch_snapshot_table_missing_schema_raises_malformed() -> None:
    payload = _snapshot_payload()
    tables = payload["tables"]
    assert isinstance(tables, list)
    first_table = tables[0]
    assert isinstance(first_table, dict)
    del first_table["schema"]
    transport = _FakeTransport(200, json.dumps(payload).encode("utf-8"))

    with pytest.raises(SchemaServiceMalformedResponse) as exc_info:
        _client(transport).fetch_snapshot()

    assert "tables[0].schema" in str(exc_info.value)


def test_fetch_snapshot_column_nullable_must_be_bool() -> None:
    payload = _snapshot_payload()
    tables = payload["tables"]
    assert isinstance(tables, list)
    first_table = tables[0]
    assert isinstance(first_table, dict)
    columns = first_table["columns"]
    assert isinstance(columns, list)
    first_column = columns[0]
    assert isinstance(first_column, dict)
    first_column["nullable"] = "yes"  # string, not bool
    transport = _FakeTransport(200, json.dumps(payload).encode("utf-8"))

    with pytest.raises(SchemaServiceMalformedResponse) as exc_info:
        _client(transport).fetch_snapshot()

    assert "tables[0].columns[0].nullable" in str(exc_info.value)


# ---- contract version mismatch -------------------------------------------


def test_fetch_snapshot_unsupported_version_raises_mismatch() -> None:
    payload = _snapshot_payload()
    payload["contract_version"] = "2"
    transport = _FakeTransport(200, json.dumps(payload).encode("utf-8"))

    with pytest.raises(SchemaContractVersionMismatch) as exc_info:
        _client(transport, supported_versions=("1",)).fetch_snapshot()

    assert "'2'" in str(exc_info.value)
    assert "('1',)" in str(exc_info.value)


def test_fetch_snapshot_explicit_supported_versions_widening_works() -> None:
    """Supplying a wider supported_versions tuple lets the client accept newer snapshots."""
    payload = _snapshot_payload()
    payload["contract_version"] = "2"
    transport = _FakeTransport(200, json.dumps(payload).encode("utf-8"))

    snapshot = _client(transport, supported_versions=("1", "2")).fetch_snapshot()

    assert snapshot.contract_version == "2"


# ---- constructor validation ----------------------------------------------


def test_constructor_rejects_empty_base_url() -> None:
    with pytest.raises(ValueError, match="base_url must be non-empty"):
        SchemaServiceClient(base_url="")


def test_constructor_rejects_empty_supported_versions() -> None:
    with pytest.raises(ValueError, match="supported_versions must contain at least one entry"):
        SchemaServiceClient(base_url="http://x", supported_versions=())


# ---- auth header propagation -------------------------------------------


def test_fetch_snapshot_without_internal_key_sends_no_headers() -> None:
    """No-key dev / test passthrough: no ``X-Internal-Api-Key`` header is sent."""
    transport = _FakeTransport(200, json.dumps(_snapshot_payload()).encode("utf-8"))

    _client(transport).fetch_snapshot()

    # ``last_headers`` is ``None`` when the client passes ``headers=None``
    # to the transport (no internal key configured).
    assert transport.last_headers is None


def test_fetch_snapshot_with_internal_key_propagates_header() -> None:
    """Configured ``internal_api_key`` is forwarded as the ``X-Internal-Api-Key`` header."""
    transport = _FakeTransport(200, json.dumps(_snapshot_payload()).encode("utf-8"))

    _client(transport, internal_api_key="route-test-key").fetch_snapshot()

    assert transport.last_headers == {"X-Internal-Api-Key": "route-test-key"}


# ---- ?schema= selector --------------------------------------------------


def test_fetch_snapshot_without_schema_omits_query_string() -> None:
    """No ``?schema=`` query is appended when ``schema`` argument is ``None`` (default)."""
    transport = _FakeTransport(200, json.dumps(_snapshot_payload()).encode("utf-8"))

    _client(transport).fetch_snapshot()

    assert transport.last_url == "http://schema-service.test:8096/api/v1/schema/snapshot"


def test_fetch_snapshot_with_schema_appends_query_param() -> None:
    """``?schema=`` selector matches schema-service ``SchemaController#getSnapshot`` signature."""
    transport = _FakeTransport(200, json.dumps(_snapshot_payload()).encode("utf-8"))

    _client(transport).fetch_snapshot(schema="workcube_mikrolink_2025")

    assert transport.last_url == (
        "http://schema-service.test:8096/api/v1/schema/snapshot"
        "?schema=workcube_mikrolink_2025"
    )


def test_fetch_snapshot_with_schema_url_encodes_special_characters() -> None:
    """Selector with reserved URL characters is properly percent-encoded."""
    transport = _FakeTransport(200, json.dumps(_snapshot_payload()).encode("utf-8"))

    _client(transport).fetch_snapshot(schema="schema with spaces")

    assert transport.last_url == (
        "http://schema-service.test:8096/api/v1/schema/snapshot"
        "?schema=schema+with+spaces"
    )
