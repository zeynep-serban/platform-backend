"""Unit tests for :mod:`etl_worker.retry`.

Every test injects a ``FakeSleeper`` so no real ``time.sleep`` runs in
CI. Adım 12 PR-2b1 scope.
"""

from __future__ import annotations

import pytest

from etl_worker.retry import RetryPolicy, Sleeper, call_with_retry


class FakeSleeper:
    """Records each requested delay; never actually blocks."""

    def __init__(self) -> None:
        self.delays: list[float] = []

    def sleep(self, seconds: float) -> None:
        self.delays.append(seconds)


# ---- RetryPolicy validation ----------------------------------------------


def test_default_policy_uses_three_attempts() -> None:
    policy = RetryPolicy()
    assert policy.max_attempts == 3
    assert policy.initial_seconds == 1.0
    assert policy.multiplier == 2.0
    assert policy.cap_seconds == 30.0


def test_policy_rejects_zero_max_attempts() -> None:
    with pytest.raises(ValueError, match=r"max_attempts must be >= 1"):
        RetryPolicy(max_attempts=0)


def test_policy_rejects_negative_initial_seconds() -> None:
    with pytest.raises(ValueError, match=r"initial_seconds must be a non-negative finite number"):
        RetryPolicy(initial_seconds=-1)


def test_policy_rejects_sub_unit_multiplier() -> None:
    with pytest.raises(ValueError, match=r"multiplier must be a finite number >= 1\.0"):
        RetryPolicy(multiplier=0.5)


def test_policy_rejects_cap_smaller_than_initial() -> None:
    with pytest.raises(ValueError, match=r"cap_seconds must be a finite number >= initial_seconds"):
        RetryPolicy(initial_seconds=5.0, cap_seconds=1.0)


@pytest.mark.parametrize(
    "non_finite",
    [float("nan"), float("inf"), float("-inf")],
)
def test_policy_rejects_non_finite_initial_seconds(non_finite: float) -> None:
    """Codex 019e2a5c REVISE absorb: ``nan`` / ``inf`` slip past the
    ``value < 0`` comparison because NaN comparisons are always false."""
    with pytest.raises(ValueError, match=r"initial_seconds must be a non-negative finite number"):
        RetryPolicy(initial_seconds=non_finite)


@pytest.mark.parametrize(
    "non_finite",
    [float("nan"), float("inf")],
)
def test_policy_rejects_non_finite_multiplier(non_finite: float) -> None:
    with pytest.raises(ValueError, match=r"multiplier must be a finite number >= 1\.0"):
        RetryPolicy(multiplier=non_finite)


@pytest.mark.parametrize(
    "non_finite",
    [float("nan"), float("inf")],
)
def test_policy_rejects_non_finite_cap_seconds(non_finite: float) -> None:
    with pytest.raises(ValueError, match=r"cap_seconds must be a finite number >= initial_seconds"):
        RetryPolicy(cap_seconds=non_finite)


# ---- delay schedule ------------------------------------------------------


def test_delay_for_attempt_first_attempt_is_zero() -> None:
    policy = RetryPolicy(initial_seconds=2.0, multiplier=3.0)
    assert policy.delay_for_attempt(1) == 0.0


def test_delay_for_attempt_exponential_growth() -> None:
    policy = RetryPolicy(initial_seconds=1.0, multiplier=2.0, cap_seconds=100.0)
    # attempt 2 is the first retry; sleeps `initial`
    assert policy.delay_for_attempt(2) == 1.0
    # attempt 3 doubles
    assert policy.delay_for_attempt(3) == 2.0
    # attempt 4 doubles again
    assert policy.delay_for_attempt(4) == 4.0


def test_delay_caps_at_configured_ceiling() -> None:
    policy = RetryPolicy(
        initial_seconds=1.0,
        multiplier=10.0,
        cap_seconds=5.0,
        max_attempts=10,
    )
    # Without the cap, attempt 4 would be 100.0
    assert policy.delay_for_attempt(4) == 5.0


# ---- call_with_retry behaviour --------------------------------------------


class _Transient(Exception):
    """Test-local retryable error."""


class _Terminal(Exception):
    """Test-local non-retryable error."""


