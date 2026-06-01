package com.example.report.execution;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-D2.1c1 — {@link RemoteRequestNormalizer} unit tests.
 *
 * <p>Codex 019e8306 iter-2: request normalizer maps dynamic-report's
 * AG-Grid request shape to user-service / permission-service's API
 * format. {@code style-api-paged-v1} = page/pageSize/search/sort
 * (semicolon+comma).
 */
class RemoteRequestNormalizerTest {

    private final RemoteRequestNormalizer normalizer = new RemoteRequestNormalizer();

    @Test
    @DisplayName("style-api-paged-v1 maps page + pageSize")
    void mapsPageAndPageSize() {
        var request = new RemoteReportRequest(
                2, 50, null, List.of(), Map.of(), null, null);
        var params = normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_STYLE_API_PAGED_V1, request);
        assertThat(params.getFirst("page")).isEqualTo("2");
        assertThat(params.getFirst("pageSize")).isEqualTo("50");
    }

    @Test
    @DisplayName("search included when non-blank")
    void searchIncludedWhenNonBlank() {
        var request = new RemoteReportRequest(
                1, 25, "ali kart", List.of(), Map.of(), null, null);
        var params = normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_STYLE_API_PAGED_V1, request);
        assertThat(params.getFirst("search")).isEqualTo("ali kart");
    }

    @Test
    @DisplayName("search omitted when blank")
    void searchOmittedWhenBlank() {
        var request = new RemoteReportRequest(
                1, 25, "   ", List.of(), Map.of(), null, null);
        var params = normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_STYLE_API_PAGED_V1, request);
        assertThat(params).doesNotContainKey("search");
    }

    @Test
    @DisplayName("sort serialized as field,dir;field2,dir2")
    void sortSerialization() {
        var request = new RemoteReportRequest(
                1, 25, null,
                List.of(
                        new RemoteReportRequest.SortEntry("EMPLOYEE_ID", "ASC"),
                        new RemoteReportRequest.SortEntry("FULL_NAME", "desc")),
                Map.of(), null, null);
        var params = normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_STYLE_API_PAGED_V1, request);
        assertThat(params.getFirst("sort")).isEqualTo("EMPLOYEE_ID,asc;FULL_NAME,desc");
    }

    @Test
    @DisplayName("empty sort list omits sort param entirely")
    void emptySortOmitted() {
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, null);
        var params = normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_STYLE_API_PAGED_V1, request);
        assertThat(params).doesNotContainKey("sort");
    }

    @Test
    @DisplayName("advancedFilter serialized as single JSON-string param matching user-service {logic, conditions} (Codex iter-4 PARTIAL absorb)")
    void advancedFilterAsJsonStringLogicConditions() {
        // Codex 019e8306 iter-5 absorb: user-service contract is
        // {logic, conditions:[{field, op, value}]} (NOT 'operator' — see
        // UserControllerV1.java:640 + UserSecurityIntegrationTest.java:237).
        // The normalizer is transport-only; serializes verbatim. PR-D2.1c2
        // dispatcher translates AG-Grid filter model → this shape.
        var conditions = List.of(
                Map.of("field", "email", "op", "contains", "value", "ali@example.com"),
                Map.of("field", "role", "op", "equals", "value", "ADMIN"));
        var advancedFilter = new java.util.LinkedHashMap<String, Object>();
        advancedFilter.put("logic", "and");
        advancedFilter.put("conditions", conditions);

        var request = new RemoteReportRequest(
                1, 25, null, List.of(),
                advancedFilter,
                null, null);
        var params = normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_STYLE_API_PAGED_V1, request);

        assertThat(params).containsKey("advancedFilter");
        // Caller-shaped payload NOT field-keyed; transport one param
        assertThat(params).doesNotContainKey("logic");
        assertThat(params).doesNotContainKey("conditions");

        String json = params.getFirst("advancedFilter");
        // user-service UserControllerV1.decodeAdvancedFilter contract roundtrip-able
        assertThat(json).contains("\"logic\":\"and\"");
        assertThat(json).contains("\"conditions\":[");
        assertThat(json).contains("\"field\":\"email\"");
        assertThat(json).contains("\"op\":\"contains\"");
        assertThat(json).contains("\"value\":\"ali@example.com\"");
    }

    @Test
    @DisplayName("advancedFilter empty map omits param")
    void advancedFilterEmptyOmitted() {
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, null);
        var params = normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_STYLE_API_PAGED_V1, request);
        assertThat(params).doesNotContainKey("advancedFilter");
    }

    @Test
    @DisplayName("unknown shape rejected")
    void unknownShapeRejected() {
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, null);
        assertThatThrownBy(() -> normalizer.toQueryParams("audit-events-v1", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown requestShape: audit-events-v1");
    }

    @Test
    @DisplayName("null shape rejected")
    void nullShapeRejected() {
        var request = new RemoteReportRequest(
                1, 25, null, List.of(), Map.of(), null, null);
        assertThatThrownBy(() -> normalizer.toQueryParams(null, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestShape must not be blank");
    }

    @Test
    @DisplayName("SortEntry rejects invalid direction")
    void sortEntryInvalidDirection() {
        assertThatThrownBy(() -> new RemoteReportRequest.SortEntry("FULL_NAME", "random"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'asc' or 'desc'");
    }

    @Test
    @DisplayName("SortEntry accepts case-insensitive direction")
    void sortEntryCaseInsensitive() {
        // Both ASC and asc should be accepted
        var entry1 = new RemoteReportRequest.SortEntry("FULL_NAME", "ASC");
        var entry2 = new RemoteReportRequest.SortEntry("FULL_NAME", "asc");
        var entry3 = new RemoteReportRequest.SortEntry("FULL_NAME", "DESC");
        assertThat(entry1.sort()).isEqualTo("ASC");
        assertThat(entry2.sort()).isEqualTo("asc");
        assertThat(entry3.sort()).isEqualTo("DESC");
    }
}
