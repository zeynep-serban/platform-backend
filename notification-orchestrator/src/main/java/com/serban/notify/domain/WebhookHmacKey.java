package com.serban.notify.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * Webhook HMAC signing key (Faz 23.2 PR-A — Codex 019dfae5 Q3 PARTIAL absorb).
 *
 * <p>Kid-aware HMAC key registry for webhook egress signature rotation.
 * Receivers verify with known {@code kid} from header
 * {@code X-Notify-Signature: t=<ts>,kid=<kid>,v1=<sig>}.
 *
 * <p>Status lifecycle:
 * <ol>
 *   <li>{@code next} — yeni key insert, henüz aktif değil; rotation hazırlık</li>
 *   <li>{@code active} — şu an signing'de kullanılan key (sadece BIR active)</li>
 *   <li>{@code retired} — eski key; verification window'unda receivers hâlâ
 *       eski signature'ları kabul etmek isteyebilir; sonra delete</li>
 * </ol>
 *
 * <p>Rotation pattern (operator runbook):
 * <ol>
 *   <li>Vault'ta yeni secret oluştur: {@code kv/platform/notify/webhook/<new-kid>}</li>
 *   <li>DB row insert: kid=&lt;new-kid&gt;, status=next, secret_ref=&lt;vault-path&gt;</li>
 *   <li>Cutover: status=next → active (eski active → retired); aynı transaction</li>
 *   <li>Consumer migrate window (default 7 day) sonra retired key delete (audit retain)</li>
 * </ol>
 */
@Entity
@Table(name = "webhook_hmac_key", schema = "notify")
public class WebhookHmacKey {

    public enum Status { NEXT, ACTIVE, RETIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String kid;

    @Column(name = "secret_ref", nullable = false, length = 255)
    private String secretRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getKid() { return kid; }
    public void setKid(String kid) { this.kid = kid; }
    public String getSecretRef() { return secretRef; }
    public void setSecretRef(String secretRef) { this.secretRef = secretRef; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public OffsetDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(OffsetDateTime activatedAt) { this.activatedAt = activatedAt; }
    public OffsetDateTime getRetiredAt() { return retiredAt; }
    public void setRetiredAt(OffsetDateTime retiredAt) { this.retiredAt = retiredAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebhookHmacKey that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() { return getClass().hashCode(); }
}
