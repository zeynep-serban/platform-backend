package com.example.report.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-D2.1b (ADR-0015) — unit tests for the report execution adapter
 * config record + integration with ReportDefinition.
 *
 * <p>Covers: ExecutionKind wire form roundtrip, ExecutionConfig field
 * validation (REMOTE_HTTP and SQL kinds), and ReportDefinition's
 * remote-http exemption from sourceQuery requirement.
 */
class ExecutionConfigTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // ---- ExecutionKind wire form ---------------------------------------- //

    @Test
    @DisplayName("ExecutionKind serializes as wire string (sql, remote-http)")
    void executionKindWireForm() {
        assertThat(ExecutionKind.SQL.wire()).isEqualTo("sql");
        assertThat(ExecutionKind.REMOTE_HTTP.wire()).isEqualTo("remote-http");
        assertThat(ExecutionKind.fromWire("sql")).isEqualTo(ExecutionKind.SQL);
        assertThat(ExecutionKind.fromWire("remote-http")).isEqualTo(ExecutionKind.REMOTE_HTTP);
        assertThat(ExecutionKind.fromWire("REMOTE-HTTP")).isEqualTo(ExecutionKind.REMOTE_HTTP); // case-insensitive
        assertThat(ExecutionKind.fromWire(null)).isNull();
    }

    @Test
    @DisplayName("ExecutionKind rejects unknown wire value")
    void executionKindRejectsUnknown() {
        assertThatThrownBy(() -> ExecutionKind.fromWire("aggregation-mart"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown ExecutionKind wire value: aggregation-mart");
    }

    // ---- ExecutionConfig field validation -------------------------------- //

    @Test
    @DisplayName("ExecutionConfig requires kind")
    void executionConfigRequiresKind() {
        assertThatThrownBy(() -> new ExecutionConfig(null, "user-service", "/api/v1/users", "paged-items-total"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind must not be null");
    }

    @Test
    @DisplayName("ExecutionConfig REMOTE_HTTP requires service + path + responseShape")
    void executionConfigRemoteHttpFields() {
        // Happy path
        var ok = new ExecutionConfig(ExecutionKind.REMOTE_HTTP, "user-service",
                "/api/v1/users", "paged-items-total");
        assertThat(ok.kind()).isEqualTo(ExecutionKind.REMOTE_HTTP);

        // Missing service
        assertThatThrownBy(() -> new ExecutionConfig(ExecutionKind.REMOTE_HTTP, null,
                "/api/v1/users", "paged-items-total"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service must not be blank");

        // Missing path
        assertThatThrownBy(() -> new ExecutionConfig(ExecutionKind.REMOTE_HTTP, "user-service",
                "", "paged-items-total"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path must not be blank");

        // Missing responseShape
        assertThatThrownBy(() -> new ExecutionConfig(ExecutionKind.REMOTE_HTTP, "user-service",
                "/api/v1/users", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("responseShape must not be blank");
    }

    @Test
    @DisplayName("ExecutionConfig SQL kind forbids remote-http fields")
    void executionConfigSqlForbidsRemoteFields() {
        // Happy path
        var ok = new ExecutionConfig(ExecutionKind.SQL, null, null, null);
        assertThat(ok.kind()).isEqualTo(ExecutionKind.SQL);

        // SQL + service = error
        assertThatThrownBy(() -> new ExecutionConfig(ExecutionKind.SQL, "user-service", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service must be null for kind=sql");

        // SQL + path = error
        assertThatThrownBy(() -> new ExecutionConfig(ExecutionKind.SQL, null, "/api/v1/users", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path must be null for kind=sql");

        // SQL + responseShape = error
        assertThatThrownBy(() -> new ExecutionConfig(ExecutionKind.SQL, null, null, "paged-items-total"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("responseShape must be null for kind=sql");
    }

    // ---- JSON roundtrip -------------------------------------------------- //

    @Test
    @DisplayName("ExecutionConfig JSON roundtrip — remote-http")
    void executionConfigJsonRoundtripRemoteHttp() throws Exception {
        var cfg = new ExecutionConfig(ExecutionKind.REMOTE_HTTP, "user-service",
                "/api/v1/users", "paged-items-total");
        String json = mapper.writeValueAsString(cfg);

        // Wire form: kind serializes as "remote-http", all 4 fields present
        assertThat(json)
                .contains("\"kind\":\"remote-http\"")
                .contains("\"service\":\"user-service\"")
                .contains("\"path\":\"/api/v1/users\"")
                .contains("\"responseShape\":\"paged-items-total\"");

        // Deserialization roundtrip
        ExecutionConfig back = mapper.readValue(json, ExecutionConfig.class);
        assertThat(back).isEqualTo(cfg);
    }

    @Test
    @DisplayName("ExecutionConfig JSON roundtrip — sql (only kind serialized)")
    void executionConfigJsonRoundtripSql() throws Exception {
        var cfg = new ExecutionConfig(ExecutionKind.SQL, null, null, null);
        String json = mapper.writeValueAsString(cfg);

        // NON_NULL: only "kind" present (service/path/responseShape null are omitted)
        assertThat(json)
                .contains("\"kind\":\"sql\"")
                .doesNotContain("service")
                .doesNotContain("path")
                .doesNotContain("responseShape");

        // Deserialization
        ExecutionConfig back = mapper.readValue(json, ExecutionConfig.class);
        assertThat(back).isEqualTo(cfg);
    }

    // ---- ReportDefinition integration ----------------------------------- //

    @Test
    @DisplayName("ReportDefinition remote-http executor is exempt from sourceQuery requirement")
    void reportDefinitionRemoteHttpExemptFromSourceQuery() {
        var col = new ColumnDefinition("user_id", "ID", "number", null, false, false, false, null, null, false, null);
        var execution = new ExecutionConfig(ExecutionKind.REMOTE_HTTP, "user-service",
                "/api/v1/users", "paged-items-total");

        // Happy path: no source, no sourceQuery — but remote-http executor OK
        var def = new ReportDefinition(
                "users-overview", "1.0", "Kullanıcılar", "Test",
                "Sistem", null, null, "static", null, null,
                List.of(col), null, null, null, null, null, null, execution);

        assertThat(def.isRemoteHttp()).isTrue();
        assertThat(def.execution()).isEqualTo(execution);
        assertThat(def.sourceQuery()).isNull();
        assertThat(def.source()).isNull();
    }

    @Test
    @DisplayName("ReportDefinition without execution still requires source or sourceQuery (legacy SQL)")
    void reportDefinitionWithoutExecutionRequiresSourceOrQuery() {
        var col = new ColumnDefinition("id", "ID", "number", null, false, false, false, null, null, false, null);

        // No source + no sourceQuery + no execution → IllegalArgumentException
        assertThatThrownBy(() -> new ReportDefinition(
                "broken-report", "1.0", "T", "D", "C",
                null, null, "static", null, null,
                List.of(col), null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source")
                .hasMessageContaining("sourceQuery");
    }

    // ---- Codex iter-2 non-blocking gap absorb ----- //

    @Test
    @DisplayName("ReportDefinition execution=SQL also requires source or sourceQuery (explicit)")
    void reportDefinitionExplicitSqlRequiresSourceOrQuery() {
        var col = new ColumnDefinition("id", "ID", "number", null, false, false, false, null, null, false, null);
        var sqlExecution = new ExecutionConfig(ExecutionKind.SQL, null, null, null);

        // Explicit SQL kind + no source + no sourceQuery → IllegalArgumentException
        // (Codex iter-2 gap absorb: legacy invariant aynı zamanda explicit SQL'de
        // kilitlenmeli — null-execution path'i ile aynı semantic).
        assertThatThrownBy(() -> new ReportDefinition(
                "broken-sql-report", "1.0", "T", "D", "C",
                null, null, "static", null, null,
                List.of(col), null, null, null, null, null, null, sqlExecution))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source")
                .hasMessageContaining("sourceQuery");
    }

    @Test
    @DisplayName("ExecutionConfig REMOTE_HTTP rejects whitespace-only fields")
    void executionConfigRemoteHttpRejectsWhitespace() {
        // Codex iter-2 gap absorb: isBlank() guard'ı whitespace-only string'i
        // de yakalamalı (sadece "" değil "   " gibi).
        assertThatThrownBy(() -> new ExecutionConfig(ExecutionKind.REMOTE_HTTP, "   ",
                "/api/v1/users", "paged-items-total"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("service must not be blank");

        assertThatThrownBy(() -> new ExecutionConfig(ExecutionKind.REMOTE_HTTP, "user-service",
                "   ", "paged-items-total"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path must not be blank");

        assertThatThrownBy(() -> new ExecutionConfig(ExecutionKind.REMOTE_HTTP, "user-service",
                "/api/v1/users", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("responseShape must not be blank");
    }
}
