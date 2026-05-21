package com.serban.notify.erasure;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.ErasureRequestLedger;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationInboxRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ErasureService — KVKK §11 / GDPR Art 17 right-to-erasure (Faz 23.2 PR-B —
 * Codex 019dfae5 Q2 PARTIAL absorb).
 *
 * <p>Codex Q2 absorb:
 * <ul>
 *   <li>Sync admin endpoint (small data; async job follow-up for bulk)</li>
 *   <li>Sadece intent.payload değil; recipients_snapshot, metadata,
 *       channel_routing, preference_override içindeki PII de purge</li>
 *   <li>delivery.recipient_id null (subscriber link severance);
 *       recipient_hash KORUNUR (operational analytics; KVKK pseudonymous boundary)</li>
 *   <li>Audit append: SUBSCRIBER_ERASURE_REQUEST event (append-only RULE — silinmez)</li>
 *   <li>Idempotent: ikinci çağrı = no-op (already erased)</li>
 * </ul>
 *
 * <p>Authorization (caller responsibility): api-gateway path-based
 * {@code ROLE_PRIVACY_OFFICER} allowlist (Codex iter-1 P0 #2 absorb:
 * in-app Spring Security + JWT decoder + role converter +
 * spring-security-test infrastructure follow-up scope; PR-C ile gateway
 * manifest/runbook gate aktif edilir).
 */
@Service
public class ErasureService {

    private static final Logger log = LoggerFactory.getLogger(ErasureService.class);

    /**
     * Audit event type for admin (privacy-officer) erasure.
     *
     * <p>Faz 23.2 PR-B baseline; codex thread {@code 019dfae5} Q2 absorb.
     */
    public static final String EVENT_ADMIN_ERASURE = "SUBSCRIBER_ERASURE_REQUEST";

    /**
     * Audit event type for self-service (subscriber) erasure.
     *
     * <p>Faz 23.2.B closure (M3 stale audit 2026-05-09 — Codex thread
     * {@code 019e0c28} subscriber self-service path). Audit reporting/query
     * netliği için admin scope'tan ayırt edilir.
     */
    public static final String EVENT_SELF_SERVICE_ERASURE = "SUBSCRIBER_SELF_ERASURE_REQUEST";

    private final NotificationIntentRepository intentRepo;
    private final NotificationDeliveryRepository deliveryRepo;
    private final NotificationInboxRepository inboxRepo;
    private final AuditEventPublisher audit;
    private final ErasureRequestLedgerService ledgerService;

    public ErasureService(
        NotificationIntentRepository intentRepo,
        NotificationDeliveryRepository deliveryRepo,
        NotificationInboxRepository inboxRepo,
        AuditEventPublisher audit,
        ErasureRequestLedgerService ledgerService
    ) {
        this.intentRepo = intentRepo;
        this.deliveryRepo = deliveryRepo;
        this.inboxRepo = inboxRepo;
        this.audit = audit;
        this.ledgerService = ledgerService;
    }

    /**
     * Erase subscriber PII across notification data — KVKK §11.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>Find all intents for (orgId, subscriberId) — by recipients_snapshot</li>
     *   <li>For each intent: payload=null, recipients_snapshot=null,
     *       metadata=null, preference_override=null (PII surface)</li>
     *   <li>For each delivery: recipient_id=null (subscriber link severance);
     *       recipient_hash KORUNUR (operational analytics)</li>
     *   <li>Audit append: SUBSCRIBER_ERASURE_REQUEST event (append-only)</li>
     * </ol>
     *
     * <p>Idempotent: second call = no-op (intent.payload already null).
     *
     * @param request erasure request (orgId + subscriberId + reason + evidence_ref)
     * @return EraseResult (intentsErased + deliveriesAnonymized)
     */
    /**
     * Default admin path (backward-compat) — emits {@link #EVENT_ADMIN_ERASURE}.
     */
    @Transactional
    public EraseResult eraseSubscriber(EraseRequest request) {
        return eraseSubscriber(request, EVENT_ADMIN_ERASURE);
    }

