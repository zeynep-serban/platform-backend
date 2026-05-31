package com.example.report.contract;

import com.example.report.contract.report.ContractViolation;
import com.example.report.contract.schema.ReportDefinitionSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.DefaultResourceLoader;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.FilterDefinition;
import com.example.report.registry.FilterKind;
import com.example.report.registry.FilterOptionEntry;
import com.example.report.registry.FilterOptionsSource;
import com.example.report.registry.FilterOptionsSourceType;
import com.example.report.registry.StatusMapEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-D1a (Codex thread {@code 019e800b}, 2026-05-31) — contract test for the
 * report definition schema + record extension covering:
 * <ul>
 *   <li>positive: extended {@link ColumnDefinition} variant + config fields accepted;</li>
 *   <li>positive: {@link FilterDefinition} array accepted on the top-level report;</li>
 *   <li>positive: {@code routeSegment} + {@code sharedReportId} accepted on top-level;</li>
 *   <li>negative: unknown column {@code type} value rejected at both record + schema layer;</li>
 *   <li>negative: malformed {@link StatusMapEntry} missing {@code labelKey} rejected;</li>
 *   <li>negative: unknown {@link FilterKind} wire value rejected at both record + schema layer.</li>
 * </ul>
 *
 * <p>These tests sit alongside the pre-existing {@code ReportContractGateTest} which
 * proves all 32 production JSON files validate against the schema; D1a's extension
 * must not break that, AND must accept the new fields when present.
 */
@DisplayName("PR-D1a report contract extension")
class D1aReportContractExtensionTest {

    private static ReportDefinitionSchemaValidator validator() {
        return new ReportDefinitionSchemaValidator(
                new ObjectMapper().findAndRegisterModules(),
                "classpath:contract/report-definition.schema.json",
                new DefaultResourceLoader());
    }

    /* ------------------------------------------------------------------ */
    /*  Java record construction — positive                                */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("ColumnDefinition accepts badge variant + variantMap + labelMap + filterValues")
    void recordAcceptsBadgeColumn() {
        ColumnDefinition column = new ColumnDefinition(
                "ROLE", "Role", "badge", 140, false,
                false, false, null, null, false, null,
                Map.of("ADMIN", "danger", "USER", "info"),
                Map.of("ADMIN", "shared.role.admin"),
                null, null, null, null, null, "info",
                List.of("ADMIN", "USER"));
        assertNotNull(column.variantMap());
        assertEquals("danger", column.variantMap().get("ADMIN"));
        assertEquals("info", column.defaultVariant());
        assertEquals(2, column.filterValues().size());
    }

    @Test
    @DisplayName("ColumnDefinition accepts currency variant + currencyCode + decimals")
    void recordAcceptsCurrencyColumn() {
        ColumnDefinition column = new ColumnDefinition(
                "GROSS_SALARY", "Brüt Maaş", "currency", 140, true,
                false, false, null, null, false, null,
                null, null, null, "TRY", 0, null, null, null, null);
        assertEquals("currency", column.type());
        assertEquals("TRY", column.currencyCode());
        assertEquals(Integer.valueOf(0), column.decimals());
    }

    @Test
    @DisplayName("ColumnDefinition accepts status variant + typed statusMap")
    void recordAcceptsStatusColumn() {
        ColumnDefinition column = new ColumnDefinition(
                "STATUS", "Durum", "status", 140, false,
                false, false, null, null, false, null,
                null, null,
                Map.of("ACTIVE", new StatusMapEntry("success", "shared.status.active"),
                        "INACTIVE", new StatusMapEntry("muted", "shared.status.inactive")),
                null, null, null, null, null, null);
        assertEquals("status", column.type());
        assertNotNull(column.statusMap());
        assertEquals("success", column.statusMap().get("ACTIVE").variant());
    }

