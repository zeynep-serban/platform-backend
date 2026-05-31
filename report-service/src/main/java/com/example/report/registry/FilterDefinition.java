package com.example.report.registry;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Metadata-driven filter widget contract.
 *
 * <p>PR-D1a (Codex thread {@code 019e800b}, 2026-05-31). The static
 * {@code apps/mfe-reporting/src/modules/&lt;module&gt;/index.tsx} files
 * hand-code sidebar filter UIs today (status select, level select, month
 * picker, department dropdown, etc.). The dynamic factory only renders
 * {@code CompanyPicker} + {@code search} input. This record lets a backend
 * report declare its sidebar widgets so the dynamic factory can reproduce
 * the static module's filter shape from JSON metadata — closing the last
 * gap that requires per-report frontend code.
 *
 * <p>See {@code docs/architecture/dynamic-report-migration-d0.md §4} for
 * the per-module proposed entries and {@code §10} for the wider PR-D1a /
 * PR-D1b file touchpoint surface.
 *
 * @param key                  Unique key inside the report's filter state. REQUIRED.
 * @param targetField          Optional backend field this filter targets (defaults to {@code key}).
 * @param kind                 Widget kind. REQUIRED.
 * @param operator             Comparison operator emitted into the advanced-filter model
 *                             (e.g. {@code contains}, {@code equals}, {@code between},
 *                             {@code gte}, {@code lte}).
 * @param defaultValue         Default value used at filter initialization.
 * @param urlParam             URL search-param key preserving deep-link behaviour
 *                             (e.g. {@code ?status=ACTIVE}).
 * @param i18nLabelKey         i18n key for the widget label.
 * @param i18nPlaceholderKey   Optional i18n key for the widget placeholder.
 * @param options              Inline option list (static {@code enum-select}).
 * @param optionsSource        Dynamic options source (overrides {@code options}).
 * @param advancedFilterTarget Override for the advanced-filter model column key.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FilterDefinition(
        String key,
        String targetField,
        FilterKind kind,
        String operator,
        Object defaultValue,
        String urlParam,
        String i18nLabelKey,
        String i18nPlaceholderKey,
        List<FilterOptionEntry> options,
        FilterOptionsSource optionsSource,
        String advancedFilterTarget
) {
    public FilterDefinition {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("FilterDefinition.key must not be blank");
        }
        if (kind == null) {
            throw new IllegalArgumentException("FilterDefinition.kind must not be null");
        }
        if (options != null) {
            options = options.isEmpty() ? null : List.copyOf(options);
        }
    }
}
