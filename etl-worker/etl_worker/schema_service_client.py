"""HTTP client for the etl-worker → schema-service **target** contract.

Adım 12 PR-1 (reporting refactor plan §400 — etl-worker → schema-service
contract consumer). The client is deliberately small: it owns the
HTTP request shape, the response parse, and the three typed failure
modes the worker has to react to. Runner / scheduling / live ETL DB
writes are out of scope here.

.. important::
   **This is the Adım 12 *target* contract, not the shape schema-service
   currently returns.** Today ``GET /api/v1/schema/snapshot`` answers with
   ``version`` (not ``contract_version``), ``metadata``, a ``tables``
   *map* keyed by table name, and a column ``dataType`` field — see
   ``schema-service/src/main/java/com/example/schema/model/SchemaSnapshot.java``
   and ``ColumnInfo.java``. The fields modelled here
   (``contract_version``, ``allowlist_name``, ``allowlist_version``,
   ``tables`` *list* with column ``type``) are what Adım 12 PR-2+ needs
   schema-service to start emitting before the runner can be wired
   live. PR-1 ships the consumer side under unit tests so the
   schema-service contract evolution has a concrete acceptance target.

Design notes:

* **Stdlib-only runtime.** ``urllib.request`` keeps PR-1 dependency-free,
  which mirrors the "minimal dependency surface" expectation Codex put on
  the first slice. A pluggable :class:`_Transport` protocol leaves the
  door open for a richer HTTP library (e.g. ``httpx``) without rewriting
  the parse layer.
* **Service-to-service auth carried.** Schema-service's
  ``SchemaController`` accepts either ``X-Internal-Api-Key`` or a valid
  JWT (Codex iter-2 §1 in that controller's history). The client takes
  an optional ``internal_api_key`` constructor argument and propagates
  it on every request; absence keeps the dev / test passthrough path
  open.
* **Schema selector.** Schema-service accepts an optional
  ``?schema=`` query parameter to disambiguate yearly / parametric
  ``workcube_mikrolink_<year>`` schemas from the canonical
  ``workcube_mikrolink``. PR-1 carries this through; the runner will
  set it per-ETL-cycle.
* **Fail-closed on contract drift.** ``contract_version`` is validated
  against a caller-supplied set. Mismatch raises
  :class:`SchemaContractVersionMismatch` so an out-of-band schema-service
  upgrade cannot silently change ETL semantics — the worker stops
  instead of corrupting reports_db inserts.
* **Retryable vs terminal split.** 5xx HTTP failures (and the
  network-level "no response" case) surface as
  :class:`SchemaServiceUnavailable` — callers may retry with backoff.
  Everything else (4xx, parse failures, contract mismatch, malformed
  body) is terminal: the worker must abort and let the operator
  reconcile.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Mapping
from typing import Protocol

from .contracts import ColumnSpec, SchemaSnapshot, TableSpec

SUPPORTED_CONTRACT_VERSION = "1"
"""Default Adım 12 target contract version the worker understands.

