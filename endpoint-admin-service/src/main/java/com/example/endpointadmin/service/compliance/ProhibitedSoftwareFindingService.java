package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.dto.v1.admin.DeviceProhibitedSoftwareResponse;
import com.example.endpointadmin.dto.v1.admin.ProhibitedSoftwareFindingResponse;
import com.example.endpointadmin.model.EndpointComplianceEvaluation;
import com.example.endpointadmin.repository.EndpointComplianceEvaluationRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * BE-025 — device-facing prohibited-software findings read service
 * (Faz 22.5).
 *
 * <p>Reads the {@code matchedItems.prohibitedInstalled} projection from the
 * device's LAST persisted {@link EndpointComplianceEvaluation} evidence — it
 * does NOT recompute live (Codex 019e7623 (d) absorb: a GET that recomputes
 * would let the device compliance state and this response diverge and would
 * be expensive + side-effecting). Re-evaluation stays the existing
 * manager-only force-evaluate action.
 *
 * <p>No-existence-leak: the query is tenant-scoped, so a cross-tenant /
 * unknown device returns {@code NO_EVALUATION} exactly like a device that
 * has simply never been evaluated (mirrors the BE-024 diff discipline).
 */
@Service
public class ProhibitedSoftwareFindingService {

    private static final String EVIDENCE_MATCHED_ITEMS = "matchedItems";
    private static final String EVIDENCE_PROHIBITED_INSTALLED = "prohibitedInstalled";

    private final EndpointComplianceEvaluationRepository evaluationRepository;

    public ProhibitedSoftwareFindingService(
            EndpointComplianceEvaluationRepository evaluationRepository) {
        this.evaluationRepository = evaluationRepository;
    }

    @Transactional(readOnly = true)
    public DeviceProhibitedSoftwareResponse getDeviceFindings(
            AdminTenantContext tenant, UUID deviceId) {
        Optional<EndpointComplianceEvaluation> latest = evaluationRepository
                .findFirstByTenantIdAndDeviceIdOrderByEvaluatedAtDesc(
                        tenant.tenantId(), deviceId);
        if (latest.isEmpty()) {
            return DeviceProhibitedSoftwareResponse.noEvaluation(deviceId);
        }
        EndpointComplianceEvaluation evaluation = latest.get();
        List<ProhibitedSoftwareFindingResponse> findings =
                extractFindings(evaluation.getEvidence());
        return new DeviceProhibitedSoftwareResponse(
                deviceId,
                DeviceProhibitedSoftwareResponse.Status.OK,
                evaluation.getDecision() == null ? null : evaluation.getDecision().name(),
                evaluation.getEvaluatedAt(),
                evaluation.getInventorySnapshotId(),
                findings);
    }

    /**
     * Pull {@code evidence.matchedItems.prohibitedInstalled} into the
     * redacted device-facing finding list. Defensive about the evidence
     * shape (older evaluations predating BE-025 simply have no such key →
     * empty list) and about per-entry nulls.
     */
    @SuppressWarnings("unchecked")
    private List<ProhibitedSoftwareFindingResponse> extractFindings(Map<String, Object> evidence) {
        List<ProhibitedSoftwareFindingResponse> out = new ArrayList<>();
        if (evidence == null) {
            return out;
        }
        Object matchedItemsObj = evidence.get(EVIDENCE_MATCHED_ITEMS);
        if (!(matchedItemsObj instanceof Map<?, ?> matchedItems)) {
            return out;
        }
        Object prohibitedObj = matchedItems.get(EVIDENCE_PROHIBITED_INSTALLED);
        if (!(prohibitedObj instanceof List<?> prohibited)) {
            return out;
        }
        for (Object entryObj : prohibited) {
            if (!(entryObj instanceof Map<?, ?> entry)) {
                continue;
            }
            Map<String, Object> e = (Map<String, Object>) entry;
            out.add(new ProhibitedSoftwareFindingResponse(
                    parseUuid(e.get(EndpointComplianceService.MatchedProhibited.KEY_RULE_ID)),
                    str(e.get(EndpointComplianceService.MatchedProhibited.KEY_MATCH_TYPE)),
                    str(e.get(EndpointComplianceService.MatchedProhibited.KEY_MATCH_MODE)),
                    str(e.get(EndpointComplianceService.MatchedProhibited.KEY_MATCHED_NAME)),
                    str(e.get(EndpointComplianceService.MatchedProhibited.KEY_MATCHED_PUBLISHER)),
                    str(e.get(EndpointComplianceService.MatchedProhibited.KEY_MATCHED_VERSION))));
        }
        return out;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
