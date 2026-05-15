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
import os
import sys
from collections.abc import Mapping, Sequence
from typing import Protocol, TextIO

from .config import Config, ConfigError
from .contracts import SchemaSnapshot
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
            "Workcube reporting ETL worker. Adım 12 PR-2a — config / CLI "
            "wiring for the schema-service contract consumer."
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
    return parser


def _parse_cli_timeout(raw: str) -> float:
    """argparse type-converter that funnels through the same contract as env parsing."""
    try:
        value = float(raw)
    except (TypeError, ValueError) as exc:
        raise argparse.ArgumentTypeError(
            "--timeout must be a positive number"
        ) from exc
    if value <= 0:
        raise argparse.ArgumentTypeError("--timeout must be a positive number")
    return value


def main(
    argv: Sequence[str] | None = None,
    *,
    env: Mapping[str, str] | None = None,
    stdout: TextIO | None = None,
    stderr: TextIO | None = None,
    client_factory: _ClientFactory = _default_client_factory,
) -> int:
    """Resolve config, parse CLI, fetch a snapshot, emit the JSON summary.

    All side-effecting inputs (``argv`` / ``env`` / ``stdout`` /
    ``stderr`` / ``client_factory``) are injectable so the CLI is
    hermetic under pytest.

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

    try:
        snapshot = client.fetch_snapshot(schema=schema_override)
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
