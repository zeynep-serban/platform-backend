package com.example.report.registry;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Inline option entry for {@code enum-select} / {@code badge-select} filter
 * widgets ({@link FilterDefinition#options()}).
 *
 * <p>PR-D1a (Codex thread {@code 019e800b}, 2026-05-31). Either
 * {@code labelKey} (i18n) or {@code label} (raw display string) may be
 * provided; both being null surfaces the {@code value} as the display label
 * at render time.
 *
 * @param value    Backend filter value. REQUIRED.
 * @param labelKey Optional i18n key for the display label.
 * @param label    Optional raw display label (legacy / hardcoded options).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FilterOptionEntry(String value, String labelKey, String label) {
    public FilterOptionEntry {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("FilterOptionEntry.value must not be blank");
        }
    }
}
