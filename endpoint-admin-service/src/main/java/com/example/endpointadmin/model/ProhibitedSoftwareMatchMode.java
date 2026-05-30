package com.example.endpointadmin.model;

/**
 * How a BE-025 prohibited-software denylist pattern is compared against an
 * installed-software field (Faz 22.5).
 *
 * <p>Both modes are literal and case-insensitive ({@code Locale.ROOT}); no
 * regex / glob is ever evaluated (ReDoS / injection surface). The
 * comparison is performed in Java against the already-sanitized inventory
 * item fields — the operator-supplied pattern never reaches a SQL
 * {@code LIKE} clause, so there is no SQL wildcard injection surface.
 *
 * <ul>
 *   <li>{@link #EXACT} — the normalized installed value equals the
 *       normalized pattern.</li>
 *   <li>{@link #CONTAINS} — the normalized installed value contains the
 *       normalized pattern as a substring. A minimum normalized pattern
 *       length (see
 *       {@code ProhibitedSoftwareRuleValidator#MIN_CONTAINS_LENGTH}) is
 *       enforced so a one- or two-character pattern cannot turn into a
 *       tenant-wide false-positive match.</li>
 * </ul>
 */
public enum ProhibitedSoftwareMatchMode {
    EXACT,
    CONTAINS
}
