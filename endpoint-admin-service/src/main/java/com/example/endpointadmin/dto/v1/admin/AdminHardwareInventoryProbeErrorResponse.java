package com.example.endpointadmin.dto.v1.admin;

import java.util.Map;

/**
 * BE-022Q — bounded probe-error projection.
 *
 * <p>The persisted snapshot stores probe errors as a jsonb
 * {@code List<Map<String, Object>>} where each element carries a
 * {@code code} and a {@code summary} string ({@code summary <= 256}
 * chars, enforced at ingest by {@code EndpointHardwareInventoryService}).
 * This DTO surfaces exactly those two fields — extra keys an agent
 * version might add are intentionally dropped so the response shape
 * stays stable.
 */
public record AdminHardwareInventoryProbeErrorResponse(
        String code,
        String summary) {

    public static AdminHardwareInventoryProbeErrorResponse from(Map<String, Object> raw) {
        if (raw == null) {
            return new AdminHardwareInventoryProbeErrorResponse(null, null);
        }
        Object code = raw.get("code");
        Object summary = raw.get("summary");
        return new AdminHardwareInventoryProbeErrorResponse(
                code != null ? String.valueOf(code) : null,
                summary != null ? String.valueOf(summary) : null);
    }
}
