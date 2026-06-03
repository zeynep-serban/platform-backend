package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointUninstallAudit;
import com.example.endpointadmin.model.UninstallResultStatus;
import com.example.endpointadmin.model.UninstallVerification;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * AG-028 Phase 1b — REST projection of an
 * {@code endpoint_uninstall_audit} row (Faz 22.5.6).
 *
 * <p>Terminal-result audit returned by
 * {@code GET .../uninstalls/history}. Mirrors
 * {@link EndpointInstallAuditDto} on the destructive side; the
 * {@code redactedPayload} and {@code detectionEvidence} maps are the
 * same JSONB shapes persisted to the audit row after the
 * {@code UninstallEvidencePayloadPolicy} sanitiser pass.
 */
public record AdminUninstallAuditResponse(
        UUID auditId,
        UUID requestId,
        UUID tenantId,
        UUID deviceId,
        UUID commandId,
        UUID catalogItemId,
        UninstallResultStatus resultStatus,
        UninstallVerification verification,
        Integer exitCode,
        Instant reportedAt,
        Map<String, Object> redactedPayload,
        Map<String, Object> detectionEvidence,
        Instant createdAt) {

    public static AdminUninstallAuditResponse from(EndpointUninstallAudit audit) {
        return new AdminUninstallAuditResponse(
                audit.getId(),
                audit.getRequestId(),
                audit.getTenantId(),
                audit.getDeviceId(),
                audit.getCommandId(),
                audit.getCatalogItemId(),
                audit.getResultStatus(),
                audit.getVerification(),
                audit.getExitCode(),
                audit.getReportedAt(),
                audit.getRedactedPayload(),
                audit.getDetectionEvidence(),
                audit.getCreatedAt());
    }
}
