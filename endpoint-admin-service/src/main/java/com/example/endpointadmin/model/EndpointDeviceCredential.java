package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "endpoint_device_credentials",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_endpoint_device_credentials_device",
                        columnNames = "device_id"),
                @UniqueConstraint(name = "uq_endpoint_device_credentials_key",
                        columnNames = "credential_key_id")
        },
        indexes = {
                @Index(name = "idx_endpoint_device_credentials_active", columnList = "active"),
                @Index(name = "idx_endpoint_device_credentials_grace", columnList = "rotation_grace_until")
        })
public class EndpointDeviceCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private EndpointDevice device;

    @Column(name = "credential_key_id", nullable = false, length = 128)
    private String credentialKeyId;

    @Column(name = "encrypted_secret", nullable = false, columnDefinition = "TEXT")
    private String encryptedSecret;

    @Column(name = "previous_encrypted_secret", columnDefinition = "TEXT")
    private String previousEncryptedSecret;

    @Column(name = "encryption_key_version", length = 64)
    private String encryptionKeyVersion;

    @Column(name = "rotation_grace_until")
    private Instant rotationGraceUntil;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public EndpointDevice getDevice() {
        return device;
    }

    public void setDevice(EndpointDevice device) {
        this.device = device;
    }

    public String getCredentialKeyId() {
        return credentialKeyId;
    }

    public void setCredentialKeyId(String credentialKeyId) {
        this.credentialKeyId = credentialKeyId;
    }

    public String getEncryptedSecret() {
        return encryptedSecret;
    }

    public void setEncryptedSecret(String encryptedSecret) {
        this.encryptedSecret = encryptedSecret;
    }

    public String getPreviousEncryptedSecret() {
        return previousEncryptedSecret;
    }

    public void setPreviousEncryptedSecret(String previousEncryptedSecret) {
        this.previousEncryptedSecret = previousEncryptedSecret;
    }

    public String getEncryptionKeyVersion() {
        return encryptionKeyVersion;
    }

    public void setEncryptionKeyVersion(String encryptionKeyVersion) {
        this.encryptionKeyVersion = encryptionKeyVersion;
    }

    public Instant getRotationGraceUntil() {
        return rotationGraceUntil;
    }

    public void setRotationGraceUntil(Instant rotationGraceUntil) {
        this.rotationGraceUntil = rotationGraceUntil;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
    }

    public void setRotatedAt(Instant rotatedAt) {
        this.rotatedAt = rotatedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public Long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointDeviceCredential that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}
