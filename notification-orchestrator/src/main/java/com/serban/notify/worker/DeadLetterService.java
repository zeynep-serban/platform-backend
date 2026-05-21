package com.serban.notify.worker;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.DeadLetter;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.DeadLetterRepository;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DeadLetterService — exhausted RETRY → DLQ + delivery FAILED + intent terminal
 * (Codex 019dfa47 Q5 AGREE absorb).
 *
 * <p>Pipeline:
 * <ol>
 *   <li>RETRY delivery {@code attempt_count >= max-attempts} → terminate
 *       delivery FAILED, set {@code permanent_failure_at}</li>
 *   <li>INSERT dead_letter row (idempotent: unique active index
 *       {@code WHERE replayed=FALSE} engelse — duplicate insert OK)</li>
 *   <li>Audit DLQ_TERMINATED event</li>
 *   <li>Caller (RetryWorker veya OutboxPoller) intent terminal status
 *       resolution çağırır</li>
 * </ol>
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final DeadLetterRepository dlqRepo;
    private final NotificationDeliveryRepository deliveryRepo;
    private final NotificationIntentRepository intentRepo;
    private final IntentStatusResolver statusResolver;
    private final AuditEventPublisher audit;
    private final WorkerMetrics metrics;

    public DeadLetterService(
        DeadLetterRepository dlqRepo,
        NotificationDeliveryRepository deliveryRepo,
        NotificationIntentRepository intentRepo,
        IntentStatusResolver statusResolver,
        AuditEventPublisher audit,
        WorkerMetrics metrics
    ) {
        this.dlqRepo = dlqRepo;
        this.deliveryRepo = deliveryRepo;
        this.intentRepo = intentRepo;
        this.statusResolver = statusResolver;
        this.audit = audit;
        this.metrics = metrics;
    }

    /**
     * Move exhausted delivery to DLQ + terminate delivery + recompute intent.
     *
     * <p>Codex iter-1 P0 #3 + P1 #5 absorb:
     * <ul>
     *   <li>{@code REQUIRED} (not REQUIRES_NEW) — inline within caller's
     *       (RetryWorker.processDelivery) transaction. No nested REQUIRES_NEW
     *       row-lock self-deadlock.</li>
     *   <li>Native {@code INSERT ... ON CONFLICT DO NOTHING} on partial unique
     *       index — concurrent multi-pod safe; no DataIntegrityViolation
     *       catch-in-transaction (transaction not aborted).</li>
     * </ul>
     *
     * @param delivery RETRY delivery whose attempt_count >= max-attempts
     * @param reason DLQ reason ({@code "max_attempts"}, {@code "expired"})
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void moveToDlq(NotificationDelivery delivery, String reason) {
        OffsetDateTime now = OffsetDateTime.now();

        // 1) DLQ row insert via native ON CONFLICT DO NOTHING (idempotent)
        OffsetDateTime lastFailAt = delivery.getLastAttemptAt() != null
            ? delivery.getLastAttemptAt() : now;
        int inserted = dlqRepo.insertIfAbsent(
            delivery.getIntentId(),
            delivery.getId(),
            delivery.getChannel(),
            delivery.getRecipientHash(),
            delivery.getProvider(),
            delivery.getAttemptCount(),
            delivery.getFailureReason(),
            lastFailAt,
            now
        );
        boolean isFreshInsert = inserted > 0;
        if (!isFreshInsert) {
            log.info("DLQ already exists (idempotent convergence): deliveryId={} reason={}",
                delivery.getId(), reason);
        }

        // Codex iter-2 P2 absorb: even when DLQ row already exists, converge
        // delivery + intent terminal state (another worker may have inserted
        // DLQ row but crashed before delivery FAILED + intent terminal updates).
        // 2) Terminate delivery FAILED (idempotent: same state if already done)
        delivery.setStatus(NotificationDelivery.Status.FAILED);
        delivery.setPermanentFailureAt(delivery.getPermanentFailureAt() != null
            ? delivery.getPermanentFailureAt() : now);
        delivery.setProcessingLeaseUntil(null);
        delivery.setClaimToken(null);
        deliveryRepo.save(delivery);

        // 3) Resolve intent terminal status
        NotificationIntent intent = intentRepo.findByIntentId(delivery.getIntentId()).orElse(null);
        if (intent != null) {
            List<NotificationDelivery> all = deliveryRepo.findByIntentId(intent.getIntentId());
            NotificationIntent.Status terminal = statusResolver.resolve(all);
            if (terminal != null && intent.getStatus() != terminal) {
                intent.setStatus(terminal);
                intent.setTerminatedAt(now);
                intent.setProcessingLeaseUntil(null);
                intent.setProcessingOwner(null);
                intent.setClaimToken(null);
                intentRepo.save(intent);
                metrics.intentTerminated(terminal.name(), intent.getOrgId());
            }
        }

        // 4) Audit (only on fresh insert — duplicate convergence skips audit row
        //    to keep append-only semantics clean)
        if (isFreshInsert && intent != null) {
            Map<String, Object> details = new HashMap<>();
            details.put("dlq_reason", reason);
            details.put("attempt_count", delivery.getAttemptCount());
            details.put("delivery_id", delivery.getId());
            if (delivery.getFailureReason() != null) {
                details.put("last_failure_reason", delivery.getFailureReason());
            }
            audit.publish("DLQ_TERMINATED", intent, delivery.getRecipientHash(),
                delivery.getChannel(), details);
            metrics.dlqTerminated(reason);
        }

        log.info("DLQ {}: intentId={} deliveryId={} channel={} reason={} attempts={}",
            isFreshInsert ? "moved" : "convergence",
            delivery.getIntentId(), delivery.getId(), delivery.getChannel(),
            reason, delivery.getAttemptCount());
    }
}
