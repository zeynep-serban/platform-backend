package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-tenant prohibited-software denylist rule — BE-025 (Faz 22.5).
 *
 * <p>Unlike the BE-023 {@link EndpointSoftwareCompliancePolicyItem}
 * (which is bound to an approved-catalog item via a composite FK), a
 * prohibited rule is <b>NOT</b> catalog-bound: prohibited / banned /
 * malware software is the inverse of approved software — you would never
 * add it to the approved catalog, so there is no catalog row to point at.
 * A rule instead matches the device's <i>current</i> installed-software
 * inventory directly by display name and/or publisher.
 *
 * <p>Matching is literal and case-insensitive (EXACT or bounded
 * CONTAINS) — never regex (ReDoS / injection surface). The pattern is
 * compared in Java against the already-sanitized
 * {@link EndpointSoftwareInventoryItem} fields by
 * {@code EndpointComplianceService}; it never reaches a SQL {@code LIKE}.
 *
 * <p>Pattern columns are constrained by the V19 migration so they line
 * up with {@link #matchType}:
 * <ul>
 *   <li>{@link ProhibitedSoftwareMatchType#NAME NAME} → only
 *       {@code namePattern}; publisher must be NULL.</li>
 *   <li>{@link ProhibitedSoftwareMatchType#PUBLISHER PUBLISHER} → only
 *       {@code publisherPattern}; name must be NULL.</li>
 *   <li>{@link ProhibitedSoftwareMatchType#NAME_AND_PUBLISHER
 *       NAME_AND_PUBLISHER} → BOTH set (AND semantics).</li>
 * </ul>
 * Blank ({@code ''}) patterns are rejected by a DB CHECK as well as the
 * service validator.
 *
 * <p>UUID generation follows the established
 * {@link EndpointSoftwareCatalogItem} pattern
 * ({@link GenerationType#UUID Hibernate-generated}, not DB
 * {@code DEFAULT gen_random_uuid()}).
 */
@Entity
@Table(name = "endpoint_prohibited_software_rules",
        indexes = {
                @Index(name = "idx_endpoint_prohibited_software_rules_tenant_enabled",
                        columnList = "tenant_id,enabled")
        })
public class EndpointProhibitedSoftwareRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 32)
    private ProhibitedSoftwareMatchType matchType;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_mode", nullable = false, length = 16)
    private ProhibitedSoftwareMatchMode matchMode;

    /**
     * Literal display-name pattern (already-normalized lower-case form is
     * computed at match time). Set when {@code matchType} is
     * {@code NAME} or {@code NAME_AND_PUBLISHER}; NULL otherwise.
     */
    @Column(name = "name_pattern", length = 256)
    private String namePattern;

    /**
     * Literal publisher pattern. Set when {@code matchType} is
     * {@code PUBLISHER} or {@code NAME_AND_PUBLISHER}; NULL otherwise.
     */
    @Column(name = "publisher_pattern", length = 256)
    private String publisherPattern;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * Free-text operator note (e.g. "banned per IT policy 2026-Q2"). NOT
     * echoed in compliance evidence or on the device-facing read surface
     * — kept server-side for the rule CRUD surface only.
     */
    @Column(name = "notes", length = 512)
    private String notes;

    @Column(name = "created_by_subject", nullable = false, length = 255)
    private String createdBySubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_updated_by_subject", nullable = false, length = 255)
    private String lastUpdatedBySubject;

    @Column(name = "last_updated_at", nullable = false)
    private Instant lastUpdatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastUpdatedAt == null) {
            lastUpdatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        lastUpdatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public ProhibitedSoftwareMatchType getMatchType() {
        return matchType;
    }

    public void setMatchType(ProhibitedSoftwareMatchType matchType) {
        this.matchType = matchType;
    }

    public ProhibitedSoftwareMatchMode getMatchMode() {
        return matchMode;
    }

    public void setMatchMode(ProhibitedSoftwareMatchMode matchMode) {
        this.matchMode = matchMode;
    }

    public String getNamePattern() {
        return namePattern;
    }

    public void setNamePattern(String namePattern) {
        this.namePattern = namePattern;
    }

    public String getPublisherPattern() {
        return publisherPattern;
    }

    public void setPublisherPattern(String publisherPattern) {
        this.publisherPattern = publisherPattern;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getCreatedBySubject() {
        return createdBySubject;
    }

    public void setCreatedBySubject(String createdBySubject) {
        this.createdBySubject = createdBySubject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getLastUpdatedBySubject() {
        return lastUpdatedBySubject;
    }

    public void setLastUpdatedBySubject(String lastUpdatedBySubject) {
        this.lastUpdatedBySubject = lastUpdatedBySubject;
    }

    public Instant getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointProhibitedSoftwareRule that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
