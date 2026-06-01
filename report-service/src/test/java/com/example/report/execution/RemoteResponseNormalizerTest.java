package com.example.report.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-D2.1c1 — {@link RemoteResponseNormalizer} unit tests.
 *
 * <p>Two supported shapes: {@code paged-items-total} (user-service /
 * permission-service standard) and {@code items-array} (flat).
 */
class RemoteResponseNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RemoteResponseNormalizer normalizer = new RemoteResponseNormalizer();

    @Test
    @DisplayName("paged-items-total happy path: items + total")
    void pagedItemsTotalHappy() throws Exception {
        JsonNode body = mapper.readTree("""
                {
                  "items": [
                    {"id": 1, "name": "Ali", "active": true},
                    {"id": 2, "name": "Veli", "active": false}
                  ],
                  "total": 42
                }
                """);
        var result = normalizer.normalize(
                RemoteResponseNormalizer.SHAPE_PAGED_ITEMS_TOTAL,
                body, "user-service", "/api/v1/users");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.total()).isEqualTo(42);
        assertThat(result.rows().get(0).get("name")).isEqualTo("Ali");
        assertThat(result.rows().get(0).get("active")).isEqualTo(true);
    }

    @Test
    @DisplayName("paged-items-total missing items rejected")
    void pagedItemsTotalMissingItems() throws Exception {
        JsonNode body = mapper.readTree("""
                { "total": 0 }
                """);
        assertThatThrownBy(() -> normalizer.normalize(
                RemoteResponseNormalizer.SHAPE_PAGED_ITEMS_TOTAL,
                body, "user-service", "/api/v1/users"))
                .isInstanceOf(RemoteExecutionException.class)
                .hasMessageContaining("'items' array field");
    }

    @Test
    @DisplayName("paged-items-total missing total rejected")
    void pagedItemsTotalMissingTotal() throws Exception {
        JsonNode body = mapper.readTree("""
                { "items": [] }
                """);
        assertThatThrownBy(() -> normalizer.normalize(
                RemoteResponseNormalizer.SHAPE_PAGED_ITEMS_TOTAL,
                body, "user-service", "/api/v1/users"))
                .isInstanceOf(RemoteExecutionException.class)
                .hasMessageContaining("'total' integer field");
    }

    @Test
    @DisplayName("paged-items-total negative total rejected")
    void pagedItemsTotalNegativeTotal() throws Exception {
        JsonNode body = mapper.readTree("""
                { "items": [], "total": -1 }
                """);
        assertThatThrownBy(() -> normalizer.normalize(
                RemoteResponseNormalizer.SHAPE_PAGED_ITEMS_TOTAL,
                body, "user-service", "/api/v1/users"))
                .isInstanceOf(RemoteExecutionException.class)
                .hasMessageContaining("'total' must be >= 0");
    }

    @Test
    @DisplayName("paged-items-total array body rejected (expects object)")
    void pagedItemsTotalArrayBody() throws Exception {
        JsonNode body = mapper.readTree("[1,2,3]");
        assertThatThrownBy(() -> normalizer.normalize(
                RemoteResponseNormalizer.SHAPE_PAGED_ITEMS_TOTAL,
                body, "user-service", "/api/v1/users"))
                .isInstanceOf(RemoteExecutionException.class)
                .hasMessageContaining("expected JSON object");
    }

    @Test
    @DisplayName("items-array happy path: total = array length")
    void itemsArrayHappy() throws Exception {
        JsonNode body = mapper.readTree("""
                [
                  {"role": "admin"},
                  {"role": "viewer"},
                  {"role": "operator"}
                ]
                """);
        var result = normalizer.normalize(
                RemoteResponseNormalizer.SHAPE_ITEMS_ARRAY,
                body, "permission-service", "/api/v1/roles");
        assertThat(result.rows()).hasSize(3);
        assertThat(result.total()).isEqualTo(3);
    }

    @Test
    @DisplayName("items-array empty array → empty result")
    void itemsArrayEmpty() throws Exception {
        JsonNode body = mapper.readTree("[]");
        var result = normalizer.normalize(
                RemoteResponseNormalizer.SHAPE_ITEMS_ARRAY,
                body, "permission-service", "/api/v1/roles");
        assertThat(result.rows()).isEmpty();
        assertThat(result.total()).isEqualTo(0);
    }

    @Test
    @DisplayName("items-array object body rejected")
    void itemsArrayObjectBody() throws Exception {
        JsonNode body = mapper.readTree("{ \"foo\": \"bar\" }");
        assertThatThrownBy(() -> normalizer.normalize(
                RemoteResponseNormalizer.SHAPE_ITEMS_ARRAY,
                body, "permission-service", "/api/v1/roles"))
                .isInstanceOf(RemoteExecutionException.class)
                .hasMessageContaining("expected JSON array");
    }

    @Test
    @DisplayName("null body rejected with structured exception")
    void nullBodyRejected() {
        assertThatThrownBy(() -> normalizer.normalize(
                RemoteResponseNormalizer.SHAPE_PAGED_ITEMS_TOTAL,
                null, "user-service", "/api/v1/users"))
                .isInstanceOf(RemoteExecutionException.class)
                .hasMessageContaining("downstream response body was null");
    }

    @Test
    @DisplayName("unknown shape rejected")
    void unknownShapeRejected() throws Exception {
        JsonNode body = mapper.readTree("{}");
        assertThatThrownBy(() -> normalizer.normalize(
                "audit-events-v1", body, "x", "/y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown responseShape: audit-events-v1");
    }

    @Test
    @DisplayName("non-object row → RemoteExecutionException (Codex iter-3 Low absorb)")
    void nonObjectRowFailsClosed() throws Exception {
        JsonNode body = mapper.readTree("""
                {
                  "items": [
                    {"id": 1, "name": "Ali"},
                    "scalar-row-string",
                    {"id": 3, "name": "Veli"}
                  ],
                  "total": 3
                }
                """);
        // Codex 019e8306 iter-3 Low absorb: silent drop would create
        // total ↔ rows.size() drift. Grid contract requires object rows.
        assertThatThrownBy(() -> normalizer.normalize(
                RemoteResponseNormalizer.SHAPE_PAGED_ITEMS_TOTAL,
                body, "user-service", "/api/v1/users"))
                .isInstanceOf(RemoteExecutionException.class)
                .hasMessageContaining("row at index 1")
                .hasMessageContaining("not a JSON object");
    }

    @Test
    @DisplayName("non-object row in items-array shape also fails closed")
    void nonObjectRowFailsClosedItemsArray() throws Exception {
        JsonNode body = mapper.readTree("""
                [
                  {"role": "admin"},
                  42,
                  {"role": "viewer"}
                ]
                """);
        assertThatThrownBy(() -> normalizer.normalize(
                RemoteResponseNormalizer.SHAPE_ITEMS_ARRAY,
                body, "permission-service", "/api/v1/roles"))
                .isInstanceOf(RemoteExecutionException.class)
                .hasMessageContaining("row at index 1")
                .hasMessageContaining("not a JSON object");
    }

    @Test
    @DisplayName("primitive values converted properly")
    void primitiveValueConversion() throws Exception {
        JsonNode body = mapper.readTree("""
                {
                  "items": [
                    {"id": 1, "rating": 4.5, "name": "Ali", "active": true, "deleted": null}
                  ],
                  "total": 1
                }
                """);
        var result = normalizer.normalize(
                RemoteResponseNormalizer.SHAPE_PAGED_ITEMS_TOTAL,
                body, "user-service", "/api/v1/users");
        var row = result.rows().get(0);
        assertThat(row.get("id")).isEqualTo(1L);  // Integer → Long
        assertThat(row.get("rating")).isEqualTo(4.5);
        assertThat(row.get("name")).isEqualTo("Ali");
        assertThat(row.get("active")).isEqualTo(true);
        assertThat(row.get("deleted")).isNull();
    }
}
