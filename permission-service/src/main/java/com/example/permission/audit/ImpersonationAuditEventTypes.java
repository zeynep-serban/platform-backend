package com.example.permission.audit;

/**
 * User Impersonation v1 — Audit event type constants.
 *
 * <p>Codex 019e0dfb iter-10/19: FAILED/BLOCKED attempts → permission_audit_events,
 * NOT impersonation_sessions (sessions tablosu sadece başarılı session
 * lifecycle).
 */
public final class ImpersonationAuditEventTypes {

    private ImpersonationAuditEventTypes() {
        // utility
    }

    /** SuperAdmin impersonate başlattı — successful path. */
    public static final String IMPERSONATION_STARTED = "IMPERSONATION_STARTED";

    /** Impersonate session sonlandı (USER_STOP / LOGOUT / EXPIRED / REVOKED). */
    public static final String IMPERSONATION_STOPPED = "IMPERSONATION_STOPPED";

    /**
     * Impersonate denied (denylist hit, target disabled, nested forbid,
     * insufficient role). FAILED attempts; sessions tablosuna girmez.
     */
    public static final String IMPERSONATION_BLOCKED = "IMPERSONATION_BLOCKED";

    /** Impersonate başarısız (broker exchange fail, KC down, vb.). */
    public static final String IMPERSONATION_FAILED = "IMPERSONATION_FAILED";

    /** Admin tarafından force revoke. */
    public static final String IMPERSONATION_REVOKED = "IMPERSONATION_REVOKED";
}
