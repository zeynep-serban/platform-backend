"""reports_db writer interface for the runner.

Adım 12 PR-2b2b/2b3 (reporting refactor plan §400 — checkpoint + DB
writer interface combined slice). Codex ``019e2a5c`` Checkpoint-A'
discipline: this PR ships the **Protocol** + a hermetic
:class:`NoopReportsDbWriter` test/dev default. A real pyodbc / psycopg
driver lands in PR-3 (Docker / K8s wiring) so this slice stays
hermetic — no Testcontainers required.

Design contract:

* **Protocol-only** — ``ReportsDbWriter`` describes the runner ⇄
  database boundary. Real adapters live behind this Protocol; the
  runner never imports a driver directly.
* **Transaction boundary** — :meth:`ReportsDbWriter.upsert` returns
  a :class:`ReportsDbWriteResult` on success or raises a typed
  :class:`ReportsDbWriteError` on failure. The runner emits
  ``db_upsert_started`` *before* the call, then
  ``db_upsert_completed`` (with the result's ``rows_written``) on
  success or ``db_upsert_failed`` (with the error class) on failure.
* **No writer default** — the runner accepts ``db_writer=None``
  (PR-2b1/PR-2b2a byte-compat) and *does not* substitute a
  :class:`NoopReportsDbWriter`. The Noop variant lives here as a
  test fake + explicit dev wiring helper; the byte-compat path
  takes the ``None`` branch.

Exit-code mapping (CLI layer):

* ``ReportsDbWriteError`` → ``EX_TEMPFAIL=75`` (operational failure;
  caller may retry the whole job)
* :class:`~etl_worker.checkpoint.CheckpointError` is wired in CLI as
  ``EX_SOFTWARE=70`` because malformed local state is terminal,
  not retryable.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True, slots=True)
class ReportsDbWriteResult:
    """Successful outcome of one :meth:`ReportsDbWriter.upsert` call."""

    rows_written: int


class ReportsDbWriteError(Exception):
    """Operational failure during a :meth:`ReportsDbWriter.upsert` call.

    Surfaced to the CLI as ``EX_TEMPFAIL=75`` so an outer scheduler
    can decide whether to re-queue the job. The runner never
    swallows this; it bubbles up after emitting
    ``db_upsert_failed``.
    """


class ReportsDbWriter(Protocol):
    """Runner ⇄ reports_db boundary.

    Implementations promise:

    * ``upsert(summary)`` is **idempotent** with respect to
      ``(snapshot_signature, contract_version)`` — calling it twice
      with the same summary leaves the same rows.
    * Failures raise :class:`ReportsDbWriteError`; success returns
      :class:`ReportsDbWriteResult`.
    * The implementation owns its own connection / transaction
      lifecycle; the runner is unaware of driver state.
    """

    def upsert(
        self,
        summary: dict[str, object],
    ) -> ReportsDbWriteResult:  # pragma: no cover - Protocol
        ...


class NoopReportsDbWriter:
    """Test/dev default that records calls but performs no DB write.

    Useful in two scenarios:

    * **Tests** — assert the runner invokes ``upsert`` with the
      expected summary without spinning up Testcontainers.
    * **Dev wiring** — operators running ``etl-worker run`` against a
      target-contract-only schema-service in dev can use this to
      exercise the audit + checkpoint lifecycle without writing to
      a real reports_db.

    The runner does **not** default to this writer; ``db_writer=None``
    keeps PR-2b1 / PR-2b2a byte-compat behaviour. Wire this writer
    explicitly via :func:`etl_worker.cli.main` when needed.
    """

    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    def upsert(self, summary: dict[str, object]) -> ReportsDbWriteResult:
        # Capture a shallow copy so the runner's later mutations to
        # ``summary`` (e.g. adding ``attempts``) do not retroactively
        # change the recorded call.
        self.calls.append(dict(summary))
        return ReportsDbWriteResult(rows_written=len(self.calls))
