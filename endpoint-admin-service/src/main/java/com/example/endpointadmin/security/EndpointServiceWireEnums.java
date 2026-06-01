package com.example.endpointadmin.security;

import java.util.Set;

/**
 * BE — shared immutable wire enum constants for endpoint services state
 * + startup mode. Used by both {@link ServicesPayloadPolicy} (AG-039)
 * and {@link AppControlPayloadPolicy} (AG-041) to keep the two policies
 * consistent without inter-policy coupling.
 *
 * <p>Codex 019e840e plan iter-2 absorb #4: the AG-041 AppIDSvc state +
 * startup mode enums are the SAME surface as AG-039 endpoint services
 * (RUNNING/STOPPED/DISABLED/UNKNOWN + AUTO/AUTO_DELAYED/MANUAL/DISABLED/
 * UNKNOWN — 4-value + 5-value, NOT a narrower 3+4-value variant). The
 * agent emits `AUTO_DELAYED` for AppIDSvc startup mode in observed
 * deployments, so the backend enum MUST accept it.
 *
 * <p>The V24 and V26 DB CHECK constraints mirror these sets exactly.
 */
public final class EndpointServiceWireEnums {

    private EndpointServiceWireEnums() {
        // constants holder — no instances
    }

    /** Service state enum — 4 values. */
    public static final Set<String> SERVICE_STATE_ENUM = Set.of(
            "RUNNING", "STOPPED", "DISABLED", "UNKNOWN"
    );

    /** Startup mode enum — 5 values (AUTO_DELAYED included for AppIDSvc). */
    public static final Set<String> STARTUP_MODE_ENUM = Set.of(
            "AUTO", "AUTO_DELAYED", "MANUAL", "DISABLED", "UNKNOWN"
    );
}
