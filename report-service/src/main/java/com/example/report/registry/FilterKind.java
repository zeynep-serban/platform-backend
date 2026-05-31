package com.example.report.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Supported widget kinds inside {@link FilterDefinition}.
 *
 * <p>PR-D1a (Codex thread {@code 019e800b}, 2026-05-31) — wire vocabulary for the
 * {@code filterDefinitions} contract proposed in {@code docs/architecture/
 * dynamic-report-migration-d0.md §4}. The Java identifiers follow upper-snake
 * convention but the JSON wire values stay hyphenated to match the frontend
 * design-system filter taxonomy.
 *
 * <p>Unknown wire values fail closed at deserialization
 * ({@code IllegalArgumentException}) per the same fail-closed posture
 * {@link ColumnDefinition#type} now applies — so a typo in a registry
 * file surfaces at load time instead of silently downgrading the
 * frontend widget to a text input.
 */
public enum FilterKind {
    TEXT_SEARCH("text-search"),
    ENUM_SELECT("enum-select"),
    DATE_RANGE("date-range"),
    NUMBER_RANGE("number-range"),
    COMPANY_PICKER("company-picker"),
    MONTH_PICKER("month-picker");

    private final String wire;

    FilterKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getWire() {
        return wire;
    }

    @JsonCreator
    public static FilterKind fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("FilterKind wire value must not be null");
        }
        String normalized = value.trim();
        for (FilterKind kind : values()) {
            if (kind.wire.equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException(
                "Unknown FilterKind wire value: " + value
                        + " (expected one of text-search, enum-select, date-range, "
                        + "number-range, company-picker, month-picker)");
    }
}
