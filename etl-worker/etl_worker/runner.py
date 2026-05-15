"""Runner: schema-service fetch + retry + JSON summary.

Adım 12 PR-2b1 (reporting refactor plan §400 — runner retry
foundation). The runner orchestrates exactly one ``fetch_snapshot``
call against the retry/backoff contract from
:mod:`etl_worker.retry`. Audit log persistence, checkpoint files,
resume semantics, and reports_db writes are explicitly *not* in
this slice; they ship in PR-2b2 + PR-2b3.

Output contract:

* On success: :class:`RunResult` carrying ``summary`` (the same shape
  the ``fetch-snapshot`` subcommand prints — ``contract_version``,
  ``allowlist_name``, ``allowlist_version``, ``table_count``,
  ``column_count``) plus ``attempts`` so callers see how many tries
  were needed.
* On failure: the underlying typed exception bubbles up unchanged
  (``SchemaServiceUnavailable``, ``SchemaServiceMalformedResponse``,
  or ``SchemaContractVersionMismatch``). The CLI ``run`` subcommand
  translates these to ``EX_TEMPFAIL`` / ``EX_SOFTWARE`` /
  ``EX_PROTOCOL`` respectively, reusing the PR-2a exit-code matrix.

Naming convention (Codex 019e2a5c): this is "runner **retry
foundation**", not "runner orchestration complete". Future PRs build
on top.
"""

from __future__ import annotations

from dataclasses import dataclass

from .retry import RetryPolicy, Sleeper, call_with_retry
from .schema_service_client import (
    SchemaServiceClient,
    SchemaServiceUnavailable,
)


@dataclass(frozen=True, slots=True)
class RunResult:
    """Successful fetch outcome carried back to the CLI layer.

    ``summary`` mirrors the ``fetch-snapshot`` subcommand's stdout
    JSON shape so operators see the same fields across both commands.
    ``attempts`` is added so a ``run`` invocation can report retry
    activity — useful when a transient outage was absorbed and the
    operator needs evidence the worker actually retried.
    """

    summary: dict[str, object]
    attempts: int


def run_fetch(
    *,
    client: SchemaServiceClient,
    schema: str | None,
    policy: RetryPolicy,
    sleeper: Sleeper,
) -> RunResult:
    """Fetch one schema-service snapshot honouring the retry policy.

    Parameters
    ----------
    client:
        Pre-configured :class:`SchemaServiceClient` (the CLI builds it
        from :class:`~etl_worker.config.Config`; tests inject a fake).
    schema:
        Optional ``?schema=`` selector forwarded to
        :meth:`SchemaServiceClient.fetch_snapshot`.
    policy:
        Bounded backoff policy; ``max_attempts == 1`` disables retries.
    sleeper:
        Injectable sleep implementation. Production wires
        :class:`~etl_worker.retry.SystemSleeper`; tests pass a fake.

    Raises
    ------
    SchemaServiceUnavailable
        Retry budget exhausted; surfaced unchanged so the CLI can
        return ``EX_TEMPFAIL``.
    SchemaServiceMalformedResponse
        Terminal parse / 4xx failure. Not retried.
    SchemaContractVersionMismatch
        Terminal protocol drift. Not retried.
    """
    snapshot, attempts = call_with_retry(
        lambda: client.fetch_snapshot(schema=schema),
        retryable=SchemaServiceUnavailable,
        policy=policy,
        sleeper=sleeper,
    )
    column_count = sum(len(table.columns) for table in snapshot.tables)
    summary: dict[str, object] = {
        "contract_version": snapshot.contract_version,
        "allowlist_name": snapshot.allowlist_name,
        "allowlist_version": snapshot.allowlist_version,
        "table_count": len(snapshot.tables),
        "column_count": column_count,
    }
    return RunResult(summary=summary, attempts=attempts)
