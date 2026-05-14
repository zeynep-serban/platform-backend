package com.example.report.controller;

import com.example.report.access.ReportAccessEvaluator;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.repository.ReportScheduleRepository;
import com.example.report.security.JwtClaimExtractor;
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
 * Report schedule CRUD endpoint.
 *
 * <p>Path: {@code /api/v1/schedules/*}
 *
 * <p>Authorization (plan §7 Adım 2, 2026-05-14):
 * <ul>
 *   <li>All endpoints require authenticated JWT (Spring Security chain).</li>
 *   <li>Read (GET) → {@code REPORT_VIEW} + {@code ReportAccessEvaluator.ALLOWED}.</li>
 *   <li>Write (POST/PUT/DELETE) → {@code REPORT_MANAGE} + scope check.</li>
 *   <li>Denied → 403 + audit row. Unknown report → 404.</li>
 * </ul>
 *
 * <p>Update/Delete by {@code scheduleId} resolves {@code reportKey} via
 * {@link ReportScheduleRepository#findReportKeyById(String)}.
 *
 * <p>Codex thread {@code 019e258f} iter-2 PARTIAL absorb: pre-fix Alert/Schedule
 * had authn-only access; this PR closes the governance gap.
 */
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ReportScheduleRepository scheduleRepository;
    private final ReportRegistry registry;
    private final PermissionResolver permissionResolver;
    private final ReportAccessEvaluator accessEvaluator;
    private final ReportAuditClient auditClient;

    public ScheduleController(ReportScheduleRepository scheduleRepository,
                              ReportRegistry registry,
                              PermissionResolver permissionResolver,
                              ReportAccessEvaluator accessEvaluator,
                              ReportAuditClient auditClient) {
        this.scheduleRepository = scheduleRepository;
        this.registry = registry;
        this.permissionResolver = permissionResolver;
        this.accessEvaluator = accessEvaluator;
        this.auditClient = auditClient;
    }

    @GetMapping("/{reportKey}")
    public ResponseEntity<Map<String, Object>> getSchedule(
            @PathVariable String reportKey,
            @AuthenticationPrincipal Jwt jwt) {
        checkReportViewAccess(reportKey, jwt);
        return scheduleRepository.findByReportKey(reportKey)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{reportKey}")
    public ResponseEntity<Map<String, Object>> createSchedule(
            @PathVariable String reportKey,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        checkReportManageAccess(reportKey, jwt, "CREATE_SCHEDULE");
        body.put("reportKey", reportKey);
        Map<String, Object> saved = scheduleRepository.save(body);
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{scheduleId}")
    public ResponseEntity<Void> updateSchedule(
            @PathVariable String scheduleId,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        String reportKey = lookupReportKeyOrThrow(scheduleId);
        checkReportManageAccess(reportKey, jwt, "UPDATE_SCHEDULE");
        boolean updated = scheduleRepository.update(scheduleId, body);
        return updated ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable String scheduleId,
            @AuthenticationPrincipal Jwt jwt) {
        String reportKey = lookupReportKeyOrThrow(scheduleId);
        checkReportManageAccess(reportKey, jwt, "DELETE_SCHEDULE");
        boolean deleted = scheduleRepository.delete(scheduleId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /* ---- Authorization helpers (plan §7 Adım 2, mirror AlertController pattern) ---- */

    private String lookupReportKeyOrThrow(String scheduleId) {
        return scheduleRepository.findReportKeyById(scheduleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "schedule:" + scheduleId));
    }

    private void checkReportViewAccess(String reportKey, Jwt jwt) {
        AuthzMeResponse authz = permissionResolver.getAuthzMe(jwt);
        requireReportView(authz, jwt);
        evaluateReportScope(reportKey, authz, jwt, "VIEW_SCHEDULE");
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
            auditClient.logReportAccessDenied("schedules:" + reportKey + ":" + action,
                    authz != null ? authz.getUserId() : "unknown",
                    JwtClaimExtractor.extractAuditUsername(jwt),
                    result.name());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, result.name());
        }
    }

    private void requireReportView(AuthzMeResponse authz, Jwt jwt) {
        if (authz == null || (!authz.isSuperAdmin() && !authz.hasPermission("REPORT_VIEW"))) {
            auditClient.logReportAccessDenied("schedules:*",
                    authz != null ? authz.getUserId() : "unknown",
                    JwtClaimExtractor.extractAuditUsername(jwt),
                    "DENIED_NO_REPORT_VIEW");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DENIED_NO_REPORT_VIEW");
        }
    }

    private void requireReportManage(AuthzMeResponse authz, Jwt jwt, String action) {
        if (authz == null || (!authz.isSuperAdmin() && !authz.hasPermission("REPORT_MANAGE"))) {
            auditClient.logReportAccessDenied("schedules:" + action,
                    authz != null ? authz.getUserId() : "unknown",
                    JwtClaimExtractor.extractAuditUsername(jwt),
                    "DENIED_NO_REPORT_MANAGE");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DENIED_NO_REPORT_MANAGE");
        }
    }
}
