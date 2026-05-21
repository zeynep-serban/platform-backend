package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "endpoint_request_nonces",
        uniqueConstraints = @UniqueConstraint(name = "uq_endpoint_request_nonces_credential_nonce",
                columnNames = {"credential_id", "nonce"}),
        indexes = {
                @Index(name = "idx_endpoint_request_nonces_expires",
                        columnList = "expires_at"),
                @Index(name = "idx_endpoint_request_nonces_device_used",
                        columnList = "device_id,used_at")
        })
public class EndpointRequestNonce {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credential_id", nullable = false)
    private EndpointDeviceCredential credential;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private EndpointDevice device;

    @Column(name = "nonce", nullable = false)
    private String nonce;

    @Column(name = "request_timestamp", nullable = false)
    private Instant requestTimestamp;

    @Column(name = "used_at", nullable = false)
    private Instant usedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "request_hash", length = 128)
    private String requestHash;

    @PrePersist
    void prePersist() {
        if (usedAt == null) {
            usedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public EndpointDeviceCredential getCredential() {
        return credential;
    }

    public void setCredential(EndpointDeviceCredential credential) {
        this.credential = credential;
    }

    public EndpointDevice getDevice() {
        return device;
    }

    public void setDevice(EndpointDevice device) {
        this.device = device;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public Instant getRequestTimestamp() {
        return requestTimestamp;
    }

    public void setRequestTimestamp(Instant requestTimestamp) {
        this.requestTimestamp = requestTimestamp;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Instant usedAt) {
        this.usedAt = usedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointRequestNonce that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
