"""Retry / backoff helpers for the runner.

Adım 12 PR-2b1 (reporting refactor plan §400 — runner retry foundation).
This module is the *only* place where the worker decides "should I
retry?" and "how long to wait before retrying?". Audit log, checkpoint,
resume, and reports_db writes are intentionally out of scope here; they
belong to PR-2b2 / PR-2b3.

Design contract:

* **Only retry the transport / 5xx class.**
  :class:`~etl_worker.schema_service_client.SchemaServiceUnavailable`
  is retryable;
  :class:`~etl_worker.schema_service_client.SchemaServiceMalformedResponse`
  (4xx / parse) and
  :class:`~etl_worker.schema_service_client.SchemaContractVersionMismatch`
  are terminal. We never let the retry loop hide a contract bug behind
  N attempts.
* **Bounded attempts.** ``max_attempts >= 1`` (one means "no retry").
  An exhausted retry surfaces the last
  :class:`SchemaServiceUnavailable` to the caller; the CLI maps this
  to ``EX_TEMPFAIL`` so an outer scheduler can decide whether to
  re-run the job.
* **Deterministic backoff.** The :class:`RetryPolicy` formula is
  ``min(initial * (multiplier ** (attempt - 1)), cap_seconds)``.
  Tests inject a fake :class:`Sleeper` so no real ``time.sleep`` is
  invoked in CI.

The helper is intentionally *not* tied to the schema-service contract;
it operates on any zero-arg callable that may raise the retryable
exception type the caller passes. That keeps it reusable for the
reports_db writer (PR-2b3) without rewriting retry semantics.
"""

from __future__ import annotations

import math
import time
from collections.abc import Callable
from dataclasses import dataclass
from typing import Protocol


class Sleeper(Protocol):
    """Injectable ``time.sleep`` replacement so tests can stay deterministic.

    Production code uses :class:`SystemSleeper`; tests pass a
    :class:`FakeSleeper` that records each requested delay without
    actually sleeping. Keeping this as a Protocol (rather than a
    ``Callable[[float], None]`` alias) makes mypy errors clearer when
    the wrong shape is passed.
    """

    def sleep(self, seconds: float) -> None:  # pragma: no cover - Protocol
        ...


class SystemSleeper:
    """Default :class:`Sleeper` backed by ``time.sleep``."""

    def sleep(self, seconds: float) -> None:  # pragma: no cover - thin wrapper
        time.sleep(seconds)


@dataclass(frozen=True, slots=True)
class RetryPolicy:
    """Bounded exponential backoff policy.

    Attributes
    ----------
    max_attempts:
        Total number of attempts (including the first). Must be ``>= 1``.
        ``1`` disables retries entirely; the helper calls the operation
        once and propagates any failure.
    initial_seconds:
        First sleep delay. Must be ``>= 0``.
    multiplier:
        Exponential growth factor applied between attempts. Must be
        ``>= 1.0``. ``1.0`` collapses the policy to a flat backoff.
    cap_seconds:
        Upper bound on each individual sleep delay. Must be
        ``>= initial_seconds``. Prevents pathological exponential
        growth from blocking the worker too long between attempts.
    """

    max_attempts: int = 3
    initial_seconds: float = 1.0
    multiplier: float = 2.0
    cap_seconds: float = 30.0

    def __post_init__(self) -> None:
        if self.max_attempts < 1:
            raise ValueError("RetryPolicy.max_attempts must be >= 1")
        # ``math.isfinite`` guards against ``nan`` / ``inf`` slipping
        # past the plain comparisons below — those comparisons are
        # false for ``nan`` so a NaN value would otherwise pass.
        if not math.isfinite(self.initial_seconds) or self.initial_seconds < 0:
            raise ValueError(
                "RetryPolicy.initial_seconds must be a non-negative finite number"
            )
        if not math.isfinite(self.multiplier) or self.multiplier < 1.0:
            raise ValueError(
                "RetryPolicy.multiplier must be a finite number >= 1.0"
            )
        if not math.isfinite(self.cap_seconds) or self.cap_seconds < self.initial_seconds:
            raise ValueError(
                "RetryPolicy.cap_seconds must be a finite number >= initial_seconds"
            )

    def delay_for_attempt(self, attempt: int) -> float:
        """Compute the sleep delay *before* ``attempt`` (1-indexed).

        ``attempt == 1`` returns ``0.0`` because the first try never
        sleeps. For ``attempt >= 2`` the delay is
        ``min(initial_seconds * (multiplier ** (attempt - 2)), cap_seconds)``
        — i.e. attempt 2 sleeps ``initial``, attempt 3 sleeps
        ``initial * multiplier``, attempt 4 sleeps
        ``initial * multiplier ** 2``, and so on, capped at
        :attr:`cap_seconds`.
        """
        if attempt <= 1:
            return 0.0
        raw = self.initial_seconds * (self.multiplier ** (attempt - 2))
        return min(raw, self.cap_seconds)


def call_with_retry[T](
    operation: Callable[[], T],
    *,
    retryable: type[BaseException] | tuple[type[BaseException], ...],
    policy: RetryPolicy,
    sleeper: Sleeper,
) -> tuple[T, int]:
    """Invoke ``operation`` honouring ``policy``; return ``(result, attempts)``.

    The function returns the first successful result along with the
    number of attempts (``1`` if no retry was needed). If the operation
    raises ``retryable`` more than ``policy.max_attempts`` times in a
    row, the *last* such exception is re-raised so the caller sees the
    real upstream message — the helper never swallows the underlying
    error. Non-retryable exceptions propagate immediately on the very
    first occurrence.

    Tests inject a :class:`FakeSleeper` so this loop never actually
    blocks on ``time.sleep``.
    """
    last_error: BaseException | None = None
    for attempt in range(1, policy.max_attempts + 1):
        delay = policy.delay_for_attempt(attempt)
        if delay > 0:
            sleeper.sleep(delay)
        try:
            return operation(), attempt
        except retryable as exc:
            last_error = exc
            continue
    assert last_error is not None  # pragma: no cover - max_attempts >= 1 guarantees a try
    raise last_error
