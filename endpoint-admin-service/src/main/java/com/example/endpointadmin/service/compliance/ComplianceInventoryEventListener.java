package com.example.endpointadmin.service.compliance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * BE-023 — Listens for {@link SoftwareInventorySnapshotPersistedEvent}
 * and triggers a background compliance re-evaluation after the parent
 * inventory ingest commits.
 *
 * <p>Failure semantics:
 *
 * <ul>
 *   <li>The advisory lock may already be held (admin POST in flight); we
 *       silently skip — the next inventory commit will fire another
 *       event.</li>
 *   <li>The device may have been deleted between the inventory commit
 *       and this listener firing; we map the NOT_FOUND to a debug log.</li>
 *   <li>Any other exception is logged at WARN to keep the parent ingest
 *       transaction unaffected. AFTER_COMMIT listeners cannot roll back
 *       the committed parent transaction; throwing here would only
 *       pollute the request thread.</li>
 * </ul>
 */
@Component
public class ComplianceInventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(ComplianceInventoryEventListener.class);

    private final EndpointComplianceService complianceService;

    public ComplianceInventoryEventListener(EndpointComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSnapshotPersisted(SoftwareInventorySnapshotPersistedEvent event) {
        try {
            complianceService.evaluateForEvent(event.tenantId(), event.deviceId())
                    .ifPresent(outcome -> log.debug(
                            "BE-023 inventory-driven compliance re-eval succeeded "
                                    + "tenant={} device={} evaluationId={} decision={}",
                            event.tenantId(), event.deviceId(),
                            outcome.evaluationId(), outcome.decision()));
        } catch (RuntimeException ex) {
            log.warn(
                    "BE-023 inventory-driven compliance re-eval failed "
                            + "tenant={} device={} ingest={}: {}",
                    event.tenantId(), event.deviceId(), event.ingestOutcome(),
                    ex.getMessage(), ex);
        }
    }
}
