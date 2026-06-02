package com.example.permission.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.permission.audit.AuditReadScope;
import com.example.permission.audit.ImpersonationActionPredicate;
import com.example.permission.dto.AuditEventPageResponse;
import com.example.permission.dto.audit.AuditWeeklyDigestResponse;
import com.example.permission.dto.v1.AuditExportJobCreateRequestDto;
import com.example.permission.dto.v1.AuditExportJobResponseDto;
import com.example.permission.service.AuditEventDigestService;
import com.example.permission.service.AuditEventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/audit/events")
public class AuditEventController {

    private final AuditEventService auditEventService;
    private final AuditEventDigestService digestService;

    public AuditEventController(AuditEventService auditEventService,
                                AuditEventDigestService digestService) {
        this.auditEventService = auditEventService;
        this.digestService = digestService;
    }

    /**
     * PR-D2.5a (Codex 019e8708 AGREE option A constrained): Source-owned
     * weekly audit event digest.
     *
     * <p>Returns ISO-week-bucketed aggregates: per-week totalEventCount,
     * distinctUserCount, actionBreakdown (action → count map),
     * serviceBreakdown (service → count map), and topUsers (top-K by event
     * count with deterministic tie-break).
     *
     * <p>Required query params:
     * <ul>
     *   <li>{@code dateFrom} (ISO-8601 instant)</li>
     *   <li>{@code dateTo} (ISO-8601 instant, strictly > dateFrom, max 366d range)</li>
     * </ul>
     *
     * <p>Optional filters:
     * <ul>
     *   <li>{@code action} / {@code service} / {@code level} / {@code user} (email or numeric userId) / {@code search}</li>
     *   <li>{@code topK} (default 5, max 20)</li>
     * </ul>
     *
     * <p>Authorized by {@code AUDIT.can_view} — same scope as generic
     * {@code GET /api/audit/events}. Tenant boundary preserved via
     * existing {@code @RequireModule} OpenFGA propagation.
     */
    @GetMapping("/digest")
    @RequireModule(value = "AUDIT", relation = "can_view")
    public ResponseEntity<AuditWeeklyDigestResponse> weeklyDigest(
            @RequestParam(name = "dateFrom") String dateFromRaw,
            @RequestParam(name = "dateTo") String dateToRaw,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String level,
            // Codex 019e8721 P1 absorb: sprint contract param name is `user`
            // (email or userId). Repository binds via OR predicate on
            // performed_by::text + user_email; numeric values match userId,
            // non-numeric match email.
            @RequestParam(required = false, name = "user") String userIdentity,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer topK) {

        Instant dateFrom = parseInstantOrBadRequest(dateFromRaw, "dateFrom");
        Instant dateTo = parseInstantOrBadRequest(dateToRaw, "dateTo");

        // P1 absorb: scope defaults to GENERIC_AUDIT — endpoint gated by
        // @RequireModule(AUDIT, can_view). IMPERSONATION_AUDIT digest would
        // be a separate endpoint @RequireModule(IMPERSONATION_AUDIT,
        // can_view) (PR-D2.5a-followup if/when needed).
        AuditWeeklyDigestResponse response = digestService.aggregate(
                dateFrom, dateTo, action, service, level, userIdentity, search, topK,
                com.example.permission.audit.AuditReadScope.GENERIC_AUDIT);
        return ResponseEntity.ok(response);
    }

