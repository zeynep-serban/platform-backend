"""Workcube reporting ETL worker.

Adım 12 (reporting refactor plan — docs/plan-reporting-refactor-2026-05-14.md
in platform-k8s-gitops): schema-service contract consumer. This package
holds the typed client + contract models. Runner orchestration and live
MSSQL / reports_db wiring land in subsequent PRs.

Provisional monorepo location: ``platform-backend/etl-worker/``. If a
future ADR moves this to a standalone repo (``Halildeu/etl-worker``),
``git filter-repo`` preserves history. See ``README.md``.
"""

from .audit import (
    SCHEMA_VERSION as _AUDIT_SCHEMA_VERSION_RAW,
)
from .audit import (
    AuditEvent,
    AuditWriter,
    JsonLinesAuditWriter,
    build_event,
)
from .checkpoint import (
    SCHEMA_VERSION as _CHECKPOINT_SCHEMA_VERSION_RAW,
)
from .checkpoint import (
    Checkpoint,
    CheckpointError,
    CheckpointFile,
    build_checkpoint,
)
from .config import Config, ConfigError
from .contracts import ColumnSpec, SchemaSnapshot, TableSpec
from .db import (
    NoopReportsDbWriter,
    ReportsDbWriteError,
    ReportsDbWriter,
    ReportsDbWriteResult,
)
from .retry import RetryPolicy, Sleeper, SystemSleeper, call_with_retry
from .runner import RunResult, run_fetch
from .schema_service_client import (
    SchemaContractVersionMismatch,
    SchemaServiceClient,
    SchemaServiceMalformedResponse,
    SchemaServiceUnavailable,
)

__all__ = [
    "AUDIT_SCHEMA_VERSION",
    "CHECKPOINT_SCHEMA_VERSION",
    "AuditEvent",
    "AuditWriter",
    "Checkpoint",
    "CheckpointError",
    "CheckpointFile",
    "ColumnSpec",
    "Config",
    "ConfigError",
    "JsonLinesAuditWriter",
    "NoopReportsDbWriter",
    "ReportsDbWriteError",
    "ReportsDbWriteResult",
    "ReportsDbWriter",
    "RetryPolicy",
    "RunResult",
    "SchemaContractVersionMismatch",
    "SchemaServiceClient",
    "SchemaServiceMalformedResponse",
    "SchemaServiceUnavailable",
    "SchemaSnapshot",
    "Sleeper",
    "SystemSleeper",
    "TableSpec",
    "build_checkpoint",
    "build_event",
    "call_with_retry",
    "run_fetch",
]

AUDIT_SCHEMA_VERSION = _AUDIT_SCHEMA_VERSION_RAW
"""Public alias for the audit event schema version constant."""

CHECKPOINT_SCHEMA_VERSION = _CHECKPOINT_SCHEMA_VERSION_RAW
"""Public alias for the checkpoint payload schema version constant."""