    @Test
    @DisplayName("ColumnDefinition accepts bold-text + date format")
    void recordAcceptsBoldTextAndFormatColumn() {
        ColumnDefinition bold = new ColumnDefinition(
                "FULL_NAME", "Ad Soyad", "bold-text", 180, false,
                false, false, null, null, false, null,
                null, null, null, null, null, null, null, null, null);
        assertEquals("bold-text", bold.type());

        ColumnDefinition date = new ColumnDefinition(
                "HIRE_DATE", "İşe Giriş", "date", 110, false,
                false, false, null, null, false, null,
                null, null, null, null, null, null, "short", null, null);
        assertEquals("short", date.format());
    }

    @Test
    @DisplayName("FilterDefinition accepts text-search + enum-select + optionsSource")
    void recordAcceptsFilterDefinitions() {
        FilterDefinition textFilter = new FilterDefinition(
                "search", "FULL_NAME", FilterKind.TEXT_SEARCH, "contains",
                null, "search", "reports.users.filters.search", null, null, null, null);
        assertEquals(FilterKind.TEXT_SEARCH, textFilter.kind());

        FilterDefinition enumFilter = new FilterDefinition(
                "status", "STATUS", FilterKind.ENUM_SELECT, "equals",
                "ACTIVE", "status", "reports.users.filters.status", null,
                List.of(new FilterOptionEntry("ACTIVE", "shared.status.active", null),
                        new FilterOptionEntry("INACTIVE", "shared.status.inactive", null)),
                null, null);
        assertEquals(2, enumFilter.options().size());

        FilterDefinition dynamicFilter = new FilterDefinition(
                "department", "DEPARTMENT_NAME", FilterKind.ENUM_SELECT, "contains",
                null, "department", "reports.hr.filters.department", null, null,
                new FilterOptionsSource(FilterOptionsSourceType.ENDPOINT,
                        "/v1/dashboards/hr-compensation/filter-options/department", null),
                "DEPARTMENT_NAME");
        assertNotNull(dynamicFilter.optionsSource());
        assertEquals(FilterOptionsSourceType.ENDPOINT, dynamicFilter.optionsSource().type());
    }

