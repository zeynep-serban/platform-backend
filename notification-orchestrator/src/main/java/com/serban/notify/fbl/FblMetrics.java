package com.serban.notify.fbl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * FBL (spam-complaint Feedback Loop) observability metrics
 * (Faz 23.8 M7 T4.3.5 — Codex 019e4fc6).
 *
 * <p>Low-cardinality tags only:
 * <ul>
 *   <li>{@code notify.fbl.received{outcome}} — every ARF message processed,
 *       tagged by terminal outcome (bounded enum set).</li>
 *   <li>{@code notify.fbl.suppressed{org_id}} — per-tenant spam-complaint
 *       suppression count (org_id cardinality matches the WorkerMetrics
 *       per-tenant tag precedent, B.1 PR #289).</li>
 * </ul>
 *
 * <p>No {@code reporter} tag — reporter strings are unbounded and would
 * risk Prometheus cardinality budget. No {@code recipient_hash} tag — PII.
 */
@Component
public class FblMetrics {

    /** Outcome: ARF report produced a fresh SPAM_COMPLAINT suppression. */
    public static final String OUTCOME_SUPPRESSED = "suppressed";
    /** Outcome: ARF event_fingerprint already processed (idempotent no-op). */
    public static final String OUTCOME_DUPLICATE = "duplicate";
    /** Outcome: feedback-type is not 'abuse' — parsed, counted, not suppressed. */
    public static final String OUTCOME_IGNORED_UNSUPPORTED = "ignored_unsupported";
    /** Outcome: no notification_delivery correlated — org_id unresolvable. */
    public static final String OUTCOME_UNRESOLVED = "unresolved";
    /** Outcome: ARF MIME could not be parsed (fail-closed). */
    public static final String OUTCOME_PARSE_ERROR = "parse_error";

    private final MeterRegistry registry;

    public FblMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Increment the per-outcome FBL received counter. */
    public void received(String outcome) {
        Counter.builder("notify.fbl.received")
            .tags(Tags.of("outcome", outcome))
            .register(registry)
            .increment();
    }

    /** Increment the per-tenant suppression counter (suppressed path only). */
    public void suppressed(String orgId) {
        Counter.builder("notify.fbl.suppressed")
            .tags(Tags.of("org_id", orgIdTag(orgId)))
            .register(registry)
            .increment();
    }

    /** Null/blank org_id normalised to "unknown" (Micrometer label safety). */
    private static String orgIdTag(String orgId) {
        return (orgId == null || orgId.isBlank()) ? "unknown" : orgId;
    }
}
