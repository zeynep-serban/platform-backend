package com.example.report.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.report.access.ColumnFilter;
import com.example.report.access.ReportAccessEvaluator;
import com.example.report.audit.ReportAuditClient;
import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.CompanyHeaderScopeNarrower;
import com.example.report.authz.PermissionResolver;
import com.example.report.dto.PagedResultDto;
import com.example.report.dto.ReportQueryErrorDto;
import com.example.report.dto.ReportQueryRequestDto;
import com.example.report.execution.AgGridFilterTranslator;
import com.example.report.execution.RemoteAllowlistException;
import com.example.report.execution.RemoteAuthException;
import com.example.report.execution.RemoteAuthzException;
import com.example.report.execution.RemoteExecutionException;
import com.example.report.execution.RemoteReportExecutor;
import com.example.report.execution.RemoteReportRequest;
import com.example.report.execution.RemoteReportResult;
import com.example.report.query.QueryEngine;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ExecutionConfig;
import com.example.report.registry.ExecutionKind;
import com.example.report.registry.ReportDefinition;
import com.example.report.registry.ReportRegistry;
import com.example.report.repository.CustomReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * PR-D2.1c2 (ADR-0015, Codex 019e8306 iter-6) — {@link ReportController}
 * remote-http dispatch tests.
 *
 * <p>Verifies the four dispatch contracts:
 *
 * <ol>
 *   <li>{@code GET /data} dispatches to {@link RemoteReportExecutor} when
 *       {@link ReportDefinition#isRemoteHttp()} is {@code true}; falls
 *       back to legacy {@link QueryEngine} for SQL reports.</li>
 *   <li>{@code POST /query} accepts flat shape for remote-http and rejects
 *       grouping / pivot with 400 {@code REMOTE_GROUPING_NOT_SUPPORTED}.</li>
 *   <li>{@code GET /filter-values} returns 422
 *       {@code REMOTE_FILTER_VALUES_NOT_SUPPORTED} for remote-http reports.</li>
 *   <li>{@link RemoteAllowlistException}, {@link RemoteAuthException},
 *       {@link RemoteAuthzException}, {@link RemoteExecutionException}
 *       map to 503 / 401 / 403 / 502 with structured error bodies.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportControllerRemoteDispatchTest {

    @Mock private PermissionResolver permissionResolver;
    @Mock private CustomReportRepository customReportRepository;
    @Mock private ReportRegistry registry;
    @Mock private QueryEngine queryEngine;
    @Mock private ColumnFilter columnFilter;
    @Mock private ReportAuditClient auditClient;
    @Mock private RemoteReportExecutor remoteReportExecutor;

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
                new CompanyHeaderScopeNarrower(),
                remoteReportExecutor,
                new AgGridFilterTranslator());
    }

    /* ------------------- GET /data dispatcher ------------------- */

    @Nested
    class GetData {

        @Test
        @DisplayName("SQL report → QueryEngine path unchanged")
        void sqlReportRoutesToQueryEngine() {
            stubAuthz(true, List.of());
            ReportDefinition def = sqlReport("hr-demografik");
            when(registry.get("hr-demografik")).thenReturn(Optional.of(def));
            when(queryEngine.executeQuery(any(), any(), any(), any(), eq(1), eq(50)))
                    .thenReturn(new QueryEngine.PagedData(
                            List.of(Map.of("EMPLOYEE_ID", 42)), 1L, 1, 50));

            ResponseEntity<?> resp = controller.getData(
                    "hr-demografik", 1, 50, null, null, null, testJwt("admin"));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            verify(queryEngine).executeQuery(any(), any(), any(), any(), eq(1), eq(50));
            verify(remoteReportExecutor, never()).execute(any(), any());
        }

        @Test
        @DisplayName("remote-http report → RemoteReportExecutor invoked")
        void remoteReportRoutesToExecutor() {
            stubAuthz(true, List.of());
            ReportDefinition def = remoteReport("users-overview");
            when(registry.get("users-overview")).thenReturn(Optional.of(def));
            when(remoteReportExecutor.execute(any(), any()))
                    .thenReturn(new RemoteReportResult(
                            List.of(Map.of("id", 1L, "email", "ali@example.com")), 17L));

            ResponseEntity<?> resp = controller.getData(
                    "users-overview", 1, 50, null, null, null, testJwt("admin"));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            PagedResultDto<?> body = (PagedResultDto<?>) resp.getBody();
            assertThat(body).isNotNull();
            assertThat(body.total()).isEqualTo(17L);
            assertThat(body.items()).hasSize(1);
            verify(remoteReportExecutor, times(1)).execute(any(), any());
            verify(queryEngine, never()).executeQuery(any(), any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("AG-Grid unsupported filter → 400 REMOTE_FILTER_UNSUPPORTED")
        void unsupportedFilterReturnsRemoteFilterUnsupported() {
            stubAuthz(true, List.of());
            ReportDefinition def = remoteReport("users-overview");
            when(registry.get("users-overview")).thenReturn(Optional.of(def));
            String unsupported =
                    "{\"email\":{\"type\":\"startsWith\",\"filter\":\"admin\"}}";

            ResponseEntity<?> resp = controller.getData(
                    "users-overview", 1, 50, null, unsupported, null, testJwt("admin"));

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            ReportQueryErrorDto err = (ReportQueryErrorDto) resp.getBody();
            assertThat(err).isNotNull();
            assertThat(err.code()).isEqualTo("REMOTE_FILTER_UNSUPPORTED");
            assertThat(err.message()).contains("startsWith");
            verify(remoteReportExecutor, never()).execute(any(), any());
        }

        @Test
        @DisplayName("RemoteAllowlistException → 503 REMOTE_EXECUTOR_UNAVAILABLE")
        void allowlistExceptionReturns503() {
            stubAuthz(true, List.of());
            ReportDefinition def = remoteReport("users-overview");
            when(registry.get("users-overview")).thenReturn(Optional.of(def));
            when(remoteReportExecutor.execute(any(), any()))
                    .thenThrow(new RemoteAllowlistException("user-service", "/api/v1/users"));

            ResponseEntity<?> resp = controller.getData(
                    "users-overview", 1, 50, null, null, null, testJwt("admin"));

            assertThat(resp.getStatusCode().value()).isEqualTo(503);
            ReportQueryErrorDto err = (ReportQueryErrorDto) resp.getBody();
            assertThat(err.code()).isEqualTo("REMOTE_EXECUTOR_UNAVAILABLE");
        }

        @Test
        @DisplayName("RemoteAuthException → 401 REMOTE_AUTHENTICATION_FAILED")
        void authExceptionReturns401() {
            stubAuthz(true, List.of());
            ReportDefinition def = remoteReport("users-overview");
            when(registry.get("users-overview")).thenReturn(Optional.of(def));
            when(remoteReportExecutor.execute(any(), any()))
                    .thenThrow(new RemoteAuthException("user-service", "/api/v1/users", null));

            ResponseEntity<?> resp = controller.getData(
                    "users-overview", 1, 50, null, null, null, testJwt("admin"));

            assertThat(resp.getStatusCode().value()).isEqualTo(401);
            ReportQueryErrorDto err = (ReportQueryErrorDto) resp.getBody();
            assertThat(err.code()).isEqualTo("REMOTE_AUTHENTICATION_FAILED");
        }

        @Test
        @DisplayName("RemoteAuthzException → 403 REMOTE_AUTHORIZATION_FAILED")
        void authzExceptionReturns403() {
            stubAuthz(true, List.of());
            ReportDefinition def = remoteReport("users-overview");
            when(registry.get("users-overview")).thenReturn(Optional.of(def));
            when(remoteReportExecutor.execute(any(), any()))
                    .thenThrow(new RemoteAuthzException("user-service", "/api/v1/users", null));

            ResponseEntity<?> resp = controller.getData(
                    "users-overview", 1, 50, null, null, null, testJwt("admin"));

            assertThat(resp.getStatusCode().value()).isEqualTo(403);
            ReportQueryErrorDto err = (ReportQueryErrorDto) resp.getBody();
            assertThat(err.code()).isEqualTo("REMOTE_AUTHORIZATION_FAILED");
        }

        @Test
        @DisplayName("RemoteExecutionException (timeout / 5xx / malformed) → 502 REMOTE_EXECUTION_FAILED")
        void executionExceptionReturns502() {
            stubAuthz(true, List.of());
            ReportDefinition def = remoteReport("users-overview");
            when(registry.get("users-overview")).thenReturn(Optional.of(def));
            when(remoteReportExecutor.execute(any(), any()))
                    .thenThrow(new RemoteExecutionException(
                            "user-service", "/api/v1/users", null,
                            "downstream timeout after PT5S"));

            ResponseEntity<?> resp = controller.getData(
                    "users-overview", 1, 50, null, null, null, testJwt("admin"));

            assertThat(resp.getStatusCode().value()).isEqualTo(502);
            ReportQueryErrorDto err = (ReportQueryErrorDto) resp.getBody();
            assertThat(err.code()).isEqualTo("REMOTE_EXECUTION_FAILED");
            assertThat(err.message()).contains("timeout");
        }
    }

    /* ------------------- POST /query dispatcher ------------------- */

    @Nested
    class PostQuery {

        @Test
        @DisplayName("remote-http flat request → RemoteReportExecutor invoked")
        void remoteFlatRequestRoutesToExecutor() {
            stubAuthz(true, List.of());
            ReportDefinition def = remoteReport("users-overview");
            when(registry.get("users-overview")).thenReturn(Optional.of(def));
            when(remoteReportExecutor.execute(any(), any()))
                    .thenReturn(new RemoteReportResult(List.of(), 0L));

            // Signature: (startRow, endRow, rowGroupCols, valueCols, pivotCols,
            //             pivotMode, groupKeys, filterModel, sortModel)
            ReportQueryRequestDto request = new ReportQueryRequestDto(
                    0, 50, null, null, null, null, null, null, null);

            ResponseEntity<?> resp = controller.queryReport(
                    "users-overview", request, null, testJwt("admin"));

            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            verify(remoteReportExecutor, times(1)).execute(any(), any());
        }

        @Test
        @DisplayName("remote-http + rowGroupCols → 400 REMOTE_GROUPING_NOT_SUPPORTED")
        void groupingRejectedForRemote() {
            stubAuthz(true, List.of());
            ReportDefinition def = remoteReport("users-overview");
            when(registry.get("users-overview")).thenReturn(Optional.of(def));

            ReportQueryRequestDto request = new ReportQueryRequestDto(
                    0, 50,
                    List.of(new com.example.report.dto.ColumnVO("role", "Role", "role", null)),
                    null, null, null, null, null, null);

            ResponseEntity<?> resp = controller.queryReport(
                    "users-overview", request, null, testJwt("admin"));

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            ReportQueryErrorDto err = (ReportQueryErrorDto) resp.getBody();
            assertThat(err.code()).isEqualTo("REMOTE_GROUPING_NOT_SUPPORTED");
            verify(remoteReportExecutor, never()).execute(any(), any());
        }

        @Test
        @DisplayName("remote-http + pivotMode=true → 400 REMOTE_GROUPING_NOT_SUPPORTED")
        void pivotModeRejectedForRemote() {
            stubAuthz(true, List.of());
            ReportDefinition def = remoteReport("users-overview");
            when(registry.get("users-overview")).thenReturn(Optional.of(def));

            ReportQueryRequestDto request = new ReportQueryRequestDto(
                    0, 50, null, null,
                    List.of(new com.example.report.dto.ColumnVO("role", "Role", "role", null)),
                    true, null, null, null);

            ResponseEntity<?> resp = controller.queryReport(
                    "users-overview", request, null, testJwt("admin"));

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            ReportQueryErrorDto err = (ReportQueryErrorDto) resp.getBody();
            assertThat(err.code()).isEqualTo("REMOTE_GROUPING_NOT_SUPPORTED");
        }

        @Test
        @DisplayName("remote-http + valueCols (no rowGroups) → 400 (caller intends aggregation)")
        void valueColsOnlyRejectedForRemote() {
            stubAuthz(true, List.of());
            ReportDefinition def = remoteReport("users-overview");
            when(registry.get("users-overview")).thenReturn(Optional.of(def));

            ReportQueryRequestDto request = new ReportQueryRequestDto(
                    0, 50, null,
                    List.of(new com.example.report.dto.ColumnVO("amount", "Amount", "amount", "sum")),
                    null, null, null, null, null);

            ResponseEntity<?> resp = controller.queryReport(
                    "users-overview", request, null, testJwt("admin"));

            assertThat(resp.getStatusCode().value()).isEqualTo(400);
            ReportQueryErrorDto err = (ReportQueryErrorDto) resp.getBody();
            assertThat(err.code()).isEqualTo("REMOTE_GROUPING_NOT_SUPPORTED");
        }
    }

    /* ------------------- GET /filter-values dispatcher ------------------- */

    @Nested
    class FilterValues {

        @Test
        @DisplayName("remote-http → 422 REMOTE_FILTER_VALUES_NOT_SUPPORTED")
        void remoteFilterValuesRejected() {
            stubAuthz(true, List.of());
            ReportDefinition def = remoteReport("users-overview");
            when(registry.get("users-overview")).thenReturn(Optional.of(def));

            ResponseEntity<?> resp = controller.getFilterValues(
                    "users-overview", "role", null, null, null, testJwt("admin"));

            assertThat(resp.getStatusCode().value()).isEqualTo(422);
            ReportQueryErrorDto err = (ReportQueryErrorDto) resp.getBody();
            assertThat(err).isNotNull();
            assertThat(err.code()).isEqualTo("REMOTE_FILTER_VALUES_NOT_SUPPORTED");
            verify(queryEngine, never()).executeFilterValues(any(), any(), any(), any(), anyInt());
        }
    }

    /* ----------------------- helpers ----------------------- */

    private void stubAuthz(boolean reportView, List<String> additionalPerms) {
        AuthzMeResponse authz = new AuthzMeResponse();
        authz.setUserId("user-1");
        java.util.List<String> perms = new java.util.ArrayList<>(additionalPerms);
        if (reportView) perms.add("REPORT_VIEW");
        authz.setPermissions(perms);
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
    }

    private ReportDefinition sqlReport(String key) {
        return new ReportDefinition(
                key, "1.0", "Test SQL Report", "desc", "test",
                "test_table", "dbo", "static", null, null,
                List.of(simpleColumn("col1", "text")),
                "col1", "ASC",
                new AccessConfig(null, "default", null, null),
                null, null, null, null);
    }

    private ReportDefinition remoteReport(String key) {
        ExecutionConfig execution = new ExecutionConfig(
                ExecutionKind.REMOTE_HTTP, "user-service",
                "/api/v1/users", "paged-items-total");
        return new ReportDefinition(
                key, "1.0", "Test Remote Report", "desc", "test",
                null, null, "static", null, null,
                List.of(simpleColumn("id", "number"),
                        simpleColumn("email", "text")),
                "id", "ASC",
                new AccessConfig(null, "default", null, null),
                null, null, null, execution);
    }

    /** ColumnDefinition has 20 fields; helper supplies sensible defaults
     *  so tests only thread field + type through. */
    private ColumnDefinition simpleColumn(String field, String type) {
        return new ColumnDefinition(
                field,
                /* headerName */ field,
                /* type */ type,
                /* width */ null,
                /* sensitive */ false,
                /* groupable */ false,
                /* aggregatable */ false,
                /* defaultAggFunc */ null,
                /* defaultAggParams */ null,
                /* pivotable */ false,
                /* pivotValues */ null,
                /* variantMap */ null,
                /* labelMap */ null,
                /* statusMap */ null,
                /* currencyCode */ null,
                /* decimals */ null,
                /* suffix */ null,
                /* format */ null,
                /* defaultVariant */ null,
                /* filterValues */ null);
    }

    private Jwt testJwt(String username) {
        return new Jwt(
                "test-token-" + username,
                Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                Map.of("preferred_username", username, "sub", username));
    }
}
