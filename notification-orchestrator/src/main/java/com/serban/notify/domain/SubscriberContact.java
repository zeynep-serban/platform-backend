package com.serban.notify.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Subscriber contact — (org_id, subscriber_id) → email/phone/locale lookup
 * (Codex 019dfaaa PR5 Q2 absorb).
 *
 * <p>Source-of-truth: Workcube ETL veya event projection (sonraki faz). PR5'te
 * local seed + test fixture ile doldurulur. {@code source} column'u origin
 * tracking için (manual / etl-workcube / event-import).
 *
 * <p>Preference table (subscriber_preference) ayrı:
 * <ul>
 *   <li>contact: subscriber-level identity (email, phone, locale, verified)</li>
 *   <li>preference: tercih (channel/topic allow/deny, quiet hours, frequency)</li>
 * </ul>
 */
@Entity
@Table(name = "subscriber_contact", schema = "notify")
public class SubscriberContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "org_id", nullable = false, length = 64)
    private String orgId;

    @Column(name = "subscriber_id", nullable = false, length = 128)
    private String subscriberId;

    @Column(length = 254)
    private String email;

    @Column(length = 32)
    private String phone;

    @Column(nullable = false, length = 16)
    private String locale = "tr-TR";

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified = false;

    @Column(nullable = false, length = 64)
    private String source = "manual";

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }
    public String getSubscriberId() { return subscriberId; }
    public void setSubscriberId(String subscriberId) { this.subscriberId = subscriberId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getLocale() { return locale; }
    public void setLocale(String locale) { this.locale = locale; }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public boolean isPhoneVerified() { return phoneVerified; }
    public void setPhoneVerified(boolean phoneVerified) { this.phoneVerified = phoneVerified; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SubscriberContact that)) return false;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() { return getClass().hashCode(); }
}
