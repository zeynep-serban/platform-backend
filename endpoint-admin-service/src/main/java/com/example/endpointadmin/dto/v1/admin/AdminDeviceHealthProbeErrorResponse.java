package com.example.endpointadmin.dto.v1.admin;

import java.util.Map;

/**
 * BE — bounded probe-error projection for a device-health snapshot
 * response (Faz 22.5, AG-033 query API). Mirrors the BE-022Q
 * {@code AdminHardwareInventoryProbeErrorResponse}.
 *
 * <p>The persisted snapshot stores probe errors as a jsonb
 * {@code List<Map<String, Object>>} where each element carries a
 * {@code code} (enum), an optional {@code source} ({@code win32 | none}),
 * and a bounded {@code summary} string (enforced at ingest by
 * {@code EndpointDeviceHealthService}). This DTO surfaces exactly those
 * fields — extra keys an agent version might add are intentionally
 * dropped so the response shape stays stable.
 */
public record AdminDeviceHealthProbeErrorResponse(
        String code,
        String source,
        String summary) {

    public static AdminDeviceHealthProbeErrorResponse from(Map<String, Object> raw) {
        if (raw == null) {
            return new AdminDeviceHealthProbeErrorResponse(null, null, null);
        }
        Object code = raw.get("code");
        Object source = raw.get("source");
        Object summary = raw.get("summary");
        return new AdminDeviceHealthProbeErrorResponse(
                code != null ? String.valueOf(code) : null,
                source != null ? String.valueOf(source) : null,
                summary != null ? String.valueOf(summary) : null);
    }
}
