package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.model.ComplianceDecision;
import com.example.endpointadmin.model.EndpointComplianceEvaluation;
import com.example.endpointadmin.model.EndpointDeviceComplianceState;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.compliance.ComplianceEvaluationOutcome;
import com.example.endpointadmin.service.compliance.ConcurrentComplianceEvaluationException;
import com.example.endpointadmin.service.compliance.EndpointComplianceService;
import com.example.endpointadmin.service.compliance.StalenessSeverity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-023 MockMvc slice for {@link AdminEndpointComplianceController}.
 */
@WebMvcTest(AdminEndpointComplianceController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointComplianceControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EVALUATION_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant EVALUATED_AT =
            Instant.parse("2026-05-28T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointComplianceService complianceService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void getLatestReturns200WithDecisionShape() throws Exception {
        bindTenantContext();
        EndpointDeviceComplianceState state = buildState();
        EndpointComplianceEvaluation eval = buildEvaluation(ComplianceDecision.COMPLIANT);
        when(complianceService.getLatest(any(AdminTenantContext.class), eq(DEVICE_ID)))
                .thenReturn(Optional.of(state));
        when(complianceService.getEvaluationById(any(AdminTenantContext.class), eq(EVALUATION_ID)))
                .thenReturn(Optional.of(eval));
        when(complianceService.computeStaleness(any(AdminTenantContext.class), eq(DEVICE_ID)))
                .thenReturn(stalenessFresh());
        when(complianceService.computeCurrentPolicyHash(eq(TENANT_ID)))
                .thenReturn("0".repeat(64));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/compliance", DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("COMPLIANT"))
                .andExpect(jsonPath("$.deviceId").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$.staleness.worst").value("FRESH"))
                .andExpect(jsonPath("$.staleness.summary").value("FRESH"))
                .andExpect(jsonPath("$.reasons").isArray())
                .andExpect(jsonPath("$.blockingReasons").isEmpty())
                .andExpect(jsonPath("$.warnings").isEmpty())
                .andExpect(jsonPath("$.policyDrift").value(false));
    }

    @Test
    void getLatestSurfacesPolicyDriftWhenHashesDiffer() throws Exception {
        bindTenantContext();
        EndpointDeviceComplianceState state = buildState();
        EndpointComplianceEvaluation eval = buildEvaluation(ComplianceDecision.COMPLIANT);
        when(complianceService.getLatest(any(AdminTenantContext.class), eq(DEVICE_ID)))
                .thenReturn(Optional.of(state));
        when(complianceService.getEvaluationById(any(AdminTenantContext.class), eq(EVALUATION_ID)))
                .thenReturn(Optional.of(eval));
        when(complianceService.computeStaleness(any(AdminTenantContext.class), eq(DEVICE_ID)))
                .thenReturn(stalenessFresh());
        when(complianceService.computeCurrentPolicyHash(eq(TENANT_ID)))
                .thenReturn("f".repeat(64));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/compliance", DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyDrift").value(true))
                .andExpect(jsonPath("$.catalogPolicyHash").value("0".repeat(64)))
                .andExpect(jsonPath("$.catalogPolicyHashCurrent").value("f".repeat(64)));
    }

    @Test
    void getLatestReturns404WhenDeviceMissing() throws Exception {
        bindTenantContext();
        when(complianceService.getLatest(any(AdminTenantContext.class), eq(DEVICE_ID)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/compliance", DEVICE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void forceEvaluateReturns200WithDecisionAndStaleness() throws Exception {
        bindTenantContext();
        ComplianceEvaluationOutcome outcome = new ComplianceEvaluationOutcome(
                EVALUATION_ID, DEVICE_ID, ComplianceDecision.NON_COMPLIANT,
                EVALUATED_AT,
                List.of("missing_required_app"),
                List.of("missing_required_app"),
                List.of(),
                Map.of("matchedItems", Map.of()),
                "0".repeat(64), null, null, null, null,
                new ComplianceEvaluationOutcome.StalenessReport(
                        StalenessSeverity.FRESH, StalenessSeverity.FRESH,
                        StalenessSeverity.UNAVAILABLE, StalenessSeverity.FRESH));
        when(complianceService.evaluateForAdmin(any(AdminTenantContext.class), eq(DEVICE_ID)))
                .thenReturn(outcome);

        mockMvc.perform(post("/api/v1/admin/endpoint-devices/{deviceId}/compliance/evaluate", DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("NON_COMPLIANT"))
                .andExpect(jsonPath("$.staleness.summary").value("FRESH"))
                .andExpect(jsonPath("$.staleness.wingetEgress").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.reasons[0]").value("missing_required_app"));
    }

    @Test
    void forceEvaluateReturns409WhenLockHeld() throws Exception {
        bindTenantContext();
        when(complianceService.evaluateForAdmin(any(AdminTenantContext.class), eq(DEVICE_ID)))
                .thenThrow(new ConcurrentComplianceEvaluationException(
                        "Another compliance evaluation is in flight for this device."));

        mockMvc.perform(post("/api/v1/admin/endpoint-devices/{deviceId}/compliance/evaluate", DEVICE_ID))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "5"));
    }

    @Test
    void forceEvaluateReturns404WhenDeviceUnknown() throws Exception {
        bindTenantContext();
        when(complianceService.evaluateForAdmin(any(AdminTenantContext.class), eq(DEVICE_ID)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found."));

        mockMvc.perform(post("/api/v1/admin/endpoint-devices/{deviceId}/compliance/evaluate", DEVICE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void listDevicesPaginatesAndFiltersByDecision() throws Exception {
        bindTenantContext();
        EndpointDeviceComplianceState state = buildState();
        Page<EndpointDeviceComplianceState> page =
                new PageImpl<>(List.of(state), PageRequest.of(0, 25), 1);
        when(complianceService.listLatestStates(
                any(AdminTenantContext.class),
                eq(ComplianceDecision.NON_COMPLIANT),
                any()))
                .thenReturn(page);
        when(complianceService.getEvaluationById(any(AdminTenantContext.class), eq(EVALUATION_ID)))
                .thenReturn(Optional.of(buildEvaluation(ComplianceDecision.NON_COMPLIANT)));
        when(complianceService.computeStaleness(any(AdminTenantContext.class), eq(DEVICE_ID)))
                .thenReturn(stalenessFresh());

        mockMvc.perform(get("/api/v1/admin/compliance/devices")
                        .param("decision", "NON_COMPLIANT")
                        .param("page", "0")
                        .param("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].decision").value("NON_COMPLIANT"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void listHistoryReturnsPaginatedShape() throws Exception {
        bindTenantContext();
        EndpointComplianceEvaluation eval = buildEvaluation(ComplianceDecision.COMPLIANT);
        Page<EndpointComplianceEvaluation> page =
                new PageImpl<>(List.of(eval), PageRequest.of(0, 25), 1);
        when(complianceService.listDeviceHistory(
                any(AdminTenantContext.class), eq(DEVICE_ID), any()))
                .thenReturn(page);
        when(complianceService.computeStaleness(any(AdminTenantContext.class), eq(DEVICE_ID)))
                .thenReturn(stalenessFresh());

        mockMvc.perform(get(
                        "/api/v1/admin/endpoint-devices/{deviceId}/compliance/evaluations",
                        DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].decision").value("COMPLIANT"));
    }

    // ────────────────────────────────────────────────────────────────
    // Fixtures

    private void bindTenantContext() {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT_ID, "admin@example.com"));
    }

    private ComplianceEvaluationOutcome.StalenessReport stalenessFresh() {
        return new ComplianceEvaluationOutcome.StalenessReport(
                StalenessSeverity.FRESH, StalenessSeverity.FRESH,
                StalenessSeverity.UNAVAILABLE, StalenessSeverity.FRESH);
    }

    private EndpointDeviceComplianceState buildState() {
        EndpointDeviceComplianceState state = new EndpointDeviceComplianceState();
        state.setTenantId(TENANT_ID);
        state.setDeviceId(DEVICE_ID);
        state.setLatestEvaluationId(EVALUATION_ID);
        state.setDecision(ComplianceDecision.COMPLIANT);
        state.setEvaluatedAt(EVALUATED_AT);
        return state;
    }

    private EndpointComplianceEvaluation buildEvaluation(ComplianceDecision decision) {
        EndpointComplianceEvaluation eval = new EndpointComplianceEvaluation();
        setField(eval, "id", EVALUATION_ID);
        eval.setTenantId(TENANT_ID);
        eval.setDeviceId(DEVICE_ID);
        eval.setEvaluatedAt(EVALUATED_AT);
        eval.setDecision(decision);
        eval.setReasons(List.of());
        eval.setBlockingReasons(List.of());
        eval.setWarnings(List.of());
        eval.setEvidence(Map.of("matchedItems", Map.of()));
        eval.setCatalogPolicyHash("0".repeat(64));
        return eval;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
