"""Typed contract models for the etl-worker → schema-service **target** consumer.

.. important::
   **These models describe the Adım 12 *target* contract, not the
   shape schema-service currently emits.** Today ``GET /api/v1/schema/snapshot``
   returns ``version`` (not ``contract_version``), a ``metadata`` block,
   a ``tables`` *map* keyed by table name, and a column ``dataType``
   (see ``schema-service`` ``SchemaSnapshot.java`` / ``ColumnInfo.java``).
   The model below is what Adım 12 PR-2+ needs schema-service to start
   emitting (``contract_version`` + ``allowlist_name`` /
   ``allowlist_version`` + ``tables`` as a list with ``type`` columns)
   before the runner can be wired live. PR-1 ships the consumer side
   under unit tests so the schema-service contract evolution has a
   concrete acceptance target.

Only fields that the ETL worker actually consumes are modelled here;
unknown JSON keys are silently ignored so additive backend changes do
not break the worker.

Adım 12 PR-1 scope keeps the model deliberately minimal:

* table identity: ``schema`` + ``name``
* per-column metadata: ``name``, ``type``, ``nullable``
* allowlist provenance: ``name`` + ``version`` (matches the report-service
  ``ReportingAllowlist.V1`` discipline established in Adım 11.1)
* contract version: explicit string carried so the client can fail-closed
  on version mismatch (see
  :class:`etl_worker.schema_service_client.SchemaContractVersionMismatch`)

Future PRs may extend these dataclasses (e.g. column ``length``, table
``schema_mode``, parametric ``yearly_schemas`` list) — those additions
stay backwards compatible because the parser ignores unknown keys.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class ColumnSpec:
    """One column inside a schema-service table snapshot."""

    name: str
    type: str
    nullable: bool


@dataclass(frozen=True, slots=True)
class TableSpec:
    """One table inside a schema-service snapshot.

    ``schema`` and ``name`` together uniquely identify the table within
    a snapshot. ``columns`` preserves the order returned by
    schema-service so downstream consumers (e.g. the future pyodbc
    SELECT generator) can rely on a stable layout.
    """

    schema: str
    name: str
    columns: tuple[ColumnSpec, ...]


@dataclass(frozen=True, slots=True)
class SchemaSnapshot:
    """Top-level schema-service contract response model.

    ``contract_version`` is a free-form string the client validates
    against the set of versions it supports
    (``SchemaServiceClient.__init__(supported_versions=...)``); unknown
    versions fail-closed with
    :class:`etl_worker.schema_service_client.SchemaContractVersionMismatch`
    so a backwards-incompatible backend change cannot silently corrupt
    an ETL run.

    ``allowlist_name`` + ``allowlist_version`` mirror the
    ``ReportingAllowlist.V1`` discipline from report-service (Adım 11.1):
    the worker must know exactly which named allowlist gated the
    snapshot it consumed, so audit logs and reports_db inserts can be
    traced back to a specific contract revision.
    """

    contract_version: str
    allowlist_name: str
    allowlist_version: str
    tables: tuple[TableSpec, ...]