def test_first_attempt_success_returns_result_and_no_sleep() -> None:
    sleeper = FakeSleeper()

    def operation() -> str:
        return "ok"

    result, attempts = call_with_retry(
        operation,
        retryable=_Transient,
        policy=RetryPolicy(max_attempts=3),
        sleeper=sleeper,
    )

    assert result == "ok"
    assert attempts == 1
    assert sleeper.delays == []  # No retries → no sleep


def test_transient_then_success_records_attempts_and_one_sleep() -> None:
    """A single transient failure recovered on the second try sleeps once."""
    sleeper = FakeSleeper()
    state = {"calls": 0}

    def operation() -> str:
        state["calls"] += 1
        if state["calls"] < 2:
            raise _Transient("upstream blip")
        return "ok"

    result, attempts = call_with_retry(
        operation,
        retryable=_Transient,
        policy=RetryPolicy(max_attempts=3, initial_seconds=0.5, multiplier=2.0),
        sleeper=sleeper,
    )

    assert result == "ok"
    assert attempts == 2
    assert sleeper.delays == [0.5]  # Backoff before attempt 2


def test_transient_exhausted_reraises_last_error() -> None:
    """Exhausted retry budget surfaces the last exception unchanged."""
    sleeper = FakeSleeper()
    errors = [_Transient("e1"), _Transient("e2"), _Transient("e3")]

    def operation() -> str:
        raise errors.pop(0)

    with pytest.raises(_Transient, match="e3"):
        call_with_retry(
            operation,
            retryable=_Transient,
            policy=RetryPolicy(max_attempts=3, initial_seconds=0.1, multiplier=2.0),
            sleeper=sleeper,
        )

    # Sleeps happened before attempts 2 and 3 → 2 entries
    assert len(sleeper.delays) == 2
    assert sleeper.delays == [0.1, 0.2]


def test_terminal_error_is_not_retried() -> None:
    """Non-retryable exception propagates on the very first occurrence."""
    sleeper = FakeSleeper()
    state = {"calls": 0}

    def operation() -> str:
        state["calls"] += 1
        raise _Terminal("contract drift")

    with pytest.raises(_Terminal, match="contract drift"):
        call_with_retry(
            operation,
            retryable=_Transient,
            policy=RetryPolicy(max_attempts=5),
            sleeper=sleeper,
        )

    assert state["calls"] == 1  # No retry on terminal
    assert sleeper.delays == []  # No sleep on terminal


def test_max_attempts_one_disables_retry() -> None:
    """``max_attempts=1`` means a single try, no retry, exception propagates."""
    sleeper = FakeSleeper()

    def operation() -> str:
        raise _Transient("nope")

    with pytest.raises(_Transient):
        call_with_retry(
            operation,
            retryable=_Transient,
            policy=RetryPolicy(max_attempts=1, initial_seconds=99.0, cap_seconds=99.0),
            sleeper=sleeper,
        )

    assert sleeper.delays == []  # 1 attempt → no sleep


def test_retryable_tuple_matches_any_member() -> None:
    """Multiple retryable types are honoured via a tuple."""
    sleeper = FakeSleeper()

    class _OtherTransient(Exception):
        pass

    errors: list[Exception] = [_Transient("a"), _OtherTransient("b")]

    def operation() -> str:
        if errors:
            raise errors.pop(0)
        return "ok"

    result, attempts = call_with_retry(
        operation,
        retryable=(_Transient, _OtherTransient),
        policy=RetryPolicy(max_attempts=3, initial_seconds=0.1),
        sleeper=sleeper,
    )

    assert result == "ok"
    assert attempts == 3
    assert sleeper.delays == [0.1, 0.2]


def test_protocol_sleeper_runtime_check() -> None:
    """A class that does not implement ``sleep`` is rejected at call time."""

    class _BrokenSleeper:
        # Intentionally missing ``sleep`` method.
        pass

    def operation() -> str:
        raise _Transient("forced")

    with pytest.raises(AttributeError):
        call_with_retry(
            operation,
            retryable=_Transient,
            policy=RetryPolicy(max_attempts=2, initial_seconds=0.1),
            sleeper=_BrokenSleeper(),  # type: ignore[arg-type]
        )


# ---- protocol export sanity ----------------------------------------------


def test_system_sleeper_implements_protocol() -> None:
    from etl_worker.retry import SystemSleeper

    sleeper: Sleeper = SystemSleeper()
    assert callable(sleeper.sleep)
