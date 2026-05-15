"""Environment-driven configuration for the etl-worker CLI.

Adım 12 PR-2a (reporting refactor plan §400 — config / CLI / client
wiring follow-up to PR-1's ``SchemaServiceClient`` scaffold). This
module is intentionally **library-testable**: nothing here touches
``os.environ`` directly outside :meth:`Config.from_env`, so unit
tests pass an explicit ``env`` mapping and never depend on process
state.

Environment variables consumed:

* ``SCHEMA_SERVICE_URL`` — required. Base URL of the schema-service
  HTTP endpoint (e.g. ``http://schema-service.platform-test:8096``).
  Must parse as ``http://`` / ``https://`` with a non-empty host and
  carry no embedded credentials (those belong in
  ``SCHEMA_SERVICE_INTERNAL_API_KEY``). Trailing slashes are normalised
  off so the resulting URL is canonical.
* ``SCHEMA_SERVICE_INTERNAL_API_KEY`` — optional. Forwarded as the
  ``X-Internal-Api-Key`` header on every request, matching the
  schema-service ``SchemaController`` auth pathway. Blank means the
  passthrough path (dev / test profile) is in use.
* ``SCHEMA_SERVICE_TIMEOUT_SECONDS`` — optional. HTTP request timeout
  in seconds. Default 10. Must parse as a positive ``float``.
* ``SCHEMA_SERVICE_SCHEMA`` — optional. Default value for the
  ``?schema=`` query selector. CLI ``--schema`` overrides; absence
  lets schema-service fall back to its configured default schema.
* ``SCHEMA_SERVICE_CONTRACT_VERSIONS`` — optional. CSV of contract
  versions the worker accepts. Default ``"1"`` matching
  :data:`etl_worker.schema_service_client.SUPPORTED_CONTRACT_VERSION`.
  Whitespace around each entry is trimmed; empty values reject.

All validation errors raise :class:`ConfigError`; the CLI layer is
responsible for translating those to exit code ``64`` (``EX_USAGE``)
without leaking secret-bearing values to stderr.
"""

from __future__ import annotations

import math
import os
from collections.abc import Mapping
from dataclasses import dataclass
from urllib.parse import urlparse

from .schema_service_client import SUPPORTED_CONTRACT_VERSION

DEFAULT_TIMEOUT_SECONDS = 10.0
"""CLI default when ``SCHEMA_SERVICE_TIMEOUT_SECONDS`` is unset."""


class ConfigError(ValueError):
    """Configuration validation failed.

    Library-only error type; the CLI translates this to ``exit 64``.
    """


@dataclass(frozen=True, slots=True)
class Config:
    """Immutable resolved configuration for one CLI invocation."""

    schema_service_url: str
    schema_service_internal_api_key: str | None
    schema_service_timeout_seconds: float
    schema_service_schema: str | None
    schema_service_contract_versions: tuple[str, ...]

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> Config:
        """Resolve a :class:`Config` from a process environment mapping.

        Parameters
        ----------
        env:
            Mapping of environment variables. Defaults to ``os.environ``
            so callers (the CLI) can simply ``Config.from_env()``. Unit
            tests should always pass an explicit dict to keep
            assertions deterministic.

        Raises
        ------
        ConfigError
            Any environment value fails validation. The message is
            safe to surface to stderr; it never echoes the offending
            value if that value might be a secret.
        """
        source = env if env is not None else os.environ

        raw_url = source.get("SCHEMA_SERVICE_URL", "").strip()
        if not raw_url:
            raise ConfigError("SCHEMA_SERVICE_URL is required")
        url = _validate_url(raw_url)

        raw_key = source.get("SCHEMA_SERVICE_INTERNAL_API_KEY")
        if raw_key is not None:
            raw_key = raw_key.strip()
        internal_api_key = raw_key if raw_key else None

        timeout = _parse_timeout(source.get("SCHEMA_SERVICE_TIMEOUT_SECONDS"))

        raw_schema = source.get("SCHEMA_SERVICE_SCHEMA")
        if raw_schema is not None:
            raw_schema = raw_schema.strip()
        schema = raw_schema if raw_schema else None

        versions = _parse_contract_versions(source.get("SCHEMA_SERVICE_CONTRACT_VERSIONS"))

        return cls(
            schema_service_url=url,
            schema_service_internal_api_key=internal_api_key,
            schema_service_timeout_seconds=timeout,
            schema_service_schema=schema,
            schema_service_contract_versions=versions,
        )


