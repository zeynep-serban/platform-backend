package com.example.report.controller;

import com.example.report.access.ReportAccessEvaluator;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.repository.AlertRuleRepository;
import com.example.report.security.JwtClaimExtractor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Report alert rules CRUD endpoint.
 *
 * <p>Path: {@code /api/v1/alerts/*}
 *
 * <p>Authorization (plan §7 Adım 2, 2026-05-14):
 * <ul>
 *   <li>All endpoints require authenticated JWT (Spring Security chain).</li>
 *   <li>Read (GET) → {@code REPORT_VIEW} + {@code ReportAccessEvaluator.ALLOWED}.</li>
 *   <li>Write (POST/PUT/DELETE) → {@code REPORT_MANAGE} + scope check.</li>
 *   <li>Denied → 403 + {@code ReportAuditClient.logReportAccessDenied} audit row.</li>
 *   <li>Unknown report key → 404 NOT_FOUND.</li>
 * </ul>
 *
 * <p>Update/Delete by {@code ruleId} resolves owner {@code reportKey} via
 * {@link AlertRuleRepository#findReportKeyById(String)} so the per-report scope
 * check applies even when the URL has no {@code reportKey} path variable.
 *
 * <p>Codex thread {@code 019e258f} iter-2 PARTIAL absorb: Alert/Schedule
 * pre-fix had authn-only access; any authenticated JWT could create/update
 * alert rules across all reports. This PR closes that governance gap.
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertRuleRepository alertRepository;
    private final ReportRegistry registry;
    private final PermissionResolver permissionResolver;
    private final ReportAccessEvaluator accessEvaluator;
    private final ReportAuditClient auditClient;

    public AlertController(AlertRuleRepository alertRepository,
                           ReportRegistry registry,
                           PermissionResolver permissionResolver,
                           ReportAccessEvaluator accessEvaluator,
                           ReportAuditClient auditClient) {
        this.alertRepository = alertRepository;
        this.registry = registry;
        this.permissionResolver = permissionResolver;
        this.accessEvaluator = accessEvaluator;
        this.auditClient = auditClient;
    }

    @GetMapping("/{reportKey}")
    public ResponseEntity<List<Map<String, Object>>> listRules(
            @PathVariable String reportKey,
            @AuthenticationPrincipal Jwt jwt) {
        checkReportViewAccess(reportKey, jwt);
        return ResponseEntity.ok(alertRepository.findByReportKey(reportKey));
    }

    @PostMapping("/{reportKey}")
    public ResponseEntity<Map<String, Object>> createRule(
            @PathVariable String reportKey,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        checkReportManageAccess(reportKey, jwt, "CREATE_ALERT");
        body.put("reportKey", reportKey);
        Map<String, Object> saved = alertRepository.save(body);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/rule/{ruleId}")
    public ResponseEntity<Void> updateRule(
            @PathVariable String ruleId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        String reportKey = lookupReportKeyOrThrow(ruleId);
        checkReportManageAccess(reportKey, jwt, "UPDATE_ALERT");
        boolean updated = alertRepository.update(ruleId, body);
        return updated ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/rule/{ruleId}")
    public ResponseEntity<Void> deleteRule(
            @PathVariable String ruleId,
            @AuthenticationPrincipal Jwt jwt) {
        String reportKey = lookupReportKeyOrThrow(ruleId);
        checkReportManageAccess(reportKey, jwt, "DELETE_ALERT");
        boolean deleted = alertRepository.delete(ruleId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /* ---- Authorization helpers (plan §7 Adım 2, mirror ReportController pattern) ---- */

    private String lookupReportKeyOrThrow(String ruleId) {
        return alertRepository.findReportKeyById(ruleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "alert:" + ruleId));
    }

    private void checkReportViewAccess(String reportKey, Jwt jwt) {
        AuthzMeResponse authz = permissionResolver.getAuthzMe(jwt);
        requireReportView(authz, jwt);
        evaluateReportScope(reportKey, authz, jwt, "VIEW_ALERT");
    }

    private void checkReportManageAccess(String reportKey, Jwt jwt, String action) {
        AuthzMeResponse authz = permissionResolver.getAuthzMe(jwt);
        requireReportManage(authz, jwt, action);
        evaluateReportScope(reportKey, authz, jwt, action);
    }

    private void evaluateReportScope(String reportKey, AuthzMeResponse authz, Jwt jwt, String action) {
        Optional<ReportDefinition> defOpt = registry.get(reportKey);
        if (defOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "report:" + reportKey);
        }
        ReportAccessEvaluator.AccessResult result = accessEvaluator.evaluate(defOpt.get(), authz);
        if (result != ReportAccessEvaluator.AccessResult.ALLOWED) {
            auditClient.logReportAccessDenied("alerts:" + reportKey + ":" + action,
                    authz != null ? authz.getUserId() : "unknown",
                    JwtClaimExtractor.extractAuditUsername(jwt),
                    result.name());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, result.name());
        }
    }

    private void requireReportView(AuthzMeResponse authz, Jwt jwt) {
        if (authz == null || (!authz.isSuperAdmin() && !authz.hasPermission("REPORT_VIEW"))) {
            auditClient.logReportAccessDenied("alerts:*",
                    authz != null ? authz.getUserId() : "unknown",
                    JwtClaimExtractor.extractAuditUsername(jwt),
                    "DENIED_NO_REPORT_VIEW");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DENIED_NO_REPORT_VIEW");
        }
    }

    private void requireReportManage(AuthzMeResponse authz, Jwt jwt, String action) {
        if (authz == null || (!authz.isSuperAdmin() && !authz.hasPermission("REPORT_MANAGE"))) {
            auditClient.logReportAccessDenied("alerts:" + action,
                    authz != null ? authz.getUserId() : "unknown",
                    JwtClaimExtractor.extractAuditUsername(jwt),
                    "DENIED_NO_REPORT_MANAGE");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DENIED_NO_REPORT_MANAGE");
        }
    }
}
