package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.model.EndpointProhibitedSoftwareRule;
import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchMode;

/**
 * BE-025 — pure prohibited-software match logic (Faz 22.5).
 *
 * <p>Decides whether a single denylist {@link EndpointProhibitedSoftwareRule}
 * fires against a single installed {@link EndpointSoftwareInventoryItem}.
 * Kept stateless + package-visible so the match semantics can be unit-tested
 * in isolation from the JPA / evaluator wiring.
 *
 * <p>Semantics (locked by {@code ProhibitedSoftwareMatcherTest}):
 * <ul>
 *   <li>Comparison is literal + case-insensitive — normalized via
 *       {@link ProhibitedSoftwareRuleValidator#normalize(String)}
 *       ({@code lower(trim(...))}, {@code Locale.ROOT}). NO regex / glob.</li>
 *   <li>{@code EXACT} → normalized installed value equals normalized
 *       pattern. {@code CONTAINS} → normalized installed value contains the
 *       normalized pattern.</li>
 *   <li>{@code NAME} matches on displayName; {@code PUBLISHER} on publisher;
 *       {@code NAME_AND_PUBLISHER} requires BOTH to match the SAME item (AND,
 *       not OR).</li>
 *   <li><b>Fail-no-match, not fail-closed</b>: a null/blank installed field
 *       that a rule needs simply does not match (it never throws and never
 *       silently counts as a hit). Rule integrity is guaranteed upstream by
 *       the validator + DB CHECKs, so a rule reaching this matcher already
 *       has the pattern columns its {@code matchType} requires.</li>
 * </ul>
 */
final class ProhibitedSoftwareMatcher {

    private ProhibitedSoftwareMatcher() {
    }

    /**
     * @return {@code true} iff {@code rule} fires against {@code item}.
     */
    static boolean matches(
            EndpointProhibitedSoftwareRule rule, EndpointSoftwareInventoryItem item) {
        if (rule == null || item == null) {
            return false;
        }
        ProhibitedSoftwareMatchMode mode = rule.getMatchMode();
        return switch (rule.getMatchType()) {
            case NAME -> fieldMatches(rule.getNamePattern(), item.getDisplayName(), mode);
            case PUBLISHER -> fieldMatches(rule.getPublisherPattern(), item.getPublisher(), mode);
            case NAME_AND_PUBLISHER ->
                    fieldMatches(rule.getNamePattern(), item.getDisplayName(), mode)
                            && fieldMatches(rule.getPublisherPattern(), item.getPublisher(), mode);
        };
    }

    private static boolean fieldMatches(
            String pattern, String installedValue, ProhibitedSoftwareMatchMode mode) {
        String normalizedPattern = ProhibitedSoftwareRuleValidator.normalize(pattern);
        // A blank pattern can never reach here for a valid rule, but stay
        // defensive: an empty normalized pattern matches nothing (rather than
        // matching every installed app under CONTAINS).
        if (normalizedPattern.isEmpty()) {
            return false;
        }
        if (installedValue == null) {
            return false;
        }
        String normalizedValue = ProhibitedSoftwareRuleValidator.normalize(installedValue);
        if (normalizedValue.isEmpty()) {
            return false;
        }
        return switch (mode) {
            case EXACT -> normalizedValue.equals(normalizedPattern);
            case CONTAINS -> normalizedValue.contains(normalizedPattern);
        };
    }
}