def _validate_url(raw: str) -> str:
    """Validate + canonicalise ``SCHEMA_SERVICE_URL``.

    Accepts ``http://`` / ``https://`` schemes only, rejects embedded
    credentials (those belong in ``SCHEMA_SERVICE_INTERNAL_API_KEY``),
    requires a non-empty host, validates the port range, and strips a
    trailing slash so the resulting URL composes cleanly with
    ``snapshot_path``.

    Codex 019e2a5c REVISE absorb: malformed URLs that
    :func:`urllib.parse.urlparse` rejects with ``ValueError`` (e.g.
    unbracketed-IPv6 ``http://[::1`` or non-numeric port
    ``http://host:badport``) must surface as :class:`ConfigError`
    rather than leak the raw stdlib exception — otherwise the CLI
    `EX_USAGE=64` contract is broken.
    """
    try:
        parsed = urlparse(raw)
    except ValueError as exc:
        raise ConfigError("SCHEMA_SERVICE_URL is not a valid URL") from exc
    if parsed.scheme not in {"http", "https"}:
        raise ConfigError(
            "SCHEMA_SERVICE_URL must be an http:// or https:// URL"
        )
    # Touching ``hostname`` and ``port`` after a successful ``urlparse``
    # can still raise ``ValueError`` for some malformed inputs (e.g.
    # ``http://host:badport`` only validates the port lazily). Trap
    # both here so the CLI keeps its typed-error contract.
    try:
        hostname = parsed.hostname
        _ = parsed.port  # property-side validation of numeric / range
    except ValueError as exc:
        raise ConfigError(
            "SCHEMA_SERVICE_URL has an invalid host or port"
        ) from exc
    if not hostname:
        raise ConfigError("SCHEMA_SERVICE_URL is missing a host")
    if parsed.username or parsed.password:
        raise ConfigError(
            "SCHEMA_SERVICE_URL must not embed credentials — use "
            "SCHEMA_SERVICE_INTERNAL_API_KEY instead"
        )
    return raw.rstrip("/")


def _parse_timeout(raw: str | None) -> float:
    """Parse ``SCHEMA_SERVICE_TIMEOUT_SECONDS`` (positive finite float).

    ``nan`` and ``inf`` are rejected via :func:`math.isfinite`. Plain
    comparisons (``value <= 0``) are false for NaN, so without the
    finite check a ``nan`` env value would silently pass validation
    and then break the retry / timeout contract downstream.
    """
    if raw is None or raw.strip() == "":
        return DEFAULT_TIMEOUT_SECONDS
    try:
        value = float(raw)
    except (TypeError, ValueError) as exc:
        raise ConfigError(
            "SCHEMA_SERVICE_TIMEOUT_SECONDS must be a positive finite number"
        ) from exc
    if not math.isfinite(value) or value <= 0:
        raise ConfigError(
            "SCHEMA_SERVICE_TIMEOUT_SECONDS must be a positive finite number"
        )
    return value


def _parse_contract_versions(raw: str | None) -> tuple[str, ...]:
    """Parse ``SCHEMA_SERVICE_CONTRACT_VERSIONS`` CSV.

    ``None`` (unset) and an empty string fall back to
    :data:`~etl_worker.schema_service_client.SUPPORTED_CONTRACT_VERSION`.
    Explicit non-empty input is split on commas, whitespace-trimmed;
    any empty piece rejects (so ``"1,"`` and ``" , "`` both raise).
    """
    if raw is None:
        return (SUPPORTED_CONTRACT_VERSION,)
    stripped = raw.strip()
    if stripped == "":
        return (SUPPORTED_CONTRACT_VERSION,)
    pieces = [piece.strip() for piece in stripped.split(",")]
    if any(piece == "" for piece in pieces):
        raise ConfigError(
            "SCHEMA_SERVICE_CONTRACT_VERSIONS must be a non-empty CSV of "
            "version strings (no empty entries)"
        )
    return tuple(pieces)
