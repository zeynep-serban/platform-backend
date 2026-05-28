package com.example.endpointadmin.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Wave-12 PR-5 — risk tier attached to a {@link PolicyChangeApproval},
 * surfaced to reviewers so they can calibrate scrutiny. Carried as
 * {@code PolicyApprovalDomainExtras.riskTier} on the platform-web side.
 * Serialised as lowercase ({@code "low" | "medium" | "high"}).
 */
public enum PolicyRiskTier {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    private final String wire;

    PolicyRiskTier(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static PolicyRiskTier fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (PolicyRiskTier r : values()) {
            if (r.wire.equalsIgnoreCase(value) || r.name().equalsIgnoreCase(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException("Unknown PolicyRiskTier: " + value);
    }
}