    private static Instant parseInstantOrBadRequest(String raw, String fieldName) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    fieldName + " required (ISO-8601 instant)");
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    fieldName + " must be ISO-8601 instant (e.g. 2026-05-26T00:00:00Z)");
        }
    }

    @GetMapping
    @RequireModule(value = "AUDIT", relation = "can_view")
    public ResponseEntity<AuditEventPageResponse> listEvents(@RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(name = "pageSize", defaultValue = "50") int pageSize,
                                                             @RequestParam(required = false) String sort,
                                                             @RequestParam Map<String, String> query) {
        // Support legacy 'size' param as alias for pageSize
        int effectiveSize = pageSize;
        if (query.containsKey("size")) {
            try { effectiveSize = Integer.parseInt(query.get("size")); } catch (Exception ignored) {}
        }

        // id shortcut: deterministic fetch or 404. PR-D2: scope-aware
        // — IMPERSONATION_* event ids return 404 here (the AUDIT viewer
        // cannot bypass the generic-feed exclusion via id guess).
        String id = query.get("id");
        if (id != null && !id.isBlank()) {
            AuditEventPageResponse single = auditEventService.findByIdPage(id, AuditReadScope.GENERIC_AUDIT);
            return ResponseEntity.ok(single);
        }

        Map<String, String> filters = extractFilters(query);
        // Convert 1-based 'page' to 0-based for Spring Data
        int zeroBasedPage = Math.max(1, page) - 1;
        AuditEventPageResponse result = auditEventService.listEvents(
                zeroBasedPage, effectiveSize, sort, filters, AuditReadScope.GENERIC_AUDIT);
        return ResponseEntity.ok(result);
    }

    /**
     * PR-D2 (User Impersonation v1): dedicated audit feed for the 5 canonical
     * IMPERSONATION_* events. Gated by {@code IMPERSONATION_AUDIT.can_view}
     * — distinct from the generic AUDIT module so an AUDIT viewer cannot read
     * impersonation events through this endpoint or the generic feed.
     *
     * <p>{@code filter[action]} is strictly validated against the canonical
     * 5-set (or alias {@code "IMPERSONATION"}) — fabricated values like
     * {@code FOO_IMPERSONATION_BAR} return 400 Bad Request. The previous
     * "silently rewrite to IMPERSONATION" behavior is gone.
     *
     * <p>Codex peer review thread {@code 019e10bf} iter-2 AGREE WITH AMENDMENTS.
     */
    @GetMapping("/impersonation")
    @RequireModule(value = "IMPERSONATION_AUDIT", relation = "can_view")
    public ResponseEntity<AuditEventPageResponse> listImpersonationEvents(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "50") int pageSize,
            @RequestParam(required = false) String sort,
            @RequestParam Map<String, String> query) {
        Map<String, String> filters = extractFilters(query);
        validateImpersonationActionFilter(filters);
        int zeroBasedPage = Math.max(1, page) - 1;
        AuditEventPageResponse result = auditEventService.listEvents(
                zeroBasedPage, pageSize, sort, filters, AuditReadScope.IMPERSONATION_AUDIT);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/export")
    @RequireModule(value = "AUDIT", relation = "can_manage")
    public ResponseEntity<byte[]> exportEvents(@RequestParam(defaultValue = "json") String format,
                                               @RequestParam(required = false) Integer limit,
                                               @RequestParam(required = false) String sort,
                                               @RequestParam Map<String, String> query) {
        Map<String, String> filters = extractFilters(query);
        var events = auditEventService.exportEvents(sort, filters, limit, AuditReadScope.GENERIC_AUDIT);
        byte[] payload = auditEventService.buildExportPayload(events, format);

        String sanitizedFormat = "csv".equalsIgnoreCase(format) ? "csv" : "json";
        String filename = "audit-events." + sanitizedFormat;

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=%s".formatted(filename))
                .contentType("csv".equalsIgnoreCase(sanitizedFormat)
                        ? MediaType.parseMediaType("text/csv")
                        : MediaType.APPLICATION_JSON)
                .body(payload);
    }

    @PostMapping("/export-jobs")
    @RequireModule(value = "AUDIT", relation = "can_manage")
    public ResponseEntity<AuditExportJobResponseDto> createExportJob(@Valid @RequestBody(required = false) AuditExportJobCreateRequestDto request,
                                                                     Authentication authentication) {
        AuditExportJobCreateRequestDto payload = request == null ? new AuditExportJobCreateRequestDto() : request;
        AuditExportJobResponseDto response = auditEventService.createExportJob(
                resolveRequestedBy(authentication),
                payload.getFormat(),
                payload.getLimit(),
                payload.getSort(),
                payload.getFilters(),
                AuditReadScope.GENERIC_AUDIT
        );
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/export-jobs/{jobId}")
    @RequireModule(value = "AUDIT", relation = "can_manage")
    public ResponseEntity<AuditExportJobResponseDto> getExportJob(@PathVariable String jobId,
                                                                  Authentication authentication) {
        // PR-D2 iter-3 absorb (Codex 019e10bf P1): scope-aware status fetch.
        // Pre-PR-D2 jobs and any IMPERSONATION-scoped job leak through the
        // generic AUDIT.can_manage gate without this third argument; legacy
        // jobs are fail-closed to 404.
        return ResponseEntity.ok(auditEventService.getExportJob(
                jobId, resolveRequestedBy(authentication), AuditReadScope.GENERIC_AUDIT));
    }

    @GetMapping("/export-jobs/{jobId}/download")
    @RequireModule(value = "AUDIT", relation = "can_manage")
    public ResponseEntity<byte[]> downloadExportJob(@PathVariable String jobId,
                                                    Authentication authentication) {
        // PR-D2 iter-3 absorb (Codex 019e10bf P1): scope-aware download path —
        // closes the legacy persisted-payload leak channel where AUDIT.can_manage
        // holders could download an export job created before PR-D2 (which
        // may embed IMPERSONATION_* rows). Fail-closed for any non-matching
        // or legacy-marker job.
        var job = auditEventService.getCompletedExportJob(
                jobId, resolveRequestedBy(authentication), AuditReadScope.GENERIC_AUDIT);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=%s".formatted(job.getFilename()))
                .contentType(MediaType.parseMediaType(job.getContentType()))
                .body(job.getPayload());
    }

    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequireModule(value = "AUDIT", relation = "can_view")
    public SseEmitter liveEvents() {
        // 2026-04-19 QLTY-PROACTIVE-03: @RequireModule added for parity with the base
        // listEvents() endpoint. Previously anonymous-but-authenticated access was possible
        // (class has no @PreAuthorize; no endpoint-level guard). SSE streams are high-value
        // (real-time audit data) so must be AUDIT.viewer-gated like the list endpoint.
        return auditEventService.openLiveStream();
    }

    private Map<String, String> extractFilters(Map<String, String> query) {
        Map<String, String> filters = new HashMap<>();
        query.forEach((key, value) -> {
            if (key.startsWith("filter[")) {
                String filterKey = key.substring(7, key.length() - 1);
                filters.put(filterKey, value);
            }
            if ("dateFrom".equals(key) || "dateTo".equals(key)) {
                filters.put(key, value);
            }
            if ("action".equals(key) || "correlationId".equals(key)) {
                filters.put(key, value);
            }
            // Spec-aligned aliases
            if ("from".equals(key)) {
                filters.put("dateFrom", value);
            }
            if ("to".equals(key)) {
                filters.put("dateTo", value);
            }
            if ("user".equals(key)) {
                filters.put("userEmail", value);
            }
            if ("service".equals(key) || "level".equals(key) || "search".equals(key)) {
                filters.put(key, value);
            }
        });
        return filters;
    }

    private String resolveRequestedBy(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return "unknown";
        }
        return authentication.getName();
    }

    /**
     * PR-D2: strict validation for {@code filter[action]} on the dedicated
     * impersonation endpoint. Anything outside the canonical 5-set or the
     * alias {@code "IMPERSONATION"} returns 400 Bad Request.
     */
    private void validateImpersonationActionFilter(Map<String, String> filters) {
        if (filters == null) {
            return;
        }
        String actionFilter = filters.get("action");
        if (actionFilter != null && !ImpersonationActionPredicate.isAllowedImpersonationFilter(actionFilter)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid filter[action]; allowed: IMPERSONATION (alias) or one of "
                            + ImpersonationActionPredicate.allActions());
        }
    }
}
