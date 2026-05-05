package com.example.endpointadmin.security;

/**
 * Endpoint admin authz constants.
 *
 * Faz 22.1.1b — 3-tier drift fix (Codex 019df86f III review absorb 2026-05-05):
 * - MODULE: ENDPOINT_ADMIN (uppercase) → endpoint-admin (kebab-case)
 *   Hizalama: gitops seed JSON `bootstrap/openfga/endpoint-admin-tuples.json`
 *   `module:endpoint-admin` instance ile uyumlu. Önceki uppercase literal
 *   tuple seed'iyle eşleşmiyordu → tüm @RequireModule routes 403 dönerdi.
 *
 * - VIEWER, MANAGER zaten {@code can_view}, {@code can_manage} (BE-009
 *   relation align bf59897'de düzeltildi). Live OpenFGA model `module` type
 *   bu relation'ları destekliyor.
 *
 * 22.1.1 acceptance scope module-level can_view/can_manage; ADR-0012-EA
 * scope/action types (endpoint, policy, command, inventory, audit + can_assign,
 * can_execute, can_signoff, can_revoke) ileri sprint (BE-009b veya 22.2/22.3).
 *
 * Tuple shape: {@code user:<id># <relation> @module:endpoint-admin}
 *
 * Drift detection: ADR-0011 DD-1 cross-repo guard (gitops drift-detection)
 * artık `module:endpoint-admin` instance name'i tek source-of-truth olarak
 * tutuyor (3-tier drift kapatıldı: code/seed/live).
 */
public final class EndpointAdminAuthz {

    /**
     * Module object ID — kebab-case (gitops seed + live OpenFGA model contract).
     * Faz 22.1.1b III review fix: önceki "ENDPOINT_ADMIN" uppercase literal'i
     * tuple seed'iyle eşleşmiyordu → 403 root cause.
     */
    public static final String MODULE = "endpoint-admin";

    /** Read-only access relation, OpenFGA model {@code module#can_view}. */
    public static final String VIEWER = "can_view";

    /** Mutation/management relation, OpenFGA model {@code module#can_manage}. */
    public static final String MANAGER = "can_manage";

    private EndpointAdminAuthz() {
    }
}
