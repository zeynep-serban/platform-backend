package com.example.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.report.access.ReportAccessEvaluator;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.repository.AlertRuleRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/**
 * AlertController authorization tests (plan §7 Adım 2).
 *
 * <p>Validates {@code /api/v1/alerts/*} authz pipeline:
 * <ul>
 *   <li>REPORT_VIEW perm + scope ALLOWED for read endpoints (GET)</li>
 *   <li>REPORT_MANAGE perm + scope ALLOWED for write endpoints (POST/PUT/DELETE)</li>
 *   <li>Fail-closed 403 + audit row when permission missing or scope denied</li>
 *   <li>Unknown report key → 404</li>
 *   <li>Unknown rule id (update/delete) → 404</li>
 * </ul>
 */
class AlertControllerAuthzTest {

    private final AlertRuleRepository alertRepo = mock(AlertRuleRepository.class);
    private final ReportRegistry registry = mock(ReportRegistry.class);
    private final PermissionResolver permissionResolver = mock(PermissionResolver.class);
    private final ReportAccessEvaluator accessEvaluator = mock(ReportAccessEvaluator.class);
    private final ReportAuditClient auditClient = mock(ReportAuditClient.class);
    private final AlertController controller = new AlertController(
            alertRepo, registry, permissionResolver, accessEvaluator, auditClient);

    @Test
    void denies403_listRules_withoutReportView() {
        Jwt jwt = jwt("user1");
        AuthzMeResponse authz = mock(AuthzMeResponse.class);
        when(permissionResolver.getAuthzMe(jwt)).thenReturn(authz);
        when(authz.isSuperAdmin()).thenReturn(false);
        when(authz.hasPermission("REPORT_VIEW")).thenReturn(false);
        when(authz.getUserId()).thenReturn("u1");

        assertThatThrownBy(() -> controller.listRules("test-report", jwt))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(auditClient).logReportAccessDenied(
                eq("alerts:*"), eq("u1"), any(), eq("DENIED_NO_REPORT_VIEW"));
    }

    @Test
    void denies403_listRules_whenReportScopeDenied() {
        Jwt jwt = jwt("user2");
        AuthzMeResponse authz = mock(AuthzMeResponse.class);
        when(permissionResolver.getAuthzMe(jwt)).thenReturn(authz);
        when(authz.isSuperAdmin()).thenReturn(false);
        when(authz.hasPermission("REPORT_VIEW")).thenReturn(true);
        when(authz.getUserId()).thenReturn("u2");
        ReportDefinition def = mock(ReportDefinition.class);
        when(registry.get("test-report")).thenReturn(Optional.of(def));
        when(accessEvaluator.evaluate(def, authz)).thenReturn(ReportAccessEvaluator.AccessResult.DENIED_NO_REPORT_PERMISSION);

        assertThatThrownBy(() -> controller.listRules("test-report", jwt))
                .isInstanceOf(ResponseStatusException.class);

        verify(auditClient).logReportAccessDenied(
                eq("alerts:test-report:VIEW_ALERT"), eq("u2"), any(), eq("DENIED_NO_REPORT_PERMISSION"));
    }

    @Test
    void allows200_listRules_whenSuperAdminAndScopeAllowed() {
        Jwt jwt = jwt("admin1");
        AuthzMeResponse authz = mock(AuthzMeResponse.class);
        when(permissionResolver.getAuthzMe(jwt)).thenReturn(authz);
        when(authz.isSuperAdmin()).thenReturn(true);
        ReportDefinition def = mock(ReportDefinition.class);
        when(registry.get("test-report")).thenReturn(Optional.of(def));
        when(accessEvaluator.evaluate(def, authz)).thenReturn(ReportAccessEvaluator.AccessResult.ALLOWED);
        when(alertRepo.findByReportKey("test-report")).thenReturn(List.of(Map.of("id", "rule1")));

        var response = controller.listRules("test-report", jwt);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void denies403_createRule_withoutReportManage() {
        Jwt jwt = jwt("user3");
        AuthzMeResponse authz = mock(AuthzMeResponse.class);
        when(permissionResolver.getAuthzMe(jwt)).thenReturn(authz);
        when(authz.isSuperAdmin()).thenReturn(false);
        when(authz.hasPermission("REPORT_MANAGE")).thenReturn(false);
        when(authz.getUserId()).thenReturn("u3");

        assertThatThrownBy(() -> controller.createRule("test-report", Map.of("field", "x"), jwt))
                .isInstanceOf(ResponseStatusException.class);

        verify(auditClient).logReportAccessDenied(
                eq("alerts:CREATE_ALERT"), eq("u3"), any(), eq("DENIED_NO_REPORT_MANAGE"));
    }

    @Test
    void allows201_createRule_superAdminAndScopeAllowed() {
        Jwt jwt = jwt("admin2");
        AuthzMeResponse authz = mock(AuthzMeResponse.class);
        when(permissionResolver.getAuthzMe(jwt)).thenReturn(authz);
        when(authz.isSuperAdmin()).thenReturn(true);
        ReportDefinition def = mock(ReportDefinition.class);
        when(registry.get("test-report")).thenReturn(Optional.of(def));
        when(accessEvaluator.evaluate(def, authz)).thenReturn(ReportAccessEvaluator.AccessResult.ALLOWED);
        when(alertRepo.save(any())).thenReturn(Map.of("id", "new-rule", "reportKey", "test-report"));

        var response = controller.createRule("test-report", new java.util.HashMap<>(Map.of("field", "y")), jwt);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).containsEntry("id", "new-rule");
    }

    @Test
    void denies404_updateRule_whenRuleIdNotFound() {
        Jwt jwt = jwt("admin3");
        when(alertRepo.findReportKeyById("missing-rule")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.updateRule("missing-rule", Map.of(), jwt))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void denies403_updateRule_foundButWithoutReportManage() {
        Jwt jwt = jwt("user4");
        when(alertRepo.findReportKeyById("rule-x")).thenReturn(Optional.of("test-report"));
        AuthzMeResponse authz = mock(AuthzMeResponse.class);
        when(permissionResolver.getAuthzMe(jwt)).thenReturn(authz);
        when(authz.isSuperAdmin()).thenReturn(false);
        when(authz.hasPermission("REPORT_MANAGE")).thenReturn(false);
        when(authz.getUserId()).thenReturn("u4");

        assertThatThrownBy(() -> controller.updateRule("rule-x", Map.of(), jwt))
                .isInstanceOf(ResponseStatusException.class);

        verify(auditClient).logReportAccessDenied(
                eq("alerts:UPDATE_ALERT"), eq("u4"), any(), eq("DENIED_NO_REPORT_MANAGE"));
    }

    @Test
    void denies404_listRules_unknownReportKey() {
        Jwt jwt = jwt("user5");
        AuthzMeResponse authz = mock(AuthzMeResponse.class);
        when(permissionResolver.getAuthzMe(jwt)).thenReturn(authz);
        when(authz.isSuperAdmin()).thenReturn(true);
        when(registry.get("unknown-report")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.listRules("unknown-report", jwt))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private Jwt jwt(String sub) {
        return new Jwt(
                "token-" + sub,
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("sub", sub, "preferred_username", sub + "@test"));
    }
}
