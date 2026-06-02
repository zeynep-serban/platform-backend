package com.example.endpointadmin.service.diff;

import java.util.UUID;

/**
 * Application event published by software- and outdated-software ingest
 * paths after they have successfully persisted a NEW state-history /
 * snapshot row, signaling that the {@link DiffCacheService} cache row for
 * the {@code (tenantId, deviceId)} pair should be refreshed against the
 * latest committed source state.
 *
 * <p>Faz 22.5 P2-A v2-c-pre-2-B (Codex 019e8964 iter-4 AGREE — A' + D'
 * hybrid). The event MUST only be published from a successful ingest
 * branch (full-payload software, brand-new outdated snapshot) — summary-
 * only / wingetEgress-only / duplicate / idempotent return paths MUST NOT
 * publish, otherwise listener churn rises without semantic delta. The
 * event is intentionally schema-thin (no payload, no source ids) — the
 * {@link DiffCacheRefreshListener} re-summarizes against the latest
 * committed DB state at AFTER_COMMIT phase + REQUIRES_NEW propagation, so
 * shipping a snapshot of the just-computed summary in the event would be
 * stale by the time the listener fires under concurrent ingest.
 *
 * <p>Audit-event piggyback is forbidden — this is its own event class
 * (Codex 019e8964 iter-3 plan-time direction). Mixing audit + cache-
 * refresh into one event leaks one concern's failure surface into the
 * other.
 *
 * @param tenantId tenant identifier of the device whose cache row should
 *                 be refreshed; must be non-null
 * @param deviceId device identifier; must be non-null
 * @param type     scope discriminator (SOFTWARE for BE-024 cache,
 *                 OUTDATED for BE-024b cache)
 */
public record DiffCacheRefreshRequested(UUID tenantId, UUID deviceId, DiffType type) {
}