    /* ------------------------------------------------------------------ */
    /*  Java record construction — negative (fail-closed)                  */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("ColumnDefinition rejects unknown type at construction")
    void recordRejectsUnknownType() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ColumnDefinition(
                        "FIELD", "Header", "invalid-variant", 100, false,
                        false, false, null, null, false, null,
                        null, null, null, null, null, null, null, null, null));
        assertTrue(ex.getMessage().contains("invalid-variant"));
        assertTrue(ex.getMessage().contains("whitelist"));
    }

    @Test
    @DisplayName("ColumnDefinition rejects unknown date format")
    void recordRejectsUnknownFormat() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ColumnDefinition(
                        "DATE_COL", "Tarih", "date", 110, false,
                        false, false, null, null, false, null,
                        null, null, null, null, null, null, "fancy-iso", null, null));
        assertTrue(ex.getMessage().contains("format"));
    }

    @Test
    @DisplayName("StatusMapEntry rejects missing labelKey")
    void statusMapEntryRejectsBlankLabelKey() {
        assertThrows(IllegalArgumentException.class,
                () -> new StatusMapEntry("success", null));
        assertThrows(IllegalArgumentException.class,
                () -> new StatusMapEntry("success", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new StatusMapEntry(null, "key"));
    }

    @Test
    @DisplayName("FilterKind.fromWire rejects unknown wire value")
    void filterKindRejectsUnknownWireValue() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FilterKind.fromWire("unknown-kind"));
        assertTrue(ex.getMessage().contains("unknown-kind"));
    }

    @Test
    @DisplayName("FilterOptionsSource rejects missing endpoint when type=ENDPOINT")
    void filterOptionsSourceRejectsMissingEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new FilterOptionsSource(FilterOptionsSourceType.ENDPOINT, null, null));
    }

    @Test
    @DisplayName("FilterOptionsSource rejects missing column when type=FILTER_VALUES")
    void filterOptionsSourceRejectsMissingColumn() {
        assertThrows(IllegalArgumentException.class,
                () -> new FilterOptionsSource(FilterOptionsSourceType.FILTER_VALUES, null, null));
    }

    /* ------------------------------------------------------------------ */
    /*  JSON Schema validation — negative                                  */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("Schema rejects unknown column type at validation time")
    void schemaRejectsUnknownColumnType() {
        String json = """
                {
                  "contractVersion": 1,
                  "key": "test-invalid-type",
                  "version": "1.0",
                  "title": "Test",
                  "category": "Test",
                  "tenantBoundary": {"mode": "none", "scopeType": "global", "reason": "test fixture"},
                  "schemaMode": "static",
                  "sourceQuery": "SELECT 1",
                  "columns": [
                    {"field": "X", "headerName": "X", "type": "invalid-variant"}
                  ]
                }
                """;
        List<ContractViolation> violations = validator().validate(json, "test-invalid-type.json");
        assertThat(violations).anyMatch(v ->
                "REPORT_SCHEMA_INVALID".equals(v.ruleId())
                        && v.field().contains("columns")
                        && v.field().contains("type"));
    }

    @Test
    @DisplayName("Schema rejects malformed statusMap entry missing labelKey")
    void schemaRejectsMalformedStatusMap() {
        String json = """
                {
                  "contractVersion": 1,
                  "key": "test-malformed-status",
                  "version": "1.0",
                  "title": "Test",
                  "category": "Test",
                  "tenantBoundary": {"mode": "none", "scopeType": "global", "reason": "test fixture"},
                  "schemaMode": "static",
                  "sourceQuery": "SELECT 1",
                  "columns": [
                    {
                      "field": "STATUS",
                      "headerName": "Status",
                      "type": "status",
                      "statusMap": {
                        "ACTIVE": {"variant": "success"}
                      }
                    }
                  ]
                }
                """;
        List<ContractViolation> violations = validator().validate(json, "test-malformed-status.json");
        assertThat(violations).anyMatch(v ->
                "REPORT_SCHEMA_INVALID".equals(v.ruleId())
                        && v.field().contains("statusMap"));
    }

    @Test
    @DisplayName("Schema rejects invalid filterDefinitions.kind")
    void schemaRejectsInvalidFilterKind() {
        String json = """
                {
                  "contractVersion": 1,
                  "key": "test-invalid-filter-kind",
                  "version": "1.0",
                  "title": "Test",
                  "category": "Test",
                  "tenantBoundary": {"mode": "none", "scopeType": "global", "reason": "test fixture"},
                  "schemaMode": "static",
                  "sourceQuery": "SELECT 1",
                  "columns": [
                    {"field": "X", "headerName": "X", "type": "text"}
                  ],
                  "filterDefinitions": [
                    {"key": "search", "kind": "invalid-kind"}
                  ]
                }
                """;
        List<ContractViolation> violations = validator().validate(json, "test-invalid-filter-kind.json");
        assertThat(violations).anyMatch(v ->
                "REPORT_SCHEMA_INVALID".equals(v.ruleId())
                        && v.field().contains("filterDefinitions")
                        && v.field().contains("kind"));
    }

    /* ------------------------------------------------------------------ */
    /*  JSON Schema validation — positive (new fields accepted)            */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("Schema accepts new variants + config + filterDefinitions + routeSegment + sharedReportId")
    void schemaAcceptsAllExtensions() {
        String json = """
                {
                  "contractVersion": 1,
                  "key": "test-d1a-all-extensions",
                  "version": "1.0",
                  "title": "Test",
                  "category": "Test",
                  "tenantBoundary": {"mode": "none", "scopeType": "global", "reason": "test fixture"},
                  "schemaMode": "static",
                  "sourceQuery": "SELECT 1",
                  "routeSegment": "test-route",
                  "sharedReportId": "test-shared-id",
                  "columns": [
                    {"field": "FULL_NAME", "headerName": "Ad Soyad", "type": "bold-text", "width": 180},
                    {
                      "field": "ROLE", "headerName": "Role", "type": "badge", "width": 140,
                      "variantMap": {"ADMIN": "danger", "USER": "info"},
                      "labelMap": {"ADMIN": "shared.role.admin"},
                      "defaultVariant": "info",
                      "filterValues": ["ADMIN", "USER"]
                    },
                    {
                      "field": "STATUS", "headerName": "Durum", "type": "status", "width": 140,
                      "statusMap": {
                        "ACTIVE": {"variant": "success", "labelKey": "shared.status.active"}
                      }
                    },
                    {
                      "field": "GROSS_SALARY", "headerName": "Brüt", "type": "currency", "width": 140,
                      "currencyCode": "TRY", "decimals": 0
                    },
                    {"field": "IS_CRITICAL", "headerName": "Kritik", "type": "boolean", "width": 90},
                    {
                      "field": "HIRE_DATE", "headerName": "İşe Giriş", "type": "date", "width": 110,
                      "format": "short"
                    },
                    {
                      "field": "TENURE_YEARS", "headerName": "Kıdem", "type": "number", "width": 90,
                      "suffix": "yıl"
                    }
                  ],
                  "filterDefinitions": [
                    {
                      "key": "search", "kind": "text-search", "operator": "contains",
                      "urlParam": "search", "i18nLabelKey": "reports.filters.search"
                    },
                    {
                      "key": "status", "kind": "enum-select", "operator": "equals",
                      "urlParam": "status", "i18nLabelKey": "reports.users.filters.status",
                      "options": [
                        {"value": "ACTIVE", "labelKey": "shared.status.active"},
                        {"value": "INACTIVE", "labelKey": "shared.status.inactive"}
                      ]
                    },
                    {
                      "key": "department", "kind": "enum-select", "operator": "contains",
                      "i18nLabelKey": "reports.hr.filters.department",
                      "optionsSource": {
                        "type": "endpoint",
                        "endpoint": "/v1/dashboards/hr-compensation/filter-options/department"
                      }
                    }
                  ]
                }
                """;
        List<ContractViolation> violations = validator().validate(json, "test-d1a-all-extensions.json");
        assertThat(violations).noneMatch(v -> v.severity() == ContractViolation.Severity.FAIL);
    }

    /* ------------------------------------------------------------------ */
    /*  Backward-compat — legacy text/number/date columns still validate    */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("ColumnDefinition 11-arg backward-compat constructor preserves null for new fields")
    void recordBackwardCompatConstructorDefaultsNew() {
        ColumnDefinition legacy = new ColumnDefinition(
                "ID", "ID", "number", 80, false,
                false, false, null, null, false, null);
        assertNull(legacy.variantMap());
        assertNull(legacy.labelMap());
        assertNull(legacy.statusMap());
        assertNull(legacy.currencyCode());
        assertNull(legacy.decimals());
        assertNull(legacy.suffix());
        assertNull(legacy.format());
        assertNull(legacy.defaultVariant());
        assertNull(legacy.filterValues());
    }

    @Test
    @DisplayName("Legacy text-only column JSON validates against extended schema")
    void schemaAcceptsLegacyColumn() {
        String json = """
                {
                  "contractVersion": 1,
                  "key": "test-legacy-text",
                  "version": "1.0",
                  "title": "Test",
                  "category": "Test",
                  "tenantBoundary": {"mode": "none", "scopeType": "global", "reason": "test fixture"},
                  "schemaMode": "static",
                  "sourceQuery": "SELECT 1",
                  "columns": [
                    {"field": "ID", "headerName": "ID", "type": "number"}
                  ]
                }
                """;
        List<ContractViolation> violations = validator().validate(json, "test-legacy-text.json");
        assertThat(violations).noneMatch(v -> v.severity() == ContractViolation.Severity.FAIL);
    }
}
