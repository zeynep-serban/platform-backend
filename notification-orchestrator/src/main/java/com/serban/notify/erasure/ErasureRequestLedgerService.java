package com.serban.notify.erasure;

import com.serban.notify.domain.ErasureRequestLedger;
import com.serban.notify.redaction.PiiRedactor;
import com.serban.notify.repository.ErasureRequestLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * KVKK Madde 13.2 erasure request ledger orchestrator (Faz 23.2 M3 R2
 * PR-K1 — Codex {@code 019e4950} P0 #1 absorb + iter-2 {@code 019e499c}
 * REVISE absorb).
 *
 * <p>Codex 019e499c REVISE absorb summary:
 * <ul>
 *   <li><strong>P0 #1 durable ledger</strong>: open/processing/complete/
 *       fail transitions {@code REQUIRES_NEW} propagation — erasure
 *       outer transaction rollback olsa bile ledger row görünür kalır.
 *       Madde 13.2 garanti: "başvuru alındı, 30-gün SLA başladı"
 *       kanıtı failure'da kaybolmaz.</li>
 *   <li><strong>P0 #2 subject-scoped idempotency</strong>: tüm source'lar
 *       için key'de subject HMAC zorunlu — iki farklı subscriber aynı
 *       evidence_ref ile tek ledger row'a çökemez. Existing row dönerken
 *       subject_ref_hmac eşleşmesi guard.</li>
 *   <li><strong>P1 #3 LEGAL_HOLD guard</strong>: hold edilmiş ledger'a
 *       sonraki erasure çağrısı IllegalStateException; erasure işlemi
 *       fiilen yapılamaz (DPO/legal manuel açar).</li>
 *   <li><strong>P1 #5 PII minimization</strong>: key materyalinde ham
 *       evidence_ref YASAK; HMAC digest (PiiRedactor pepper ile)
 *       kullanılır — operator e-posta/dilekçe metni ledger'a sızmaz.</li>
 * </ul>
 *
 * <p>Append-only saklanır; 90-gün retention purge buna dokunmaz.
 */
@Service
public class ErasureRequestLedgerService {

    private static final Logger log = LoggerFactory.getLogger(ErasureRequestLedgerService.class);

    /**
     * Self-service evidence_ref sabit constant — controller path-based
     * routing. Match → SELF_SERVICE source classification.
     */
    public static final String SELF_SERVICE_EVIDENCE_REF = "self-service-kvkk-art-11";

    private final ErasureRequestLedgerRepository ledgerRepo;
    private final PiiRedactor piiRedactor;

    public ErasureRequestLedgerService(
        ErasureRequestLedgerRepository ledgerRepo,
        PiiRedactor piiRedactor
    ) {
        this.ledgerRepo = ledgerRepo;
        this.piiRedactor = piiRedactor;
    }

