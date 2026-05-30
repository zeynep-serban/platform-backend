package com.example.endpointadmin.dto.v1.admin;

import java.util.Map;

/**
 * BE — bounded probe-error projection for an outdated-software snapshot
 * response (Faz 22.5, AG-036 query API). Mirrors the AG-033
 * {@code AdminDeviceHealthProbeErrorResponse}.
 *
 * <p>The persisted snapshot stores probe errors as a jsonb
 * {@code List<Map<String, Object>>} where each element carries a
 * {@code code} (enum), an optional {@code source} ({@code winget | none}),
 * and a bounded {@code summary} string (enforced at ingest by the policy +
 * {@code EndpointOutdatedSoftwareService}). This DTO surfaces exactly those
 * fields — extra keys an agent version might add are intentionally dropped so
 * the response shape stays stable.
 */
public record AdminOutdatedSoftwareProbeErrorResponse(
        String code,
        String source,
        String summary) {

    public static AdminOutdatedSoftwareProbeErrorResponse from(Map<String, Object> raw) {
        if (raw == null) {
            return new AdminOutdatedSoftwareProbeErrorResponse(null, null, null);
        }
        Object code = raw.get("code");
        Object source = raw.get("source");
        Object summary = raw.get("summary");
        return new AdminOutdatedSoftwareProbeErrorResponse(
                code != null ? String.valueOf(code) : null,
                source != null ? String.valueOf(source) : null,
                summary != null ? String.valueOf(summary) : null);
    }
}
