package com.example.report.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    class MetadataCapabilities {

        @Test
        void getMetadata_exposesServerSideGroupingFlagAsFalse() {
            // PR-0.1: capability flag is hard-coded false until PR-0.2 lands.
            // The frontend reads this to decide whether to expose the
            // grouping/pivot UI; absent the flag, the platform-web stop-gap
            // hides everything.
            stubAuthz(true, List.of());
            when(registry.get("any")).thenReturn(Optional.of(report("any")));
            when(columnFilter.getVisibleColumnDefinitions(any(), any()))
                    .thenReturn(List.of(new ColumnDefinition("col1", "Col 1", "text", 150, false)));

            var response = controller.getMetadata("any", testJwt("admin"));

            assertEquals(200, response.getStatusCode().value());
            ReportMetadataDto body = response.getBody();
            assertNotNull(body, "Metadata body must not be null");
            ReportCapabilitiesDto caps = body.capabilities();
            assertNotNull(caps, "capabilities field must be populated");
            assertFalse(caps.serverSideGrouping(),
                    "PR-0.1 ships serverSideGrouping=false; PR-0.2 will flip per-report.");
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
