package com.serban.notify.api;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Static helper for delivery-log query validation (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE absorb: {@code @Min} alone is
 * not enough — the admin endpoint also needs an explicit
 * {@code from <= to} guard, a maximum window guard, and a future
 * clock-skew guard. {@code IllegalArgumentException} surfaces as 400 via the
 * controller-level {@code @ExceptionHandler}.
 */
public final class DeliveryLogQueryValidator {

    /** Maximum admin search window from {@code from} to {@code to}. */
    public static final Duration MAX_WINDOW = Duration.ofDays(7);

    /** Default admin search window when caller omits both endpoints. */
    public static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    /** Future skew tolerance (NTP slack). */
    public static final Duration FUTURE_SKEW = Duration.ofDays(1);

    /** Maximum page size (caller exceeding this gets 400, not silent clamp). */
    public static final int MAX_SIZE = 100;

    private DeliveryLogQueryValidator() {
        // static-only
    }

    public static void validatePage(int page) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
    }

    public static void validateSize(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
        if (size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be <= " + MAX_SIZE);
        }
    }

    /**
     * Resolve the admin time window. When the caller omits both endpoints,
     * the window defaults to {@link #DEFAULT_WINDOW} ending at {@code now}.
     *
     * @return {@code [from, to]} after applying defaults and validation
     * @throws IllegalArgumentException for {@code from > to}, range above
     *         {@link #MAX_WINDOW}, or timestamps further than
     *         {@link #FUTURE_SKEW} into the future
     */
    public static OffsetDateTime[] resolveAdminWindow(
        OffsetDateTime from, OffsetDateTime to, OffsetDateTime now
    ) {
        OffsetDateTime resolvedTo = (to != null) ? to : now;
        OffsetDateTime resolvedFrom = (from != null)
            ? from
            : resolvedTo.minus(DEFAULT_WINDOW);

        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new IllegalArgumentException("'from' must be <= 'to'");
        }

        Duration window = Duration.between(resolvedFrom, resolvedTo);
        if (window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException(
                "time window must be <= " + MAX_WINDOW.toDays() + " days"
            );
        }

        OffsetDateTime futureLimit = now.plus(FUTURE_SKEW);
        if (resolvedFrom.isAfter(futureLimit) || resolvedTo.isAfter(futureLimit)) {
            throw new IllegalArgumentException(
                "timestamps must not be more than " + FUTURE_SKEW.toDays() + " day(s) in the future"
            );
        }

        return new OffsetDateTime[] { resolvedFrom, resolvedTo };
    }
}
