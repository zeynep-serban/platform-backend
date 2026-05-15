"""Unit tests for :mod:`etl_worker.checkpoint`.

Adım 12 PR-2b2b/2b3 — checkpoint + DB writer interface combined slice.
Tests use ``tmp_path`` so no on-disk state leaks between cases.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from etl_worker.checkpoint import (
    SCHEMA_VERSION,
    CheckpointError,
    CheckpointFile,
    build_checkpoint,
)


def _good_summary() -> dict[str, object]:
    return {
        "contract_version": "1",
        "allowlist_name": "ReportingAllowlist",
        "allowlist_version": "V1",
        "table_count": 2,
        "column_count": 5,
        "attempts": 1,
    }


# ---- build_checkpoint helper --------------------------------------------


def test_build_checkpoint_computes_deterministic_signature() -> None:
    cp1 = build_checkpoint(
        run_id="r1",
        last_successful_attempt=2,
        summary=_good_summary(),
    )
    cp2 = build_checkpoint(
        run_id="r2",  # different run_id
        last_successful_attempt=2,
        summary=_good_summary(),
    )
    # Same summary → same signature regardless of run_id
    assert cp1.snapshot_signature == cp2.snapshot_signature
    assert cp1.schema_version == SCHEMA_VERSION


def test_build_checkpoint_signature_changes_when_summary_differs() -> None:
    base = _good_summary()
    other = dict(base)
    other["table_count"] = 99

    cp1 = build_checkpoint(run_id="r1", last_successful_attempt=1, summary=base)
    cp2 = build_checkpoint(run_id="r1", last_successful_attempt=1, summary=other)

    assert cp1.snapshot_signature != cp2.snapshot_signature


def test_build_checkpoint_signature_is_canonical_json_sha256() -> None:
    """Order-independent signature: reshuffled keys produce same hash."""
    base = _good_summary()
    reshuffled = {key: base[key] for key in sorted(base, reverse=True)}

    cp_base = build_checkpoint(run_id="r1", last_successful_attempt=1, summary=base)
    cp_reshuffled = build_checkpoint(
        run_id="r1", last_successful_attempt=1, summary=reshuffled
    )

    assert cp_base.snapshot_signature == cp_reshuffled.snapshot_signature


# ---- CheckpointFile load / write ----------------------------------------


def test_load_missing_file_returns_none(tmp_path: Path) -> None:
    cp_file = CheckpointFile(tmp_path / "absent.json")
    assert cp_file.exists() is False
    assert cp_file.load() is None


def test_write_creates_parent_directory(tmp_path: Path) -> None:
    nested = tmp_path / "deep" / "state" / "checkpoint.json"
    cp_file = CheckpointFile(nested)
    cp = build_checkpoint(run_id="r1", last_successful_attempt=1, summary=_good_summary())

    cp_file.write(cp)

    assert nested.parent.is_dir()
    assert nested.exists()


def test_write_then_load_roundtrip(tmp_path: Path) -> None:
    cp_file = CheckpointFile(tmp_path / "checkpoint.json")
    cp = build_checkpoint(run_id="r1", last_successful_attempt=2, summary=_good_summary())

    cp_file.write(cp)
    loaded = cp_file.load()

    assert loaded == cp


def test_write_overrides_previous_state(tmp_path: Path) -> None:
    cp_file = CheckpointFile(tmp_path / "checkpoint.json")
    cp1 = build_checkpoint(run_id="r1", last_successful_attempt=1, summary=_good_summary())
    cp2 = build_checkpoint(run_id="r2", last_successful_attempt=3, summary=_good_summary())

    cp_file.write(cp1)
    cp_file.write(cp2)

    loaded = cp_file.load()
    assert loaded is not None
    assert loaded.run_id == "r2"
    assert loaded.last_successful_attempt == 3


def test_write_atomic_temp_file_cleaned_up(tmp_path: Path) -> None:
    """Successful write leaves no ``.tmp`` siblings behind."""
    cp_file = CheckpointFile(tmp_path / "checkpoint.json")
    cp = build_checkpoint(run_id="r1", last_successful_attempt=1, summary=_good_summary())

    cp_file.write(cp)

    siblings = list(tmp_path.iterdir())
    assert siblings == [tmp_path / "checkpoint.json"]


def test_write_is_compact_json(tmp_path: Path) -> None:
    cp_file = CheckpointFile(tmp_path / "checkpoint.json")
    cp = build_checkpoint(run_id="r1", last_successful_attempt=1, summary=_good_summary())

    cp_file.write(cp)

    raw = cp_file.path.read_text(encoding="utf-8")
    # Single line; no pretty-printing whitespace.
    assert "\n" not in raw
    payload = json.loads(raw)
    assert payload["schema_version"] == SCHEMA_VERSION
    assert payload["run_id"] == "r1"


# ---- malformed checkpoint -----------------------------------------------


def test_load_invalid_json_raises_checkpoint_error(tmp_path: Path) -> None:
    path = tmp_path / "checkpoint.json"
    path.write_text("not json at all", encoding="utf-8")
    cp_file = CheckpointFile(path)

    with pytest.raises(CheckpointError, match="not valid JSON"):
        cp_file.load()


def test_load_non_object_root_raises_checkpoint_error(tmp_path: Path) -> None:
    path = tmp_path / "checkpoint.json"
    path.write_text("[1,2,3]", encoding="utf-8")
    cp_file = CheckpointFile(path)

    with pytest.raises(CheckpointError, match="must be a JSON object"):
        cp_file.load()


@pytest.mark.parametrize(
    "missing_field",
    ["run_id", "last_successful_attempt", "snapshot_signature", "written_at"],
)
def test_load_missing_field_raises_checkpoint_error(
    tmp_path: Path, missing_field: str
) -> None:
    path = tmp_path / "checkpoint.json"
    payload = {
        "schema_version": SCHEMA_VERSION,
        "run_id": "r1",
        "last_successful_attempt": 1,
        "snapshot_signature": "abc",
        "written_at": "2026-05-15T09:00:00.000Z",
    }
    del payload[missing_field]
    path.write_text(json.dumps(payload), encoding="utf-8")
    cp_file = CheckpointFile(path)

    with pytest.raises(CheckpointError, match=missing_field):
        cp_file.load()


def test_load_unsupported_schema_version_raises_checkpoint_error(tmp_path: Path) -> None:
    path = tmp_path / "checkpoint.json"
    payload = {
        "schema_version": 99,  # not SCHEMA_VERSION
        "run_id": "r1",
        "last_successful_attempt": 1,
        "snapshot_signature": "abc",
        "written_at": "2026-05-15T09:00:00.000Z",
    }
    path.write_text(json.dumps(payload), encoding="utf-8")
    cp_file = CheckpointFile(path)

    with pytest.raises(CheckpointError, match="unsupported schema_version"):
        cp_file.load()


def test_checkpoint_dataclass_frozen() -> None:
    cp = build_checkpoint(run_id="r1", last_successful_attempt=1, summary=_good_summary())
    with pytest.raises(AttributeError):
        cp.run_id = "other"  # type: ignore[misc]
