"""Checkpoint state for the runner.

Adım 12 PR-2b2b/2b3 (reporting refactor plan §400 — checkpoint + DB
writer interface combined slice). Codex ``019e2a5c`` Checkpoint-A'
discipline: checkpoint is written **after** a successful DB upsert,
never before. The checkpoint thus represents "applied through this
point" — not "fetched through this point".

Design contract:

* **Atomic write-then-rename** — the writer materialises the new
  state in a same-directory temp file, ``fsync`` s the fd, then
  ``os.replace`` s it over the destination. Survives a crash mid-write
  on local filesystems; cross-process / network-FS guarantees are
  not promised.
* **Stable schema** — every checkpoint carries ``schema_version``,
  ``run_id`` of the writing run, ``last_successful_attempt`` count,
  ``snapshot_signature`` (canonical-JSON SHA-256 of the applied
  snapshot summary), and ``written_at`` ISO 8601 timestamp.
* **No skip logic** — Codex explicitly rejected TTL-based or
  signature-based "skip the run if checkpoint is recent" behaviour
  in PR-2b2b. The checkpoint is observability + audit correlation
  for now; true idempotency belongs to PR-3+ once a real DB adapter
  knows what rows it has already inserted.
* **Resume semantics (PR-2b2b)** — loading a checkpoint when
  ``--resume`` is set surfaces a ``checkpoint_loaded`` audit event so
  operators see the cursor; the runner does **not** short-circuit
  fetch/apply.

The writer is fronted by a :class:`Checkpoint` value object so the
audit + DB writer slices can share the same model without coupling
to the on-disk format.
"""

from __future__ import annotations

import hashlib
import json
import os
import threading
from dataclasses import asdict, dataclass
from pathlib import Path

from .audit import now_isoformat

SCHEMA_VERSION = 1
"""Checkpoint payload schema version. Bumped when fields change incompatibly."""


class CheckpointError(Exception):
    """Raised when a checkpoint file is unreadable or malformed.

    Distinct from :class:`OSError` so the CLI can map this to
    ``EX_SOFTWARE`` (terminal parse/schema failure) vs the operating-
    system level errors that surface as raw ``OSError`` and translate
    via the audit path's existing exit-code wiring.
    """


@dataclass(frozen=True, slots=True)
class Checkpoint:
    """Persisted state of the most-recent applied run."""

    schema_version: int
    run_id: str
    last_successful_attempt: int
    snapshot_signature: str
    written_at: str

    def to_payload(self) -> dict[str, object]:
        """Convert to a deterministic JSON-serializable dict."""
        return {key: value for key, value in asdict(self).items()}


class CheckpointFile:
    """Atomic on-disk JSON checkpoint store.

    The file lives at the operator-supplied path; the writer
    serialises in-process writes with :class:`threading.Lock` and
    rewrites the file atomically via temp-file + ``os.replace``.
    Crash-safety on local filesystems is guaranteed up to the
    ``fsync``; network-FS behaviour is not promised.
    """

    def __init__(self, path: str | os.PathLike[str]) -> None:
        self._path = Path(path)
        self._lock = threading.Lock()
        self._path.parent.mkdir(parents=True, exist_ok=True)

    @property
    def path(self) -> Path:
        return self._path

    def exists(self) -> bool:
        return self._path.exists()

    def load(self) -> Checkpoint | None:
        """Read + parse the checkpoint. Returns ``None`` when missing.

        Raises
        ------
        CheckpointError
            File present but malformed (invalid JSON, wrong shape,
            unknown ``schema_version``, missing required fields).
        OSError
            Underlying filesystem error (permission denied, etc.).
            Propagated unchanged so the CLI can map to ``EX_SOFTWARE``
            via its existing audit path handler.
        """
        if not self._path.exists():
            return None
        raw = self._path.read_text(encoding="utf-8")
        try:
            payload = json.loads(raw)
        except ValueError as exc:
            raise CheckpointError(
                f"checkpoint at {self._path} is not valid JSON ({exc})"
            ) from exc
        if not isinstance(payload, dict):
            raise CheckpointError(
                f"checkpoint at {self._path} must be a JSON object"
            )
        return _parse_checkpoint(payload, source=str(self._path))

    def write(self, checkpoint: Checkpoint) -> None:
        """Persist a :class:`Checkpoint` atomically.

        Implementation: write to same-directory temp file, flush +
        ``fsync`` the fd, then ``os.replace`` over the destination.
        ``threading.Lock`` covers in-process concurrency between the
        runner and any future async DB writer.
        """
        payload = checkpoint.to_payload()
        encoded = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")
        with self._lock:
            # Use a temp file in the same directory so ``os.replace``
            # is atomic on POSIX.
            tmp_path = self._path.with_suffix(self._path.suffix + ".tmp")
            fd = os.open(
                tmp_path,
                os.O_WRONLY | os.O_CREAT | os.O_TRUNC,
                0o644,
            )
            try:
                os.write(fd, encoded)
                os.fsync(fd)
            finally:
                os.close(fd)
            os.replace(tmp_path, self._path)


