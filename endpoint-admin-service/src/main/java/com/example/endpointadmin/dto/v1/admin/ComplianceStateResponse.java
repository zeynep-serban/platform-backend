package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.ComplianceDecision;
import com.example.endpointadmin.service.compliance.StalenessSeverity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BE-023 — Latest-pointer compliance state read shape.
 *
 * <p>Returned by:
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/endpoint-devices/{deviceId}/compliance}
 *   <li>{@code POST /api/v1/admin/endpoint-devices/{deviceId}/compliance/evaluate}
 *   <li>each row of
 *       {@code GET /api/v1/admin/compliance/devices?decision=...&page=...}
 * </ul>
 *
 * <p>{@code staleness} is computed at GET time relative to NOW against
 * the underlying inventory snapshot's per-stream timestamps, not against
 * the persisted {@code evaluatedAt}. Codex 019e6bbf iter-3 absorb so the
 * UI can render "STALE_HARD — re-evaluate" CTAs even when the persisted
 * decision says COMPLIANT.
 */
public record ComplianceStateResponse(
        UUID deviceId,
        UUID latestEvaluationId,
        ComplianceDecision decision,
        Instant evaluatedAt,
        StalenessReport staleness,
        List<String> reasons,
        List<String> blockingReasons,
        List<String> warnings,
        ComplianceEvidence evidence,
        String catalogPolicyHash,
        String catalogPolicyHashCurrent,
        Boolean policyDrift,
        Long catalogRowVersionMax,
        Long policyRowVersionMax) {

    public record StalenessReport(
            StalenessSeverity summary,
            StalenessSeverity apps,
            StalenessSeverity wingetEgress,
            StalenessSeverity worst) {
    }

    public record ComplianceEvidence(
            UUID inventorySnapshotId,
            Long inventorySnapshotRowVersion,
            Instant inventoryUpdatedAt,
            Instant summaryCollectedAt,
            Instant appsCollectedAt,
            UUID latestSummaryCommandResultId,
            UUID latestFullCommandResultId,
            UUID latestWingetEgressCommandResultId,
            Instant wingetEgressCollectedAt,
            Integer wingetEgressSchemaVersion,
            Map<String, Object> matchedItems) {
    }
}
