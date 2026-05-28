package com.example.endpointadmin.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Wave-12 PR-5 — kind of policy change a {@link PolicyChangeApproval}
 * proposes. Carried as {@code PolicyApprovalDomainExtras.changeKind} on
 * the platform-web side. Serialised as lowercase
 * ({@code "create" | "update" | "delete"}).
 */
public enum PolicyChangeKind {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete");

    private final String wire;

    PolicyChangeKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static PolicyChangeKind fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (PolicyChangeKind k : values()) {
            if (k.wire.equalsIgnoreCase(value) || k.name().equalsIgnoreCase(value)) {
                return k;
            }
        }
        throw new IllegalArgumentException("Unknown PolicyChangeKind: " + value);
    }
}
