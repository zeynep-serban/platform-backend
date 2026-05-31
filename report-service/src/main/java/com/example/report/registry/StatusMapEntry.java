package com.example.report.registry;

/**
 * Per-status entry inside a {@code status} column's {@link ColumnDefinition#statusMap()}.
 *
 * <p>PR-D1a (Codex thread {@code 019e800b}, 2026-05-31) — typed config object for status
 * column metadata. Replaces the loose {@code Map<String, Object>} shape so the
 * variant + i18n label key contract is enforced at JSON deserialization and at record
 * construction.
 *
 * @param variant  Badge variant tag (e.g. {@code success}, {@code danger}). REQUIRED.
 * @param labelKey i18n key the frontend resolves at render time. REQUIRED.
 */
public record StatusMapEntry(String variant, String labelKey) {
    public StatusMapEntry {
        if (variant == null || variant.isBlank()) {
            throw new IllegalArgumentException("StatusMapEntry.variant must not be blank");
        }
        if (labelKey == null || labelKey.isBlank()) {
            throw new IllegalArgumentException("StatusMapEntry.labelKey must not be blank");
        }
    }
}
