package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Entity
// BE-021 (issue #331): the endpoint_audit_events table is append-only —
// enforced at the DB layer by the BE-016 trigger trg_endpoint_audit_events_
// append_only (migration V4), which RAISEs on any UPDATE/DELETE. @Immutable
// aligns the JPA mapping with that invariant: Hibernate INSERTs an audit row
// once and never dirty-checks or UPDATEs it again. Without this, Hibernate
// re-evaluates the mutable JSON `Map` columns (metadata / before_state /
// after_state) as dirty on a LATER auto-flush in the same transaction (e.g.
// the INSTALL_SOFTWARE dispatch path runs preflight reads + a command
// saveAndFlush + a toDto repository read after record()), and schedules a
// spurious UPDATE on the just-inserted audit row — which the trigger rejects
// with a DataIntegrityViolationException → HTTP 500. The hash-chain is
// computed before INSERT and the row never legitimately changes, so making
// the entity immutable is the correct, durable mapping (it fixes every audit
// caller, not just install).
@Immutable
@Table(name = "endpoint_audit_events",
        indexes = {
                @Index(name = "idx_endpoint_audit_events_tenant_occurred",
                        columnList = "tenant_id,occurred_at"),
                @Index(name = "idx_endpoint_audit_events_device_occurred",
                        columnList = "device_id,occurred_at"),
                @Index(name = "idx_endpoint_audit_events_command",
                        columnList = "command_id"),
                @Index(name = "idx_endpoint_audit_events_type",
                        columnList = "event_type")
        })
public class EndpointAuditEvent implements Persistable<UUID> {

    // BE-016: id is application-assigned (EndpointAuditService) because it is
    // part of the canonical hash payload and must be known before INSERT.
    // Persistable.isNew() drives Spring Data to call persist() (a clean
    // INSERT) instead of merge() (which would issue a redundant SELECT).
    @Id
    private UUID id;

    @Transient
    private boolean persisted = false;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private EndpointDevice device;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "command_id")
    private EndpointCommand command;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "performed_by_subject")
    private String performedBySubject;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_state", columnDefinition = "jsonb")
    private Map<String, Object> beforeState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_state", columnDefinition = "jsonb")
    private Map<String, Object> afterState;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    // BE-016 audit integrity hash-chain (Codex 019e4f8e). All four columns
    // are nullable: legacy pre-BE-016 rows keep them NULL. The application
    // layer (EndpointAuditService) populates them for every new row, and the
    // DB-level append-only trigger makes the chain tamper-evident.
    @Column(name = "prev_event_hash", length = 64, updatable = false)
    private String prevEventHash;

    @Column(name = "event_hash", length = 64, updatable = false)
    private String eventHash;

    @Column(name = "event_hash_alg", length = 32, updatable = false)
    private String eventHashAlg;

    @Column(name = "event_hash_version", updatable = false)
    private Integer eventHashVersion;

    @PrePersist
    void prePersist() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.persisted = true;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public EndpointDevice getDevice() {
        return device;
    }

    public void setDevice(EndpointDevice device) {
        this.device = device;
    }

    public EndpointCommand getCommand() {
        return command;
    }

    public void setCommand(EndpointCommand command) {
        this.command = command;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getPerformedBySubject() {
        return performedBySubject;
    }

    public void setPerformedBySubject(String performedBySubject) {
        this.performedBySubject = performedBySubject;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new HashMap<>() : metadata;
    }

    public Map<String, Object> getBeforeState() {
        return beforeState;
    }

    public void setBeforeState(Map<String, Object> beforeState) {
        this.beforeState = beforeState;
    }

    public Map<String, Object> getAfterState() {
        return afterState;
    }

    public void setAfterState(Map<String, Object> afterState) {
        this.afterState = afterState;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getPrevEventHash() {
        return prevEventHash;
    }

    public void setPrevEventHash(String prevEventHash) {
        this.prevEventHash = prevEventHash;
    }

    public String getEventHash() {
        return eventHash;
    }

    public void setEventHash(String eventHash) {
        this.eventHash = eventHash;
    }

    public String getEventHashAlg() {
        return eventHashAlg;
    }

    public void setEventHashAlg(String eventHashAlg) {
        this.eventHashAlg = eventHashAlg;
    }

    public Integer getEventHashVersion() {
        return eventHashVersion;
    }

    public void setEventHashVersion(Integer eventHashVersion) {
        this.eventHashVersion = eventHashVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointAuditEvent that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
