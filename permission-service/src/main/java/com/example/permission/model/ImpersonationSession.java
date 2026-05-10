package com.example.permission.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.net.InetAddress;
import java.time.Instant;
import java.util.UUID;

/**
 * User Impersonation v1 — authoritative session lookup table.
 *
 * <p>Codex 019e0dfb iter-10/19 design:
 * <ul>
 *   <li>UNIQUE (issuer, jti) for cross-realm safety</li>
 *   <li>status varchar + check ('ACTIVE','STOPPED','EXPIRED','REVOKED')</li>
 *   <li>impersonator_user_id authoritative from DB session, NOT JWT</li>
 *   <li>HPA-managed assumption: pod-independent lookup</li>
 *   <li>FAILED attempts → permission_audit_events, NOT sessions</li>
 * </ul>
 *
 * <p>Spike-2 binding model: PASS_JTI_SESSION_LOOKUP (Spike-2 doc).
 * Runtime middleware extracts jti+sid from exchanged token and looks up
 * this table for impersonator identity.
 */
@Entity
@Table(name = "impersonation_sessions")
public class ImpersonationSession {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "impersonator_user_id", nullable = false)
    private Long impersonatorUserId;

    @Column(name = "impersonator_subject", nullable = false, length = 255)
    private String impersonatorSubject;

    @Column(name = "impersonator_email", length = 255)
    private String impersonatorEmail;

    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Column(name = "target_subject", nullable = false, length = 255)
    private String targetSubject;

    @Column(name = "target_email", length = 255)
    private String targetEmail;

    /** OIDC token issuer (KC realm URL). Codex iter-10: UNIQUE with jti. */
    @Column(name = "issuer", nullable = false, length = 255)
    private String issuer;

    /** JWT jti claim — exchange token id. */
    @Column(name = "jti", nullable = false, length = 255)
    private String jti;

    /** KC SSO session id (sid claim). */
    @Column(name = "sid", nullable = false, length = 255)
    private String sid;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** USER_STOP, LOGOUT, TOKEN_EXPIRED, ADMIN_REVOKE, SYSTEM_SWEEP. */
    @Column(name = "ended_reason", length = 50)
    private String endedReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    @Column(name = "ip_address")
    private InetAddress ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "client_ip_via_xff")
    private InetAddress clientIpViaXff;

    public enum Status {
        ACTIVE,
        STOPPED,
        EXPIRED,
        REVOKED;
    }

    public ImpersonationSession() {
    }

    /**
     * App-generated UUID — Codex iter-25 P1 absorb.
     *
     * <p>V19 migration'da DB default `gen_random_uuid()` KALDIRILDI
     * (pgcrypto extension assumption riski). Hibernate 6 + Spring Boot
     * 3.5 ile id assigned bekler; @PrePersist null id'yi
     * {@link java.util.UUID#randomUUID()} ile doldurur — JVM-side
     * deterministic, DB extension bağımsız.
     */
    @PrePersist
    protected void ensureId() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
    }

    // Getters & setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Long getImpersonatorUserId() {
        return impersonatorUserId;
    }

    public void setImpersonatorUserId(Long impersonatorUserId) {
        this.impersonatorUserId = impersonatorUserId;
    }

    public String getImpersonatorSubject() {
        return impersonatorSubject;
    }

    public void setImpersonatorSubject(String impersonatorSubject) {
        this.impersonatorSubject = impersonatorSubject;
    }

    public String getImpersonatorEmail() {
        return impersonatorEmail;
    }

    public void setImpersonatorEmail(String impersonatorEmail) {
        this.impersonatorEmail = impersonatorEmail;
    }

    public Long getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(Long targetUserId) {
        this.targetUserId = targetUserId;
    }

    public String getTargetSubject() {
        return targetSubject;
    }

    public void setTargetSubject(String targetSubject) {
        this.targetSubject = targetSubject;
    }

    public String getTargetEmail() {
        return targetEmail;
    }

    public void setTargetEmail(String targetEmail) {
        this.targetEmail = targetEmail;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getJti() {
        return jti;
    }

    public void setJti(String jti) {
        this.jti = jti;
    }

    public String getSid() {
        return sid;
    }

    public void setSid(String sid) {
        this.sid = sid;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getEndedReason() {
        return endedReason;
    }

    public void setEndedReason(String endedReason) {
        this.endedReason = endedReason;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public InetAddress getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(InetAddress ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public InetAddress getClientIpViaXff() {
        return clientIpViaXff;
    }

    public void setClientIpViaXff(InetAddress clientIpViaXff) {
        this.clientIpViaXff = clientIpViaXff;
    }

    /**
     * True if this session is live, not ended, not expired.
     */
    public boolean isActive(Instant now) {
        return status == Status.ACTIVE
                && endedAt == null
                && expiresAt.isAfter(now);
    }
}
