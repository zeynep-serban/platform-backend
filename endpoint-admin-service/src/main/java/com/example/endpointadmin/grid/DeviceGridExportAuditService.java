package com.example.endpointadmin.grid;

import com.example.endpointadmin.service.EndpointAuditService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the tenant-scoped audit event for a device-grid export (#1154
 * PR-2b, Codex 019e7e65).
 *
 * <p>{@link EndpointAuditService#record} is {@code @Transactional(MANDATORY)}
 * (its hash-chain advisory lock must be transaction-scoped), but the grid
 * controller is deliberately NOT {@code @Transactional} (it streams the
 * export body outside any session). This thin {@code @Transactional} wrapper
 * supplies the required outer transaction so the audit row is written — and
 * committed — BEFORE the stream begins.
 *
 * <p>The event is named {@code ..._REQUESTED} (not {@code _EXPORTED}): it is
 * recorded before the bytes flow, so a later stream failure must not read as
 * a successful export.
 */
@Service
public class DeviceGridExportAuditService {

    public static final String EVENT_TYPE = "ENDPOINT_DEVICE_GRID_EXPORT_REQUESTED";

    private final EndpointAuditService auditService;

    public DeviceGridExportAuditService(EndpointAuditService auditService) {
        this.auditService = auditService;
    }

    @Transactional
    public void recordExportRequested(UUID tenantId, String subject, String format,
                                      String exportMode, long rowCount, int columnCount) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("format", format);
        metadata.put("exportMode", exportMode);
        metadata.put("rowCount", rowCount);
        metadata.put("columnCount", columnCount);
        // Fleet-wide export ⇒ no specific device/command. correlationId/before/
        // after are unused for a read-only export event.
        auditService.record(tenantId, null, null, EVENT_TYPE, "EXPORT",
                subject, null, metadata, null, null);
    }
}
