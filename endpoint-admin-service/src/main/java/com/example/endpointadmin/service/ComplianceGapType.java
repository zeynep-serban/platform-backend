package com.example.endpointadmin.service;

/**
 * Faz 22.7 D2 (Codex 019e881c AGREE D): canonical compliance gap types
 * surfaced by the cross-snapshot mart layer.
 *
 * <p>Each type maps to a specific source snapshot table + predicate.
 * Gap types are bounded to a small enum — NO arbitrary user-defined gap
 * expressions (Codex No Fake Work guard: prevents schema drift + visibility
 * surface expansion).
 *
 * <p>D2 MVP scope (2 initial types):
 * <ul>
 *   <li>{@link #RDP_ENABLED} — startup_exposure snapshot rdp_enabled=true</li>
 *   <li>{@link #PENDING_SECURITY_UPDATES} — hotfix_posture snapshot pending_total_count > 0</li>
 * </ul>
 *
 * <p>D2.1 follow-up scope (additional types, separate sub-PR):
 * critical_service_down, appLocker_disabled, wdac_audit_only, local_admin_present,
 * outdated_software, prohibited_software, winget_unreachable, ...
 */
public enum ComplianceGapType {

    RDP_ENABLED("rdp_enabled", "Uzak Masaüstü Etkin"),
    PENDING_SECURITY_UPDATES("pending_security_updates", "Bekleyen Güvenlik Güncellemeleri");

    private final String wire;
    private final String label;

    ComplianceGapType(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    public String wire() {
        return wire;
    }

    public String label() {
        return label;
    }

    public static ComplianceGapType fromWire(String value) {
        if (value == null) return null;
        for (ComplianceGapType t : values()) {
            if (t.wire.equalsIgnoreCase(value)) return t;
        }
        throw new IllegalArgumentException(
                "Unknown ComplianceGapType wire value: " + value
                        + " (valid: rdp_enabled, pending_security_updates)");
    }
}