def _parse_checkpoint(payload: dict[str, object], *, source: str) -> Checkpoint:
    schema_version = payload.get("schema_version")
    if not isinstance(schema_version, int) or schema_version != SCHEMA_VERSION:
        raise CheckpointError(
            f"checkpoint at {source} has unsupported schema_version "
            f"{schema_version!r} (expected {SCHEMA_VERSION})"
        )
    run_id = payload.get("run_id")
    last_successful_attempt = payload.get("last_successful_attempt")
    snapshot_signature = payload.get("snapshot_signature")
    written_at = payload.get("written_at")
    if not isinstance(run_id, str) or not run_id:
        raise CheckpointError(f"checkpoint at {source} missing string 'run_id'")
    if not isinstance(last_successful_attempt, int):
        raise CheckpointError(
            f"checkpoint at {source} missing int 'last_successful_attempt'"
        )
    if not isinstance(snapshot_signature, str) or not snapshot_signature:
        raise CheckpointError(
            f"checkpoint at {source} missing string 'snapshot_signature'"
        )
    if not isinstance(written_at, str) or not written_at:
        raise CheckpointError(f"checkpoint at {source} missing string 'written_at'")
    return Checkpoint(
        schema_version=schema_version,
        run_id=run_id,
        last_successful_attempt=last_successful_attempt,
        snapshot_signature=snapshot_signature,
        written_at=written_at,
    )


_SIGNATURE_FIELDS = (
    "contract_version",
    "allowlist_name",
    "allowlist_version",
    "table_count",
    "column_count",
)
"""Whitelist of summary fields that contribute to ``snapshot_signature``.

Codex 019e2a5c REVISE absorb: the signature must be **content-only**
— a retry-recovered run that ended up with the same fetched data
should hash identically to a first-attempt-success on the same
content. Retry telemetry (``attempts``) belongs to
:attr:`Checkpoint.last_successful_attempt`, not the signature.
"""


def snapshot_signature_for_summary(summary: dict[str, object]) -> str:
    """Compute the canonical-JSON SHA-256 over the content-only subset.

    Public helper so the runner can compute the signature once and
    forward it both to the DB writer (idempotency key) and the
    checkpoint (operator correlation). The :data:`_SIGNATURE_FIELDS`
    whitelist guarantees retry telemetry never contaminates the hash.
    """
    canonical_input = {
        key: summary[key] for key in _SIGNATURE_FIELDS if key in summary
    }
    canonical = json.dumps(canonical_input, separators=(",", ":"), sort_keys=True)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def build_checkpoint(
    *,
    run_id: str,
    last_successful_attempt: int,
    summary: dict[str, object],
) -> Checkpoint:
    """Compose a :class:`Checkpoint` from the runner's success summary.

    ``snapshot_signature`` is computed via
    :func:`snapshot_signature_for_summary` — see that helper for the
    content-only field whitelist.
    """
    return Checkpoint(
        schema_version=SCHEMA_VERSION,
        run_id=run_id,
        last_successful_attempt=last_successful_attempt,
        snapshot_signature=snapshot_signature_for_summary(summary),
        written_at=now_isoformat(),
    )
