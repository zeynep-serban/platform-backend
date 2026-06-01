package com.example.report.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Report execution dispatch kind (ADR-0015, PR-D2.1b).
 *
 * <p>{@link #SQL} (default) routes to the existing MSSQL {@code QueryEngine}
 * path — legacy reports + Workcube-backed reports (hr-compensation-detay,
 * hr-demografik-yapi, ...). {@link #REMOTE_HTTP} routes to the source-owned
 * execution adapter that delegates to another platform service over an
 * allowlist'li HTTP call (user-service, permission-service, audit hattı, ...).
 *
 * <p>JSON wire form: {@code "kind": "sql"} or {@code "kind": "remote-http"}.
 *
 * <p>Future expansion (planned but NOT implemented in PR-D2.1b): {@code
 * "aggregation-mart"} for scheduled audit digests + reporting projections
 * that don't live in either Workcube SQL or a single REST endpoint.
 */
public enum ExecutionKind {
    SQL("sql"),
    REMOTE_HTTP("remote-http");

    private final String wire;

    ExecutionKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static ExecutionKind fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (ExecutionKind k : values()) {
            if (k.wire.equalsIgnoreCase(value)) {
                return k;
            }
        }
        throw new IllegalArgumentException(
                "Unknown ExecutionKind wire value: " + value
                        + " (expected one of: sql, remote-http)");
    }
}
