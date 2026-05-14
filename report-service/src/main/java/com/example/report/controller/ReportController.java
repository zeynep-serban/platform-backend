package com.example.report.controller;

import com.example.report.access.ColumnFilter;
import com.example.report.access.ReportAccessEvaluator;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.CompanyHeaderScopeNarrower;
import com.example.report.authz.PermissionResolver;
import com.example.report.dto.CategoryDto;
import com.example.report.dto.ColumnVO;
import com.example.report.dto.PagedResultDto;
import com.example.report.dto.ReportCapabilitiesDto;
import com.example.report.dto.ReportListItemDto;
import com.example.report.dto.ReportMetadataDto;
import com.example.report.dto.ReportQueryErrorDto;
import com.example.report.dto.ReportQueryRequestDto;
import com.example.report.query.QueryEngine;
import com.example.report.query.SqlBuilder;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.repository.CustomReportRepository;
import com.example.report.security.JwtClaimExtractor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportRegistry registry;
    private final CustomReportRepository customReportRepository;
    private final PermissionResolver permissionClient;
    private final ReportAccessEvaluator accessEvaluator;
    private final ColumnFilter columnFilter;
    private final QueryEngine queryEngine;
    private final ReportAuditClient auditClient;
    private final ObjectMapper objectMapper;
    private final CompanyHeaderScopeNarrower companyHeaderNarrower;

    public ReportController(ReportRegistry registry,
                            CustomReportRepository customReportRepository,
                            PermissionResolver permissionClient,
                            ReportAccessEvaluator accessEvaluator,
                            ColumnFilter columnFilter,
                            QueryEngine queryEngine,
                            ReportAuditClient auditClient,
                            ObjectMapper objectMapper,
                            CompanyHeaderScopeNarrower companyHeaderNarrower) {
        this.registry = registry;
        this.customReportRepository = customReportRepository;
        this.permissionClient = permissionClient;
        this.accessEvaluator = accessEvaluator;
        this.columnFilter = columnFilter;
        this.queryEngine = queryEngine;
        this.auditClient = auditClient;
        this.objectMapper = objectMapper;
        this.companyHeaderNarrower = companyHeaderNarrower;
    }

    @GetMapping
    public ResponseEntity<List<ReportListItemDto>> listReports(@AuthenticationPrincipal Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);

        // Static reports from JSON registry
        List<ReportListItemDto> staticReports = registry.getAll().stream()
                .filter(def -> accessEvaluator.evaluate(def, authz) == ReportAccessEvaluator.AccessResult.ALLOWED)
                .map(def -> new ReportListItemDto(def.key(), def.title(), def.description(), def.category(),
                        def.access() != null ? def.access().reportGroup() : null))
                .toList();

        // Custom reports from PostgreSQL — filtered by access_config reportGroup (CNS-006 R17)
        List<ReportListItemDto> customReports = List.of();
        try {
            customReports = customReportRepository.findAll().stream()
                    .filter(row -> evaluateCustomReportAccess(row, authz))
                    .map(row -> new ReportListItemDto(
                            (String) row.get("key"),
                            (String) row.get("title"),
                            (String) row.get("description"),
                            (String) row.get("category"),
                            extractReportGroup(row)
                    ))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to load custom reports from PostgreSQL: {}", e.getMessage());
        }

        // Merge: static + custom (static wins on key conflict)
        var staticKeys = staticReports.stream().map(ReportListItemDto::key).collect(java.util.stream.Collectors.toSet());
        List<ReportListItemDto> merged = new ArrayList<>(staticReports);
        customReports.stream()
                .filter(r -> !staticKeys.contains(r.key()))
                .forEach(merged::add);

        return ResponseEntity.ok(merged);
    }

    /* ---- Custom Report CRUD (CNS-006 R16: OpenFGA permission enforced) ---- */

    @PostMapping
    public ResponseEntity<Map<String, Object>> createCustomReport(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        requireReportManage(authz, jwt, "CREATE");

        String username = JwtClaimExtractor.extractPreferredUsername(jwt);
        body.put("createdBy", username);
        Map<String, Object> saved = customReportRepository.save(body);
        auditClient.logReportAccess("custom:" + saved.get("key"), authz.getUserId(), JwtClaimExtractor.extractAuditUsername(jwt));
        return ResponseEntity.status(201).body(saved);
    }

    @PutMapping("/{key}")
    public ResponseEntity<Map<String, Object>> updateCustomReport(
            @PathVariable String key,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        requireReportManageOrOwner(authz, jwt, key, "UPDATE");

        String username = JwtClaimExtractor.extractPreferredUsername(jwt);
        body.put("createdBy", username);
        Map<String, Object> updated = customReportRepository.update(key, body);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> deleteCustomReport(
            @PathVariable String key,
            @AuthenticationPrincipal Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        requireReportManageOrOwner(authz, jwt, key, "DELETE");

        boolean deleted = customReportRepository.softDelete(key);
        if (deleted) {
            auditClient.logReportAccessDenied("custom:" + key, authz.getUserId(), JwtClaimExtractor.extractAuditUsername(jwt), "SOFT_DELETED");
        }
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/{key}/history")
    public ResponseEntity<List<Map<String, Object>>> getReportHistory(
            @PathVariable String key,
            @AuthenticationPrincipal Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        requireReportView(authz, jwt);

        List<Map<String, Object>> history = customReportRepository.getVersionHistory(key);
        return ResponseEntity.ok(history);
    }

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryDto>> listCategories(@AuthenticationPrincipal Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);

        Map<String, Long> categoryCounts = registry.getAll().stream()
                .filter(def -> accessEvaluator.evaluate(def, authz) == ReportAccessEvaluator.AccessResult.ALLOWED)
                .collect(java.util.stream.Collectors.groupingBy(
                        ReportDefinition::category,
                        java.util.stream.Collectors.counting()));

        List<CategoryDto> categories = categoryCounts.entrySet().stream()
                .map(e -> new CategoryDto(e.getKey(), e.getValue()))
                .sorted(java.util.Comparator.comparing(CategoryDto::name))
                .toList();

        return ResponseEntity.ok(categories);
    }

    @GetMapping("/{key}/metadata")
    public ResponseEntity<ReportMetadataDto> getMetadata(@PathVariable String key,
                                                          @AuthenticationPrincipal Jwt jwt) {
        ReportDefinition def = findReportOrThrow(key);
        AuthzMeResponse authz = resolveAndCheckAccess(def, jwt);

        List<ColumnDefinition> visibleCols = columnFilter.getVisibleColumnDefinitions(def, authz);

        // PR-0.1 introduced the capabilities envelope; PR-0.2 derives the
        // grouping flag from per-column flags on the registry. Reports with
        // at least one groupable column light up serverSideGrouping=true,
        // and the field lists tell the frontend which columns are valid
        // drop-targets for the row-group panel and the value-aggregation
        // picker without re-deriving from the column metadata.
        List<String> groupable = visibleCols.stream()
                .filter(ColumnDefinition::groupable)
                .map(ColumnDefinition::field)
                .toList();
        List<String> aggregatable = visibleCols.stream()
                .filter(ColumnDefinition::aggregatable)
                .map(ColumnDefinition::field)
                .toList();
        // PR-0.4a (Codex 019e2695 hybrid pivot design): pivot capability
        // derivation. `serverSidePivoting` and `clientPivotAllowed` stay
        // false-by-default until a report explicitly opts in via its
        // registry entry — backend pivot SQL is not yet wired in
        // SqlBuilder (lands in PR-0.4b). For now we surface the
        // pivotableFields list so the frontend can already plan AG Grid's
        // `enablePivot` per-column gating; the capability flags will flip
        // to true on a per-report basis as the SSRM pivot path matures.
        List<String> pivotable = visibleCols.stream()
                .filter(ColumnDefinition::pivotable)
                .map(ColumnDefinition::field)
                .toList();
        ReportCapabilitiesDto capabilities = new ReportCapabilitiesDto(
                !groupable.isEmpty(), groupable, aggregatable,
                /* serverSidePivoting */ false,
                /* clientPivotAllowed */ false,
                pivotable);

        return ResponseEntity.ok(new ReportMetadataDto(
                def.key(), def.title(), def.description(), def.category(),
                visibleCols, def.defaultSort(), def.defaultSortDirection(),
                capabilities));
    }

    @GetMapping("/{key}/data")
    public ResponseEntity<PagedResultDto<Map<String, Object>>> getData(
            @PathVariable String key,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String advancedFilter,
            @RequestHeader(value = CompanyHeaderScopeNarrower.HEADER_NAME, required = false) String companyHeader,
            @AuthenticationPrincipal Jwt jwt) {

        ReportDefinition def = findReportOrThrow(key);
        AuthzMeResponse authz = resolveAndCheckAccess(def, jwt);
        // Narrow to the company the user picked in the CompanyPicker so
        // YearlySchemaResolver / RowFilterInjector see only that tenant.
        // Without this, multi-company users get a UNION of every allowed
        // schema and the dropdown does nothing visible.
        AuthzMeResponse scopedAuthz = companyHeaderNarrower.narrow(authz, companyHeader);

        Map<String, Object> agGridFilter = parseJson(advancedFilter, new TypeReference<>() {});
        List<Map<String, String>> sortModel = parseJson(sort, new TypeReference<>() {});

        pageSize = Math.min(Math.max(pageSize, 1), 500);

        QueryEngine.PagedData result = queryEngine.executeQuery(def, scopedAuthz, agGridFilter, sortModel, page, pageSize);

        auditClient.logReportAccess(key, authz.getUserId(), JwtClaimExtractor.extractAuditUsername(jwt));

        // Codex 019e0c99 iter-3 §C: surface degradation warnings as
        // X-Report-Degraded header (dedupe by code).
        return ResponseEntity.ok()
                .headers(com.example.report.query.DegradationHeaders.of(result.warnings()))
                .body(new PagedResultDto<>(result.items(), result.total(), result.page(), result.pageSize()));
    }

    /**
     * AG Grid SSRM-compatible query endpoint introduced by PR-0.1 of the
     * reporting platform hardening plan (2026-05).
     *
     * <p>For PR-0.1 the contract is intentionally minimal: pagination,
     * sorting and filtering delegate to the same {@link QueryEngine#executeQuery}
     * path used by the legacy GET {@code /data} handler. Any request that
     * asks for grouping or pivoting is rejected with HTTP 400 because every
     * report currently advertises {@code capabilities.serverSideGrouping=false}.
     *
     * <p>PR-0.1 hardening notes:
     * <ul>
     *   <li>The 400 response carries a structured {@link ReportQueryErrorDto}
     *       body so the frontend can branch on {@code body.code} rather
     *       than parsing the {@code reason} field of Spring's default
     *       error envelope.</li>
     *   <li>Pagination guards reject {@code endRow <= startRow} and
     *       {@code startRow % pageSize != 0} with structured codes so
     *       a misaligned SSRM cache window (which would otherwise
     *       silently shift the SQL OFFSET) fails closed.</li>
     * </ul>
     *
     * <p>PR-0.2 will graduate {@code rowGroupCols + groupKeys + valueCols}
     * from rejected to handled (single-level GROUP BY), then PR-0.3+ adds
     * multi-level expansion, weighted AVG and pivot.
     */
    @PostMapping("/{key}/query")
    public ResponseEntity<?> queryReport(
            @PathVariable String key,
            @RequestBody(required = false) ReportQueryRequestDto request,
            @RequestHeader(value = CompanyHeaderScopeNarrower.HEADER_NAME, required = false) String companyHeader,
            @AuthenticationPrincipal Jwt jwt) {

        ReportDefinition def = findReportOrThrow(key);
        AuthzMeResponse authz = resolveAndCheckAccess(def, jwt);
        AuthzMeResponse scopedAuthz = companyHeaderNarrower.narrow(authz, companyHeader);

        ReportQueryRequestDto safeRequest = request != null
                ? request
                : new ReportQueryRequestDto(null, null, null, null, null, null, null, null, null);

        // PR-0.3 capability dispatcher. Three buckets:
        // 1. Multi-level GROUP BY (1..N rowGroupCols + 0..N groupKeys
        //    where groupKeys.size ≤ rowGroupCols.size) → grouped path
        //    when level < N, leaf-flat path when level == N.
        // 2. Pivot / pivotMode → still a structured 400 because PR-0.4
        //    hasn't shipped yet.
        // 3. Flat request → delegate to the legacy executeQuery path.
        List<ColumnDefinition> visibleCols = columnFilter.getVisibleColumnDefinitions(def, scopedAuthz);
        Set<String> groupableFields = visibleCols.stream()
                .filter(ColumnDefinition::groupable)
                .map(ColumnDefinition::field)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> aggregatableFields = visibleCols.stream()
                .filter(ColumnDefinition::aggregatable)
                .map(ColumnDefinition::field)
                .collect(java.util.stream.Collectors.toSet());

        boolean wantsMultiLevelGroup = multiLevelGroupRequest(safeRequest, groupableFields);
        if (safeRequest.requestsGrouping() && !wantsMultiLevelGroup) {
            return ResponseEntity.badRequest().body(new ReportQueryErrorDto(
                    "GROUPING_NOT_SUPPORTED",
                    "Server-side pivot / pivotMode not yet enabled for this "
                            + "report; row-grouping requires every rowGroupCols "
                            + "field to be marked groupable=true and groupKeys "
                            + "depth must not exceed the rowGroupCols path."));
        }

        // Pagination: same fail-closed translation as the flat path. The
        // grouped path also paginates over GROUP-BY buckets (not source
        // rows), so the same alignment guard applies.
        int[] paging;
        try {
            paging = computePaging(safeRequest.startRow(), safeRequest.endRow());
        } catch (PagingException pe) {
            return ResponseEntity.badRequest().body(
                    new ReportQueryErrorDto(pe.code, pe.getMessage()));
        }
        int page = paging[0];
        int pageSize = paging[1];

        QueryEngine.PagedData result;
        if (wantsMultiLevelGroup) {
            // PR-0.3 multi-level dispatch. Validate valueCols upfront so
            // a leaf-path request with malformed aggregation metadata
            // also fails closed (Codex iter-1 absorb — invalid valueCols
            // would otherwise slip through executeQuery silently).
            List<SqlBuilder.GroupedAggregation> aggregations;
            try {
                aggregations = sanitizeAggregations(
                        safeRequest.valueCols(), aggregatableFields, visibleCols);
            } catch (IllegalArgumentException iae) {
                return ResponseEntity.badRequest().body(new ReportQueryErrorDto(
                        "INVALID_AGGREGATION_REQUEST", iae.getMessage()));
            }

            // PR #5b (Codex 019e2695): merge ancestor groupKeys on top
            // of the user's filterModel. Same-field user+ancestor pairs
            // now produce a compound AND entry rather than a 400; only
            // type-coercion failures still surface as structured 400.
            List<ColumnVO> rowGroupCols = safeRequest.rowGroupCols();
            List<String> groupKeys = safeRequest.groupKeys() != null
                    ? safeRequest.groupKeys()
                    : List.of();
            int currentLevel = groupKeys.size();

            Map<String, Object> mergedFilter;
            try {
                mergedFilter = mergeAncestorFilters(
                        safeRequest.filterModel(), rowGroupCols, groupKeys, visibleCols);
            } catch (IllegalArgumentException iae) {
                // PR #5b (Codex 019e2695): AncestorFilterCollisionException
                // removed; same-field ancestor + user filter now merges
                // as a compound AND. Only the type-coercion failure
                // path (IllegalArgumentException from coerceGroupKey)
                // still surfaces as a structured 400.
                return ResponseEntity.badRequest().body(new ReportQueryErrorDto(
                        "INVALID_GROUP_KEY", iae.getMessage()));
            }

            if (currentLevel < rowGroupCols.size()) {
                // GROUP BY the current level's column.
                String groupColumn = rowGroupCols.get(currentLevel).field();
                try {
                    result = queryEngine.executeGroupedQuery(
                            def, scopedAuthz,
                            groupColumn, aggregations,
                            mergedFilter,
                            safeRequest.sortModel(),
                            page, pageSize);
                } catch (IllegalArgumentException iae) {
                    return ResponseEntity.badRequest().body(new ReportQueryErrorDto(
                            "INVALID_GROUPING_REQUEST", iae.getMessage()));
                }
            } else {
                // Leaf rows: groupKeys depth == rowGroupCols depth →
                // emit raw rows filtered by every ancestor key. Reuses
                // executeQuery so sort/filter/RLS handling stays unified.
                result = queryEngine.executeQuery(
                        def, scopedAuthz,
                        mergedFilter,
                        safeRequest.sortModel(),
                        page, pageSize);
            }
        } else {
            result = queryEngine.executeQuery(
                    def, scopedAuthz,
                    safeRequest.filterModel(),
                    safeRequest.sortModel(),
                    page, pageSize);
        }

        auditClient.logReportAccess(key, authz.getUserId(), JwtClaimExtractor.extractAuditUsername(jwt));

        // Codex 019e0c99 iter-3 §C: degradation header propagation
        // (multi-level grouped path uses same warning list as flat path).
        return ResponseEntity.ok()
                .headers(com.example.report.query.DegradationHeaders.of(result.warnings()))
                .body(new PagedResultDto<>(
                        result.items(), result.total(), result.page(), result.pageSize()));
    }

    /** PR-0.3 hardening: cap recursion depth so a malicious payload
     *  can't request a 100-deep grouping pipeline. AG Grid SSRM in
     *  practice uses ≤ 4-deep so the cap leaves comfortable headroom. */
    static final int MAX_ROW_GROUP_DEPTH = 8;

    /**
     * PR-0.3 grouping classifier: returns true iff the request expresses
     * a supported multi-level GROUP BY shape — every {@code rowGroupCols}
     * entry references a column registered as {@code groupable}, no
     * pivot, and {@code groupKeys.size <= rowGroupCols.size}. Pivot stays
     * in the rejected bucket until PR-0.4.
     *
     * <p>{@code groupKeys.size == rowGroupCols.size} is supported (it's
     * the leaf-row expansion: the user has drilled all the way down and
     * wants the flat rows under the deepest bucket).
     *
     * <p>PR-0.3 hardening (Codex iter-1 absorb):
     * <ul>
     *   <li>{@code rowGroupCols.size} is capped at {@link #MAX_ROW_GROUP_DEPTH}.</li>
     *   <li>Duplicate {@code rowGroupCols.field} entries are rejected
     *       (a path with two of the same column would either silently
     *       collapse a level or break the ancestor-filter merge).</li>
     *   <li>{@code groupKeys[i] == null} is accepted as an
     *       {@code IS NULL} ancestor key (PR #4 contract, Codex thread
     *       {@code 019e2695}): AG Grid SSRM emits this when the user
     *       expands the "(Blanks)" bucket on a nullable group column.
     *       {@link #mergeAncestorFilters} renders the entry as a
     *       {@code blank} filter so {@link com.example.report.query.FilterTranslator}
     *       pushes it down to {@code WHERE [col] IS NULL}.</li>
     * </ul>
     */
    private static boolean multiLevelGroupRequest(ReportQueryRequestDto req,
                                                   Set<String> groupableFields) {
        if (req == null) return false;
        if (req.rowGroupCols() == null || req.rowGroupCols().isEmpty()) return false;
        if (req.rowGroupCols().size() > MAX_ROW_GROUP_DEPTH) return false;
        if (req.pivotCols() != null && !req.pivotCols().isEmpty()) return false;
        if (Boolean.TRUE.equals(req.pivotMode())) return false;
        // groupKeys.size > rowGroupCols.size is malformed — the client
        // claims to have expanded deeper than the path defines.
        int gkSize = req.groupKeys() != null ? req.groupKeys().size() : 0;
        if (gkSize > req.rowGroupCols().size()) return false;
        // PR #4: null groupKey is no longer rejected here. It is
        // accepted as an IS NULL ancestor expansion and merged as a
        // blank filter by {@link #mergeAncestorFilters}.
        Set<String> seenFields = new java.util.HashSet<>();
        for (var rgc : req.rowGroupCols()) {
            if (rgc == null || rgc.field() == null) return false;
            if (!groupableFields.contains(rgc.field())) return false;
            if (!seenFields.add(rgc.field())) return false; // duplicate path entry
        }
        return true;
    }

    /**
     * Merge AG Grid's expansion {@code groupKeys} into the user's
     * {@code filterModel} as equality filters so the SQL builder
     * narrows to the expanded ancestor path without needing a
     * separate WHERE pathway.
     *
     * <p>{@code rowGroupCols[i].field == groupKeys[i]} is the AG Grid
     * SSRM contract — entry {@code i} of the keys array is the value
     * the user expanded at depth {@code i}. The merged filter shape
     * mirrors {@link com.example.report.query.FilterTranslator}'s
     * existing {@code equals} branch.
     *
     * <p>Hardening contract (PR-0.3 → PR #4 → PR #5b):
     * <ul>
     *   <li>{@code groupKeys[i]} is type-coerced via
     *       {@link ColumnDefinition#type()}: {@code "number"} →
     *       parse double; {@code "date"} → leave as ISO string (the
     *       SQL Server driver accepts ISO-8601). Parse failures
     *       still throw {@link IllegalArgumentException}, which the
     *       controller turns into a structured
     *       {@code 400 INVALID_GROUP_KEY}.</li>
     *   <li>PR #4 (Codex thread {@code 019e2695}): a {@code null}
     *       ancestor key renders as a {@code blank} filter entry,
     *       which {@link com.example.report.query.FilterTranslator}
     *       maps to {@code [col] IS NULL} — AG Grid's "(Blanks)"
     *       expansion case lights up end-to-end without any further
     *       wiring.</li>
     *   <li>PR #5b (same Codex thread): same-field user filter +
     *       ancestor groupKey no longer throws. The merge layer
     *       calls {@link #buildAncestorEntry} for the ancestor
     *       shape, {@link #areSimpleEntriesEquivalent} to skip
     *       semantically redundant predicates (same-equals,
     *       same-blank), and {@link #mergeAsCompoundAnd} to wrap
     *       the rest into an AND compound that
     *       {@link com.example.report.query.FilterTranslator} (PR #5a)
     *       parses into a parenthesised SQL clause. Logically
     *       conflicting predicates (e.g. {@code equals FIN AND
     *       equals HR}) compose into a 0-row SQL result rather
     *       than a fail-fast 400 — Codex Q4 verdict: SQL is the
     *       source of truth for predicate satisfiability.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeAncestorFilters(
            Map<String, Object> userFilter,
            List<ColumnVO> rowGroupCols,
            List<String> groupKeys,
            List<ColumnDefinition> visibleCols) {
        Map<String, Object> merged = userFilter != null
                ? new java.util.HashMap<>(userFilter)
                : new java.util.HashMap<>();
        Map<String, ColumnDefinition> byField = visibleCols.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ColumnDefinition::field, c -> c, (a, b) -> a));
        for (int i = 0; i < groupKeys.size(); i++) {
            ColumnVO col = rowGroupCols.get(i);
            String field = col != null ? col.field() : null;
            String value = groupKeys.get(i);
            if (field == null) continue;

            Map<String, Object> ancestorEntry =
                    buildAncestorEntry(field, value, byField.get(field));

            // PR #5b (Codex thread 019e2695): compound AND merge.
            // Existing semantics flipped — same-field user filter and
            // ancestor groupKey are no longer fatal. The merge layer
            // emits a compound `{operator: "AND", ...}` entry that the
            // FilterTranslator (PR #5a) translates into a parenthesised
            // SQL clause. Pure-duplicate predicates (same-equals,
            // same-blank) are skipped to keep the generated SQL clean.
            if (merged.containsKey(field)) {
                Object existingObj = merged.get(field);
                if (existingObj instanceof Map<?, ?> existingRaw) {
                    Map<String, Object> existing = (Map<String, Object>) existingRaw;
                    if (areSimpleEntriesEquivalent(ancestorEntry, existing)) {
                        // Redundant predicate (ancestor reasserts what
                        // the user filter already says); skip to avoid
                        // `[col] = X AND [col] = X` noise. Codex iter-2
                        // §1 absorb: idempotent skip only on exact
                        // simple equivalence — number/type coercion
                        // mismatches fall through to compound emit.
                        continue;
                    }
                    merged.put(field, mergeAsCompoundAnd(ancestorEntry, existing));
                    continue;
                }
                // Existing entry is not a map (malformed). Defensive
                // fallback: overwrite with the ancestor so the query
                // still runs with the route constraint applied.
            }
            merged.put(field, ancestorEntry);
        }
        return merged;
    }

    /**
     * Build the canonical filter entry shape for a single ancestor
     * groupKey. {@code null} value renders as {@code {type: "blank"}}
     * (FilterTranslator emits {@code IS NULL}); scalar values run
     * through {@link #coerceGroupKey} for type-aware binding.
     */
    private static Map<String, Object> buildAncestorEntry(
            String field, String value, ColumnDefinition cd) {
        if (value == null) {
            return Map.of("type", "blank");
        }
        Object coercedValue = coerceGroupKey(field, value, cd);
        return Map.of("type", "equals", "filter", coercedValue);
    }

    /**
     * PR #5b idempotent-skip predicate. Two filter entries count as
     * semantically equivalent when both are simple (no compound
     * {@code operator}) AND either both are {@code blank} or both are
     * {@code equals} with {@link java.util.Objects#equals} value
     * parity. Anything else returns {@code false} so the merge layer
     * falls through to compound AND emission.
     *
     * <p>Codex iter-2 §1 absorb: the skip must be type-aware, since
     * a numeric ancestor groupKey is coerced to {@code Double} while
     * a user filterModel value can arrive as {@code Integer} from
     * JSON. {@code Double(2024.0)} != {@code Integer(2024)} via
     * {@code Objects.equals}, so the type-coercion ambiguity falls
     * through to compound emit rather than producing a silent skip.
     */
    private static boolean areSimpleEntriesEquivalent(
            Map<String, Object> ancestor, Map<String, Object> existing) {
        if (ancestor == null || existing == null) return false;
        if (ancestor.containsKey("operator") || existing.containsKey("operator")) {
            return false;
        }
        String ancestorType = (String) ancestor.get("type");
        String existingType = (String) existing.get("type");
        if (ancestorType == null || existingType == null) return false;
        if (!ancestorType.equals(existingType)) return false;

        if ("blank".equals(ancestorType)) {
            return true;
        }
        if ("equals".equals(ancestorType)) {
            return java.util.Objects.equals(
                    ancestor.get("filter"), existing.get("filter"));
        }
        // Other simple-filter types (contains, inRange, set, ...) do not
        // claim idempotent equivalence; they fall through to compound
        // emit so the SQL preserves both predicates.
        return false;
    }

    /**
     * PR #5b compound merge. Wraps the ancestor entry and the existing
     * user-filter entry into a single AND compound that
     * {@link com.example.report.query.FilterTranslator} can parse via
     * its PR #5a recursive shape.
     *
     * <p>When the existing entry is already an {@code AND} compound,
     * the ancestor is flattened into the {@code conditions[]} list so
     * the resulting predicate stays {@code (ancestor AND a AND b)}
     * rather than {@code (ancestor AND (a AND b))} — equivalent SQL
     * but easier to read in logs.
     *
     * <p>When the existing entry is an {@code OR} compound, an outer
     * {@code AND} wraps it intact, preserving the OR precedence:
     * {@code (ancestor AND (a OR b))}.
     *
     * <p>Simple-filter or unrecognised existing entries always go
     * through a fresh outer AND.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeAsCompoundAnd(
            Map<String, Object> ancestorEntry,
            Map<String, Object> existing) {
        Object opObj = existing.get("operator");
        if (opObj instanceof String opStr && "AND".equalsIgnoreCase(opStr.trim())) {
            // Flatten into existing AND.
            List<Map<String, Object>> flattened = new java.util.ArrayList<>();
            flattened.add(ancestorEntry);
            Object conditionsObj = existing.get("conditions");
            if (conditionsObj instanceof List<?> list) {
                for (Object child : list) {
                    if (child instanceof Map<?, ?> childMap) {
                        flattened.add((Map<String, Object>) childMap);
                    }
                }
            } else {
                Object c1 = existing.get("condition1");
                Object c2 = existing.get("condition2");
                if (c1 instanceof Map<?, ?> c1Map) flattened.add((Map<String, Object>) c1Map);
                if (c2 instanceof Map<?, ?> c2Map) flattened.add((Map<String, Object>) c2Map);
            }
            return Map.of(
                    "operator", "AND",
                    "conditions", flattened);
        }
        // Existing is simple, OR-compound, or unknown — nest under a
        // fresh AND wrapper so the user's predicate keeps its shape.
        return Map.of(
                "operator", "AND",
                "condition1", ancestorEntry,
                "condition2", existing);
    }

    /**
     * Type-coerce a single AG Grid SSRM {@code groupKeys} entry based on
     * the registry's column type. Failures throw
     * {@link IllegalArgumentException} so the controller can emit a
     * structured 400 rather than letting SQL Server's implicit
     * conversion silently swallow the error.
     */
    private static Object coerceGroupKey(String field, String rawValue, ColumnDefinition cd) {
        if (cd == null || cd.type() == null) return rawValue;
        String t = cd.type().toLowerCase();
        try {
            return switch (t) {
                case "number" -> {
                    // Some columns store integral, others decimal — Double
                    // accepts both and FilterTranslator binds it as Object,
                    // so SQL Server picks the right comparison path.
                    yield Double.valueOf(rawValue);
                }
                case "date", "datetime" -> rawValue; // ISO-8601 string left as-is
                default -> rawValue;
            };
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException(
                    "groupKey value for column '" + field + "' (type=" + t
                            + ") could not be parsed: '" + rawValue + "'");
        }
    }

    // PR #5b (Codex thread 019e2695): AncestorFilterCollisionException
    // removed. Same-field ancestor + user filter is no longer a fatal
    // condition; mergeAncestorFilters emits a compound AND entry that
    // FilterTranslator (PR #5a) parses into a parenthesised SQL clause.

    /**
     * Project AG Grid's {@code valueCols} payload onto the registry's
     * {@code aggregatable} allow-list and the column registry's default
     * aggregation function. Throws {@link IllegalArgumentException} when
     * any entry references a non-aggregatable field or an unknown
     * aggregation function so the controller can surface a structured
     * {@code 400 INVALID_AGGREGATION_REQUEST} (PR-0.2 hardening — Codex
     * iter-1 absorb: invalid entries no longer silently drop).
     *
     * <p>{@code aggFunc} fallback when the request omits it:
     * <ul>
     *   <li>Column registry's {@code defaultAggFunc} when set.</li>
     *   <li>Otherwise {@code sum} when {@code type=number}.</li>
     *   <li>Otherwise {@code count}.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static List<SqlBuilder.GroupedAggregation> sanitizeAggregations(
            List<ColumnVO> valueCols,
            Set<String> aggregatableFields,
            List<ColumnDefinition> visibleCols) {
        if (valueCols == null || valueCols.isEmpty()) return List.of();
        Map<String, ColumnDefinition> byField = visibleCols.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ColumnDefinition::field, c -> c, (a, b) -> a));
        List<SqlBuilder.GroupedAggregation> out = new java.util.ArrayList<>();
        // PR #6b (Codex 019e2695): track field-level duplicates so two
        // value-cols on the same field (e.g. sum+median on AMOUNT) are
        // rejected up front. The external alias contract reserves one
        // response column per field; supporting multi-agg per field
        // would require an alias-suffix scheme that PR #6b intentionally
        // does not introduce.
        java.util.Set<String> seenFields = new java.util.HashSet<>();
        for (ColumnVO vc : valueCols) {
            if (vc == null || vc.field() == null) {
                throw new IllegalArgumentException(
                        "valueCols entry must have a field");
            }
            if (!aggregatableFields.contains(vc.field())) {
                throw new IllegalArgumentException(
                        "valueCols field is not aggregatable: " + vc.field());
            }
            if (!seenFields.add(vc.field())) {
                throw new IllegalArgumentException(
                        "valueCols field is duplicated: " + vc.field()
                                + " — only one aggregation per field is supported");
            }
            ColumnDefinition cd = byField.get(vc.field());
            String func = vc.aggFunc();
            if (func == null || func.isBlank()) {
                if (cd != null && cd.defaultAggFunc() != null) {
                    func = cd.defaultAggFunc();
                } else if (cd != null && "number".equalsIgnoreCase(cd.type())) {
                    func = "sum";
                } else {
                    func = "count";
                }
            }
            // GroupedAggregation constructor validates against
            // ALLOWED_AGG_FUNCS and throws IllegalArgumentException
            // — propagated unchanged so the caller turns it into a 400.
            Map<String, Object> params = sanitizeAggParams(vc, cd, func);
            SqlBuilder.GroupedAggregation agg =
                    new SqlBuilder.GroupedAggregation(vc.field(), func, params);

            // PR #6a + PR #6b (Codex thread 019e2695): median and
            // percentilecont are only valid on numeric columns. SQL
            // Server PERCENTILE_CONT requires a numeric ordering
            // column; running it on a text/date field would either
            // fail at execution or coerce silently. Catch the
            // mismatch here so the client sees a structured 400
            // INVALID_AGGREGATION_REQUEST rather than a database
            // error.
            if ("median".equals(agg.func()) || "percentilecont".equals(agg.func())) {
                if (cd == null || cd.type() == null
                        || !"number".equalsIgnoreCase(cd.type())) {
                    throw new IllegalArgumentException(
                            agg.func() + " aggregation is only valid on numeric "
                                    + "columns; got field '" + vc.field()
                                    + "' with type '"
                                    + (cd != null ? cd.type() : "?") + "'");
                }
            }

            out.add(agg);
        }
        return out;
    }

    /**
     * PR #6b: extract and validate the {@code aggParams} payload for a
     * single value column. The fallback order is request → registry
     * default, mirroring {@code aggFunc} resolution; the validation
     * itself depends on the resolved {@code func} so it lives here
     * rather than on {@code GroupedAggregation}.
     *
     * <ul>
     *   <li>{@code percentilecont}: {@code params.percentile} required;
     *       must be a {@code Number} in {@code [0, 1]} inclusive.</li>
     *   <li>Other parametric funcs land here in future PRs (weightedAvg)
     *       — for now the helper falls through to {@code null}.</li>
     *   <li>Non-parametric funcs (sum/avg/min/.../median) with a
     *       populated {@code params} map are rejected rather than
     *       silently ignored, per Codex iter-7 guidance ("non-percentile
     *       funcs + non-empty params → reject").</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeAggParams(
            ColumnVO vc, ColumnDefinition cd, String func) {
        Map<String, Object> params = vc.aggParams();
        // Codex 019e2695 iter-8 absorb: registry defaultAggParams
        // fallback applies ONLY when (a) the request did not supply
        // its own aggParams, (b) the resolved func is `percentilecont`,
        // AND (c) the registry's defaultAggFunc is also `percentilecont`
        // — otherwise an explicit request `aggFunc=sum` would pick up
        // a stray percentile default from the registry and fail the
        // "non-parametric + populated params" guard further down.
        if ((params == null || params.isEmpty())
                && "percentilecont".equals(func)
                && cd != null
                && "percentilecont".equals(cd.defaultAggFunc())
                && cd.defaultAggParams() != null) {
            params = cd.defaultAggParams();
        }
        if (params == null || params.isEmpty()) {
            params = null;
        }
        if ("percentilecont".equals(func)) {
            if (params == null || !params.containsKey("percentile")) {
                throw new IllegalArgumentException(
                        "percentilecont aggregation requires aggParams.percentile "
                                + "(double in [0,1]) on field '" + vc.field() + "'");
            }
            Object raw = params.get("percentile");
            if (!(raw instanceof Number n)) {
                throw new IllegalArgumentException(
                        "percentilecont aggParams.percentile must be a number, got: " + raw);
            }
            double p = n.doubleValue();
            if (!Double.isFinite(p) || p < 0.0 || p > 1.0) {
                throw new IllegalArgumentException(
                        "percentilecont aggParams.percentile must be in [0, 1], got: " + p);
            }
            return Map.of("percentile", p);
        }
        // Non-parametric agg + populated params → reject so config
        // mistakes surface loudly rather than being silently dropped.
        if (params != null) {
            throw new IllegalArgumentException(
                    "aggParams is only supported for percentilecont; "
                            + "got '" + func + "' on field '" + vc.field() + "'");
        }
        return null;
    }

    /**
     * Translate AG Grid SSRM {@code startRow / endRow} indices into the
     * 1-based {@code page / pageSize} pair used by {@link QueryEngine}.
     *
     * <p>Defaults to page=1 / pageSize=50 when the indices are absent so
     * SSRM clients that omit the cache window (e.g. tests, ad-hoc curl)
     * still get a deterministic response. PageSize is clamped to the same
     * {@code [1, 500]} window enforced by GET {@code /data}.
     *
     * <p>PR-0.1 hardening: the helper fails closed when the window is
     * malformed or misaligned so {@link com.example.report.query.SqlBuilder}
     * never receives a page number whose {@code (page - 1) * pageSize}
     * differs from the {@code startRow} the client requested. AG Grid SSRM
     * cache windows are guaranteed-aligned in practice; a misaligned
     * payload is almost always a hand-crafted curl that would otherwise
     * silently get wrong rows.
     *
     * @throws PagingException with {@code code=INVALID_ROW_WINDOW} when
     *         {@code endRow <= startRow}, or {@code NON_ALIGNED_ROW_WINDOW}
     *         when {@code startRow} is not a multiple of the derived page
     *         size.
     */
    static int[] computePaging(Integer startRow, Integer endRow) {
        int s = startRow != null ? Math.max(startRow, 0) : 0;
        int e = endRow != null ? endRow : s + 50;
        if (e <= s) {
            throw new PagingException("INVALID_ROW_WINDOW",
                    "endRow must be strictly greater than startRow (got "
                            + "startRow=" + s + ", endRow=" + e + ")");
        }
        int size = Math.min(e - s, 500);
        if (s % size != 0) {
            throw new PagingException("NON_ALIGNED_ROW_WINDOW",
                    "startRow must be a multiple of (endRow - startRow) so "
                            + "the SQL OFFSET matches the requested startRow "
                            + "(got startRow=" + s + ", pageSize=" + size + ")");
        }
        return new int[]{(s / size) + 1, size};
    }

    /**
     * Internal sentinel for {@link #computePaging} validation failures.
     * Carries the structured error code so the caller can populate
     * {@link ReportQueryErrorDto} without string parsing.
     */
    static final class PagingException extends RuntimeException {
        final String code;

        PagingException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private ReportDefinition findReportOrThrow(String key) {
        return registry.get(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found: " + key));
    }

    private AuthzMeResponse resolveAndCheckAccess(ReportDefinition def, Jwt jwt) {
        AuthzMeResponse authz = permissionClient.getAuthzMe(jwt);
        ReportAccessEvaluator.AccessResult result = accessEvaluator.evaluate(def, authz);

        if (result != ReportAccessEvaluator.AccessResult.ALLOWED) {
            auditClient.logReportAccessDenied(def.key(),
                    authz != null ? authz.getUserId() : "unknown",
                    JwtClaimExtractor.extractAuditUsername(jwt),
                    result.name());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, result.name());
        }
        return authz;
    }

    /* ---- Authorization helpers (CNS-006 R16/R17) ---- */

    private void requireReportView(AuthzMeResponse authz, Jwt jwt) {
        if (authz == null || (!authz.isSuperAdmin() && !authz.hasPermission("REPORT_VIEW"))) {
            auditClient.logReportAccessDenied("custom:*",
                    authz != null ? authz.getUserId() : "unknown",
                    JwtClaimExtractor.extractAuditUsername(jwt), "DENIED_NO_REPORT_VIEW");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DENIED_NO_REPORT_VIEW");
        }
    }

    private void requireReportManage(AuthzMeResponse authz, Jwt jwt, String action) {
        if (authz == null || (!authz.isSuperAdmin() && !authz.hasPermission("REPORT_MANAGE"))) {
            auditClient.logReportAccessDenied("custom:" + action,
                    authz != null ? authz.getUserId() : "unknown",
                    JwtClaimExtractor.extractAuditUsername(jwt), "DENIED_NO_REPORT_MANAGE");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DENIED_NO_REPORT_MANAGE");
        }
    }

    private void requireReportManageOrOwner(AuthzMeResponse authz, Jwt jwt, String key, String action) {
        if (authz != null && authz.isSuperAdmin()) {
            return;
        }
        if (authz != null && authz.hasPermission("REPORT_MANAGE")) {
            return;
        }
        // Fallback: owner can modify their own reports
        String username = jwt != null ? jwt.getClaimAsString("preferred_username") : null;
        if (username != null) {
            Optional<Map<String, Object>> existing = customReportRepository.findByKey(key);
            if (existing.isPresent() && username.equals(existing.get().get("createdBy"))) {
                return;
            }
        }
        auditClient.logReportAccessDenied("custom:" + key + ":" + action,
                authz != null ? authz.getUserId() : "unknown",
                JwtClaimExtractor.extractAuditUsername(jwt), "DENIED_NOT_OWNER_OR_MANAGE");
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "DENIED_NOT_OWNER_OR_MANAGE");
    }

    /**
     * Evaluate access to a custom report based on its access_config.reportGroup field.
     * CNS-006 R17: deny-default when reportGroup is set and user doesn't have ALLOW grant.
     */
    @SuppressWarnings("unchecked")
    private boolean evaluateCustomReportAccess(Map<String, Object> row, AuthzMeResponse authz) {
        if (authz.isSuperAdmin()) {
            return true;
        }
        if (!authz.hasPermission("REPORT_VIEW")) {
            return false;
        }
        Object accessConfigObj = row.get("accessConfig");
        if (accessConfigObj instanceof Map<?, ?> accessConfig) {
            Object reportGroup = accessConfig.get("reportGroup");
            if (reportGroup instanceof String group && !group.isBlank()) {
                return authz.canViewReport(group);
            }
        }
        // No reportGroup in access_config → allow if user has REPORT_VIEW (backwards compat)
        return true;
    }

    @SuppressWarnings("unchecked")
    private String extractReportGroup(Map<String, Object> row) {
        Object accessConfigObj = row.get("accessConfig");
        if (accessConfigObj instanceof Map<?, ?> accessConfig) {
            Object group = accessConfig.get("reportGroup");
            return group instanceof String s ? s : null;
        }
        return null;
    }

    private <T> T parseJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("Failed to parse JSON parameter: {}", e.getMessage());
            return null;
        }
    }

}