    /**
     * Erase with explicit audit event type — supports admin vs self-service
     * audit reporting separation (M3 stale audit 2026-05-09 P2 absorb).
     *
     * <p>Free-form `reason` log'a yazılmaz (Codex `019e0c28` P1 absorb:
     * KVKK self-service path için PII risk). Reason/evidence_ref audit
     * details içine girer; PiiRedactor whitelist'i bunu zaten korur, ama
     * minimal log surface için INFO level'da yalnız orgId+subscriberId.
     */
    /**
     * Public eraseSubscriber — @Transactional sınırı burada. Codex
     * 019e499c iter-3 REVISE P1 absorb: önceden inner performErasure'a
     * @Transactional yapıştırılmıştı ama same-class invocation Spring
     * proxy bypass eder → AuditEventPublisher MANDATORY transaction
     * beklediği için runtime'da patlardı. Artık dış sınır public
     * overload'da.
     */
    @Transactional
    public EraseResult eraseSubscriber(EraseRequest request, String eventType) {
        // Codex `019e4950` P1 absorb: subscriberId + evidence_ref direct
        // log YASAK (KVKK Madde 12 data minimization).
        log.info("KVKK erasure start: orgId={} subjectRef=<hmac-redacted> eventType={}",
            request.orgId(), eventType);

        // Codex `019e4950` P0 #1 + `019e499c` REVISE P0 #1 absorb:
        // KVKK Madde 13.2 30-gün SLA ledger. LedgerService method'ları
        // REQUIRES_NEW propagation — outer erasure transaction rollback
        // olsa bile ledger row görünür kalır.
        ErasureRequestLedger ledgerEntry = ledgerService.openRequest(
            request.orgId(),
            request.subscriberId(),
            request.evidenceRef(),
            request.idempotencyKey()
        );

        // Codex 019e499c REVISE P1 #3 absorb: LEGAL_HOLD guard.
        if (ledgerEntry.getStatus() == ErasureRequestLedger.Status.LEGAL_HOLD) {
            log.warn("KVKK erasure blocked LEGAL_HOLD: orgId={} ledgerId={} reasonCode={}",
                request.orgId(), ledgerEntry.getRequestId(),
                ledgerEntry.getLegalHoldReasonCode());
            throw new LegalHoldException(
                "Erasure blocked: ledger entry is on LEGAL_HOLD (Madde 28 istisna). "
                    + "DPO/legal must release hold before erasure proceeds.",
                ledgerEntry.getRequestId(),
                ledgerEntry.getLegalHoldReasonCode()
            );
        }

        // Idempotent ikinci çağrı (COMPLETED) — no-op.
        if (ledgerEntry.getStatus() == ErasureRequestLedger.Status.COMPLETED) {
            log.info("KVKK erasure idempotent no-op (COMPLETED): orgId={} ledgerId={}",
                request.orgId(), ledgerEntry.getRequestId());
            return new EraseResult(0, 0, 0,
                ledgerEntry.getRequestId(), ledgerEntry.getDueAt());
        }

        ledgerService.markProcessing(ledgerEntry.getRequestId());

        try {
            return performErasure(request, eventType, ledgerEntry);
        } catch (RuntimeException e) {
            // Codex 019e499c REVISE P0 #1 absorb: durable failure tracking.
            // Codex iter-3 REVISE P0 absorb: markFailed status non-terminal
            // (PROCESSING kalır) — KVKK Madde 13.2 SLA scan unresolved
            // teknik hatayı görmeye devam eder.
            log.warn("KVKK erasure failed: orgId={} ledgerId={} error={}",
                request.orgId(), ledgerEntry.getRequestId(), e.getClass().getSimpleName());
            ledgerService.markFailed(ledgerEntry.getRequestId(), classifyFailure(e));
            throw e;
        }
    }

