package com.example.endpointadmin.model;

/**
 * Which installed-software field(s) a BE-025 prohibited-software denylist
 * rule matches against (Faz 22.5).
 *
 * <p>A rule's pattern columns must line up with the match type, enforced
 * both at the DB layer (CHECK constraint on
 * {@code endpoint_prohibited_software_rules}) and at the service-layer
 * validator so an operator gets a clean 400 rather than a 500:
 *
 * <ul>
 *   <li>{@link #NAME} — only {@code name_pattern} is set;
 *       {@code publisher_pattern} must be NULL.</li>
 *   <li>{@link #PUBLISHER} — only {@code publisher_pattern} is set;
 *       {@code name_pattern} must be NULL.</li>
 *   <li>{@link #NAME_AND_PUBLISHER} — BOTH patterns are set and both must
 *       match the same installed item for the rule to fire (AND
 *       semantics, not OR).</li>
 * </ul>
 *
 * <p>There is intentionally no user-supplied regex variant: regex is a
 * ReDoS / injection surface. Matching is a literal, case-insensitive
 * EXACT or bounded CONTAINS comparison only (see
 * {@link ProhibitedSoftwareMatchMode}).
 */
public enum ProhibitedSoftwareMatchType {
    NAME,
    PUBLISHER,
    NAME_AND_PUBLISHER
}
