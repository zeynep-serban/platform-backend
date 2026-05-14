package com.example.report.registry;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Registry-declared pivot column value (PR-0.4b, Codex thread
 * {@code 019e2695}). A pivot column's {@code pivotValues} array lists every
 * concrete bucket the backend will materialise as a {@code CASE WHEN [field]
 * = :value} aggregate. The compile-time list lets the SQL builder bind every
 * literal as a named parameter (no string interpolation, no SQL injection
 * surface) and keeps response column ordering stable across requests.
 *
 * <p>Two-field shape ({@code value}, {@code label}) covers the common case
 * where the SQL value differs from the user-facing header (e.g. enum codes
 * vs. localized labels). When the source emits a single token that already
 * doubles as both, the JSON array can carry bare strings — Jackson's
 * {@link #fromString(String)} delegating creator collapses them onto the
 * canonical shape with {@code label == value}.
 *
 * @param value SQL comparison literal; bound as a named parameter so the
 *              {@code CASE WHEN} predicate stays parametric.
 * @param label User-facing label intended for the frontend secondary
 *              column header. <b>Reserved for PR-0.4d</b>
 *              (Codex thread {@code 019e2695} iter-2 absorb): PR-0.4b
 *              accepts the field in the registry and round-trips it
 *              through the record, but the {@code /query} response
 *              currently surfaces only {@link
 *              com.example.report.dto.PagedResultDto#pivotResultFields()}
 *              (the SQL alias list). PR-0.4d will add a
 *              backend-emitted {@code pivotResultColumns} list whose
 *              entries carry the alias + label pair so AG Grid can
 *              render the user-facing header without re-fetching
 *              metadata. Defaults to the value when the registry
 *              omits the label, so registries that do not need the
 *              metadata split can stay terse.
 */
public record PivotValue(String value, String label) {

    public PivotValue {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "PivotValue value must not be blank");
        }
        if (label == null || label.isBlank()) {
            // SQL-stable identifier doubles as the default user-facing
            // label so registry entries don't have to spell both out
            // when they match.
            label = value;
        }
    }

    /**
     * Convenience constructor for code paths (tests, registry-loader
     * fallbacks) that only carry the SQL value — the label defaults
     * to the same string. Kept programmatic-only (no
     * {@code @JsonCreator}) so it doesn't compete with
     * {@link #fromString(String)} for Jackson's short-form deserialiser.
     */
    public PivotValue(String value) {
        this(value, value);
    }

    /**
     * Jackson short-form creator: a bare string in the JSON array
     * (e.g. {@code "pivotValues": ["A", "B"]}) materialises into a
     * {@link PivotValue} whose label mirrors the value. Object form
     * ({@code {"value": "A", "label": "Aktif"}}) keeps using Jackson's
     * native record-canonical constructor binding.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PivotValue fromString(String value) {
        return new PivotValue(value, value);
    }
}