    /**
     * Open or fetch ledger entry — idempotent.
     *
     * <p>Codex 019e499c REVISE P0 #1 absorb: {@link Propagation#REQUIRES_NEW}.
     * Outer erasure transaction rollback olsa bile ledger row commit edilir
     * (Madde 13.2 başvuru kanıtı durability).
     *
     * <p>Codex 019e499c REVISE P0 #2 absorb: existing row check artık
     * subject HMAC eşleşmesi de yapar. İki farklı subscriber aynı
     * idempotency_key'e çökerse {@link IllegalStateException} fırlatılır
     * (collision detection — schema corruption alarm).
     *
     * @param orgId          tenant boundary
     * @param subscriberId   subscriber to erase (raw; HMAC içeride)
     * @param evidenceRef    operator/runbook reference (HMAC'lenir,
     *                       ham metin ledger'da YASAK)
     * @param idempotencyKey caller-provided veya null (auto-derive
     *                       subject-scoped — P0 #2 absorb)
     * @return ledger row (yeni veya mevcut)
     * @throws IllegalStateException subject HMAC mismatch on existing row
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ErasureRequestLedger openRequest(
        String orgId,
        String subscriberId,
        String evidenceRef,
        String idempotencyKey
    ) {
        if (orgId == null || subscriberId == null) {
            throw new IllegalArgumentException("openRequest: orgId/subscriberId required");
        }

        // Codex 019e499c REVISE P0 #2 absorb: subject HMAC her zaman
        // önceden hesaplanır; existing row collision guard'ı için gerek.
        String subjectRefHmac = piiRedactor.hashRecipient(orgId, "subscriber", subscriberId);

        String resolvedKey = (idempotencyKey != null && !idempotencyKey.isBlank())
            ? idempotencyKey
            : deriveIdempotencyKey(orgId, subscriberId, evidenceRef);

        Optional<ErasureRequestLedger> existing = ledgerRepo.findByOrgIdAndIdempotencyKey(orgId, resolvedKey);
        if (existing.isPresent()) {
            ErasureRequestLedger existingEntry = existing.get();
            // Subject mismatch guard — P0 #2 fix
            if (!subjectRefHmac.equals(existingEntry.getSubjectRefHmac())) {
                log.warn(
                    "KVKK ledger: subject HMAC mismatch on existing idempotency_key — "
                        + "orgId={} existingHmac=<redacted> attemptedHmac=<redacted>",
                    orgId
                );
                throw new IllegalStateException(
                    "Idempotency key collision with different subject — caller must"
                        + " provide unique idempotency_key or service-side derive must"
                        + " include subject HMAC."
                );
            }
            log.info("KVKK ledger: idempotent open hit orgId={} requestId={} status={}",
                orgId, existingEntry.getRequestId(), existingEntry.getStatus());
            return existingEntry;
        }

        ErasureRequestLedger.RequestSource source = classifySource(evidenceRef);
        ErasureRequestLedger entry = new ErasureRequestLedger();
        entry.setOrgId(orgId);
        entry.setSubjectRefHmac(subjectRefHmac);
        entry.setRequestSource(source);
        entry.setStatus(ErasureRequestLedger.Status.RECEIVED);
        entry.setIdempotencyKey(resolvedKey);

        try {
            ErasureRequestLedger saved = ledgerRepo.save(entry);
            log.info("KVKK ledger: opened orgId={} requestId={} source={} dueAt={}",
                orgId, saved.getRequestId(), source, saved.getDueAt());
            return saved;
        } catch (DataIntegrityViolationException e) {
            log.warn("KVKK ledger: concurrent insert race orgId={} key=<redacted>", orgId);
            ErasureRequestLedger refetched = ledgerRepo.findByOrgIdAndIdempotencyKey(orgId, resolvedKey)
                .orElseThrow(() -> new IllegalStateException(
                    "Ledger unique violation but row missing — schema corruption?", e
                ));
            // Race ile gelen row da subject mismatch guard'ından geçmeli
            if (!subjectRefHmac.equals(refetched.getSubjectRefHmac())) {
                throw new IllegalStateException(
                    "Idempotency key race resolved to different subject — collision."
                );
            }
            return refetched;
        }
    }

    /**
     * RECEIVED → PROCESSING transition. Codex 019e499c REVISE P0 #1:
     * REQUIRES_NEW propagation.
     *
     * <p>Idempotent: zaten PROCESSING / COMPLETED / FAILED / LEGAL_HOLD
     * ise no-op.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(UUID requestId) {
        ledgerRepo.findById(requestId).ifPresent(entry -> {
            if (entry.getStatus() == ErasureRequestLedger.Status.RECEIVED) {
                entry.setStatus(ErasureRequestLedger.Status.PROCESSING);
                ledgerRepo.save(entry);
                log.debug("KVKK ledger: marked PROCESSING requestId={}", requestId);
            }
        });
    }

    /**
     * Erasure başarıyla tamamlandı → COMPLETED + closed_at + audit chain.
     *
     * <p>Codex 019e499c REVISE P0 #1: REQUIRES_NEW propagation —
     * outer transaction commit garantisi gerek.
     * Codex 019e499c REVISE P1 #4: audit_event_v2 BIGINT id + composite
     * PK occurred_at uyumu.
     *
     * <p>Caller, outer transaction commit'ten sonra çağırmalı (örn.
     * TransactionSynchronizationManager afterCommit hook).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeRequest(
        UUID requestId,
        Long auditEventId,
        OffsetDateTime auditEventOccurredAt
    ) {
        int updated = ledgerRepo.markCompleted(
            requestId, OffsetDateTime.now(), auditEventId, auditEventOccurredAt
        );
        if (updated > 0) {
            log.info("KVKK ledger: completed requestId={} auditEventId={}",
                requestId, auditEventId);
        } else {
            log.debug("KVKK ledger: complete no-op (terminal/legal-hold) requestId={}",
                requestId);
        }
    }

    /**
     * Erasure runtime hatası → failure_reason kaydı (status non-terminal).
     *
     * <p>Codex 019e499c iter-3 REVISE P0 absorb: terminal FAILED state
     * SLA breach scan'den row'u düşürdüğü için kullanılmaz. Bunun
     * yerine status PROCESSING (mevcut) kalır, failure_reason yazılır,
     * closed_at NULL kalır → 30-gün SLA scan unresolved teknik hatayı
     * görmeye devam eder.
     *
     * <p>Codex 019e499c REVISE P0 #1: REQUIRES_NEW propagation —
     * outer transaction rollback olsa bile failure_reason row'a
     * yazılır; Madde 13.2 audit visibility için kritik.
     *
     * <p>Status enum {@code FAILED} terminal state DPO/legal formal
     * <em>denial</em> closure için reserve edilir (manuel operator
     * action — PR-K-DPO follow-up scope).
     *
     * @param failureReason kategori (TRANSACTION_ROLLBACK /
     *                      AUDIT_PUBLISH_ERROR / DB_CONSTRAINT /
     *                      UNKNOWN); stack trace ASLA — PII sızması
     *                      riski.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID requestId, String failureReason) {
        int updated = ledgerRepo.markFailed(
            requestId, OffsetDateTime.now(), failureReason
        );
        if (updated > 0) {
            log.warn("KVKK ledger: erasure error logged (non-terminal, SLA scan visible) "
                + "requestId={} reason={}", requestId, failureReason);
        } else {
            log.debug("KVKK ledger: markFailed no-op (terminal/legal-hold) requestId={}",
                requestId);
        }
    }

    /**
     * SLA Watchdog scan — KVKK Madde 13.2 30-gün breach check.
     *
     * <p>{@code due_at <= NOW()} ve status NOT terminal (RECEIVED /
     * PROCESSING / LEGAL_HOLD) → breach list. Caller (scheduler)
     * Slack alert + DPO görünür yapar.
     */
    @Transactional(readOnly = true)
    public java.util.List<ErasureRequestLedger> findOverdueRequests() {
        return ledgerRepo.findOverdueRequests(OffsetDateTime.now());
    }

