package com.example.report.execution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-D2.1c2 — {@link AgGridFilterTranslator} unit tests.
 *
 * <p>Verifies the 7-op mapping table validated by Codex 019e8306 iter-6
 * against user-service {@code UserControllerV1.buildAdvancedFilterSpecSafe}
 * (source-truth read, not synthetic). Multi-condition, set-filter and
 * unsupported ops MUST fail closed (faz 1 c2.5 candidates).
 */
class AgGridFilterTranslatorTest {

    private final AgGridFilterTranslator translator = new AgGridFilterTranslator();

    /* ----------------------- happy path ----------------------- */

    @Test
    @DisplayName("empty filterModel → empty result map (normalizer omits param)")
    void emptyFilterModel() {
        assertThat(translator.translate(null)).isEmpty();
        assertThat(translator.translate(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("contains: single condition produces flat conditions array")
    void contains() {
        Map<String, Object> agGrid = Map.of(
                "email", Map.of("type", "contains", "filter", "ali@", "filterType", "text"));
        Map<String, Object> result = translator.translate(agGrid);
        assertThat(result).containsEntry("logic", "and");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conds = (List<Map<String, Object>>) result.get("conditions");
        assertThat(conds).hasSize(1);
        assertThat(conds.get(0))
                .containsEntry("field", "email")
                .containsEntry("op", "contains")
                .containsEntry("value", "ali@");
    }

    @Test
    @DisplayName("equals: text value preserved verbatim")
    void equals_() {
        Map<String, Object> agGrid = Map.of(
                "role", Map.of("type", "equals", "filter", "ADMIN"));
        Map<String, Object> result = translator.translate(agGrid);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conds = (List<Map<String, Object>>) result.get("conditions");
        assertThat(conds.get(0))
                .containsEntry("op", "equals")
                .containsEntry("value", "ADMIN");
    }

    @Test
    @DisplayName("notEqual: AG-Grid uses 'notEqual' (no s); downstream same")
    void notEqual() {
        Map<String, Object> agGrid = Map.of(
                "role", Map.of("type", "notEqual", "filter", "VIEWER"));
        Map<String, Object> result = translator.translate(agGrid);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conds = (List<Map<String, Object>>) result.get("conditions");
        assertThat(conds.get(0))
                .containsEntry("op", "notEqual")
                .containsEntry("value", "VIEWER");
    }

    @Test
    @DisplayName("notContains: maps verbatim")
    void notContains() {
        Map<String, Object> agGrid = Map.of(
                "email", Map.of("type", "notContains", "filter", "spam"));
        Map<String, Object> result = translator.translate(agGrid);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conds = (List<Map<String, Object>>) result.get("conditions");
        assertThat(conds.get(0)).containsEntry("op", "notContains");
    }

    @Test
    @DisplayName("lessThan / greaterThan: numeric values preserved")
    void lessThanGreaterThan() {
        Map<String, Object> agGridLt = Map.of(
                "age", Map.of("type", "lessThan", "filter", 18));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> condsLt = (List<Map<String, Object>>)
                translator.translate(agGridLt).get("conditions");
        assertThat(condsLt.get(0))
                .containsEntry("op", "lessThan")
                .containsEntry("value", 18);

        Map<String, Object> agGridGt = Map.of(
                "age", Map.of("type", "greaterThan", "filter", 65));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> condsGt = (List<Map<String, Object>>)
                translator.translate(agGridGt).get("conditions");
        assertThat(condsGt.get(0))
                .containsEntry("op", "greaterThan")
                .containsEntry("value", 65);
    }

    @Test
    @DisplayName("inRange: filterTo becomes value2 in downstream payload")
    void inRange() {
        Map<String, Object> agGrid = Map.of(
                "age", Map.of("type", "inRange", "filter", 18, "filterTo", 65));
        Map<String, Object> result = translator.translate(agGrid);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conds = (List<Map<String, Object>>) result.get("conditions");
        assertThat(conds.get(0))
                .containsEntry("op", "inRange")
                .containsEntry("value", 18)
                .containsEntry("value2", 65);
    }

    @Test
    @DisplayName("multiple fields → multiple conditions under root 'and'")
    void multipleFields() {
        Map<String, Object> agGrid = new LinkedHashMap<>();
        agGrid.put("email", Map.of("type", "contains", "filter", "ali"));
        agGrid.put("role", Map.of("type", "equals", "filter", "ADMIN"));
        Map<String, Object> result = translator.translate(agGrid);
        assertThat(result).containsEntry("logic", "and");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conds = (List<Map<String, Object>>) result.get("conditions");
        assertThat(conds).hasSize(2);
        assertThat(conds.get(0)).containsEntry("field", "email");
        assertThat(conds.get(1)).containsEntry("field", "role");
    }

    @Test
    @DisplayName("case-insensitive type: 'CONTAINS', 'Contains' both accepted")
    void caseInsensitiveType() {
        Map<String, Object> agGrid = Map.of(
                "email", Map.of("type", "Contains", "filter", "ali"));
        Map<String, Object> result = translator.translate(agGrid);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conds = (List<Map<String, Object>>) result.get("conditions");
        // Downstream op is canonical lowercase
        assertThat(conds.get(0)).containsEntry("op", "contains");
    }

    /* --------------------- known-unsupported ops --------------------- */

    @Test
    @DisplayName("startsWith → IllegalArgumentException (c2.5 candidate)")
    void startsWithRejected() {
        Map<String, Object> agGrid = Map.of(
                "email", Map.of("type", "startsWith", "filter", "admin"));
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startsWith")
                .hasMessageContaining("not supported");
    }

    @Test
    @DisplayName("endsWith → reject")
    void endsWithRejected() {
        Map<String, Object> agGrid = Map.of(
                "email", Map.of("type", "endsWith", "filter", "@acme.com"));
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endsWith");
    }

    @Test
    @DisplayName("lessThanOrEqual → reject (downstream has only strict lessThan)")
    void lessThanOrEqualRejected() {
        Map<String, Object> agGrid = Map.of(
                "age", Map.of("type", "lessThanOrEqual", "filter", 18));
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lessThanOrEqual");
    }

    @Test
    @DisplayName("greaterThanOrEqual → reject")
    void greaterThanOrEqualRejected() {
        Map<String, Object> agGrid = Map.of(
                "age", Map.of("type", "greaterThanOrEqual", "filter", 65));
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greaterThanOrEqual");
    }

    @Test
    @DisplayName("blank / notBlank → reject")
    void blankRejected() {
        Map<String, Object> agGridBlank = Map.of(
                "email", Map.of("type", "blank"));
        assertThatThrownBy(() -> translator.translate(agGridBlank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");

        Map<String, Object> agGridNotBlank = Map.of(
                "email", Map.of("type", "notBlank"));
        assertThatThrownBy(() -> translator.translate(agGridNotBlank))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notBlank");
    }

    @Test
    @DisplayName("unknown type → reject with supported list")
    void unknownTypeRejected() {
        Map<String, Object> agGrid = Map.of(
                "email", Map.of("type", "fuzzy-match", "filter", "ali"));
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown")
                .hasMessageContaining("supported types");
    }

    /* --------------------- shape rejection --------------------- */

    @Test
    @DisplayName("multi-condition per field (condition1/condition2/operator) → reject")
    void multiConditionRejected() {
        Map<String, Object> agGrid = Map.of(
                "email", Map.of(
                        "filterType", "text",
                        "operator", "OR",
                        "condition1", Map.of("type", "contains", "filter", "ali"),
                        "condition2", Map.of("type", "contains", "filter", "veli")));
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multi-condition")
                .hasMessageContaining("c2.5");
    }

    @Test
    @DisplayName("AG-Grid v32 conditions array → reject (same family)")
    void conditionsArrayRejected() {
        Map<String, Object> agGrid = Map.of(
                "email", Map.of(
                        "filterType", "text",
                        "operator", "AND",
                        "conditions", List.of(
                                Map.of("type", "contains", "filter", "ali"),
                                Map.of("type", "contains", "filter", "veli"))));
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multi-condition");
    }

    @Test
    @DisplayName("set filter (filterType=set) → reject (c2.5 op:in widening)")
    void setFilterRejected() {
        Map<String, Object> agGrid = Map.of(
                "role", Map.of(
                        "filterType", "set",
                        "values", List.of("ADMIN", "VIEWER")));
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("set-filter")
                .hasMessageContaining("op:'in'");
    }

    @Test
    @DisplayName("array 'values' payload without filterType=set declaration → reject")
    void arrayValuesPayloadRejected() {
        Map<String, Object> agGrid = Map.of(
                "role", Map.of("values", List.of("ADMIN", "VIEWER")));
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("set-filter");
    }

    @Test
    @DisplayName("missing 'type' field → reject")
    void missingTypeRejected() {
        Map<String, Object> agGrid = Map.of(
                "email", Map.of("filter", "ali"));
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required 'type'");
    }

    @Test
    @DisplayName("missing 'filter' value → reject")
    void missingFilterValueRejected() {
        Map<String, Object> agGrid = Map.of(
                "email", Map.of("type", "contains"));
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required 'filter'");
    }

    @Test
    @DisplayName("inRange missing 'filterTo' → reject")
    void inRangeMissingFilterToRejected() {
        Map<String, Object> agGrid = Map.of(
                "age", Map.of("type", "inRange", "filter", 18));
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inRange")
                .hasMessageContaining("filterTo")
                .hasMessageContaining("value2");
    }

    @Test
    @DisplayName("blank field name → reject")
    void blankFieldRejected() {
        // Using a HashMap to allow blank key in test
        Map<String, Object> agGrid = new java.util.HashMap<>();
        agGrid.put("", Map.of("type", "contains", "filter", "ali"));
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank field");
    }

    @Test
    @DisplayName("non-object spec value → reject")
    void nonObjectSpecRejected() {
        Map<String, Object> agGrid = Map.of("email", "ali@example.com");
        assertThatThrownBy(() -> translator.translate(agGrid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
    }
}
