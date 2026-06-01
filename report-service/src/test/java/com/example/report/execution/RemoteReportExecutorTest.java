package com.example.report.execution;

import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ExecutionConfig;
import com.example.report.registry.ExecutionKind;
import com.example.report.registry.ReportDefinition;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-D2.1c1 — {@link RemoteReportExecutor} integration tests.
 *
 * <p>Uses {@link MockWebServer} (Codex 019e8306 iter-2 — repo already has
 * this pattern in {@code SchemaServiceClientTest}; WireMock not added).
 *
 * <p>Coverage:
 * <ul>
 *   <li>Happy path: GET → 200 paged → result</li>
 *   <li>Auth + company-id header propagation</li>
 *   <li>Allowlist reject (pre-HTTP guard)</li>
 *   <li>401 → RemoteAuthException</li>
 *   <li>403 → RemoteAuthzException</li>
 *   <li>500 → RemoteExecutionException</li>
 *   <li>Timeout → RemoteExecutionException</li>
 *   <li>Malformed response → RemoteExecutionException</li>
 *   <li>items-array shape (alternative responseShape)</li>
 *   <li>Non-remote-http definition rejected</li>
 * </ul>
 */
class RemoteReportExecutorTest {

    private MockWebServer server;
    private RemoteReportExecutor executor;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        baseUrl = "http://" + server.getHostName() + ":" + server.getPort();

        var props = new RemoteExecutorProperties(
                true,
                Duration.ofSeconds(2), // shorter timeout for tests
                List.of(
                        new RemoteExecutorProperties.AllowlistEntry(
                                "user-service", baseUrl,
                                "/api/v1/users", "style-api-paged-v1"),
                        new RemoteExecutorProperties.AllowlistEntry(
                                "permission-service", baseUrl,
                                "/api/v1/roles", "style-api-paged-v1")));
        var allowlist = new RemoteAllowlist(props);
        var requestNormalizer = new RemoteRequestNormalizer();
        var responseNormalizer = new RemoteResponseNormalizer();

        executor = new RemoteReportExecutor(
                WebClient.builder(),
                allowlist,
                requestNormalizer,
                responseNormalizer,
                props);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    // ---- Happy path -------------------------------------------------- //

