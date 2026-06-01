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
        assertThatThrownBy(() -> normalizer.toQueryParams("aggregation-mart-v1", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown requestShape: aggregation-mart-v1");
    }

    /* PR-D2.1c5: audit-events-v1 shape tests */

    @Test
    @DisplayName("audit-events-v1 maps page + pageSize + search")
    void auditEventsV1MapsPagePageSizeSearch() {
        var request = new RemoteReportRequest(
                3, 25, "kullanici ali", List.of(), Map.of(), null, null);
        var params = normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_AUDIT_EVENTS_V1, request);
        assertThat(params.getFirst("page")).isEqualTo("3");
        assertThat(params.getFirst("pageSize")).isEqualTo("25");
        assertThat(params.getFirst("search")).isEqualTo("kullanici ali");
    }

    @Test
    @DisplayName("audit-events-v1 direct fields go to plain query params (NOT filter[...])")
    void auditEventsV1DirectFieldsPlainParams() {
        var conditions = List.<Map<String, Object>>of(
                Map.of("field", "dateFrom", "op", "equals", "value", "2026-06-01"),
                Map.of("field", "dateTo", "op", "equals", "value", "2026-06-30"),
                Map.of("field", "action", "op", "equals", "value", "SESSION_CREATED"),
                Map.of("field", "level", "op", "equals", "value", "INFO"),
                Map.of("field", "service", "op", "equals", "value", "auth-service"));
        var advancedFilter = new java.util.LinkedHashMap<String, Object>();
        advancedFilter.put("logic", "and");
        advancedFilter.put("conditions", conditions);

        var request = new RemoteReportRequest(
                1, 50, null, List.of(), advancedFilter, null, null);
        var params = normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_AUDIT_EVENTS_V1, request);
        assertThat(params.getFirst("dateFrom")).isEqualTo("2026-06-01");
        assertThat(params.getFirst("dateTo")).isEqualTo("2026-06-30");
        assertThat(params.getFirst("action")).isEqualTo("SESSION_CREATED");
        assertThat(params.getFirst("level")).isEqualTo("INFO");
        assertThat(params.getFirst("service")).isEqualTo("auth-service");
        // No filter[xxx] for direct fields
        assertThat(params).doesNotContainKey("filter[dateFrom]");
    }

    @Test
    @DisplayName("audit-events-v1 non-direct fields go to filter[<field>]=<value>")
    void auditEventsV1NonDirectFieldsFilterPattern() {
        var conditions = List.<Map<String, Object>>of(
                Map.of("field", "customField", "op", "equals", "value", "abc"));
        var advancedFilter = new java.util.LinkedHashMap<String, Object>();
        advancedFilter.put("logic", "and");
        advancedFilter.put("conditions", conditions);

        var request = new RemoteReportRequest(
                1, 50, null, List.of(), advancedFilter, null, null);
        var params = normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_AUDIT_EVENTS_V1, request);
        assertThat(params.getFirst("filter[customField]")).isEqualTo("abc");
    }

    @Test
    @DisplayName("audit-events-v1 single sort field,dir honored")
    void auditEventsV1SingleSortHonored() {
        var request = new RemoteReportRequest(
                1, 50, null,
                List.of(new RemoteReportRequest.SortEntry("timestamp", "DESC")),
                Map.of(), null, null);
        var params = normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_AUDIT_EVENTS_V1, request);
        assertThat(params.getFirst("sort")).isEqualTo("timestamp,desc");
    }

    @Test
    @DisplayName("audit-events-v1 multi-sort throws RemoteRequestNormalizationException")
    void auditEventsV1MultiSortRejects() {
        var request = new RemoteReportRequest(
                1, 50, null,
                List.of(
                        new RemoteReportRequest.SortEntry("timestamp", "DESC"),
                        new RemoteReportRequest.SortEntry("action", "ASC")),
                Map.of(), null, null);
        assertThatThrownBy(() -> normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_AUDIT_EVENTS_V1, request))
                .isInstanceOf(RemoteRequestNormalizationException.class)
                .matches(ex -> "REMOTE_SORT_MULTI_UNSUPPORTED".equals(
                        ((RemoteRequestNormalizationException) ex).code()));
    }

    @Test
    @DisplayName("audit-events-v1 logic=or with multi conditions throws")
    void auditEventsV1LogicOrRejects() {
        var conditions = List.<Map<String, Object>>of(
                Map.of("field", "action", "op", "equals", "value", "LOGIN"),
                Map.of("field", "action", "op", "equals", "value", "LOGOUT"));
        var advancedFilter = new java.util.LinkedHashMap<String, Object>();
        advancedFilter.put("logic", "or");
        advancedFilter.put("conditions", conditions);

        var request = new RemoteReportRequest(
                1, 50, null, List.of(), advancedFilter, null, null);
        assertThatThrownBy(() -> normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_AUDIT_EVENTS_V1, request))
                .isInstanceOf(RemoteRequestNormalizationException.class)
                .matches(ex -> "REMOTE_FILTER_OR_UNSUPPORTED".equals(
                        ((RemoteRequestNormalizationException) ex).code()));
    }

    @Test
    @DisplayName("audit-events-v1 NO advancedFilter JSON param (downstream does not parse)")
    void auditEventsV1NoAdvancedFilterParam() {
        var conditions = List.<Map<String, Object>>of(
                Map.of("field", "action", "op", "equals", "value", "LOGIN"));
        var advancedFilter = new java.util.LinkedHashMap<String, Object>();
        advancedFilter.put("logic", "and");
        advancedFilter.put("conditions", conditions);

        var request = new RemoteReportRequest(
                1, 50, null, List.of(), advancedFilter, null, null);
        var params = normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_AUDIT_EVENTS_V1, request);
        assertThat(params).doesNotContainKey("advancedFilter");
        assertThat(params.getFirst("action")).isEqualTo("LOGIN");
    }

    @Test
    @DisplayName("audit-events-v1 empty advancedFilter emits no filter params")
    void auditEventsV1EmptyFilterClean() {
        var request = new RemoteReportRequest(
                1, 50, null, List.of(), Map.of(), null, null);
        var params = normalizer.toQueryParams(
                RemoteRequestNormalizer.SHAPE_AUDIT_EVENTS_V1, request);
        assertThat(params).hasSize(2); // only page + pageSize
        assertThat(params).containsKey("page");
        assertThat(params).containsKey("pageSize");
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
