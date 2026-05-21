package com.serban.notify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * KVKK Madde 13.2 erasure request ledger (Faz 23.2 M3 R2 PR-K1 —
 * Codex {@code 019e4950} P0 #1 absorb).
 *
 * <p>Hukuki dayanak: KVKK Madde 13.2 — "Veri sorumlusu, başvuruyu
 * talebin niteliğine göre en kısa sürede ve en geç <strong>otuz gün
 * içinde</strong> ücretsiz olarak sonuçlandırır."
 *
 * <p>Append-only saklanır; 90-gün retention purge buna dokunmaz (KVKK
 * denetim sorumluluğu).
 *
 * <p>V18 migration: {@code notify.erasure_request_ledger}.
 */
@Entity
@Table(schema = "notify", name = "erasure_request_ledger")
public class ErasureRequestLedger {

    /**
     * KVKK Madde 13.2 SLA — 30 gün.
     */
    public static final Duration SLA_DURATION = Duration.ofDays(30L);

    public enum RequestSource {
        SELF_SERVICE,
        ADMIN,
        LEGAL,
        DPO,
        COMPLIANCE_AUDIT
    }

    public enum Status {
        RECEIVED,
        PROCESSING,
        COMPLETED,
        LEGAL_HOLD,
        FAILED
    }

    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "org_id", nullable = false, length = 64)
    private String orgId;

    /**
     * HMAC-SHA256 with org-namespaced Vault pepper (PiiRedactor).
     * Pseudonymous; raw email/phone YASAK (KVKK Madde 12).
     */
    @Column(name = "subject_ref_hmac", nullable = false, length = 128)
    private String subjectRefHmac;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_source", nullable = false, length = 32)
    private RequestSource requestSource;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    /**
     * KVKK Madde 13.2 SLA: {@code received_at + 30 gün}.
     * ErasureSlaWatchdog scheduled scan {@code due_at <= NOW()}
     * → Slack alert.
     */
    @Column(name = "due_at", nullable = false)
    private OffsetDateTime dueAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    /**
     * Erasure runtime hatası kategorisi (Codex 019e499c P0 #1 +
     * iter-3 P0 absorb): TRANSACTION_ROLLBACK / AUDIT_PUBLISH_ERROR /
     * DB_CONSTRAINT / UNKNOWN. PII sızması olmayacak şekilde sadece
     * kategori; stack trace YASAK.
     *
     * <p>Status NON-terminal kalır (PROCESSING); failure_reason
     * yazılır; closed_at NULL kalır → KVKK Madde 13.2 SLA scan
     * unresolved teknik hatayı görmeye devam eder. Terminal FAILED
     * state DPO/legal formal "denied" closure için reserve.
     */
    @Column(name = "failure_reason", length = 256)
    private String failureReason;

    /**
     * KVKK Madde 28 istisna kategorisi (Codex 019e499c P1 #5 absorb).
     * Serbest metin YASAK; sadece enum-like reason code.
     */
    @Column(name = "legal_hold_reason_code", length = 64)
    private String legalHoldReasonCode;

    /**
     * Kısa external referans (mahkeme kararı no, ticket id) — insan
     * okunabilir açıklama DEĞİL (Codex 019e499c P1 #5 absorb).
     */
    @Column(name = "legal_hold_external_reference", length = 128)
    private String legalHoldExternalReference;

    /**
     * Cross-request deduplication. Aynı {@code (org_id, idempotency_key)}
     * ikinci başvuru ledger insert UNIQUE violation → service-side
     * no-op (mevcut row döner). Codex 019e499c P1 #5 absorb: ham
     * evidence_ref ASLA key materyali değil; HMAC digest kullanılır
     * (PII minimization).
     */
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    /**
     * Bağlı {@code audit_event_v2} row id (Codex 019e499c P1 #4 absorb:
     * BIGINT, audit schema gerçek tipiyle uyumlu — UUID değildi). Composite
     * PK (id, occurred_at) gereği {@link #lastAuditEventOccurredAt} ile
     * birlikte JOIN edilir.
     */
    @Column(name = "last_audit_event_id")
    private Long lastAuditEventId;

    /**
     * audit_event_v2 partition discriminator (Codex 019e499c P1 #4 absorb).
     */
    @Column(name = "last_audit_event_occurred_at")
    private OffsetDateTime lastAuditEventOccurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (requestId == null) {
            requestId = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (receivedAt == null) {
            receivedAt = now;
        }
        if (dueAt == null) {
            dueAt = receivedAt.plus(SLA_DURATION);
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (status == null) {
            status = Status.RECEIVED;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // Getters / Setters

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getSubjectRefHmac() { return subjectRefHmac; }
    public void setSubjectRefHmac(String subjectRefHmac) { this.subjectRefHmac = subjectRefHmac; }

    public RequestSource getRequestSource() { return requestSource; }
    public void setRequestSource(RequestSource requestSource) { this.requestSource = requestSource; }

    public OffsetDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(OffsetDateTime receivedAt) { this.receivedAt = receivedAt; }

    public OffsetDateTime getDueAt() { return dueAt; }
    public void setDueAt(OffsetDateTime dueAt) { this.dueAt = dueAt; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public OffsetDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(OffsetDateTime closedAt) { this.closedAt = closedAt; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getLegalHoldReasonCode() { return legalHoldReasonCode; }
    public void setLegalHoldReasonCode(String legalHoldReasonCode) { this.legalHoldReasonCode = legalHoldReasonCode; }

    public String getLegalHoldExternalReference() { return legalHoldExternalReference; }
    public void setLegalHoldExternalReference(String s) { this.legalHoldExternalReference = s; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Long getLastAuditEventId() { return lastAuditEventId; }
    public void setLastAuditEventId(Long lastAuditEventId) { this.lastAuditEventId = lastAuditEventId; }

    public OffsetDateTime getLastAuditEventOccurredAt() { return lastAuditEventOccurredAt; }
    public void setLastAuditEventOccurredAt(OffsetDateTime t) { this.lastAuditEventOccurredAt = t; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    /**
     * KVKK Madde 13.2 SLA breach check (operasyonel).
     *
     * @return true if {@code now > dueAt} and status not terminal
     */
    public boolean isSlaBreached(OffsetDateTime now) {
        if (status == Status.COMPLETED || status == Status.FAILED) {
            return false;
        }
        return now.isAfter(dueAt);
    }
}
