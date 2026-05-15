"""``etl-worker`` console-script entry point.

Adım 12 PR-2a (reporting refactor plan §400). The CLI owns the
contract that operator tooling + future CI smoke runs depend on:

* one subcommand, ``fetch-snapshot``
* env-driven defaults via :class:`~etl_worker.config.Config`,
  overridable per-invocation via ``--schema`` / ``--timeout``
* sysexits-style exit codes (see :data:`EXIT_CODES`) so callers can
  retry only the transport-level failures and never retry the
  contract-level ones
* deterministic JSON success line on stdout — one line, no logging
  noise, no secret-bearing values

Logging is **not** configured here. PR-2b adds retry / audit / resume
logging once the runner orchestration lands; until then the CLI is
silent on success and writes a single human-readable error line to
stderr on failure.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
from collections.abc import Callable, Mapping, Sequence
from typing import Protocol, TextIO

from .audit import AuditWriter, JsonLinesAuditWriter
from .checkpoint import CheckpointError, CheckpointFile
from .config import Config, ConfigError
from .contracts import SchemaSnapshot
from .db import ReportsDbWriteError, ReportsDbWriter
from .retry import RetryPolicy, Sleeper, SystemSleeper
from .runner import RunResult, run_fetch
from .schema_service_client import (
    SchemaContractVersionMismatch,
    SchemaServiceClient,
    SchemaServiceMalformedResponse,
    SchemaServiceUnavailable,
)

EX_OK = 0
EX_USAGE = 64
"""Bad CLI args or invalid / missing configuration."""

EX_SOFTWARE = 70
"""4xx response, parse failure, or other malformed response."""

EX_TEMPFAIL = 75
"""5xx response or transport-level outage — caller may retry."""

EX_PROTOCOL = 76
"""Contract version mismatch — terminal, distinct from generic malformed."""

EXIT_CODES = {
    "ok": EX_OK,
    "usage": EX_USAGE,
    "software": EX_SOFTWARE,
    "tempfail": EX_TEMPFAIL,
    "protocol": EX_PROTOCOL,
}


class _ClientFactory(Protocol):
    """Factory contract for the CLI's HTTP client construction.

    Tests inject a stub so no live HTTP fires. Production callers use
    the default :class:`~etl_worker.schema_service_client.SchemaServiceClient`.
    """

    def __call__(
        self,
        base_url: str,
        *,
        timeout: float,
        supported_versions: tuple[str, ...],
        internal_api_key: str | None,
    ) -> SchemaServiceClient:  # pragma: no cover - protocol
        ...


def _default_client_factory(
    base_url: str,
    *,
    timeout: float,
    supported_versions: tuple[str, ...],
    internal_api_key: str | None,
) -> SchemaServiceClient:
    return SchemaServiceClient(
        base_url=base_url,
        timeout=timeout,
        supported_versions=supported_versions,
        internal_api_key=internal_api_key,
    )


class _NonExitingParser(argparse.ArgumentParser):
    """``argparse`` parser that surfaces usage errors as exceptions.

    The default :class:`argparse.ArgumentParser` exits with status ``2``
    on bad input — bypassing the :data:`EX_USAGE` ``64`` contract. We
    raise a typed :class:`_UsageError` instead so :func:`main` can
    translate consistently.
    """

    def error(self, message: str) -> None:  # type: ignore[override]
        raise _UsageError(message)


class _UsageError(Exception):
    """Internal signal for argparse-level usage failures."""


def _build_parser() -> _NonExitingParser:
    parser = _NonExitingParser(
        prog="etl-worker",
        description=(
            "Workcube reporting ETL worker. Adım 12 schema-service "
            "contract consumer (PR-2a CLI + PR-2b1 runner retry "
            "foundation)."
        ),
    )
    sub = parser.add_subparsers(dest="command", required=True)

    fetch = sub.add_parser(
        "fetch-snapshot",
        help="Fetch a schema-service snapshot and print a JSON summary.",
        description=(
            "Fetches GET /api/v1/schema/snapshot from the configured "
            "schema-service URL. Prints a one-line JSON summary "
            "{contract_version, allowlist_name, allowlist_version, "
            "table_count, column_count} to stdout on success. Errors "
            "go to stderr with a sysexits-style exit code."
        ),
    )
    fetch.add_argument(
        "--schema",
        default=None,
        help=(
            "Override SCHEMA_SERVICE_SCHEMA. Forwarded to schema-service "
            "as the ?schema= query parameter."
        ),
    )
    fetch.add_argument(
        "--timeout",
        type=_parse_cli_timeout,
        default=None,
        help=(
            "Override SCHEMA_SERVICE_TIMEOUT_SECONDS (positive float, "
            "seconds)."
        ),
    )

    run = sub.add_parser(
        "run",
        help=(
            "Run a single ETL fetch cycle with bounded retry / backoff "
            "(PR-2b1 retry foundation; audit + resume + DB writes are "
            "out of scope until PR-2b2 / PR-2b3)."
        ),
        description=(
            "Like fetch-snapshot but with retry semantics: transient 5xx "
            "/ transport outages are retried up to --retry-attempts "
            "times with exponential backoff capped at --retry-cap-seconds. "
            "Malformed (4xx / parse) and contract-version-mismatch "
            "failures are NOT retried. The stdout JSON summary adds an "
            "`attempts` field so operators can see retry activity."
        ),
    )
    run.add_argument(
        "--schema",
        default=None,
        help=(
            "Override SCHEMA_SERVICE_SCHEMA. Forwarded to schema-service "
            "as the ?schema= query parameter."
        ),
    )
    run.add_argument(
        "--timeout",
        type=_parse_cli_timeout,
        default=None,
        help=(
            "Override SCHEMA_SERVICE_TIMEOUT_SECONDS (positive float, "
            "seconds)."
        ),
    )
    run.add_argument(
        "--retry-attempts",
        type=_parse_positive_int,
        default=3,
        help="Total attempts (>=1, default 3). 1 disables retry.",
    )
    run.add_argument(
        "--retry-initial-seconds",
        type=_parse_non_negative_float,
        default=1.0,
        help="Initial backoff delay in seconds (default 1.0).",
    )
    run.add_argument(
        "--retry-multiplier",
        type=_parse_multiplier_float,
        default=2.0,
        help="Exponential growth factor (>=1.0, default 2.0).",
    )
    run.add_argument(
        "--retry-cap-seconds",
        type=_parse_non_negative_float,
        default=30.0,
        help=(
            "Upper bound on any single sleep delay (default 30.0). "
            "Must be >= --retry-initial-seconds."
        ),
    )
    run.add_argument(
        "--audit-path",
        default=None,
        help=(
            "Path to a JSON Lines audit file. When provided the runner "
            "emits run_started / attempt_* / run_* events. Absent (default) "
            "= no audit side effect (PR-2b1 behaviour preserved)."
        ),
    )
    run.add_argument(
        "--run-id",
        default=None,
        help=(
            "Optional caller-supplied run identifier. Forwarded to the "
            "audit events and surfaced in the stdout JSON summary so "
            "operators can correlate stdout / stderr / audit log lines. "
            "Defaults to a freshly generated uuid4 hex."
        ),
    )
    run.add_argument(
        "--checkpoint-path",
        default=None,
        help=(
            "Path to a JSON checkpoint file. When provided the runner "
            "atomically writes a checkpoint after a successful DB upsert "
            "(or after a successful fetch when no --db-writer is "
            "configured). Absent (default) = no checkpoint side effect."
        ),
    )
    run.add_argument(
        "--resume",
        action="store_true",
        help=(
            "When set together with --checkpoint-path, load the existing "
            "checkpoint and emit a checkpoint_loaded audit event so "
            "operators see the resume cursor. PR-2b2b scope: this flag "
            "does NOT short-circuit fetch/apply — true idempotency is "
            "deferred to a later slice once a real DB driver lands."
        ),
    )
    return parser


def _parse_cli_timeout(raw: str) -> float:
    """argparse type-converter that funnels through the same contract as env parsing.

    Like :func:`config._parse_timeout`, ``nan`` / ``inf`` are rejected
    via :func:`math.isfinite` so a malformed numeric flag cannot
    silently pass and later be misclassified as an upstream failure.
    """
    try:
        value = float(raw)
    except (TypeError, ValueError) as exc:
        raise argparse.ArgumentTypeError(
            "--timeout must be a positive finite number"
        ) from exc
    if not math.isfinite(value) or value <= 0:
        raise argparse.ArgumentTypeError("--timeout must be a positive finite number")
    return value


def _parse_positive_int(raw: str) -> int:
    try:
        value = int(raw)
    except (TypeError, ValueError) as exc:
        raise argparse.ArgumentTypeError("must be a positive integer") from exc
    if value < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return value


def _parse_non_negative_float(raw: str) -> float:
    try:
        value = float(raw)
    except (TypeError, ValueError) as exc:
        raise argparse.ArgumentTypeError("must be a non-negative finite number") from exc
    if not math.isfinite(value) or value < 0:
        raise argparse.ArgumentTypeError("must be a non-negative finite number")
    return value


def _parse_multiplier_float(raw: str) -> float:
    try:
        value = float(raw)
    except (TypeError, ValueError) as exc:
        raise argparse.ArgumentTypeError("must be a finite number >= 1.0") from exc
    if not math.isfinite(value) or value < 1.0:
        raise argparse.ArgumentTypeError("must be a finite number >= 1.0")
    return value


def main(
    argv: Sequence[str] | None = None,
    *,
    env: Mapping[str, str] | None = None,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
    client_factory: _ClientFactory = _default_client_factory,
    sleeper: Sleeper | None = None,
    audit_factory: Callable[[str], AuditWriter] | None = None,
    checkpoint_factory: Callable[[str], CheckpointFile] | None = None,
    db_writer: ReportsDbWriter | None = None,
) -> int:
    """Resolve config, parse CLI, fetch a snapshot, emit the JSON summary.

    All side-effecting inputs (``argv`` / ``env`` / ``stdout`` /
    ``stderr`` / ``client_factory`` / ``sleeper``) are injectable so
    the CLI is hermetic under pytest.

    ``sleeper`` only matters for the ``run`` subcommand; it defaults to
    :class:`~etl_worker.retry.SystemSleeper` in production, and tests
    pass a deterministic fake so retry tests never actually block.

    Returns
    -------
    int
        sysexits-style exit code. ``0`` on success; ``64`` for any
        usage / config error; ``70`` for parse / malformed responses;
        ``75`` for retryable upstream outages; ``76`` for contract
        version mismatch.
    """
    out = stdout if stdout is not None else sys.stdout
    err = stderr if stderr is not None else sys.stderr
    parser = _build_parser()
    try:
        args = parser.parse_args(list(argv) if argv is not None else None)
    except _UsageError as exc:
        print(f"etl-worker: usage: {exc}", file=err)
        return EX_USAGE
    except SystemExit as exc:
        # ``-h`` / ``--help`` bypass _UsageError because argparse calls
        # ``parser.print_help()`` then ``sys.exit(0)``. We honour that
        # signal as a usage success but still return the normalised
        # code so the caller's exit-code contract holds.
        if exc.code in (0, None):
            return EX_OK
        return EX_USAGE

    try:
        config = Config.from_env(env if env is not None else os.environ)
    except ConfigError as exc:
        print(f"etl-worker: config error: {exc}", file=err)
        return EX_USAGE

    schema_override = args.schema if args.schema else config.schema_service_schema
    timeout_override = (
        args.timeout
        if args.timeout is not None
        else config.schema_service_timeout_seconds
    )

    client = client_factory(
        config.schema_service_url,
        timeout=timeout_override,
        supported_versions=config.schema_service_contract_versions,
        internal_api_key=config.schema_service_internal_api_key,
    )

    if args.command == "fetch-snapshot":
        return _handle_fetch_snapshot(client, schema_override, out, err)
    if args.command == "run":
        return _handle_run(
            client,
            schema_override,
            args,
            sleeper,
            out,
            err,
            audit_factory,
            checkpoint_factory,
            db_writer,
        )
    # ``argparse`` with ``required=True`` should make this branch
    # unreachable; treat any other command as a usage failure.
    print(f"etl-worker: usage: unknown command '{args.command}'", file=err)
    return EX_USAGE


def _handle_fetch_snapshot(
    client: SchemaServiceClient,
    schema: str | None,
    out: TextIO,
    err: TextIO,
) -> int:
    try:
        snapshot = client.fetch_snapshot(schema=schema)
    except SchemaServiceUnavailable as exc:
        print(f"etl-worker: upstream unavailable: {exc}", file=err)
        return EX_TEMPFAIL
    except SchemaContractVersionMismatch as exc:
        print(f"etl-worker: contract version mismatch: {exc}", file=err)
        return EX_PROTOCOL
    except SchemaServiceMalformedResponse as exc:
        print(f"etl-worker: malformed response: {exc}", file=err)
        return EX_SOFTWARE

    summary = _summarise(snapshot)
    print(json.dumps(summary, separators=(",", ":")), file=out)
    return EX_OK


def _handle_run(
    client: SchemaServiceClient,
    schema: str | None,
    args: argparse.Namespace,
    sleeper: Sleeper | None,
    out: TextIO,
    err: TextIO,
    audit_factory: Callable[[str], AuditWriter] | None = None,
    checkpoint_factory: Callable[[str], CheckpointFile] | None = None,
    db_writer: ReportsDbWriter | None = None,
) -> int:
    try:
        policy = RetryPolicy(
            max_attempts=args.retry_attempts,
            initial_seconds=args.retry_initial_seconds,
            multiplier=args.retry_multiplier,
            cap_seconds=args.retry_cap_seconds,
        )
    except ValueError as exc:
        # Inter-field validation (e.g. cap < initial) — surface as
        # usage error so the caller can fix the invocation.
        print(f"etl-worker: usage: retry policy: {exc}", file=err)
        return EX_USAGE

    # Build the audit writer when the operator asks for one. The
    # ``audit_factory`` parameter is for tests that want to inject an
    # in-memory writer without hitting the filesystem; production
    # code defaults to :class:`JsonLinesAuditWriter`.
    #
    # Audit sink creation can fail with ``OSError`` (missing
    # directory it cannot create, permission denied, etc.). Codex
    # 019e2a5c REVISE absorb: trap that here so an unwritable audit
    # path surfaces as a clean ``EX_SOFTWARE`` with a one-line
    # stderr message rather than a Python traceback escaping the
    # CLI exit-code contract.
    audit: AuditWriter | None = None
    if args.audit_path is not None:
        try:
            if audit_factory is not None:
                audit = audit_factory(args.audit_path)
            else:
                audit = JsonLinesAuditWriter(args.audit_path)
        except OSError as exc:
            print(f"etl-worker: audit error: {exc}", file=err)
            return EX_SOFTWARE

    # Codex 019e2a5c REVISE absorb: ``--resume`` without
    # ``--checkpoint-path`` is a misinvocation; fail-closed with
    # ``EX_USAGE=64`` so the operator sees the contract drift before
    # any side effect.
    if args.resume and args.checkpoint_path is None:
        print(
            "etl-worker: usage: --resume requires --checkpoint-path",
            file=err,
        )
        return EX_USAGE

    # Build the checkpoint file when the operator asks for one. Same
    # OSError-handling discipline as the audit writer: typed
    # ``EX_SOFTWARE`` exit + one-line stderr on construction failure.
    checkpoint: CheckpointFile | None = None
    if args.checkpoint_path is not None:
        try:
            if checkpoint_factory is not None:
                checkpoint = checkpoint_factory(args.checkpoint_path)
            else:
                checkpoint = CheckpointFile(args.checkpoint_path)
        except OSError as exc:
            print(f"etl-worker: checkpoint error: {exc}", file=err)
            return EX_SOFTWARE

    active_sleeper: Sleeper = sleeper if sleeper is not None else SystemSleeper()
    try:
        result: RunResult = run_fetch(
            client=client,
            schema=schema,
            policy=policy,
            sleeper=active_sleeper,
            audit=audit,
            run_id=args.run_id,
            db_writer=db_writer,
            checkpoint=checkpoint,
            resume=args.resume,
        )
    except SchemaServiceUnavailable as exc:
        print(
            f"etl-worker: upstream unavailable after {args.retry_attempts} "
            f"attempts: {exc}",
            file=err,
        )
        return EX_TEMPFAIL
    except SchemaContractVersionMismatch as exc:
        print(f"etl-worker: contract version mismatch: {exc}", file=err)
        return EX_PROTOCOL
    except SchemaServiceMalformedResponse as exc:
        print(f"etl-worker: malformed response: {exc}", file=err)
        return EX_SOFTWARE
    except ReportsDbWriteError as exc:
        # DB upsert failure is an operational failure: surface as
        # ``EX_TEMPFAIL`` (75) so an outer scheduler can re-queue.
        print(f"etl-worker: db write error: {exc}", file=err)
        return EX_TEMPFAIL
    except CheckpointError as exc:
        # Malformed checkpoint at load time: terminal, fix-local-state.
        print(f"etl-worker: checkpoint error: {exc}", file=err)
        return EX_SOFTWARE
    except OSError as exc:
        # Audit/checkpoint sink failure during a write surfaces as
        # ``OSError``; trap with ``EX_SOFTWARE`` so the CLI never leaks
        # a traceback. (The construction-time variant is already
        # handled above where the sink is built.) Codex 019e2a5c
        # REVISE absorb: use a neutral "persistence error" label rather
        # than "audit error" so a checkpoint write failure is not
        # mis-reported as an audit failure to the operator.
        print(f"etl-worker: persistence error: {exc}", file=err)
        return EX_SOFTWARE

    summary = dict(result.summary)
    summary["attempts"] = result.attempts
    summary["run_id"] = result.run_id
    print(json.dumps(summary, separators=(",", ":")), file=out)
    return EX_OK


def _summarise(snapshot: SchemaSnapshot) -> dict[str, object]:
    """Build the deterministic stdout JSON summary."""
    column_count = sum(len(table.columns) for table in snapshot.tables)
    return {
        "contract_version": snapshot.contract_version,
        "allowlist_name": snapshot.allowlist_name,
        "allowlist_version": snapshot.allowlist_version,
        "table_count": len(snapshot.tables),
        "column_count": column_count,
    }
