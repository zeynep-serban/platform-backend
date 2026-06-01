package com.example.report.controller;

import com.example.report.access.ReportAccessEvaluator;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import com.example.report.repository.CustomReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for ReportController authorization enforcement.
 * CNS-006 R16: CRUD endpoints require REPORT_MANAGE or ownership.
 * CNS-006 R17: Custom report list filters by access_config.reportGroup.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ReportControllerAuthzTest {

    @Mock private PermissionResolver permissionResolver;
    @Mock private CustomReportRepository customReportRepository;
    @Mock private com.example.report.registry.ReportRegistry reportRegistry;
    private ReportController controller;

    @BeforeEach
    void setUp() {
        when(reportRegistry.getAll()).thenReturn(List.of());
        controller = new ReportController(
                reportRegistry,
                customReportRepository,
                permissionResolver,
                new ReportAccessEvaluator(),
                null, // columnFilter
                null, // queryEngine
                mock(com.example.report.audit.ReportAuditClient.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.example.report.authz.CompanyHeaderScopeNarrower(),
                // PR-D2.1c2 (ADR-0015): remote-http executor + AG-Grid translator
                // injected; not exercised in these CRUD authorization tests.
                mock(com.example.report.execution.RemoteReportExecutor.class),
                new com.example.report.execution.AgGridFilterTranslator()
        );
    }

    // ---- R16: CRUD authorization ----

    @Test
    void createCustomReport_withoutReportManage_denied() {
        AuthzMeResponse authz = authzWith(false, List.of("REPORT_VIEW"), null);
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);

        assertThrows(ResponseStatusException.class, () ->
                controller.createCustomReport(new HashMap<>(Map.of("key", "test")), testJwt("user1")));
    }

    @Test
    void createCustomReport_withReportManage_allowed() {
        AuthzMeResponse authz = authzWith(false, List.of("REPORT_VIEW", "REPORT_MANAGE"), null);
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(customReportRepository.save(any())).thenReturn(new HashMap<>(Map.of("key", "test")));

        var response = controller.createCustomReport(new HashMap<>(Map.of("key", "test")), testJwt("user1"));
        assertEquals(201, response.getStatusCode().value());
    }

    @Test
    void createCustomReport_superAdmin_allowed() {
        AuthzMeResponse authz = authzWith(true, List.of(), null);
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(customReportRepository.save(any())).thenReturn(new HashMap<>(Map.of("key", "test")));

        var response = controller.createCustomReport(new HashMap<>(Map.of("key", "test")), testJwt("admin"));
        assertEquals(201, response.getStatusCode().value());
    }

    @Test
    void updateCustomReport_notOwnerNoManage_denied() {
        AuthzMeResponse authz = authzWith(false, List.of("REPORT_VIEW"), null);
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(customReportRepository.findByKey("test")).thenReturn(
                Optional.of(Map.of("createdBy", "other-user")));

        assertThrows(ResponseStatusException.class, () ->
                controller.updateCustomReport("test", Map.of(), testJwt("user1")));
    }

    @Test
    void updateCustomReport_owner_allowed() {
        AuthzMeResponse authz = authzWith(false, List.of("REPORT_VIEW"), null);
        authz.setUserId("user1");
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(customReportRepository.findByKey("test")).thenReturn(
                Optional.of(Map.of("createdBy", "user1")));
        when(customReportRepository.update(eq("test"), any())).thenReturn(new HashMap<>(Map.of("key", "test")));

        var response = controller.updateCustomReport("test", new HashMap<>(), testJwt("user1"));
        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    void deleteCustomReport_notOwnerNoManage_denied() {
        AuthzMeResponse authz = authzWith(false, List.of("REPORT_VIEW"), null);
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(customReportRepository.findByKey("test")).thenReturn(
                Optional.of(Map.of("createdBy", "other-user")));

        assertThrows(ResponseStatusException.class, () ->
                controller.deleteCustomReport("test", testJwt("user1")));
    }

    @Test
    void getHistory_withoutReportView_denied() {
        AuthzMeResponse authz = authzWith(false, List.of(), null);
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);

        assertThrows(ResponseStatusException.class, () ->
                controller.getReportHistory("test", testJwt("user1")));
    }

    @Test
    void getHistory_withReportView_allowed() {
        AuthzMeResponse authz = authzWith(false, List.of("REPORT_VIEW"), null);
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(customReportRepository.getVersionHistory("test")).thenReturn(List.of());

        var response = controller.getReportHistory("test", testJwt("user1"));
        assertEquals(200, response.getStatusCode().value());
    }

    // ---- R17: Custom report access_config filtering ----

    @Test
    void listReports_customReportWithReportGroup_filteredByAuthz() {
        // User has FINANCE_REPORTS but not HR_REPORTS
        AuthzMeResponse authz = authzWith(false, List.of("REPORT_VIEW"),
                Map.of("FINANCE_REPORTS", "ALLOW"));
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);

        Map<String, Object> finReport = new LinkedHashMap<>();
        finReport.put("key", "custom-fin");
        finReport.put("title", "Finance Custom");
        finReport.put("description", "desc");
        finReport.put("category", "Finance");
        finReport.put("accessConfig", Map.of("reportGroup", "FINANCE_REPORTS"));

        Map<String, Object> hrReport = new LinkedHashMap<>();
        hrReport.put("key", "custom-hr");
        hrReport.put("title", "HR Custom");
        hrReport.put("description", "desc");
        hrReport.put("category", "HR");
        hrReport.put("accessConfig", Map.of("reportGroup", "HR_REPORTS"));

        when(customReportRepository.findAll()).thenReturn(List.of(finReport, hrReport));

        var response = controller.listReports(testJwt("user1"));
        var reports = response.getBody();
        assertNotNull(reports);
        // Only FINANCE custom report should be visible (HR filtered out)
        assertTrue(reports.stream().anyMatch(r -> "custom-fin".equals(r.key())));
        assertFalse(reports.stream().anyMatch(r -> "custom-hr".equals(r.key())));
    }

    @Test
    void listReports_customReportNoAccessConfig_allowedWithReportView() {
        AuthzMeResponse authz = authzWith(false, List.of("REPORT_VIEW"), Map.of());
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);

        Map<String, Object> noAccessReport = new LinkedHashMap<>();
        noAccessReport.put("key", "custom-open");
        noAccessReport.put("title", "Open Report");
        noAccessReport.put("description", "desc");
        noAccessReport.put("category", "General");

        when(customReportRepository.findAll()).thenReturn(List.of(noAccessReport));

        var response = controller.listReports(testJwt("user1"));
        var reports = response.getBody();
        assertNotNull(reports);
        assertTrue(reports.stream().anyMatch(r -> "custom-open".equals(r.key())));
    }

    // ---- Helpers ----

    private static AuthzMeResponse authzWith(boolean superAdmin,
                                              List<String> permissions,
                                              Map<String, String> reports) {
        var authz = new AuthzMeResponse();
        authz.setSuperAdmin(superAdmin);
        authz.setPermissions(permissions);
        authz.setReports(reports);
        authz.setUserId("test-user");
        return authz;
    }

    private static Jwt testJwt(String username) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("preferred_username", username)
                .claim("sub", username)
                .claim("email", username + "@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
