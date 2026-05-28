package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.ComplianceEvaluationListResponse;
import com.example.endpointadmin.dto.v1.admin.ComplianceStateResponse;
import com.example.endpointadmin.model.ComplianceDecision;
import com.example.endpointadmin.model.EndpointComplianceEvaluation;
import com.example.endpointadmin.model.EndpointDeviceComplianceState;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.compliance.ComplianceEvaluationOutcome;
import com.example.endpointadmin.service.compliance.ConcurrentComplianceEvaluationException;
import com.example.endpointadmin.service.compliance.EndpointComplianceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BE-023 — Admin compliance read + force-evaluate REST surface (Faz 22.5).
 *
 * <pre>
 * GET  /api/v1/admin/endpoint-devices/{deviceId}/compliance
 *      ─ Latest persisted state for the device. 404 if device not in
 *        tenant; 200 with empty body shape NOT used — callers must
 *        check the response and treat absence as "never evaluated".
 *        Returns a fresh {@code staleness} report computed against the
 *        underlying inventory snapshot at GET time.
 *
 * POST /api/v1/admin/endpoint-devices/{deviceId}/compliance/evaluate
 *      ─ Force a new evaluation. 200 + new state. 409 +
 *        {@code Retry-After: 5} if the per-(tenant, device) advisory
 *        lock is held by another transaction.
 *
 * GET  /api/v1/admin/compliance/devices?decision=&page=&size=
 *      ─ Tenant-wide cross-device list, optional decision filter.
 *
 * GET  /api/v1/admin/endpoint-devices/{deviceId}/compliance/evaluations
 *      ?page=&size=
 *      ─ Append-only evaluation history for the device.
 * </pre>
 *
 * <p>Codex 019e6bbf iter-3 AGREE — auth bound to OpenFGA
 * {@code module:endpoint-admin} {@code can_view} for read,
 * {@code can_manage} for force-evaluate, matching the BE-021A pattern.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointComplianceController {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private final EndpointComplianceService complianceService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointComplianceController(
            EndpointComplianceService complianceService,
            TenantContextResolver tenantContextResolver) {
        this.complianceService = complianceService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-devices/{deviceId}/compliance")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public ComplianceStateResponse getLatest(@PathVariable UUID deviceId) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        EndpointDeviceComplianceState state = complianceService
                .getLatest(tenant, deviceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Device has not been evaluated yet."));
        EndpointComplianceEvaluation latest = complianceService
                .getEvaluationById(tenant, state.getLatestEvaluationId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Latest evaluation row missing."));
        ComplianceEvaluationOutcome.StalenessReport staleness =
                complianceService.computeStaleness(tenant, deviceId);
        String currentHash = complianceService.computeCurrentPolicyHash(tenant.tenantId());
        return toStateResponse(latest, staleness, currentHash);
    }

    @PostMapping("/endpoint-devices/{deviceId}/compliance/evaluate")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<ComplianceStateResponse> forceEvaluate(@PathVariable UUID deviceId) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        try {
            ComplianceEvaluationOutcome outcome =
                    complianceService.evaluateForAdmin(tenant, deviceId);
            // The force-evaluate response is a freshly persisted row so
            // catalogPolicyHashCurrent == evaluation.catalogPolicyHash
            // (no drift between the just-written history and the
            // current policy set).
            return ResponseEntity.ok(toStateResponse(outcome, outcome.catalogPolicyHash()));
        } catch (ConcurrentComplianceEvaluationException ex) {
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.RETRY_AFTER, "5");
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .headers(headers)
                    .build();
        }
    }

    @GetMapping("/compliance/devices")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public ComplianceEvaluationListResponse<ComplianceStateResponse> listDevices(
            @RequestParam(required = false) ComplianceDecision decision,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        Pageable pageable = clampPageable(page, size,
                Sort.by(Sort.Direction.DESC, "evaluatedAt"));
        Page<EndpointDeviceComplianceState> states =
                complianceService.listLatestStates(tenant, decision, pageable);
        // Resolve the tenant policy hash once for the whole page; every
        // row in the response compares its persisted hash against it.
        String currentHash = complianceService.computeCurrentPolicyHash(tenant.tenantId());
        List<ComplianceStateResponse> mapped = states.stream().map(state -> {
            EndpointComplianceEvaluation latest = complianceService
                    .getEvaluationById(tenant, state.getLatestEvaluationId())
                    .orElse(null);
            ComplianceEvaluationOutcome.StalenessReport staleness =
                    complianceService.computeStaleness(tenant, state.getDeviceId());
            return latest == null
                    ? toStateResponseFromPointer(state, staleness, currentHash)
                    : toStateResponse(latest, staleness, currentHash);
        }).toList();
        return new ComplianceEvaluationListResponse<>(
                mapped, states.getNumber(), states.getSize(),
                states.getTotalElements(), states.getTotalPages());
    }

    @GetMapping("/endpoint-devices/{deviceId}/compliance/evaluations")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public ComplianceEvaluationListResponse<ComplianceStateResponse> listHistory(
            @PathVariable UUID deviceId,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        AdminTenantContext tenant = tenantContextResolver.resolveRequired();
        Pageable pageable = clampPageable(page, size,
                Sort.by(Sort.Direction.DESC, "evaluatedAt"));
        Page<EndpointComplianceEvaluation> history =
                complianceService.listDeviceHistory(tenant, deviceId, pageable);
        ComplianceEvaluationOutcome.StalenessReport staleness =
                complianceService.computeStaleness(tenant, deviceId);
        String currentHash = complianceService.computeCurrentPolicyHash(tenant.tenantId());
        List<ComplianceStateResponse> mapped = history.stream()
                .map(e -> toStateResponse(e, staleness, currentHash))
                .toList();
        return new ComplianceEvaluationListResponse<>(
                mapped, history.getNumber(), history.getSize(),
                history.getTotalElements(), history.getTotalPages());
    }

    // ────────────────────────────────────────────────────────────────
    // Mappers

    private static Pageable clampPageable(int page, int size, Sort sort) {
        if (page < 0) {
            page = 0;
        }
        if (size < 1) {
            size = 1;
        } else if (size > MAX_PAGE_SIZE) {
            size = MAX_PAGE_SIZE;
        }
        return PageRequest.of(page, size, sort);
    }

    @SuppressWarnings("unchecked")
    private static ComplianceStateResponse toStateResponse(
            EndpointComplianceEvaluation evaluation,
            ComplianceEvaluationOutcome.StalenessReport staleness,
            String currentPolicyHash) {
        Map<String, Object> evidenceMap = evaluation.getEvidence() == null
                ? Map.of() : evaluation.getEvidence();
        ComplianceStateResponse.ComplianceEvidence evidence = new ComplianceStateResponse.ComplianceEvidence(
                stringToUuid(evidenceMap.get("inventorySnapshotId")),
                toLong(evidenceMap.get("inventorySnapshotRowVersion")),
                stringToInstant(evidenceMap.get("inventoryUpdatedAt")),
                stringToInstant(evidenceMap.get("summaryCollectedAt")),
                stringToInstant(evidenceMap.get("appsCollectedAt")),
                stringToUuid(evidenceMap.get("latestSummaryCommandResultId")),
                stringToUuid(evidenceMap.get("latestFullCommandResultId")),
                stringToUuid(evidenceMap.get("latestWingetEgressCommandResultId")),
                stringToInstant(evidenceMap.get("wingetEgressCollectedAt")),
                toInteger(evidenceMap.get("wingetEgressSchemaVersion")),
                (Map<String, Object>) evidenceMap.getOrDefault("matchedItems", Map.of()));
        boolean drift = currentPolicyHash != null
                && evaluation.getCatalogPolicyHash() != null
                && !currentPolicyHash.equals(evaluation.getCatalogPolicyHash());
        return new ComplianceStateResponse(
                evaluation.getDeviceId(),
                evaluation.getId(),
                evaluation.getDecision(),
                evaluation.getEvaluatedAt(),
                new ComplianceStateResponse.StalenessReport(
                        staleness.summary(), staleness.apps(),
                        staleness.wingetEgress(), staleness.worst()),
                evaluation.getReasons(),
                evaluation.getBlockingReasons(),
                evaluation.getWarnings(),
                evidence,
                evaluation.getCatalogPolicyHash(),
                currentPolicyHash,
                drift,
                evaluation.getCatalogRowVersionMax(),
                evaluation.getPolicyRowVersionMax());
    }

    private static ComplianceStateResponse toStateResponse(
            ComplianceEvaluationOutcome outcome, String currentPolicyHash) {
        ComplianceStateResponse.ComplianceEvidence evidence = new ComplianceStateResponse.ComplianceEvidence(
                outcome.inventorySnapshotId(),
                outcome.inventorySnapshotRowVersion(),
                stringToInstant(outcome.evidence().get("inventoryUpdatedAt")),
                stringToInstant(outcome.evidence().get("summaryCollectedAt")),
                stringToInstant(outcome.evidence().get("appsCollectedAt")),
                stringToUuid(outcome.evidence().get("latestSummaryCommandResultId")),
                stringToUuid(outcome.evidence().get("latestFullCommandResultId")),
                stringToUuid(outcome.evidence().get("latestWingetEgressCommandResultId")),
                stringToInstant(outcome.evidence().get("wingetEgressCollectedAt")),
                toInteger(outcome.evidence().get("wingetEgressSchemaVersion")),
                castMatched(outcome.evidence().get("matchedItems")));
        boolean drift = currentPolicyHash != null
                && outcome.catalogPolicyHash() != null
                && !currentPolicyHash.equals(outcome.catalogPolicyHash());
        return new ComplianceStateResponse(
                outcome.deviceId(),
                outcome.evaluationId(),
                outcome.decision(),
                outcome.evaluatedAt(),
                new ComplianceStateResponse.StalenessReport(
                        outcome.staleness().summary(),
                        outcome.staleness().apps(),
                        outcome.staleness().wingetEgress(),
                        outcome.staleness().worst()),
                outcome.reasons(),
                outcome.blockingReasons(),
                outcome.warnings(),
                evidence,
                outcome.catalogPolicyHash(),
                currentPolicyHash,
                drift,
                outcome.catalogRowVersionMax(),
                outcome.policyRowVersionMax());
    }

    /** Minimal projection when the latest pointer references a missing evaluation row (defensive). */
    private static ComplianceStateResponse toStateResponseFromPointer(
            EndpointDeviceComplianceState pointer,
            ComplianceEvaluationOutcome.StalenessReport staleness,
            String currentPolicyHash) {
        return new ComplianceStateResponse(
                pointer.getDeviceId(),
                pointer.getLatestEvaluationId(),
                pointer.getDecision(),
                pointer.getEvaluatedAt(),
                new ComplianceStateResponse.StalenessReport(
                        staleness.summary(), staleness.apps(),
                        staleness.wingetEgress(), staleness.worst()),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                currentPolicyHash,
                null,
                null,
                null);
    }

    private static UUID stringToUuid(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return UUID.fromString(o.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Instant stringToInstant(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Instant.parse(o.toString());
        } catch (Exception ex) {
            return null;
        }
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer toInteger(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMatched(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }
}
