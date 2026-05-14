package com.example.report.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Centralized JWT claim extraction utility for report-service.
 *
 * <p>Eliminates the prior ad-hoc patterns scattered across:
 * <ul>
 *   <li>{@code ReportController.java:122, 137, 718} — {@code preferred_username} only</li>
 *   <li>{@code ReportController.java:776-780} — {@code extractEmail()}: email → subject</li>
 *   <li>{@code ReportExportController.java:98-99} — email → subject (inline)</li>
 * </ul>
 *
 * <p>Two semantically distinct extraction methods:
 * <ol>
 *   <li>{@link #extractAuditUsername(Jwt)} — human-readable username for
 *       audit log entries (email → preferred_username → subject → "anonymous")</li>
 *   <li>{@link #extractPreferredUsername(Jwt)} — preferred_username for
 *       system context (preferred_username → "system")</li>
 * </ol>
 *
 * <p>Plan ref: {@code docs/plan-reporting-refactor-2026-05-14.md} §7 Adım 8.
 * Codex audit thread {@code 019e258f}.
 */
public final class JwtClaimExtractor {

    public static final String FALLBACK_ANONYMOUS = "anonymous";
    public static final String FALLBACK_SYSTEM = "system";

    private JwtClaimExtractor() {
        // utility class
    }

    /**
     * Human-readable username for audit logs.
     *
     * <p>Precedence:
     * <ol>
     *   <li>{@code email} claim (Keycloak default for user-facing identity)</li>
     *   <li>{@code preferred_username} claim (fallback when email absent)</li>
     *   <li>{@code sub} (JWT subject — typically internal UUID)</li>
     *   <li>{@link #FALLBACK_ANONYMOUS} (no JWT)</li>
     * </ol>
     *
     * <p>Blank strings are treated as missing (falls through to next claim).
     */
    public static String extractAuditUsername(Jwt jwt) {
        if (jwt == null) {
            return FALLBACK_ANONYMOUS;
        }
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) {
            return email;
        }
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername;
        }
        String subject = jwt.getSubject();
        if (subject != null && !subject.isBlank()) {
            return subject;
        }
        return FALLBACK_ANONYMOUS;
    }

    /**
     * Preferred username for system context (e.g. internal logs).
     *
     * <p>Precedence:
     * <ol>
     *   <li>{@code preferred_username} claim</li>
     *   <li>{@link #FALLBACK_SYSTEM} (no JWT or claim missing/blank)</li>
     * </ol>
     *
     * <p>Behavior preserves the prior {@code jwt != null ? jwt.getClaimAsString("preferred_username") : "system"}
     * pattern with an additional null-safety fallback when the claim itself
     * is missing.
     */
    public static String extractPreferredUsername(Jwt jwt) {
        if (jwt == null) {
            return FALLBACK_SYSTEM;
        }
        String preferredUsername = jwt.getClaimAsString("preferred_username");
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            return preferredUsername;
        }
        return FALLBACK_SYSTEM;
    }
}
