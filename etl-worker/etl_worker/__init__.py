"""Workcube reporting ETL worker.

Adım 12 (reporting refactor plan — docs/plan-reporting-refactor-2026-05-14.md
in platform-k8s-gitops): schema-service contract consumer. This package
holds the typed client + contract models. Runner orchestration and live
MSSQL / reports_db wiring land in subsequent PRs.

Provisional monorepo location: ``platform-backend/etl-worker/``. If a
future ADR moves this to a standalone repo (``Halildeu/etl-worker``),
``git filter-repo`` preserves history. See ``README.md``.
"""

from .config import Config, ConfigError
from .contracts import ColumnSpec, SchemaSnapshot, TableSpec
from .retry import RetryPolicy, Sleeper, SystemSleeper, call_with_retry
from .runner import RunResult, run_fetch
from .schema_service_client import (
    SchemaContractVersionMismatch,
    SchemaServiceClient,
    SchemaServiceMalformedResponse,
    SchemaServiceUnavailable,
)

__all__ = [
    "ColumnSpec",
    "Config",
    "ConfigError",
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
    "call_with_retry",
    "run_fetch",
]
