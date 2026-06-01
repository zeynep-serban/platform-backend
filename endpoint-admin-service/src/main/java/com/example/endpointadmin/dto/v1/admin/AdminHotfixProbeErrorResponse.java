package com.example.endpointadmin.dto.v1.admin;

import java.util.Map;

/**
 * BE — bounded probe-error projection for a hotfix-posture snapshot
 * response (Faz 22.5, AG-037 query API). Mirrors the AG-036
 * {@code AdminOutdatedSoftwareProbeErrorResponse}.
 *
 * <p>The persisted snapshot stores probe errors as a jsonb
 * {@code List<Map<String, Object>>} where each element carries a
 * {@code code} (one of the 12 wire enum values), an optional
 * {@code source} (one of the 3-set source-attribution values), and a
 * bounded {@code summary} (<= 200 chars, CRLF/tab stripped) — enforced
 * at ingest by {@link com.example.endpointadmin.security.HotfixPosturePayloadPolicy}.
 */
public record AdminHotfixProbeErrorResponse(
        String code,
        String source,
        String summary) {

    public static AdminHotfixProbeErrorResponse from(Map<String, Object> raw) {
        if (raw == null) {
            return new AdminHotfixProbeErrorResponse(null, null, null);
        }
        Object code = raw.get("code");
        Object source = raw.get("source");
        Object summary = raw.get("summary");
        return new AdminHotfixProbeErrorResponse(
                code != null ? String.valueOf(code) : null,
                source != null ? String.valueOf(source) : null,
                summary != null ? String.valueOf(summary) : null);
    }
}
