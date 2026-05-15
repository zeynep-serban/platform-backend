"""Unit tests for :class:`etl_worker.config.Config`.

Every test injects an explicit ``env`` mapping so ``os.environ`` is
never touched. Adım 12 PR-2a scope.
"""

from __future__ import annotations

import pytest

from etl_worker.config import (
    DEFAULT_TIMEOUT_SECONDS,
    Config,
    ConfigError,
)


def _env(**overrides: str) -> dict[str, str]:
    """Build a minimal valid env, mutated by ``overrides``."""
    base: dict[str, str] = {"SCHEMA_SERVICE_URL": "http://schema-service.test:8096"}
    base.update(overrides)
    return base


# ---- happy path -----------------------------------------------------------


def test_from_env_only_required_url_uses_defaults() -> None:
    config = Config.from_env(_env())

    assert config.schema_service_url == "http://schema-service.test:8096"
    assert config.schema_service_internal_api_key is None
    assert config.schema_service_timeout_seconds == DEFAULT_TIMEOUT_SECONDS
    assert config.schema_service_schema is None
    assert config.schema_service_contract_versions == ("1",)


def test_from_env_strips_trailing_slash_from_url() -> None:
    config = Config.from_env(_env(SCHEMA_SERVICE_URL="https://schema-service.prod/"))

    assert config.schema_service_url == "https://schema-service.prod"


def test_from_env_propagates_all_optional_values() -> None:
    config = Config.from_env(
        _env(
            SCHEMA_SERVICE_INTERNAL_API_KEY="route-test-key",
            SCHEMA_SERVICE_TIMEOUT_SECONDS="15.5",
            SCHEMA_SERVICE_SCHEMA="workcube_mikrolink_2025",
            SCHEMA_SERVICE_CONTRACT_VERSIONS="1,2,3",
        )
    )

    assert config.schema_service_internal_api_key == "route-test-key"
    assert config.schema_service_timeout_seconds == 15.5
    assert config.schema_service_schema == "workcube_mikrolink_2025"
    assert config.schema_service_contract_versions == ("1", "2", "3")


def test_from_env_csv_with_whitespace_around_versions() -> None:
    config = Config.from_env(_env(SCHEMA_SERVICE_CONTRACT_VERSIONS="1, 2 , 3"))

    assert config.schema_service_contract_versions == ("1", "2", "3")


def test_from_env_blank_internal_key_becomes_none() -> None:
    """Empty / whitespace key is treated as 'no key', not as an empty header."""
    config = Config.from_env(_env(SCHEMA_SERVICE_INTERNAL_API_KEY="   "))

    assert config.schema_service_internal_api_key is None


def test_from_env_blank_optional_strings_become_none() -> None:
    config = Config.from_env(
        _env(
            SCHEMA_SERVICE_SCHEMA="   ",
            SCHEMA_SERVICE_TIMEOUT_SECONDS="",
            SCHEMA_SERVICE_CONTRACT_VERSIONS="",
        )
    )

    assert config.schema_service_schema is None
    # Blank timeout falls back to default
    assert config.schema_service_timeout_seconds == DEFAULT_TIMEOUT_SECONDS
    # Blank CSV falls back to default supported set
    assert config.schema_service_contract_versions == ("1",)


# ---- required-field validation -------------------------------------------


def test_from_env_missing_url_raises() -> None:
    with pytest.raises(ConfigError, match="SCHEMA_SERVICE_URL is required"):
        Config.from_env({})


def test_from_env_blank_url_raises() -> None:
    with pytest.raises(ConfigError, match="SCHEMA_SERVICE_URL is required"):
        Config.from_env({"SCHEMA_SERVICE_URL": "   "})


# ---- URL validation -------------------------------------------------------


@pytest.mark.parametrize(
    "bad_url",
    [
        "schema-service:8096",  # no scheme
        "ftp://schema-service",  # wrong scheme
        "file:///etc/hosts",  # wrong scheme
        "http:///just-a-path",  # no host
    ],
)
def test_from_env_rejects_invalid_url(bad_url: str) -> None:
    with pytest.raises(ConfigError):
        Config.from_env({"SCHEMA_SERVICE_URL": bad_url})


def test_from_env_rejects_url_with_credentials() -> None:
    with pytest.raises(ConfigError, match="must not embed credentials"):
        Config.from_env(
            {"SCHEMA_SERVICE_URL": "http://user:pass@schema-service:8096"}
        )


@pytest.mark.parametrize(
    "malformed_url",
    [
        # Unbracketed IPv6 — urlparse() raises ValueError
        "http://[::1",
        # Non-numeric port — parsed.port raises ValueError on access
        "http://schema-service:badport",
        # Out-of-range port (>65535) — parsed.port raises ValueError on access
        "http://schema-service:99999",
    ],
)
def test_from_env_traps_malformed_url_parse_errors(malformed_url: str) -> None:
    """Codex 019e2a5c REVISE absorb: ``urlparse`` / ``.port`` ``ValueError``s
    must surface as :class:`ConfigError`, not leak raw stdlib exceptions
    that would break the CLI's ``EX_USAGE=64`` contract.
    """
    with pytest.raises(ConfigError):
        Config.from_env({"SCHEMA_SERVICE_URL": malformed_url})


# ---- timeout validation ---------------------------------------------------


@pytest.mark.parametrize(
    "bad_timeout",
    ["abc", "0", "-1", "-5.5"],
)
def test_from_env_rejects_invalid_timeout(bad_timeout: str) -> None:
    with pytest.raises(ConfigError, match="must be a positive number"):
        Config.from_env(_env(SCHEMA_SERVICE_TIMEOUT_SECONDS=bad_timeout))


# ---- contract version validation ------------------------------------------


@pytest.mark.parametrize(
    "bad_versions",
    ["1,", ",1", "1,,2", " , ", ","],
)
def test_from_env_rejects_empty_csv_entries(bad_versions: str) -> None:
    with pytest.raises(ConfigError, match="non-empty CSV"):
        Config.from_env(_env(SCHEMA_SERVICE_CONTRACT_VERSIONS=bad_versions))


def test_from_env_falls_back_to_os_environ_when_env_none(monkeypatch: pytest.MonkeyPatch) -> None:
    """Passing ``env=None`` uses ``os.environ`` — sanity check for the production
    code path where the CLI calls ``Config.from_env()``."""
    monkeypatch.setenv("SCHEMA_SERVICE_URL", "https://from-os-environ:9000")
    monkeypatch.delenv("SCHEMA_SERVICE_TIMEOUT_SECONDS", raising=False)
    monkeypatch.delenv("SCHEMA_SERVICE_INTERNAL_API_KEY", raising=False)
    monkeypatch.delenv("SCHEMA_SERVICE_SCHEMA", raising=False)
    monkeypatch.delenv("SCHEMA_SERVICE_CONTRACT_VERSIONS", raising=False)

    config = Config.from_env()

    assert config.schema_service_url == "https://from-os-environ:9000"