    /**
     * DPO subject lookup — KVKK Madde 13 right-to-information: bir
     * subscriber için tüm geçmiş erasure başvuruları (newest-first).
     */
    @Transactional(readOnly = true)
    public java.util.List<ErasureRequestLedger> findBySubject(
        String orgId, String subscriberId
    ) {
        String hmac = piiRedactor.hashRecipient(orgId, "subscriber", subscriberId);
        return ledgerRepo.findByOrgIdAndSubjectRefHmacOrderByReceivedAtDesc(orgId, hmac);
    }

    // ========================================================================
    // Source classification — Locale.ROOT defensive (Turkish dotless-I)
    // ========================================================================

    static ErasureRequestLedger.RequestSource classifySource(String evidenceRef) {
        if (evidenceRef == null || evidenceRef.isBlank()) {
            return ErasureRequestLedger.RequestSource.ADMIN;
        }
        String lower = evidenceRef.toLowerCase(Locale.ROOT);
        if (lower.contains("self-service") || lower.contains("self_service")
            || lower.contains("kvkk-art-11") || lower.contains("kvkk art 11")) {
            return ErasureRequestLedger.RequestSource.SELF_SERVICE;
        }
        if (lower.contains("dpo")) {
            return ErasureRequestLedger.RequestSource.DPO;
        }
        if (lower.contains("compliance") || lower.contains("audit")) {
            return ErasureRequestLedger.RequestSource.COMPLIANCE_AUDIT;
        }
        if (lower.contains("legal") || lower.contains("court") || lower.contains("mahkeme")) {
            return ErasureRequestLedger.RequestSource.LEGAL;
        }
        return ErasureRequestLedger.RequestSource.ADMIN;
    }

    /**
     * Auto-derive idempotency_key — Codex 019e499c REVISE P0 #2 + P1 #5
     * absorb.
     *
     * <p>ALL source'lar için subject HMAC zorunlu (P0 #2 — iki farklı
     * subscriber aynı evidence_ref ile tek ledger row'a çökemez).
     *
     * <p>Evidence_ref ham metin ASLA key materyali değil; HMAC digest
     * kullanılır (P1 #5 — operator dilekçe/email/phone yazsa bile
     * ledger'da ham metin görünmez).
     *
     * <p>Pattern:
     * <ul>
     *   <li>SELF_SERVICE: {@code self-{org}-{subjectHash16}-{YYYY-MM-DD}}
     *       (günlük dedup, idempotent retry suppression)</li>
     *   <li>ADMIN/LEGAL/DPO/COMPLIANCE_AUDIT: {@code
     *       {source}-{org}-{subjectHash16}-{evidenceHash16}}</li>
     * </ul>
     */
    String deriveIdempotencyKey(String orgId, String subscriberId, String evidenceRef) {
        var source = classifySource(evidenceRef);

        // Subject HMAC her source için zorunlu — P0 #2 fix
        String subjectHash = piiRedactor.hashRecipient(orgId, "subscriber", subscriberId);
        String subjectHash16 = subjectHash.substring(0, 16);

        if (source == ErasureRequestLedger.RequestSource.SELF_SERVICE) {
            String today = java.time.LocalDate.now().toString();
            return "self-" + orgId + "-" + subjectHash16 + "-" + today;
        }

        // ADMIN/LEGAL/DPO/COMPLIANCE_AUDIT — evidenceRef HMAC digest
        // (P1 #5 fix — ham metin YASAK).
        String prefix = source.name().toLowerCase(Locale.ROOT);
        String evidenceHash16 = (evidenceRef != null && !evidenceRef.isBlank())
            ? piiRedactor.hashRecipient(orgId, "subscriber", "evidence:" + evidenceRef).substring(0, 16)
            : UUID.randomUUID().toString().substring(0, 16);
        return prefix + "-" + orgId + "-" + subjectHash16 + "-" + evidenceHash16;
    }
}
