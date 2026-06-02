package com.example.endpointadmin.service.diff;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * BE-024c v2-c-pre-2-B AFTER_COMMIT listener for
 * {@link DiffCacheRefreshRequested} (Codex 019e8964 iter-4 AGREE).
 *
 * <p>Runs after the ingest transaction has committed. Delegates to
 * {@link DiffCacheRefreshService} which opens a fresh transaction with
 * {@link org.springframework.transaction.annotation.Propagation#REQUIRES_NEW}
 * to do the summarize + upsert. Exceptions are caught at this listener
 * boundary so a cache-refresh failure cannot break the ingest path
 * (ingest is already committed by the time AFTER_COMMIT fires) and the
 * on-demand drawer/summarize() read path remains the canonical fallback
 * — only the cache row may lag temporarily until the next refresh.
 *
 * <p>Codex iter-4 guardrail #1: the {@code try/catch} sits OUTSIDE the
 * REQUIRES_NEW service so Spring still rolls back its own transaction on
 * failure; the listener swallows after Spring has unwound. The service
 * itself does NOT swallow, so a failed UPSERT propagates correctly.
 */
@Component
public class DiffCacheRefreshListener {

    private static final Logger log = LoggerFactory.getLogger(DiffCacheRefreshListener.class);

    private final DiffCacheRefreshService refreshService;

    public DiffCacheRefreshListener(DiffCacheRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDiffCacheRefreshRequested(DiffCacheRefreshRequested event) {
        try {
            refreshService.refresh(event);
        } catch (RuntimeException ex) {
            // Cache-refresh failure must NOT propagate to the caller — the
            // ingest tx that emitted this event already committed (AFTER_COMMIT
            // phase). Log redacted: tenant/device/type/error class+message
            // only, no raw payload (Codex iter-3 plan-time direction).
            log.warn("DiffCache refresh failed tenant={} device={} type={} error={}: {}",
                    event.tenantId(), event.deviceId(), event.type(),
                    ex.getClass().getSimpleName(), ex.getMessage(), ex);
        }
    }
}