    @Test
    @DisplayName("happy path: GET → 200 paged → normalized result")
    void happyPathPaged() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "items": [
                            {"id": 1, "name": "Ali"},
                            {"id": 2, "name": "Veli"}
                          ],
                          "total": 42
                        }
                        """));

        var definition = users("paged-items-total");
        var request = new RemoteReportRequest(
                1, 25, "", List.of(), Map.of(), null, null);

        var result = executor.execute(definition, request);
        assertThat(result.rows()).hasSize(2);
        assertThat(result.total()).isEqualTo(42);
        assertThat(result.rows().get(0).get("name")).isEqualTo("Ali");

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("GET");
        assertThat(recorded.getPath()).startsWith("/api/v1/users");
        assertThat(recorded.getPath()).contains("page=1");
        assertThat(recorded.getPath()).contains("pageSize=25");
    }

    @Test
    @DisplayName("Authorization Bearer header propagated when JWT provided")
    void authorizationHeaderPropagated() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{ \"items\": [], \"total\": 0 }")
                .setHeader("Content-Type", "application/json"));

        var definition = users("paged-items-total");
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(),
                null, "test-jwt-token-xyz");

        executor.execute(definition, request);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader("Authorization"))
                .isEqualTo("Bearer test-jwt-token-xyz");
    }

    @Test
    @DisplayName("X-Company-Id header propagated when company-id provided")
    void companyIdHeaderPropagated() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{ \"items\": [], \"total\": 0 }")
                .setHeader("Content-Type", "application/json"));

        var definition = users("paged-items-total");
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(),
                "00000000-0000-0000-0000-000000000001", null);

        executor.execute(definition, request);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader("X-Company-Id"))
                .isEqualTo("00000000-0000-0000-0000-000000000001");
    }

    @Test
    @DisplayName("no Authorization / X-Company-Id headers when null")
    void noAuthHeadersWhenNull() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{ \"items\": [], \"total\": 0 }")
                .setHeader("Content-Type", "application/json"));

        var definition = users("paged-items-total");
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, null);

        executor.execute(definition, request);

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader("Authorization")).isNull();
        assertThat(recorded.getHeader("X-Company-Id")).isNull();
    }

    // ---- Error paths ------------------------------------------------- //

    @Test
    @DisplayName("allowlist reject BEFORE HTTP request issued")
    void allowlistRejectBeforeHttp() {
        // Definition references a path NOT in allowlist
        var execution = new ExecutionConfig(
                ExecutionKind.REMOTE_HTTP, "user-service",
                "/api/v1/admins", "paged-items-total");
        var definition = buildDefinition("rejected", execution);
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, null);

        assertThatThrownBy(() -> executor.execute(definition, request))
                .isInstanceOf(RemoteAllowlistException.class)
                .extracting(e -> ((RemoteAllowlistException) e).path())
                .isEqualTo("/api/v1/admins");

        // Verify no HTTP request was issued
        assertThat(server.getRequestCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("downstream 401 → RemoteAuthException")
    void downstream401() {
        server.enqueue(new MockResponse().setResponseCode(401));

        var definition = users("paged-items-total");
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, "expired-token");

        assertThatThrownBy(() -> executor.execute(definition, request))
                .isInstanceOf(RemoteAuthException.class)
                .hasMessageContaining("401 Unauthorized");
    }

    @Test
    @DisplayName("downstream 403 → RemoteAuthzException")
    void downstream403() {
        server.enqueue(new MockResponse().setResponseCode(403));

        var definition = users("paged-items-total");
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, "valid-token");

        assertThatThrownBy(() -> executor.execute(definition, request))
                .isInstanceOf(RemoteAuthzException.class)
                .hasMessageContaining("403 Forbidden");
    }

    @Test
    @DisplayName("downstream 500 → RemoteExecutionException")
    void downstream500() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("ouch"));

        var definition = users("paged-items-total");
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, null);

        assertThatThrownBy(() -> executor.execute(definition, request))
                .isInstanceOf(RemoteExecutionException.class)
                .extracting(e -> ((RemoteExecutionException) e).downstreamStatus())
                .isEqualTo(500);
    }

    @Test
    @DisplayName("downstream 503 → RemoteExecutionException")
    void downstream503() {
        server.enqueue(new MockResponse().setResponseCode(503));

        var definition = users("paged-items-total");
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, null);

        assertThatThrownBy(() -> executor.execute(definition, request))
                .isInstanceOf(RemoteExecutionException.class)
                .extracting(e -> ((RemoteExecutionException) e).downstreamStatus())
                .isEqualTo(503);
    }

    @Test
    @DisplayName("timeout → RemoteExecutionException (downstreamStatus=null)")
    void downstreamTimeout() {
        // Server "responds" but takes longer than the 2s test timeout
        server.enqueue(new MockResponse()
                .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS)
                .setResponseCode(200)
                .setBody("{ \"items\": [], \"total\": 0 }"));

        var definition = users("paged-items-total");
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, null);

        assertThatThrownBy(() -> executor.execute(definition, request))
                .isInstanceOf(RemoteExecutionException.class)
                .extracting(e -> ((RemoteExecutionException) e).downstreamStatus())
                .isNull(); // null because no HTTP status reached
    }

    @Test
    @DisplayName("malformed response (missing 'items') → RemoteExecutionException")
    void malformedResponse() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{ \"total\": 0 }")
                .setHeader("Content-Type", "application/json"));

        var definition = users("paged-items-total");
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, null);

        assertThatThrownBy(() -> executor.execute(definition, request))
                .isInstanceOf(RemoteExecutionException.class)
                .hasMessageContaining("'items' array field");
    }

    // ---- items-array shape ------------------------------------------ //

    @Test
    @DisplayName("items-array shape happy path")
    void itemsArrayShape() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("""
                        [
                          {"role": "admin"},
                          {"role": "viewer"}
                        ]
                        """)
                .setHeader("Content-Type", "application/json"));

        var definition = roles("items-array");
        var request = new RemoteReportRequest(
                1, 100, null, List.of(), Map.of(), null, null);

        var result = executor.execute(definition, request);
        assertThat(result.rows()).hasSize(2);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.rows().get(0).get("role")).isEqualTo("admin");
    }

    // ---- Definition validation -------------------------------------- //

    @Test
    @DisplayName("non-remote-http definition rejected")
    void nonRemoteHttpRejected() {
        // SQL-kind definition (legacy)
        var col = new ColumnDefinition("id", "ID", "number", null, false, false, false,
                null, null, false, null);
        var definition = new ReportDefinition(
                "sql-legacy", "1.0", "Legacy", "Test",
                "Test", "users_table", "dbo", "static", null, null,
                List.of(col), null, null, null, null, null, null,
                new ExecutionConfig(ExecutionKind.SQL, null, null, null));

        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, null);

        assertThatThrownBy(() -> executor.execute(definition, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-remote-http report");
    }

    @Test
    @DisplayName("null definition rejected")
    void nullDefinitionRejected() {
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, null);

        assertThatThrownBy(() -> executor.execute(null, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ReportDefinition must not be null");
    }

    @Test
    @DisplayName("null request rejected")
    void nullRequestRejected() {
        var definition = users("paged-items-total");

        assertThatThrownBy(() -> executor.execute(definition, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RemoteReportRequest must not be null");
    }

    // ---- Helper builders ----- //

    private ReportDefinition users(String shape) {
        return buildDefinition("users-overview",
                new ExecutionConfig(ExecutionKind.REMOTE_HTTP,
                        "user-service", "/api/v1/users", shape));
    }

    private ReportDefinition roles(String shape) {
        return buildDefinition("access-report",
                new ExecutionConfig(ExecutionKind.REMOTE_HTTP,
                        "permission-service", "/api/v1/roles", shape));
    }

    private ReportDefinition buildDefinition(String key, ExecutionConfig execution) {
        var col = new ColumnDefinition("id", "ID", "number", null, false, false, false,
                null, null, false, null);
        return new ReportDefinition(
                key, "1.0", "T", "D", "C",
                null, null, "static", null, null,
                List.of(col), null, null, null, null, null, null, execution);
    }
}
