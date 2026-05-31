package com.example.report.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Supported origin types for a {@link FilterOptionsSource} carried by
 * {@code enum-select} / {@code badge-select} filter widgets.
 *
 * <p>PR-D1a (Codex thread {@code 019e800b}, 2026-05-31). Wire values stay
 * hyphenated to match the proposed frontend renderer dispatch keys in
 * {@code docs/architecture/dynamic-report-migration-d0.md §4}. Unknown wire
 * values fail closed at deserialization.
 */
public enum FilterOptionsSourceType {
    STATIC("static"),
    ENDPOINT("endpoint"),
    FILTER_VALUES("filter-values");

    private final String wire;

    FilterOptionsSourceType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getWire() {
        return wire;
    }

    @JsonCreator
    public static FilterOptionsSourceType fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("FilterOptionsSourceType wire value must not be null");
        }
        String normalized = value.trim();
        for (FilterOptionsSourceType type : values()) {
            if (type.wire.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException(
                "Unknown FilterOptionsSourceType wire value: " + value
                        + " (expected one of static, endpoint, filter-values)");
    }
}
