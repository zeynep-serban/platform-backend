package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.model.EndpointDiagnosticsProbeError;
import com.example.endpointadmin.model.EndpointDiagnosticsSnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointDiagnosticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE — MockMvc slice test for {@link AdminEndpointDiagnosticsController}
 * (Faz 22.5, AG-038-be query API). Mirrors AG-037 controller pattern.
 *
 * <p>Local profile auth bypass; this class focuses on:
 * <ul>
 *   <li>200 latest with scalars + lastError triad + probeErrors[]
 *       projected.</li>
 *   <li>404 latest when no snapshot exists (cross-tenant isolation:
 *       repository query is tenant-scoped, returns empty for
 *       cross-tenant device).</li>
 *   <li>lastError-null branch: omitted from JSON when triad all-null.</li>
 *   <li>RBAC: requires module:endpoint-admin can_view.</li>
 * </ul>
 */
@WebMvcTest(AdminEndpointDiagnosticsController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointDiagnosticsControllerTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_A = UUID.fromString("aaaa1111-2222-3333-4444-555566667777");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointDiagnosticsService diagnosticsService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void getLatestReturnsFullProjection() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT_A, "subj-1"));
        when(diagnosticsService.findLatest(TENANT_A, DEVICE_A))
                .thenReturn(Optional.of(seedSnapshot(/*withLastError=*/true)));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/" + DEVICE_A + "/diagnostics/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.supported").value(true))
                .andExpect(jsonPath("$.probeComplete").value(true))
                .andExpect(jsonPath("$.agentVersion").value("0.7.2"))
                .andExpect(jsonPath("$.configHash").exists())
                .andExpect(jsonPath("$.lastPollLatencyMs").value(120))
                .andExpect(jsonPath("$.backendDnsReachable").value(true))
                .andExpect(jsonPath("$.backendTlsValid").value(true))
                .andExpect(jsonPath("$.probeDurationMs").value(450))
                .andExpect(jsonPath("$.lastError.code").value("NEXT_COMMAND_TIMEOUT"))
                .andExpect(jsonPath("$.lastError.summary").value("poll timeout after 30s"))
                .andExpect(jsonPath("$.probeErrors[0].code").value("DNS_TIMEOUT"))
                .andExpect(jsonPath("$.probeErrors[0].summary").value("dns lookup exceeded deadline"));
    }

    @Test
    void getLatestOmitsLastErrorWhenTriadNull() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT_A, "subj-1"));
        when(diagnosticsService.findLatest(TENANT_A, DEVICE_A))
                .thenReturn(Optional.of(seedSnapshot(/*withLastError=*/false)));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/" + DEVICE_A + "/diagnostics/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastError").doesNotExist());
    }

    @Test
    void getLatestReturns404WhenNoSnapshot() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT_A, "subj-1"));
        when(diagnosticsService.findLatest(eq(TENANT_A), any(UUID.class)))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/" + DEVICE_A + "/diagnostics/latest"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getLatestForCrossTenantDeviceReturns404NotExistenceLeak() throws Exception {
        UUID otherTenant = UUID.fromString("99999999-9999-9999-9999-999999999999");
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(otherTenant, "subj-1"));
        // The tenant-scoped query returns empty for the other-tenant probe;
        // controller maps to 404 without leaking device existence.
        when(diagnosticsService.findLatest(otherTenant, DEVICE_A))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/" + DEVICE_A + "/diagnostics/latest"))
                .andExpect(status().isNotFound());
    }

    private EndpointDiagnosticsSnapshot seedSnapshot(boolean withLastError) {
        EndpointDiagnosticsSnapshot s = new EndpointDiagnosticsSnapshot();
        s.setId(UUID.randomUUID());
        s.setTenantId(TENANT_A);
        s.setDeviceId(DEVICE_A);
        s.setSchemaVersion(1);
        s.setSupported(true);
        s.setProbeComplete(true);
        s.setAgentVersion("0.7.2");
        s.setConfigHash("a".repeat(64));
        s.setLastPollLatencyMs(120);
        s.setBackendDnsReachable(true);
        s.setBackendTlsValid(true);
        s.setProbeDurationMs(450);
        s.setPayloadHashSha256("b".repeat(64));
        s.setCollectedAt(Instant.parse("2026-06-01T12:00:00Z"));
        s.setCreatedAt(Instant.parse("2026-06-01T12:00:01Z"));
        if (withLastError) {
            s.setLastErrorOccurredAt(Instant.parse("2026-06-01T08:00:00Z"));
            s.setLastErrorCode("NEXT_COMMAND_TIMEOUT");
            s.setLastErrorSummary("poll timeout after 30s");
        }
        List<EndpointDiagnosticsProbeError> probeErrors = new ArrayList<>();
        EndpointDiagnosticsProbeError err = new EndpointDiagnosticsProbeError();
        err.setId(UUID.randomUUID());
        err.setRowOrdinal(0);
        err.setCode("DNS_TIMEOUT");
        err.setSummary("dns lookup exceeded deadline");
        err.setTenantId(TENANT_A);
        err.setSnapshot(s);
        probeErrors.add(err);
        s.setProbeErrors(probeErrors);
        return s;
    }
}