Caller code may pass a wider tuple via
``SchemaServiceClient(supported_versions=...)``. Bumping this constant
is a deliberate, version-controlled action — see
:class:`SchemaContractVersionMismatch` for rationale.
"""

DEFAULT_SNAPSHOT_PATH = "/api/v1/schema/snapshot"
DEFAULT_TIMEOUT_SECONDS = 10.0
INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key"
"""Mirrors ``SchemaController.INTERNAL_API_KEY_HEADER`` on the
service side."""


class SchemaServiceUnavailable(Exception):
    """schema-service upstream returned 5xx / no response.

    Retryable. Callers may back-off and retry; the worker should not
    advance state on this signal.
    """


class SchemaContractVersionMismatch(Exception):
    """Snapshot ``contract_version`` is not in the supported set.

    Fail-closed. Indicates an out-of-band backend upgrade; the worker
    must abort and an operator must reconcile by either bumping the
    supported set or rolling back schema-service.
    """


class SchemaServiceMalformedResponse(Exception):
    """Response body is not a parseable schema-service snapshot.

    Terminal — includes invalid JSON, wrong root type, missing required
    fields, and any 4xx status the schema-service can return.
    """


class _Transport(Protocol):
    """Minimal HTTP transport interface used by :class:`SchemaServiceClient`.

    The contract is intentionally narrow: a single ``GET`` carrying an
    optional header map and returning ``(status_code, body_bytes)``.
    ``status_code == 0`` is reserved for "transport-level failure, treat
    as retryable upstream outage" so timeouts / DNS / connection-refused
    all surface as :class:`SchemaServiceUnavailable`.
    """

    def get(  # pragma: no cover
        self,
        url: str,
        *,
        timeout: float,
        headers: Mapping[str, str] | None = None,
    ) -> tuple[int, bytes]:
        ...


class _UrllibTransport:
    """Default :class:`_Transport` implementation backed by ``urllib``.

    Maps :class:`urllib.error.URLError` (network / DNS / timeout) to a
    sentinel ``(0, b"")`` tuple so the parse layer can lift it into
    :class:`SchemaServiceUnavailable` without leaking ``urllib`` types
    through the public API.
    """

    def get(
        self,
        url: str,
        *,
        timeout: float,
        headers: Mapping[str, str] | None = None,
    ) -> tuple[int, bytes]:
        request = urllib.request.Request(url, method="GET")
        if headers:
            for name, value in headers.items():
                request.add_header(name, value)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return int(response.status), response.read()
        except urllib.error.HTTPError as exc:
            # ``HTTPError`` is also a ``HTTPResponse``; ``.read()`` is
            # always safe to call here.
            return int(exc.code), exc.read()
        except urllib.error.URLError:
            # Network-level failure (DNS, refused, timeout). Sentinel
            # status 0 signals "treat as retryable upstream outage".
            return 0, b""


class SchemaServiceClient:
    """Typed schema-service snapshot fetcher.

    The client owns *only* the request + parse path. Caller code drives
    retry / backoff (using the typed exceptions to distinguish between
    retryable and terminal failures) and is responsible for what to do
    with the parsed :class:`~etl_worker.contracts.SchemaSnapshot`.
    """

    def __init__(
        self,
        base_url: str,
        *,
        transport: _Transport | None = None,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        supported_versions: tuple[str, ...] = (SUPPORTED_CONTRACT_VERSION,),
        snapshot_path: str = DEFAULT_SNAPSHOT_PATH,
        internal_api_key: str | None = None,
    ) -> None:
        if not base_url:
            raise ValueError("base_url must be non-empty")
        if not supported_versions:
            raise ValueError("supported_versions must contain at least one entry")
        self._base_url = base_url.rstrip("/")
        self._transport: _Transport = transport or _UrllibTransport()
        self._timeout = timeout
        self._supported_versions = supported_versions
        self._snapshot_path = snapshot_path
        self._internal_api_key = internal_api_key

    def fetch_snapshot(self, *, schema: str | None = None) -> SchemaSnapshot:
        """Issue ``GET <base_url>/api/v1/schema/snapshot[?schema=…]`` and parse.

        Parameters
        ----------
        schema:
            Optional schema selector forwarded as ``?schema=`` query
            parameter — mirrors the schema-service ``SchemaController``
            signature so the runner can pick parametric / yearly
            ``workcube_mikrolink_<year>`` shards explicitly. ``None``
            (the default) lets schema-service fall back to its
            configured default schema.

        Raises
        ------
        SchemaServiceUnavailable
            5xx response or transport-level failure. Retryable.
        SchemaServiceMalformedResponse
            4xx response, non-JSON body, wrong shape, or missing
            required fields. Terminal.
        SchemaContractVersionMismatch
            Body parsed but ``contract_version`` is outside the supported
            set. Terminal.
        """
        url = f"{self._base_url}{self._snapshot_path}"
        if schema:
            url = f"{url}?{urllib.parse.urlencode({'schema': schema})}"
        headers: dict[str, str] = {}
        if self._internal_api_key:
            headers[INTERNAL_API_KEY_HEADER] = self._internal_api_key
        status, body = self._transport.get(url, timeout=self._timeout, headers=headers or None)
        if status == 0 or 500 <= status < 600:
            raise SchemaServiceUnavailable(
                f"schema-service unavailable at {url} (status={status})"
            )
        if status != 200:
            raise SchemaServiceMalformedResponse(
                f"schema-service returned unexpected status {status} for {url}"
            )
        try:
            payload = json.loads(body)
        except ValueError as exc:
            raise SchemaServiceMalformedResponse(
                f"schema-service response body is not valid JSON ({exc})"
            ) from exc
        return self._parse_snapshot(payload)

    def _parse_snapshot(self, payload: object) -> SchemaSnapshot:
        if not isinstance(payload, dict):
            raise SchemaServiceMalformedResponse(
                "schema-service response root must be a JSON object"
            )
        contract_version = payload.get("contract_version")
        if not isinstance(contract_version, str) or not contract_version:
            raise SchemaServiceMalformedResponse(
                "schema-service response is missing string 'contract_version'"
            )
        if contract_version not in self._supported_versions:
            raise SchemaContractVersionMismatch(
                "schema-service contract_version "
                f"{contract_version!r} not in supported set "
                f"{self._supported_versions!r}"
            )
        allowlist_name = payload.get("allowlist_name")
        allowlist_version = payload.get("allowlist_version")
        if not isinstance(allowlist_name, str) or not allowlist_name:
            raise SchemaServiceMalformedResponse(
                "schema-service response is missing string 'allowlist_name'"
            )
        if not isinstance(allowlist_version, str) or not allowlist_version:
            raise SchemaServiceMalformedResponse(
                "schema-service response is missing string 'allowlist_version'"
            )
        raw_tables = payload.get("tables")
        if not isinstance(raw_tables, list):
            raise SchemaServiceMalformedResponse(
                "schema-service response field 'tables' must be a list"
            )
        return SchemaSnapshot(
            contract_version=contract_version,
            allowlist_name=allowlist_name,
            allowlist_version=allowlist_version,
            tables=tuple(self._parse_table(entry, index) for index, entry in enumerate(raw_tables)),
        )

    def _parse_table(self, entry: object, index: int) -> TableSpec:
        if not isinstance(entry, dict):
            raise SchemaServiceMalformedResponse(
                f"tables[{index}] must be a JSON object"
            )
        schema = entry.get("schema")
        name = entry.get("name")
        raw_columns = entry.get("columns")
        if not isinstance(schema, str) or not schema:
            raise SchemaServiceMalformedResponse(
                f"tables[{index}].schema must be a non-empty string"
            )
        if not isinstance(name, str) or not name:
            raise SchemaServiceMalformedResponse(
                f"tables[{index}].name must be a non-empty string"
            )
        if not isinstance(raw_columns, list):
            raise SchemaServiceMalformedResponse(
                f"tables[{index}].columns must be a list"
            )
        columns = tuple(
            self._parse_column(col, index, col_index)
            for col_index, col in enumerate(raw_columns)
        )
        return TableSpec(schema=schema, name=name, columns=columns)

    def _parse_column(self, entry: object, table_index: int, column_index: int) -> ColumnSpec:
        prefix = f"tables[{table_index}].columns[{column_index}]"
        if not isinstance(entry, dict):
            raise SchemaServiceMalformedResponse(f"{prefix} must be a JSON object")
        name = entry.get("name")
        type_ = entry.get("type")
        nullable = entry.get("nullable")
        if not isinstance(name, str) or not name:
            raise SchemaServiceMalformedResponse(f"{prefix}.name must be a non-empty string")
        if not isinstance(type_, str) or not type_:
            raise SchemaServiceMalformedResponse(f"{prefix}.type must be a non-empty string")
        if not isinstance(nullable, bool):
            raise SchemaServiceMalformedResponse(f"{prefix}.nullable must be a boolean")
        return ColumnSpec(name=name, type=type_, nullable=nullable)