    /**
     * Inner erasure performer — same-class invocation. @Transactional
     * burada DEĞİL (Spring proxy bypass riski); outer
     * {@link #eraseSubscriber(EraseRequest, String)} method'undaki
     * @Transactional sınır içinde çalışır.
     *
     * <p>Codex 019e499c iter-3 REVISE P1 absorb: önceki yanlış pattern
     * düzeltildi.
     */
    private EraseResult performErasure(
        EraseRequest request, String eventType, ErasureRequestLedger ledgerEntry
    ) {
        // Find all intents that have this subscriber in recipients_snapshot
        List<NotificationIntent> intents = intentRepo.findIntentsBySubscriber(
            request.orgId(), request.subscriberId()
        );

        int intentsErased = 0;
        int deliveriesAnonymized = 0;

        for (NotificationIntent intent : intents) {
            // Codex iter-1 P1 absorb: idempotent check expanded — only skip
            // if BOTH intent PII and target deliveries already anonymized.
            // Earlier check (payload+snapshot null) skipped delivery cleanup
            // when first call partial-failed.
            boolean intentNeedsErase = intent.getPayload() != null
                || intent.getRecipientsSnapshot() != null
                || intent.getMetadata() != null
                || intent.getPreferenceOverride() != null
                || intent.getChannelRouting() != null;

            // Find ONLY the target subscriber's deliveries (Codex iter-1 P1 absorb:
            // multi-recipient intent → other subscribers' deliveries preserved)
            var allDeliveries = deliveryRepo.findByIntentId(intent.getIntentId());
            var targetDeliveries = allDeliveries.stream()
                .filter(d -> d.getRecipientType() == NotificationDelivery.RecipientType.SUBSCRIBER)
                .filter(d -> request.subscriberId().equals(d.getRecipientId()))
                .toList();

            boolean deliveriesNeedAnonymize = targetDeliveries.stream()
                .anyMatch(d -> d.getRecipientId() != null);

            if (!intentNeedsErase && !deliveriesNeedAnonymize) {
                // Fully idempotent skip
                continue;
            }

            if (intentNeedsErase) {
                intent.setPayload(null);
                intent.setRecipientsSnapshot(null);
                intent.setMetadata(null);
                intent.setPreferenceOverride(null);
                intent.setChannelRouting(null);  // Codex iter-1 absorb: channelRouting may contain PII (slack URL etc.)
                intentRepo.save(intent);
                intentsErased++;
            }

            // Anonymize ONLY target subscriber's deliveries (recipient_hash KORUNUR)
            for (var delivery : targetDeliveries) {
                if (delivery.getRecipientId() != null) {
                    delivery.setRecipientId(null);
                    deliveryRepo.save(delivery);
                    deliveriesAnonymized++;
                }
            }

            // Audit append (only when actual change happened — append-only RULE)
            if (intentNeedsErase || deliveriesNeedAnonymize) {
                Map<String, Object> details = new HashMap<>();
                details.put("erasure_reason", request.reason());
                details.put("evidence_ref", request.evidenceRef());
                details.put("subscriber_id", request.subscriberId());  // NOT email/phone
                details.put("deliveries_anonymized", deliveriesAnonymized);
                // Codex 019e4950 P0 #1 absorb: ledger correlation
                details.put("ledger_request_id", ledgerEntry.getRequestId().toString());
                details.put("request_source", ledgerEntry.getRequestSource().name());
                details.put("due_at", ledgerEntry.getDueAt().toString());
                audit.publish(eventType, intent, null, null, details);
            }
        }

        // Faz 23.3 PR-E.1 (Codex iter-1 P1.2 absorb): inbox rows are
        // subscriber-coupled PII (subject + body content snapshots) — KVKK
        // Art 17 right-to-erasure requires complete removal. Hard delete
        // (NOT anonymize) since content cannot be retained pseudonymously.
        int inboxRowsDeleted = inboxRepo.deleteByOrgIdAndSubscriberId(
            request.orgId(), request.subscriberId()
        );

        // Codex iter-2 P1 absorb: standalone audit (no source intent) — avoids
        // NPE on intent.getTemplateId() / .getTopicKey() in publish() and
        // satisfies audit_event_v2 NOT NULL constraints (intent_id, topic_key)
        // via synthesized standalone values.
        if (inboxRowsDeleted > 0) {
            Map<String, Object> inboxDetails = new HashMap<>();
            inboxDetails.put("erasure_reason", request.reason());
            inboxDetails.put("evidence_ref", request.evidenceRef());
            inboxDetails.put("subscriber_id", request.subscriberId());
            inboxDetails.put("inbox_rows_deleted", inboxRowsDeleted);
            // Codex 019e4950 P0 #1 absorb: ledger correlation
            inboxDetails.put("ledger_request_id", ledgerEntry.getRequestId().toString());
            inboxDetails.put("request_source", ledgerEntry.getRequestSource().name());
            inboxDetails.put("due_at", ledgerEntry.getDueAt().toString());
            audit.publishStandalone(
                "SUBSCRIBER_INBOX_ERASURE",
                request.orgId(),
                null,  // recipient_hash null acceptable (audit_event_v2 nullable)
                inboxDetails
            );
        }

        // Codex 019e499c REVISE P0 #1 absorb: completeRequest outer
        // transaction commit'inden SONRA çağrılmalı (afterCommit hook).
        // Aksi halde outer tx fail ederse ledger yanlış COMPLETED yazardı.
        // Test contexts (no active synchronization) için direct call
        // fallback — best-effort; production tx context'inde afterCommit.
        final UUID ledgerRequestId = ledgerEntry.getRequestId();
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            // Codex 019e499c REVISE P1 #4 absorb: audit_event_v2
                            // BIGINT id + occurred_at composite (audit publisher
                            // event_id döndürene kadar null wire).
                            ledgerService.completeRequest(ledgerRequestId, null, null);
                        }
                    }
                );
        } else {
            // Unit-test edge — no Spring tx context; direct call OK.
            ledgerService.completeRequest(ledgerRequestId, null, null);
        }

        log.info("KVKK erasure complete: orgId={} subjectRef=<hmac-redacted> ledgerId={} intents_erased={} deliveries_anonymized={} inbox_rows_deleted={}",
            request.orgId(), ledgerEntry.getRequestId(), intentsErased, deliveriesAnonymized, inboxRowsDeleted);

        return new EraseResult(
            intentsErased, deliveriesAnonymized, inboxRowsDeleted,
            ledgerEntry.getRequestId(), ledgerEntry.getDueAt()
        );
    }

    /**
     * Failure category classification — PII sızması olmayacak şekilde
     * sadece exception class adı veya bilinen kategoriler.
     * Codex 019e499c REVISE P0 #1 absorb.
     */
    static String classifyFailure(Throwable e) {
        if (e == null) return "UNKNOWN";
        String name = e.getClass().getSimpleName();
        // Spring / JPA bilinen kategoriler — stack trace metni YASAK
        if (name.contains("DataIntegrity") || name.contains("Constraint")) {
            return "DB_CONSTRAINT";
        }
        if (name.contains("Transaction") || name.contains("Rollback")) {
            return "TRANSACTION_ROLLBACK";
        }
        if (name.contains("Audit") || name.contains("Publish")) {
            return "AUDIT_PUBLISH_ERROR";
        }
        return "UNKNOWN";
    }

    /**
     * KVKK Madde 28 LEGAL_HOLD exception — erasure operasyonu fiilen
     * yapılamaz; DPO/legal manuel release gerek.
     */
    public static class LegalHoldException extends RuntimeException {
        private final UUID ledgerRequestId;
        private final String reasonCode;

        public LegalHoldException(String message, UUID ledgerRequestId, String reasonCode) {
            super(message);
            this.ledgerRequestId = ledgerRequestId;
            this.reasonCode = reasonCode;
        }

        public UUID getLedgerRequestId() { return ledgerRequestId; }
        public String getReasonCode() { return reasonCode; }
    }

    /**
     * Erasure request — KVKK admin / self-service trigger.
     *
     * @param orgId tenant boundary
     * @param subscriberId subscriber to erase
     * @param reason KVKK §11 reason (e.g., "subject_request", "expired_consent")
     * @param evidenceRef ticket/letter/audit reference (operator runbook)
     * @param idempotencyKey optional ledger dedup key (Codex 019e4950 P0 #1);
     *                       null geçilirse service-side auto-derive
     */
    public record EraseRequest(
        String orgId,
        String subscriberId,
        String reason,
        String evidenceRef,
        String idempotencyKey
    ) {
        /**
         * Backward-compatible 4-arg ctor — ledger idempotency_key auto-derive.
         */
        public EraseRequest(String orgId, String subscriberId, String reason, String evidenceRef) {
            this(orgId, subscriberId, reason, evidenceRef, null);
        }
    }

    /**
     * Erasure result.
     *
     * @param intentsErased intents whose PII (payload, snapshot, metadata, preference) cleared
     * @param deliveriesAnonymized delivery rows where recipient_id null'lanan
     * @param inboxRowsDeleted in-app inbox rows hard-deleted (Faz 23.3 PR-E.1)
     * @param ledgerRequestId KVKK Madde 13.2 ledger request id (Codex 019e4950 P0 #1)
     * @param dueAt KVKK Madde 13.2 30-gün SLA hedef tarihi
     */
    public record EraseResult(
        int intentsErased,
        int deliveriesAnonymized,
        int inboxRowsDeleted,
        java.util.UUID ledgerRequestId,
        java.time.OffsetDateTime dueAt
    ) {
        /**
         * Backward-compatible 3-arg ctor — ledger fields null (legacy
         * test fixture'ları için). Production path her zaman 5-arg.
         */
        public EraseResult(int intentsErased, int deliveriesAnonymized, int inboxRowsDeleted) {
            this(intentsErased, deliveriesAnonymized, inboxRowsDeleted, null, null);
        }
    }
}
