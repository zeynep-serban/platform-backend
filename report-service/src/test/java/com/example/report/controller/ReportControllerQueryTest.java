package com.example.report.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.report.access.ColumnFilter;
import com.example.report.access.ReportAccessEvaluator;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.CompanyHeaderScopeNarrower;
import com.example.report.authz.PermissionResolver;
import com.example.report.dto.ColumnVO;
import com.example.report.dto.PagedResultDto;
import com.example.report.dto.ReportCapabilitiesDto;
import com.example.report.dto.ReportMetadataDto;
import com.example.report.dto.ReportQueryErrorDto;
import com.example.report.dto.ReportQueryRequestDto;
import com.example.report.query.QueryEngine;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.repository.CustomReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/**
 * PR-0.1 (reporting platform hardening plan, 2026-05) — coverage for the new
 * AG Grid SSRM-compatible {@code POST /api/v1/reports/{key}/query} endpoint
 * and the capability flag exposed on {@code GET /metadata}.
 *
 * <p>Five blocks:
 * <ul>
 *   <li>{@code QueryEndpoint} — happy path, capability gate (rejected
 *       grouping payloads), 403 / 404 paths and pagination translation.</li>
 *   <li>{@code MetadataCapabilities} — verifies the new
 *       {@code capabilities.serverSideGrouping=false} field is populated.</li>
 *   <li>{@code Paging} — unit tests on
 *       {@link ReportController#computePaging(Integer, Integer)} including
 *       fail-closed guards on misaligned windows.</li>
 *   <li>{@code ErrorBody} — verifies the structured
 *       {@link ReportQueryErrorDto} is returned (PR-0.1 hardening).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportControllerQueryTest {

    @Mock private PermissionResolver permissionResolver;
    @Mock private CustomReportRepository customReportRepository;
    @Mock private ReportRegistry registry;
    @Mock private QueryEngine queryEngine;
    @Mock private ColumnFilter columnFilter;
    @Mock private ReportAuditClient auditClient;

    private ReportController controller;

    @BeforeEach
    void setUp() {
        controller = new ReportController(
                registry,
                customReportRepository,
                permissionResolver,
                new ReportAccessEvaluator(),
                columnFilter,
                queryEngine,
                auditClient,
                new ObjectMapper(),
                new CompanyHeaderScopeNarrower());
    }

    @Nested
    class QueryEndpoint {

        @Test
        void nullBody_returnsFlatPagedDataWithDefaults() {
            // Empty body → request becomes the default empty DTO →
            // pagination defaults to page=1 / pageSize=50, no grouping.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(queryEngine.executeQuery(any(), any(), any(), any(), eq(1), eq(50)))
                    .thenReturn(new QueryEngine.PagedData(List.of(Map.of("col1", "v1")), 1L, 1, 50));

            var response = controller.queryReport("any", null, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());
            // 200 path returns PagedResultDto; assertInstanceOf carries
            // the pattern-typed body so we can read items().size().
            PagedResultDto<?> body = assertInstanceOf(PagedResultDto.class, response.getBody());
            assertEquals(1, body.items().size());
            verify(queryEngine).executeQuery(any(), any(), any(), any(), eq(1), eq(50));
        }

        @Test
        void rowGroupColsPresent_returns400StructuredError() {
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));

            var grouping = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("col1", "Col 1", "col1", null)),
                    null, null, false, null, null, null);

            var response = controller.queryReport("any", grouping, null, testJwt("admin"));

            assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
            ReportQueryErrorDto error = assertInstanceOf(
                    ReportQueryErrorDto.class, response.getBody());
            assertEquals("GROUPING_NOT_SUPPORTED", error.code(),
                    "Frontend branches on body.code; must be the documented constant");
            assertNotNull(error.message());
            // Capability gate must short-circuit before the DB call to
            // avoid wasting a query that would silently return flat rows.
            verify(queryEngine, never()).executeQuery(any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        void valueColsPresent_returns400StructuredError() {
            // PR-0.1 hardening: aggregation requests fail closed too —
            // silently ignoring valueCols would return raw rows under a
            // "I want sums" payload, which is worse than a clean 400.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));

            var aggregation = new ReportQueryRequestDto(
                    0, 50, null,
                    List.of(new ColumnVO("amount", "Amount", "amount", "sum")),
                    null, false, null, null, null);

            var response = controller.queryReport("any", aggregation, null, testJwt("admin"));

            assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
            assertEquals("GROUPING_NOT_SUPPORTED",
                    assertInstanceOf(ReportQueryErrorDto.class, response.getBody()).code());
        }

        @Test
        void pivotModeTrue_returns400StructuredError() {
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));

            var pivot = new ReportQueryRequestDto(
                    0, 50, null, null,
                    List.of(new ColumnVO("col1", "Col 1", "col1", null)),
                    true, null, null, null);

            var response = controller.queryReport("any", pivot, null, testJwt("admin"));
            assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
            assertInstanceOf(ReportQueryErrorDto.class, response.getBody());
        }

        @Test
        void groupKeysPresent_returns400StructuredError() {
            // groupKeys non-empty implies the client expanded a node — only
            // makes sense when grouping is on. Reject for parity with
            // rowGroupCols guard.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));

            var expansion = new ReportQueryRequestDto(
                    0, 50, null, null, null, false,
                    List.of("Finance"), null, null);

            var response = controller.queryReport("any", expansion, null, testJwt("admin"));
            assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
            assertInstanceOf(ReportQueryErrorDto.class, response.getBody());
        }

        @Test
        void noReportView_returns403() {
            // No REPORT_VIEW permission and not super-admin → 403 must come
            // from the access evaluator before the DTO is even inspected.
            stubAuthz(false, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.queryReport("any", null, null, testJwt("user1")));
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }

        @Test
        void nonExistentReport_returns404() {
            stubAuthz(true, List.of());
            when(registry.get("ghost")).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> controller.queryReport("ghost", null, null, testJwt("admin")));
            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }

        @Test
        void startRowEndRow_translateToPageAndPageSize() {
            // startRow=100, endRow=200 → pageSize=100, page=2
            // (this is the path AG Grid SSRM uses by default; 100 is a
            // multiple of 100 so the alignment guard passes).
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(queryEngine.executeQuery(any(), any(), any(), any(), eq(2), eq(100)))
                    .thenReturn(new QueryEngine.PagedData(List.of(), 0L, 2, 100));

            var dto = new ReportQueryRequestDto(
                    100, 200, null, null, null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());
            verify(queryEngine).executeQuery(any(), any(), any(), any(), eq(2), eq(100));
        }

        @Test
        void misalignedWindow_returns400NonAlignedCode() {
            // PR-0.1 hardening: 75/125 produces pageSize=50, but 75 is
            // not a multiple of 50 → SQL OFFSET would be 50, mismatching
            // the requested startRow. Fail closed.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));

            var dto = new ReportQueryRequestDto(
                    75, 125, null, null, null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
            ReportQueryErrorDto error = assertInstanceOf(
                    ReportQueryErrorDto.class, response.getBody());
            assertEquals("NON_ALIGNED_ROW_WINDOW", error.code());
            verify(queryEngine, never()).executeQuery(any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        void zeroWindow_returns400InvalidWindowCode() {
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));

            var dto = new ReportQueryRequestDto(
                    50, 50, null, null, null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
            ReportQueryErrorDto error = assertInstanceOf(
                    ReportQueryErrorDto.class, response.getBody());
            assertEquals("INVALID_ROW_WINDOW", error.code());
        }
    }

    @Nested
    class GroupingHappyPath {

        @Test
        void singleLevelGroupBy_dispatchesToExecuteGroupedQuery() {
            // PR-0.2: when capability is enabled (column.groupable=true)
            // and the request is exactly a single-level GROUP BY, the
            // controller routes to QueryEngine.executeGroupedQuery.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, "sum")));
            when(queryEngine.executeGroupedQuery(
                    any(), any(), eq("category"), any(), any(), any(), eq(1), eq(50)))
                    .thenReturn(new QueryEngine.PagedData(
                            List.of(Map.of("category", "FIN", "_rowCount", 42L,
                                    "amount", 1234.56)),
                            1L, 1, 50));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "sum")),
                    null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());
            PagedResultDto<?> body = assertInstanceOf(PagedResultDto.class, response.getBody());
            assertEquals(1, body.items().size());
            verify(queryEngine).executeGroupedQuery(
                    any(), any(), eq("category"), any(), any(), any(), eq(1), eq(50));
            verify(queryEngine, never())
                    .executeQuery(any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        void multiLevelGroupBy_dispatchesToFirstLevel() {
            // PR-0.3: multi-level rowGroupCols. With no groupKeys, the
            // controller groups on rowGroupCols[0] (root level).
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("region", "Region", "text",
                                    150, false, true, false, null)));
            when(queryEngine.executeGroupedQuery(any(), any(), eq("category"),
                    any(), any(), any(), eq(1), eq(50)))
                    .thenReturn(new QueryEngine.PagedData(
                            List.of(Map.of("category", "FIN", "_rowCount", 12L)),
                            5L, 1, 50));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(
                            new ColumnVO("category", "Category", "category", null),
                            new ColumnVO("region", "Region", "region", null)),
                    null, null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());
            verify(queryEngine).executeGroupedQuery(any(), any(), eq("category"),
                    any(), any(), any(), eq(1), eq(50));
        }

        @Test
        void groupKeysExpansion_dispatchesToNextLevel() {
            // PR-0.3 expansion: groupKeys=["FIN"] means the user clicked
            // open the FIN bucket; we group by rowGroupCols[1] now and
            // filter rowGroupCols[0] = "FIN".
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("region", "Region", "text",
                                    150, false, true, false, null)));

            // Capture the merged filter so we can prove the ancestor
            // equality entry was injected.
            org.mockito.ArgumentCaptor<Map<String, Object>> filterCaptor =
                    org.mockito.ArgumentCaptor.forClass(Map.class);
            when(queryEngine.executeGroupedQuery(any(), any(), eq("region"),
                    any(), filterCaptor.capture(), any(), eq(1), eq(50)))
                    .thenReturn(new QueryEngine.PagedData(List.of(), 0L, 1, 50));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(
                            new ColumnVO("category", "Category", "category", null),
                            new ColumnVO("region", "Region", "region", null)),
                    null, null, false,
                    List.of("FIN"), null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());
            // The merged filter must contain category=FIN as an equality
            // entry so the SQL WHERE narrows under that ancestor bucket.
            Map<String, Object> merged = filterCaptor.getValue();
            assertNotNull(merged);
            assertTrue(merged.containsKey("category"));
            @SuppressWarnings("unchecked")
            Map<String, Object> categoryFilter = (Map<String, Object>) merged.get("category");
            assertEquals("equals", categoryFilter.get("type"));
            assertEquals("FIN", categoryFilter.get("filter"));
        }

        @Test
        void leafExpansion_returnsFlatRowsViaExecuteQuery() {
            // PR-0.3 leaf case: groupKeys.size == rowGroupCols.size →
            // user has drilled all the way down; emit flat rows for that
            // bucket via executeQuery (not executeGroupedQuery).
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("region", "Region", "text",
                                    150, false, true, false, null)));
            when(queryEngine.executeQuery(any(), any(), any(), any(),
                    eq(1), eq(50)))
                    .thenReturn(new QueryEngine.PagedData(
                            List.of(Map.of("amount", 1234), Map.of("amount", 5678)),
                            2L, 1, 50));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(
                            new ColumnVO("category", "Category", "category", null),
                            new ColumnVO("region", "Region", "region", null)),
                    null, null, false,
                    List.of("FIN", "EU"), null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());
            // Leaf path uses executeQuery (flat) — never executeGroupedQuery.
            verify(queryEngine).executeQuery(any(), any(), any(), any(),
                    eq(1), eq(50));
            verify(queryEngine, never()).executeGroupedQuery(
                    any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        void groupKeysDeeperThanPath_returns400() {
            // Malformed: groupKeys.size > rowGroupCols.size means the
            // client claims to have expanded deeper than the path defines.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null)));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    null, null, false,
                    List.of("FIN", "EU"), null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            assertEquals("GROUPING_NOT_SUPPORTED",
                    assertInstanceOf(ReportQueryErrorDto.class, response.getBody()).code());
        }

        @Test
        void leafExpansion_invalidValueCols_returns400() {
            // PR-0.3 Codex iter-1 absorb: leaf path used to skip
            // sanitizeAggregations because executeQuery was called
            // directly. Invalid valueCols (non-aggregatable / unknown
            // aggFunc) now fail closed even on the leaf path.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            // amount NOT aggregatable
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, false, null)));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "sum")),
                    null, false,
                    List.of("FIN"), null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            assertEquals("INVALID_AGGREGATION_REQUEST",
                    assertInstanceOf(ReportQueryErrorDto.class, response.getBody()).code());
            verify(queryEngine, never()).executeQuery(
                    any(), any(), any(), any(), anyInt(), anyInt());
            verify(queryEngine, never()).executeGroupedQuery(
                    any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        void groupKeysWithNullEntry_acceptedAsBlankFilterMerge() {
            // PR #4 contract (Codex thread 019e2695, supersedes PR-0.3
            // null reject): AG Grid SSRM emits `groupKeys=[null]` when
            // the user expands the "(Blanks)" bucket on a nullable
            // group column. The merge layer now injects a blank filter
            // on the ancestor column rather than failing the request
            // closed. FilterTranslator's existing `blank` semantics
            // (IS NULL) push the bucket constraint into SQL.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("region", "Region", "text",
                                    150, false, true, false, null)));
            when(queryEngine.executeGroupedQuery(any(), any(), any(), any(),
                    any(), any(), anyInt(), anyInt()))
                    .thenReturn(new com.example.report.query.QueryEngine.PagedData(
                            java.util.List.of(), 0, 1, 50, java.util.List.of()));

            var groupKeys = new java.util.ArrayList<String>();
            groupKeys.add(null);

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(
                            new ColumnVO("category", "Category", "category", null),
                            new ColumnVO("region", "Region", "region", null)),
                    null, null, false,
                    groupKeys, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<java.util.Map<String, Object>> filterCaptor =
                    (org.mockito.ArgumentCaptor<java.util.Map<String, Object>>)
                            (org.mockito.ArgumentCaptor<?>)
                                    org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(queryEngine).executeGroupedQuery(any(), any(), any(), any(),
                    filterCaptor.capture(), any(), anyInt(), anyInt());
            var mergedFilter = filterCaptor.getValue();

            // Null ancestor key → blank filter on the matching column.
            assertNotNull(mergedFilter.get("category"),
                    "category column must carry the merged ancestor filter");
            @SuppressWarnings("unchecked")
            var categoryFilter = (java.util.Map<String, Object>) mergedFilter.get("category");
            assertEquals("blank", categoryFilter.get("type"),
                    "null ancestor groupKey must merge as a blank filter, "
                            + "not an equals/null filter");
            assertFalse(categoryFilter.containsKey("filter"),
                    "blank filter must not carry a literal value");
        }

        @Test
        void groupKeysNullWithUserContainsFilter_compoundAndMerge() {
            // PR #5b (supersedes PR #4 collision behaviour): null
            // ancestor key + a user contains filter on the same column
            // is no longer a 400. The merge layer now produces a
            // compound AND wrapping the blank ancestor and the user
            // predicate so the SQL becomes
            // `([col] IS NULL AND [col] LIKE :pX)` — which evaluates
            // to an empty result set on its own, but composes
            // correctly with the rest of the multi-level grouping
            // path. (Empty result is the correct behaviour per Codex
            // Q4 verdict — backend doesn't introspect logical
            // satisfiability.)
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("region", "Region", "text",
                                    150, false, true, false, null)));
            when(queryEngine.executeGroupedQuery(any(), any(), any(), any(),
                    any(), any(), anyInt(), anyInt()))
                    .thenReturn(new com.example.report.query.QueryEngine.PagedData(
                            java.util.List.of(), 0, 1, 50, java.util.List.of()));

            var groupKeys = new java.util.ArrayList<String>();
            groupKeys.add(null);

            Map<String, Object> userFilter = new java.util.HashMap<>();
            userFilter.put("category", Map.of("type", "contains", "filter", "FI"));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(
                            new ColumnVO("category", "Category", "category", null),
                            new ColumnVO("region", "Region", "region", null)),
                    null, null, false,
                    groupKeys, userFilter, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<java.util.Map<String, Object>> filterCaptor =
                    (org.mockito.ArgumentCaptor<java.util.Map<String, Object>>)
                            (org.mockito.ArgumentCaptor<?>)
                                    org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(queryEngine).executeGroupedQuery(any(), any(), any(), any(),
                    filterCaptor.capture(), any(), anyInt(), anyInt());
            var mergedFilter = filterCaptor.getValue();

            @SuppressWarnings("unchecked")
            var categoryEntry = (java.util.Map<String, Object>) mergedFilter.get("category");
            assertEquals("AND", categoryEntry.get("operator"),
                    "null ancestor + user contains → compound AND");
            @SuppressWarnings("unchecked")
            var c1 = (java.util.Map<String, Object>) categoryEntry.get("condition1");
            assertEquals("blank", c1.get("type"),
                    "null ancestor renders as blank (IS NULL) in condition1");
            @SuppressWarnings("unchecked")
            var c2 = (java.util.Map<String, Object>) categoryEntry.get("condition2");
            assertEquals("contains", c2.get("type"));
            assertEquals("FI", c2.get("filter"));
        }

        @Test
        void ancestorEqualsUserContains_compoundAndQueryExecution() {
            // PR #5b (Codex thread 019e2695, supersedes PR-0.3 collision):
            // same-field ancestor + user filter is no longer fatal. The
            // merge layer now emits a compound `{operator: "AND", ...}`
            // entry that FilterTranslator (PR #5a) renders into a
            // parenthesised SQL clause `([col] = :p1 AND [col] LIKE :p2)`.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("region", "Region", "text",
                                    150, false, true, false, null)));
            when(queryEngine.executeGroupedQuery(any(), any(), any(), any(),
                    any(), any(), anyInt(), anyInt()))
                    .thenReturn(new com.example.report.query.QueryEngine.PagedData(
                            java.util.List.of(), 0, 1, 50, java.util.List.of()));

            // User's existing filterModel: category contains "FI"
            Map<String, Object> userFilter = new java.util.HashMap<>();
            userFilter.put("category", Map.of("type", "contains", "filter", "FI"));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(
                            new ColumnVO("category", "Category", "category", null),
                            new ColumnVO("region", "Region", "region", null)),
                    null, null, false,
                    List.of("FINANCE"), userFilter, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            // Query now succeeds; compound AND replaces the 400 collision.
            assertEquals(200, response.getStatusCode().value());

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<java.util.Map<String, Object>> filterCaptor =
                    (org.mockito.ArgumentCaptor<java.util.Map<String, Object>>)
                            (org.mockito.ArgumentCaptor<?>)
                                    org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(queryEngine).executeGroupedQuery(any(), any(), any(), any(),
                    filterCaptor.capture(), any(), anyInt(), anyInt());
            var mergedFilter = filterCaptor.getValue();

            @SuppressWarnings("unchecked")
            var categoryEntry = (java.util.Map<String, Object>) mergedFilter.get("category");
            assertEquals("AND", categoryEntry.get("operator"),
                    "same-field ancestor + user filter must merge as AND compound");
            assertNotNull(categoryEntry.get("condition1"),
                    "ancestor entry should land in condition1");
            assertNotNull(categoryEntry.get("condition2"),
                    "user filter should land in condition2");
            @SuppressWarnings("unchecked")
            var c1 = (java.util.Map<String, Object>) categoryEntry.get("condition1");
            @SuppressWarnings("unchecked")
            var c2 = (java.util.Map<String, Object>) categoryEntry.get("condition2");
            assertEquals("equals", c1.get("type"),
                    "ancestor groupKey FINANCE → equals predicate");
            assertEquals("FINANCE", c1.get("filter"));
            assertEquals("contains", c2.get("type"),
                    "existing user filter shape preserved in condition2");
            assertEquals("FI", c2.get("filter"));
        }

        @Test
        void ancestorEqualsUserEqualsSameValue_idempotentSkip() {
            // PR #5b idempotent skip: ancestor `equals X` + user `equals X`
            // produces no compound (both predicates are semantically the
            // same). The merged filter keeps the user's entry intact —
            // ancestor is the redundant one, so the SQL stays clean
            // (`[col] = X` instead of `[col] = X AND [col] = X`).
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("region", "Region", "text",
                                    150, false, true, false, null)));
            when(queryEngine.executeGroupedQuery(any(), any(), any(), any(),
                    any(), any(), anyInt(), anyInt()))
                    .thenReturn(new com.example.report.query.QueryEngine.PagedData(
                            java.util.List.of(), 0, 1, 50, java.util.List.of()));

            Map<String, Object> userFilter = new java.util.HashMap<>();
            userFilter.put("category", Map.of("type", "equals", "filter", "FINANCE"));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(
                            new ColumnVO("category", "Category", "category", null),
                            new ColumnVO("region", "Region", "region", null)),
                    null, null, false,
                    List.of("FINANCE"), userFilter, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<java.util.Map<String, Object>> filterCaptor =
                    (org.mockito.ArgumentCaptor<java.util.Map<String, Object>>)
                            (org.mockito.ArgumentCaptor<?>)
                                    org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(queryEngine).executeGroupedQuery(any(), any(), any(), any(),
                    filterCaptor.capture(), any(), anyInt(), anyInt());
            var mergedFilter = filterCaptor.getValue();

            @SuppressWarnings("unchecked")
            var categoryEntry = (java.util.Map<String, Object>) mergedFilter.get("category");
            assertEquals("equals", categoryEntry.get("type"),
                    "idempotent skip keeps the simple equals shape — no compound wrapper");
            assertFalse(categoryEntry.containsKey("operator"),
                    "no compound AND emitted for redundant predicates");
            assertEquals("FINANCE", categoryEntry.get("filter"));
        }

        @Test
        void ancestorBlankUserBlank_idempotentSkip() {
            // Both predicates collapse to IS NULL — idempotent skip
            // keeps the user's `blank` entry intact.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("region", "Region", "text",
                                    150, false, true, false, null)));
            when(queryEngine.executeGroupedQuery(any(), any(), any(), any(),
                    any(), any(), anyInt(), anyInt()))
                    .thenReturn(new com.example.report.query.QueryEngine.PagedData(
                            java.util.List.of(), 0, 1, 50, java.util.List.of()));

            Map<String, Object> userFilter = new java.util.HashMap<>();
            userFilter.put("category", Map.of("type", "blank"));
            var groupKeys = new java.util.ArrayList<String>();
            groupKeys.add(null); // ancestor null → blank entry

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(
                            new ColumnVO("category", "Category", "category", null),
                            new ColumnVO("region", "Region", "region", null)),
                    null, null, false,
                    groupKeys, userFilter, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<java.util.Map<String, Object>> filterCaptor =
                    (org.mockito.ArgumentCaptor<java.util.Map<String, Object>>)
                            (org.mockito.ArgumentCaptor<?>)
                                    org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(queryEngine).executeGroupedQuery(any(), any(), any(), any(),
                    filterCaptor.capture(), any(), anyInt(), anyInt());
            var mergedFilter = filterCaptor.getValue();

            @SuppressWarnings("unchecked")
            var categoryEntry = (java.util.Map<String, Object>) mergedFilter.get("category");
            assertEquals("blank", categoryEntry.get("type"));
            assertFalse(categoryEntry.containsKey("operator"));
        }

        @Test
        void ancestorEquals_existingUserCompoundOr_outerAndNest() {
            // Codex iter-2 §5: when the user's filterModel already
            // carries an OR compound on the same column, the merge
            // must wrap the existing OR intact under a fresh outer
            // AND so the semantic stays `ancestor AND (user OR user)`
            // rather than flattening into `ancestor OR user OR user`.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("region", "Region", "text",
                                    150, false, true, false, null)));
            when(queryEngine.executeGroupedQuery(any(), any(), any(), any(),
                    any(), any(), anyInt(), anyInt()))
                    .thenReturn(new com.example.report.query.QueryEngine.PagedData(
                            java.util.List.of(), 0, 1, 50, java.util.List.of()));

            Map<String, Object> userOr = Map.of(
                    "operator", "OR",
                    "condition1", Map.of("type", "contains", "filter", "FI"),
                    "condition2", Map.of("type", "contains", "filter", "HR"));
            Map<String, Object> userFilter = new java.util.HashMap<>();
            userFilter.put("category", userOr);

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(
                            new ColumnVO("category", "Category", "category", null),
                            new ColumnVO("region", "Region", "region", null)),
                    null, null, false,
                    List.of("FINANCE"), userFilter, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<java.util.Map<String, Object>> filterCaptor =
                    (org.mockito.ArgumentCaptor<java.util.Map<String, Object>>)
                            (org.mockito.ArgumentCaptor<?>)
                                    org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(queryEngine).executeGroupedQuery(any(), any(), any(), any(),
                    filterCaptor.capture(), any(), anyInt(), anyInt());
            var mergedFilter = filterCaptor.getValue();

            @SuppressWarnings("unchecked")
            var categoryEntry = (java.util.Map<String, Object>) mergedFilter.get("category");
            assertEquals("AND", categoryEntry.get("operator"),
                    "outer wrapper must be AND so ancestor binds to (user OR ...)");
            @SuppressWarnings("unchecked")
            var nestedOr = (java.util.Map<String, Object>) categoryEntry.get("condition2");
            assertEquals("OR", nestedOr.get("operator"),
                    "inner OR compound must stay intact under the outer AND");
        }

        @Test
        void ancestorNumericEqualsUserIntegerEquals_compoundDespiteValueParity() {
            // Codex 019e2695 iter-5 absorb: numeric ancestor groupKeys
            // are coerced to Double by coerceGroupKey, but a user
            // filterModel value can arrive as Integer from JSON.
            // Objects.equals(2024.0, 2024) is false, so the merge layer
            // must NOT idempotent-skip here — type-coercion ambiguity
            // falls through to compound AND emit, and the SQL surfaces
            // both predicates so the user sees the actual semantic
            // (the predicate evaluates true via numeric comparison
            // anyway, but the audit trail keeps both shapes).
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("year", "Year", "number",
                                    100, false, true, false, null),
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null)));
            when(queryEngine.executeGroupedQuery(any(), any(), any(), any(),
                    any(), any(), anyInt(), anyInt()))
                    .thenReturn(new com.example.report.query.QueryEngine.PagedData(
                            java.util.List.of(), 0, 1, 50, java.util.List.of()));

            // User filter carries Integer 2024 (JSON-native); ancestor
            // groupKey "2024" coerces to Double 2024.0.
            Map<String, Object> userFilter = new java.util.HashMap<>();
            userFilter.put("year", Map.of("type", "equals", "filter", 2024));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(
                            new ColumnVO("year", "Year", "year", null),
                            new ColumnVO("category", "Category", "category", null)),
                    null, null, false,
                    List.of("2024"), userFilter, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<java.util.Map<String, Object>> filterCaptor =
                    (org.mockito.ArgumentCaptor<java.util.Map<String, Object>>)
                            (org.mockito.ArgumentCaptor<?>)
                                    org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(queryEngine).executeGroupedQuery(any(), any(), any(), any(),
                    filterCaptor.capture(), any(), anyInt(), anyInt());
            var mergedFilter = filterCaptor.getValue();

            @SuppressWarnings("unchecked")
            var yearEntry = (java.util.Map<String, Object>) mergedFilter.get("year");
            assertEquals("AND", yearEntry.get("operator"),
                    "Double 2024.0 ancestor + Integer 2024 user must not "
                            + "idempotent-skip — compound AND emit instead");
            @SuppressWarnings("unchecked")
            var c1 = (java.util.Map<String, Object>) yearEntry.get("condition1");
            @SuppressWarnings("unchecked")
            var c2 = (java.util.Map<String, Object>) yearEntry.get("condition2");
            assertEquals(2024.0, c1.get("filter"),
                    "ancestor side carries Double 2024.0 from coerceGroupKey");
            assertEquals(2024, c2.get("filter"),
                    "user side preserves the original Integer 2024");
        }

        @Test
        void ancestorEquals_existingUserCompoundAnd_flattenedConditions() {
            // Codex iter-2 §5: an existing AND compound on the same
            // column should be flattened — ancestor lands in the
            // `conditions[]` list so the result is
            // (ancestor AND a AND b) rather than (ancestor AND (a AND b)).
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("region", "Region", "text",
                                    150, false, true, false, null)));
            when(queryEngine.executeGroupedQuery(any(), any(), any(), any(),
                    any(), any(), anyInt(), anyInt()))
                    .thenReturn(new com.example.report.query.QueryEngine.PagedData(
                            java.util.List.of(), 0, 1, 50, java.util.List.of()));

            Map<String, Object> userAnd = Map.of(
                    "operator", "AND",
                    "condition1", Map.of("type", "contains", "filter", "FI"),
                    "condition2", Map.of("type", "notBlank"));
            Map<String, Object> userFilter = new java.util.HashMap<>();
            userFilter.put("category", userAnd);

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(
                            new ColumnVO("category", "Category", "category", null),
                            new ColumnVO("region", "Region", "region", null)),
                    null, null, false,
                    List.of("FINANCE"), userFilter, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());

            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<java.util.Map<String, Object>> filterCaptor =
                    (org.mockito.ArgumentCaptor<java.util.Map<String, Object>>)
                            (org.mockito.ArgumentCaptor<?>)
                                    org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
            verify(queryEngine).executeGroupedQuery(any(), any(), any(), any(),
                    filterCaptor.capture(), any(), anyInt(), anyInt());
            var mergedFilter = filterCaptor.getValue();

            @SuppressWarnings("unchecked")
            var categoryEntry = (java.util.Map<String, Object>) mergedFilter.get("category");
            assertEquals("AND", categoryEntry.get("operator"));
            @SuppressWarnings("unchecked")
            var conditions = (java.util.List<java.util.Map<String, Object>>)
                    categoryEntry.get("conditions");
            assertNotNull(conditions, "flattened AND must use conditions[] shape");
            assertEquals(3, conditions.size(),
                    "ancestor + existing c1 + existing c2 = 3 flattened siblings");
        }

        @Test
        void numericGroupKey_coercedToDouble() {
            // PR-0.3 Codex iter-1 absorb: groupKeys are List<String> on
            // the wire but numeric columns must round-trip correctly.
            // The merged filter should carry a Double, not the raw
            // string, so SQL Server doesn't fall back to implicit
            // conversion (which can break index plans).
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("year", "Year", "number",
                                    100, false, true, false, null),
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null)));

            org.mockito.ArgumentCaptor<Map<String, Object>> filterCaptor =
                    org.mockito.ArgumentCaptor.forClass(Map.class);
            when(queryEngine.executeGroupedQuery(any(), any(), eq("category"),
                    any(), filterCaptor.capture(), any(), eq(1), eq(50)))
                    .thenReturn(new QueryEngine.PagedData(List.of(), 0L, 1, 50));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(
                            new ColumnVO("year", "Year", "year", null),
                            new ColumnVO("category", "Category", "category", null)),
                    null, null, false,
                    List.of("2024"), null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());
            Map<String, Object> merged = filterCaptor.getValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> yearFilter = (Map<String, Object>) merged.get("year");
            assertEquals("equals", yearFilter.get("type"));
            assertEquals(2024.0, yearFilter.get("filter"),
                    "Numeric groupKey must be coerced to Double so the SQL "
                            + "binding stays type-aware.");
        }

        @Test
        void invalidNumericGroupKey_returns400() {
            // "abc" can't be parsed as a number → 400 INVALID_GROUP_KEY
            // (rather than letting SQL Server's implicit conversion fail
            // at execute time with a 500-shaped exception).
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("year", "Year", "number",
                                    100, false, true, false, null)));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("year", "Year", "year", null)),
                    null, null, false,
                    List.of("abc"), null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            assertEquals("INVALID_GROUP_KEY",
                    assertInstanceOf(ReportQueryErrorDto.class, response.getBody()).code());
        }

        @Test
        void rowGroupColsExceedingDepthCap_returns400() {
            // PR-0.3 hardening: cap at MAX_ROW_GROUP_DEPTH=8. Anything
            // deeper falls into the rejected bucket.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            // Build 9 distinct groupable columns (one over the cap).
            List<ColumnDefinition> cols = new java.util.ArrayList<>();
            List<ColumnVO> vos = new java.util.ArrayList<>();
            for (int i = 0; i < 9; i++) {
                String name = "g" + i;
                cols.add(new ColumnDefinition(name, name, "text",
                        100, false, true, false, null));
                vos.add(new ColumnVO(name, name, name, null));
            }
            when(columnFilter.getVisibleColumnDefinitions(any(), any())).thenReturn(cols);

            var dto = new ReportQueryRequestDto(
                    0, 50, vos, null, null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            assertEquals("GROUPING_NOT_SUPPORTED",
                    assertInstanceOf(ReportQueryErrorDto.class, response.getBody()).code());
        }

        @Test
        void duplicateRowGroupCols_returns400() {
            // Path with same column twice would either silently collapse
            // a level or break ancestor-filter merge → 400.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null)));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(
                            new ColumnVO("category", "Category", "category", null),
                            new ColumnVO("category", "Category", "category", null)),
                    null, null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            assertEquals("GROUPING_NOT_SUPPORTED",
                    assertInstanceOf(ReportQueryErrorDto.class, response.getBody()).code());
        }

        @Test
        void pivotMode_stillRejectedAsNotSupported() {
            // PR-0.4 territory: pivotMode=true → still 400 in PR-0.3.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null)));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    null,
                    List.of(new ColumnVO("region", "Region", "region", null)),
                    true, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            assertEquals("GROUPING_NOT_SUPPORTED",
                    assertInstanceOf(ReportQueryErrorDto.class, response.getBody()).code());
        }

        @Test
        void valueColsNonAggregatableField_returns400Structured() {
            // PR-0.2 Codex iter-1 absorb: invalid valueCols (field not
            // marked aggregatable) fail closed with a structured 400
            // instead of silently dropping the aggregation and returning
            // 200 with a misleading flat sum.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            // amount is NOT aggregatable
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, false, null)));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "sum")),
                    null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            ReportQueryErrorDto error =
                    assertInstanceOf(ReportQueryErrorDto.class, response.getBody());
            assertEquals("INVALID_AGGREGATION_REQUEST", error.code());
            // SQL must NOT be touched — capability gate short-circuits.
            verify(queryEngine, never()).executeGroupedQuery(
                    any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        void valueColsInvalidAggFunc_returns400Structured() {
            // Codex 019e2695 iter-5 absorb: PR #6a accepts `median`,
            // so the negative test now references a permanently
            // invalid token. Roadmap functions (percentile, weightedavg)
            // will become valid in later PRs and must not be used here.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, "sum")));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "garbage_xyz")),
                    null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            ReportQueryErrorDto error =
                    assertInstanceOf(ReportQueryErrorDto.class, response.getBody());
            assertEquals("INVALID_AGGREGATION_REQUEST", error.code());
        }

        // ── PR #6b: percentilecont parametric agg + duplicate field reject ─

        @Test
        void percentileContAggMissingParams_returns400() {
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, null)));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "percentilecont")),
                    null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            var error = assertInstanceOf(ReportQueryErrorDto.class, response.getBody());
            assertEquals("INVALID_AGGREGATION_REQUEST", error.code());
            assertTrue(error.message().contains("percentile"),
                    "error must mention 'percentile' so the client can fix the request");
        }

        @Test
        void percentileContAggRangeOutOfBounds_returns400() {
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, null)));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "percentilecont",
                            Map.of("percentile", 1.5))),
                    null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            var error = assertInstanceOf(ReportQueryErrorDto.class, response.getBody());
            assertEquals("INVALID_AGGREGATION_REQUEST", error.code());
        }

        @Test
        void duplicateValueColField_returns400() {
            // PR #6b: external alias contract reserves one response
            // column per field. sum+median on the same AMOUNT would
            // either silently overwrite or require an alias suffix
            // scheme this PR does not introduce — reject up front.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, null)));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "sum"),
                            new ColumnVO("amount", "Amount", "amount", "median")),
                    null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            var error = assertInstanceOf(ReportQueryErrorDto.class, response.getBody());
            assertEquals("INVALID_AGGREGATION_REQUEST", error.code());
            assertTrue(error.message().contains("duplicate") || error.message().contains("duplicated"),
                    "error must explain the field-duplication rejection");
        }

        @Test
        void registryDefaultAggParams_fallbackWhenRequestPercentileContIsEmpty() {
            // PR #6b iter-8 (Codex 019e2695): registry defaultAggParams
            // back-fills request aggParams only when the resolved func
            // is `percentilecont` AND the registry's defaultAggFunc is
            // also `percentilecont`. Here both line up: request omits
            // aggFunc and aggParams → resolution falls back to the
            // registry's percentilecont/0.9 default and the merged
            // filterModel carries that percentile through to SqlBuilder.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null, null),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, "percentilecont",
                                    Map.of("percentile", 0.9))));
            when(queryEngine.executeGroupedQuery(any(), any(), any(), any(),
                    any(), any(), anyInt(), anyInt()))
                    .thenReturn(new com.example.report.query.QueryEngine.PagedData(
                            java.util.List.of(), 0, 1, 50, java.util.List.of()));

            // Request omits aggFunc AND aggParams; both fall back to
            // registry defaults.
            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", null)),
                    null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));
            assertEquals(200, response.getStatusCode().value(),
                    "registry fallback must drive a valid percentilecont request");
        }

        @Test
        void registryDefaultAggParams_doesNotLeakIntoExplicitNonParametricRequest() {
            // Codex 019e2695 iter-8 absorb: registry default percentile
            // params must NOT bleed into an explicit `aggFunc=sum`
            // request on the same column. Otherwise the
            // non-parametric-plus-populated-params guard would falsely
            // 400 a perfectly legal `sum` aggregation.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null, null),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, "percentilecont",
                                    Map.of("percentile", 0.9))));
            when(queryEngine.executeGroupedQuery(any(), any(), any(), any(),
                    any(), any(), anyInt(), anyInt()))
                    .thenReturn(new com.example.report.query.QueryEngine.PagedData(
                            java.util.List.of(), 0, 1, 50, java.util.List.of()));

            // Explicit sum override; registry default is percentilecont/0.9.
            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "sum")),
                    null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));
            assertEquals(200, response.getStatusCode().value(),
                    "explicit sum override must not pick up the registry's "
                            + "percentilecont default params");
        }

        @Test
        void requestAggParams_overridesRegistryDefaultAggParams() {
            // Same registry default (percentilecont/0.9) but the request
            // explicitly asks for percentilecont/0.25 — the explicit
            // value wins. We don't have direct access to the merged
            // GroupedAggregation here; the 200 plus mock invocation is
            // proof that the request flowed through validation cleanly.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null, null),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, "percentilecont",
                                    Map.of("percentile", 0.9))));
            when(queryEngine.executeGroupedQuery(any(), any(), any(), any(),
                    any(), any(), anyInt(), anyInt()))
                    .thenReturn(new com.example.report.query.QueryEngine.PagedData(
                            java.util.List.of(), 0, 1, 50, java.util.List.of()));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "percentilecont",
                            Map.of("percentile", 0.25))),
                    null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));
            assertEquals(200, response.getStatusCode().value());
        }

        @Test
        void aggParamsOnNonParametricFunc_returns400() {
            // sum + populated aggParams → reject (Codex iter-7 absorb).
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, null)));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "sum",
                            Map.of("percentile", 0.9))),
                    null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            var error = assertInstanceOf(ReportQueryErrorDto.class, response.getBody());
            assertEquals("INVALID_AGGREGATION_REQUEST", error.code());
        }

        @Test
        void medianAggOnNonNumericColumn_returns400Structured() {
            // PR #6a contract: median is only valid on `type=number`
            // columns. Targeting it at a text column must yield a
            // structured 400 rather than a SQL Server runtime error.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("name", "Name", "text",
                                    120, false, false, true, null)));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("name", "Name", "name", "median")),
                    null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            ReportQueryErrorDto error =
                    assertInstanceOf(ReportQueryErrorDto.class, response.getBody());
            assertEquals("INVALID_AGGREGATION_REQUEST", error.code());
            assertTrue(error.message().contains("median"),
                    "error message should mention 'median' so the client "
                            + "can surface why the aggregation was rejected");
        }

        @Test
        void aggFuncFallback_textColumnDefaultsToCount() {
            // PR-0.2 Codex iter-1 absorb: documentation says "numeric →
            // sum, others → count". The controller now respects type
            // when defaultAggFunc is not set on the registry.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("note", "Note", "text",
                                    150, false, false, true, null)));
            // The mock's argument captor isn't necessary — we only care
            // that executeGroupedQuery is reached. The SqlBuilder unit
            // tests already verify the actual aggregation function in SQL.
            when(queryEngine.executeGroupedQuery(any(), any(), eq("category"),
                    org.mockito.ArgumentMatchers.argThat(aggs ->
                            aggs.size() == 1
                                    && aggs.get(0).field().equals("note")
                                    && aggs.get(0).func().equals("count")),
                    any(), any(), eq(1), eq(50)))
                    .thenReturn(new QueryEngine.PagedData(
                            List.of(), 0L, 1, 50));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    // aggFunc null → fallback. note is type=text →
                    // count (not sum) per documented behaviour.
                    List.of(new ColumnVO("note", "Note", "note", null)),
                    null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());
        }

        @Test
        void aggFuncFallback_numericColumnDefaultsToSum() {
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, null)));
            when(queryEngine.executeGroupedQuery(any(), any(), eq("category"),
                    org.mockito.ArgumentMatchers.argThat(aggs ->
                            aggs.size() == 1
                                    && aggs.get(0).field().equals("amount")
                                    && aggs.get(0).func().equals("sum")),
                    any(), any(), eq(1), eq(50)))
                    .thenReturn(new QueryEngine.PagedData(
                            List.of(), 0L, 1, 50));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    // aggFunc null + defaultAggFunc null + type=number → sum.
                    List.of(new ColumnVO("amount", "Amount", "amount", null)),
                    null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());
        }

        @Test
        void groupByNonGroupableColumn_rejectedAsNotSupported() {
            // Defence-in-depth: a malicious payload requesting GROUP BY
            // on a non-groupable column must not slip into the grouped
            // SQL path. PR-0.2 routes it to the rejected bucket because
            // the field isn't in groupableFields.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            // amount is aggregatable but NOT groupable
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, "sum")));

            var dto = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("amount", "Amount", "amount", null)),
                    null, null, false, null, null, null);

            var response = controller.queryReport("any", dto, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            assertEquals("GROUPING_NOT_SUPPORTED",
                    assertInstanceOf(ReportQueryErrorDto.class, response.getBody()).code());
        }
    }

    @Nested
    class MetadataCapabilities {

        @Test
        void getMetadata_serverSideGroupingFalseWhenNoColumnGroupable() {
            // PR-0.2: capability flag derived from column flags. A column
            // with groupable=false (default) means no grouping can be
            // performed → serverSideGrouping=false, matching the stop-gap UX.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(new ColumnDefinition("col1", "Col 1", "text", 150, false)));

            var response = controller.getMetadata("any", testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());
            ReportMetadataDto body = response.getBody();
            ReportCapabilitiesDto caps = body.capabilities();
            assertFalse(caps.serverSideGrouping());
            assertEquals(List.of(), caps.groupableFields());
            assertEquals(List.of(), caps.aggregatableFields());
        }

        @Test
        void getMetadata_serverSideGroupingTrueWhenAnyColumnGroupable() {
            // PR-0.2: report registry can opt-in by marking columns
            // groupable / aggregatable. Capability flag flips to true and
            // the field lists tell the frontend which columns participate.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, "sum")));

            var response = controller.getMetadata("any", testJwt("admin"));

            ReportCapabilitiesDto caps = response.getBody().capabilities();
            assertTrue(caps.serverSideGrouping(),
                    "Any column with groupable=true must light up the capability");
            assertEquals(List.of("category"), caps.groupableFields());
            assertEquals(List.of("amount"), caps.aggregatableFields());
        }

        @Test
        void getMetadata_pivotableFieldsListedWhenColumnsOptIn() {
            // PR-0.4a (Codex 019e2695 hybrid pivot): pivotableFields
            // surfaces every column with pivotable=true. The flags
            // (serverSidePivoting / clientPivotAllowed) stay false until
            // the SSRM pivot path lands in PR-0.4b — for now the
            // metadata only signals "these columns are pivotable when
            // the modes are eventually flipped on".
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null, null, false),
                            new ColumnDefinition("action_type", "Action", "text",
                                    100, false, true, false, null, null, true),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, "sum", null, false)));

            ReportCapabilitiesDto caps = response("any").getBody().capabilities();

            assertEquals(List.of("action_type"), caps.pivotableFields(),
                    "Only columns explicitly marked pivotable=true are listed");
            assertFalse(caps.serverSidePivoting(),
                    "PR-0.4a only surfaces the field list; backend pivot SQL "
                            + "(serverSidePivoting=true) lands in PR-0.4b");
            assertFalse(caps.clientPivotAllowed(),
                    "PR-0.4a defaults clientPivotAllowed=false; per-report opt-in lights it up");
        }

        @Test
        void getMetadata_pivotableFieldsEmptyByDefault() {
            // Legacy reports without pivotable columns surface an empty
            // pivotableFields list. The frontend's PR-0.4a wiring should
            // hide the pivot UI entirely on these reports.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(
                            new ColumnDefinition("col1", "Col 1", "text", 150, false)));

            ReportCapabilitiesDto caps = response("any").getBody().capabilities();

            assertEquals(List.of(), caps.pivotableFields());
        }

        private org.springframework.http.ResponseEntity<ReportMetadataDto> response(String key) {
            return controller.getMetadata(key, testJwt("admin"));
        }
    }

    @Nested
    class Paging {

        @Test
        void noBounds_defaultsToPage1Size50() {
            assertArrayEquals(new int[]{1, 50},
                    ReportController.computePaging(null, null));
        }

        @Test
        void firstPage50() {
            assertArrayEquals(new int[]{1, 50},
                    ReportController.computePaging(0, 50));
        }

        @Test
        void secondPage100() {
            // SSRM cache window 100..200 = page=2, pageSize=100.
            assertArrayEquals(new int[]{2, 100},
                    ReportController.computePaging(100, 200));
        }

        @Test
        void clampsOversizedWindowToMax500() {
            // AG Grid could request a 10k window; the GET /data path caps at
            // 500 and the POST /query path must agree. 0 % 500 == 0 so the
            // alignment guard still passes.
            int[] paging = ReportController.computePaging(0, 10_000);
            assertEquals(1, paging[0]);
            assertEquals(500, paging[1]);
        }

        @Test
        void zeroWindowThrowsInvalidRowWindow() {
            // PR-0.1 hardening: zero / negative window is malformed; QueryEngine
            // would return undefined rows, so fail closed.
            ReportController.PagingException ex = assertThrows(
                    ReportController.PagingException.class,
                    () -> ReportController.computePaging(50, 50));
            assertEquals("INVALID_ROW_WINDOW", ex.code);
        }

        @Test
        void misalignedWindowThrowsNonAligned() {
            // 75/125 → pageSize=50, but 75 is not a multiple of 50.
            ReportController.PagingException ex = assertThrows(
                    ReportController.PagingException.class,
                    () -> ReportController.computePaging(75, 125));
            assertEquals("NON_ALIGNED_ROW_WINDOW", ex.code);
        }

        @Test
        void misalignedAfterClampThrowsNonAligned() {
            // 100/10000 → clamp pageSize to 500;
            // 100 is not a multiple of 500 → fail closed instead of
            // silently shifting OFFSET to 0.
            ReportController.PagingException ex = assertThrows(
                    ReportController.PagingException.class,
                    () -> ReportController.computePaging(100, 10_000));
            assertEquals("NON_ALIGNED_ROW_WINDOW", ex.code);
        }

        @Test
        void negativeStartRowClampsToZeroAndPasses() {
            // Defensive: should not propagate a negative offset to QueryEngine.
            // 0 % 40 == 0 so alignment guard still passes.
            int[] paging = ReportController.computePaging(-10, 40);
            assertEquals(1, paging[0]);
            assertEquals(40, paging[1]);
        }
    }

    // ── PR-0.4b: single-level pivot dispatch ────────────────────

    @Nested
    class PivotDispatch {

        private ReportDefinition pivotReport() {
            return new ReportDefinition(
                    "fin-pivot", "1", "Pivot", "desc", "fin",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("status", "Status", "text",
                                    100, false, false, false, null, null, true,
                                    List.of(new com.example.report.registry.PivotValue("A"),
                                            new com.example.report.registry.PivotValue("P"))),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, "sum")),
                    null, null,
                    new AccessConfig("REPORT_VIEW", null, null, null));
        }

        private ReportDefinition pivotReportNoValues() {
            // Mirrors the pre-PR-0.4b registry shape (pivotable=true,
            // pivotValues=null). Dispatch must short-circuit with the
            // `PIVOT_NOT_CONFIGURED` 400 rather than calling the SQL
            // builder.
            return new ReportDefinition(
                    "fin-pivot-stub", "1", "Pivot Stub", "desc", "fin",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("status", "Status", "text",
                                    100, false, false, false, null, null, true),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, "sum")),
                    null, null,
                    new AccessConfig("REPORT_VIEW", null, null, null));
        }

        @Test
        void wellFormedPivotRequest_routedToExecutePivotedGroupedQuery() {
            stubAuthz(true, List.of());
            ReportDefinition def = pivotReport();
            when(registry.get("fin-pivot")).thenReturn(Optional.of(def));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(def.columns());
            when(queryEngine.executePivotedGroupedQuery(
                    any(), any(), eq("category"), eq("status"),
                    any(), any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(new QueryEngine.PagedData(
                            List.of(Map.of("category", "x",
                                    "pvt__status__A__sum__amount", 100)),
                            1L, 1, 50, List.of(),
                            List.of("pvt__status__A__sum__amount",
                                    "pvt__status__P__sum__amount")));

            var pivot = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "sum")),
                    List.of(new ColumnVO("status", "Status", "status", null)),
                    true, null, null, null);

            var response = controller.queryReport("fin-pivot", pivot, null, testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());
            verify(queryEngine).executePivotedGroupedQuery(
                    any(), any(), eq("category"), eq("status"),
                    any(), any(), any(), any(), eq(1), eq(50));
            // Pivot result fields surface on the response envelope.
            PagedResultDto<?> body = assertInstanceOf(PagedResultDto.class, response.getBody());
            assertEquals(List.of(
                    "pvt__status__A__sum__amount",
                    "pvt__status__P__sum__amount"), body.pivotResultFields());
        }

        @Test
        void pivotRequestWithoutPivotValues_returns400PivotNotConfigured() {
            stubAuthz(true, List.of());
            ReportDefinition def = pivotReportNoValues();
            when(registry.get("fin-pivot-stub")).thenReturn(Optional.of(def));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(def.columns());

            var pivot = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "sum")),
                    List.of(new ColumnVO("status", "Status", "status", null)),
                    true, null, null, null);

            var response = controller.queryReport("fin-pivot-stub", pivot, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            ReportQueryErrorDto error = assertInstanceOf(
                    ReportQueryErrorDto.class, response.getBody());
            assertEquals("PIVOT_NOT_CONFIGURED", error.code());
            verify(queryEngine, never()).executePivotedGroupedQuery(
                    any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        void pivotRequestWithGroupKeys_returns400GroupingNotSupported() {
            // PR-0.4b cap: groupKeys must be empty (top-level only).
            // Expanded pivot drill-down is reserved for PR-0.4e.
            stubAuthz(true, List.of());
            ReportDefinition def = pivotReport();
            when(registry.get("fin-pivot")).thenReturn(Optional.of(def));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(def.columns());

            var pivot = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "sum")),
                    List.of(new ColumnVO("status", "Status", "status", null)),
                    true,
                    List.of("Finance"),
                    null, null);

            var response = controller.queryReport("fin-pivot", pivot, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            ReportQueryErrorDto error = assertInstanceOf(
                    ReportQueryErrorDto.class, response.getBody());
            assertEquals("GROUPING_NOT_SUPPORTED", error.code());
        }

        @Test
        void pivotRequestWithTwoPivotCols_returns400GroupingNotSupported() {
            // PR-0.4b cap: exactly one pivot column.
            stubAuthz(true, List.of());
            ReportDefinition def = pivotReport();
            when(registry.get("fin-pivot")).thenReturn(Optional.of(def));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(def.columns());

            var pivot = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "sum")),
                    List.of(new ColumnVO("status", "Status", "status", null),
                            new ColumnVO("category", "Category", "category", null)),
                    true, null, null, null);

            var response = controller.queryReport("fin-pivot", pivot, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            ReportQueryErrorDto error = assertInstanceOf(
                    ReportQueryErrorDto.class, response.getBody());
            assertEquals("GROUPING_NOT_SUPPORTED", error.code());
        }

        @Test
        void pivotRequestWithMedianAggFunc_returns400InvalidAggregation() {
            stubAuthz(true, List.of());
            ReportDefinition def = pivotReport();
            when(registry.get("fin-pivot")).thenReturn(Optional.of(def));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(def.columns());

            var pivot = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "median")),
                    List.of(new ColumnVO("status", "Status", "status", null)),
                    true, null, null, null);

            var response = controller.queryReport("fin-pivot", pivot, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            ReportQueryErrorDto error = assertInstanceOf(
                    ReportQueryErrorDto.class, response.getBody());
            assertEquals("INVALID_AGGREGATION_REQUEST", error.code());
        }

        @Test
        void pivotOnNonPivotableColumn_returns400GroupingNotSupported() {
            // Pivot on `category` while it's only `groupable=true` (not
            // pivotable=true) must fall through to the rejection path.
            stubAuthz(true, List.of());
            ReportDefinition def = pivotReport();
            when(registry.get("fin-pivot")).thenReturn(Optional.of(def));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(def.columns());

            var pivot = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new ColumnVO("status", "Status", "status", null)),
                    List.of(new ColumnVO("amount", "Amount", "amount", "sum")),
                    List.of(new ColumnVO("category", "Category", "category", null)),
                    true, null, null, null);

            var response = controller.queryReport("fin-pivot", pivot, null, testJwt("admin"));

            assertEquals(400, response.getStatusCode().value());
            ReportQueryErrorDto error = assertInstanceOf(
                    ReportQueryErrorDto.class, response.getBody());
            assertEquals("GROUPING_NOT_SUPPORTED", error.code());
        }
    }

    @Nested
    class MetadataPivotCapabilities {

        @Test
        void serverSidePivotingDerivedFromPivotableColumnsWithValues() {
            stubAuthz(true, List.of());
            ReportDefinition def = new ReportDefinition(
                    "fin-pivot", "1", "Pivot", "desc", "fin",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("status", "Status", "text",
                                    100, false, false, false, null, null, true,
                                    List.of(new com.example.report.registry.PivotValue("A"))),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, "sum")),
                    null, null,
                    new AccessConfig("REPORT_VIEW", null, null, null));
            when(registry.get("fin-pivot")).thenReturn(Optional.of(def));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(def.columns());

            var response = controller.getMetadata("fin-pivot", testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());
            ReportMetadataDto body = response.getBody();
            assertNotNull(body);
            ReportCapabilitiesDto caps = body.capabilities();
            assertTrue(caps.serverSidePivoting(),
                    "serverSidePivoting must derive true when at least one "
                            + "visible column is pivotable + has populated pivotValues");
            assertEquals(List.of("status"), caps.pivotableFields());
        }

        @Test
        void serverSidePivotingStaysFalseWhenPivotValuesMissing() {
            // PR-0.4a-era report (pivotable=true, no pivotValues) must
            // not flip the capability flag — runtime dispatch would
            // otherwise route a pivot request into the SQL builder that
            // immediately throws PIVOT_NOT_CONFIGURED.
            stubAuthz(true, List.of());
            ReportDefinition def = new ReportDefinition(
                    "fin-pivot-stub", "1", "Pivot Stub", "desc", "fin",
                    "TXN", "dbo", "static", null, null,
                    List.of(
                            new ColumnDefinition("category", "Category", "text",
                                    150, false, true, false, null),
                            new ColumnDefinition("status", "Status", "text",
                                    100, false, false, false, null, null, true),
                            new ColumnDefinition("amount", "Amount", "number",
                                    120, false, false, true, "sum")),
                    null, null,
                    new AccessConfig("REPORT_VIEW", null, null, null));
            when(registry.get("fin-pivot-stub")).thenReturn(Optional.of(def));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(def.columns());

            var response = controller.getMetadata("fin-pivot-stub", testJwt("admin"));

            ReportMetadataDto body = response.getBody();
            assertNotNull(body);
            assertFalse(body.capabilities().serverSidePivoting(),
                    "serverSidePivoting must stay false when no column carries "
                            + "a populated pivotValues list");
            assertEquals(List.of("status"), body.capabilities().pivotableFields(),
                    "pivotableFields still surfaces the registry allowlist so "
                            + "the frontend can plan UI even before backend "
                            + "pivot is configured");
        }
    }

    // ---- helpers ---------------------------------------------------------

    private void stubAuthz(boolean superAdmin, List<String> permissions) {
        AuthzMeResponse authz = new AuthzMeResponse();
        authz.setSuperAdmin(superAdmin);
        authz.setPermissions(permissions);
        authz.setUserId("test-user");
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
    }

    private static ReportDefinition report(String key) {
        return new ReportDefinition(
                key,
                "1",
                "Report " + key,
                "desc",
                "category",
                "dbo.fact_table",
                "dbo",
                "static",
                null,
                null,
                List.of(new ColumnDefinition("col1", "Col 1", "text", 150, false)),
                null,
                null,
                new AccessConfig("REPORT_VIEW", null, null, null));
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
