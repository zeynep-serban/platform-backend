package com.example.endpointadmin.model;

/**
 * AG-028 Phase 1 — verification taxonomy for {@link EndpointUninstallAudit}
 * (Faz 22.5.6).
 *
 * <p>Codex plan-time iter-2 P0 #7 absorb: uninstall verification is
 * absence-aware and CANNOT collapse "package absent" with "detection failed".
 * Verification is derived from the agent {@code probeState} field
 * (Phase 2 detection result extension) per the mapping:
 *
 * <ul>
 *   <li>{@code probeState=ABSENT}             → {@link #ABSENT_VERIFIED}</li>
 *   <li>{@code probeState=MATCHED}            → {@link #PRESENT_VERIFIED}</li>
 *   <li>{@code probeState=PRESENT_MISMATCH}   → {@link #RESIDUE_PRESENT}</li>
 *   <li>{@code probeState=AMBIGUOUS/ERROR/UNSUPPORTED} → {@link #VERIFY_INCONCLUSIVE}</li>
 *   <li>{@code probeState} missing or unknown → {@link #VERIFY_INCONCLUSIVE} (fail-closed)</li>
 * </ul>
 *
 * <p>{@link #NOT_RUN} is the placeholder for the {@code SKIP_ALREADY_ABSENT}
 * pre-check no-op case (winget never invoked).
 *
 * <p>V32 DB CHECK {@code ck_endpoint_uninstall_audit_verification} enforces
 * this exact closed set.
 */
public enum UninstallVerification {
    ABSENT_VERIFIED,
    PRESENT_VERIFIED,
    RESIDUE_PRESENT,
    VERIFY_INCONCLUSIVE,
    NOT_RUN
}
