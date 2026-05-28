package com.example.endpointadmin.service.compliance;

import java.time.Duration;
import java.time.Instant;

/**
 * Per-stream inventory freshness severity — BE-023 (Faz 22.5).
 *
 * <p>Codex 019e6bbf iter-2/3: a single {@code inventoryUpdatedAt}
 * stamp masks per-stream staleness (e.g. apps untouched for 5 days
 * but summary heart-beated this morning). Each input stream
 * (summary / apps / wingetEgress) is classified independently and
 * the report carries both the per-stream severities and the worst
 * non-{@link #UNAVAILABLE} severity.
 *
 * <p>SOFT threshold = 24 h (matches the BE-021A
 * {@code INVENTORY_STALE_AFTER} warn band). HARD threshold = 72 h.
 */
public enum StalenessSeverity {
    /**
     * Stream not collected yet. Distinct from "stale" — absence
     * does not itself drive UNKNOWN; the reason taxonomy carries
     * {@code INVENTORY_MISSING} / {@code WINGET_EGRESS_MISSING}
     * separately when the missing data matters.
     */
    UNAVAILABLE,
    FRESH,
    SOFT,
    HARD;

    public static final Duration SOFT_THRESHOLD = Duration.ofHours(24);
    public static final Duration HARD_THRESHOLD = Duration.ofHours(72);

    /**
     * Classify a single stream by age.
     *
     * @param collectedAt the agent-side timestamp when the stream
     *                    was last observed; {@code null} returns
     *                    {@link #UNAVAILABLE}.
     * @param now         evaluation timestamp.
     */
    public static StalenessSeverity classify(Instant collectedAt, Instant now) {
        if (collectedAt == null || now == null) {
            return UNAVAILABLE;
        }
        Duration age = Duration.between(collectedAt, now);
        if (age.isNegative()) {
            // Agent clock skew; treat as fresh rather than negative-aged.
            return FRESH;
        }
        if (age.compareTo(SOFT_THRESHOLD) < 0) {
            return FRESH;
        }
        if (age.compareTo(HARD_THRESHOLD) < 0) {
            return SOFT;
        }
        return HARD;
    }

    /**
     * Worst severity among the supplied streams, ignoring
     * {@link #UNAVAILABLE}. If every supplied stream is
     * {@code UNAVAILABLE} the return value is {@link #UNAVAILABLE}
     * (Codex 019e6bbf iter-3 implementation note).
     */
    public static StalenessSeverity worstOf(StalenessSeverity... values) {
        StalenessSeverity worst = null;
        for (StalenessSeverity s : values) {
            if (s == null || s == UNAVAILABLE) {
                continue;
            }
            if (worst == null || s.ordinal() > worst.ordinal()) {
                worst = s;
            }
        }
        return worst == null ? UNAVAILABLE : worst;
    }
}
