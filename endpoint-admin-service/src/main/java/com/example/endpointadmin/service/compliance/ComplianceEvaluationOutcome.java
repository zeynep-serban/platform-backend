package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.model.ComplianceDecision;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service-internal projection of a single compliance evaluation result.
 *
 * <p>Mirrors what is persisted in
 * {@link com.example.endpointadmin.model.EndpointComplianceEvaluation}
 * but stays in service-package types to keep the controller layer
 * free of persistence concerns.
 */
public record ComplianceEvaluationOutcome(
        UUID evaluationId,
        UUID deviceId,
        ComplianceDecision decision,
        Instant evaluatedAt,
        List<String> reasons,
        List<String> blockingReasons,
        List<String> warnings,
        Map<String, Object> evidence,
        String catalogPolicyHash,
        UUID inventorySnapshotId,
        Long inventorySnapshotRowVersion,
        Long catalogRowVersionMax,
        Long policyRowVersionMax,
        StalenessReport staleness) {

    public record StalenessReport(
            StalenessSeverity summary,
            StalenessSeverity apps,
            StalenessSeverity wingetEgress,
            StalenessSeverity worst) {
    }
}
