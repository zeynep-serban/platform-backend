package com.example.permission.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-D2 unit tests for {@link ImpersonationActionPredicate}.
 *
 * <p>Closes the leaks identified in PR-D iter-1 review:
 * <ul>
 *   <li>{@code containsIgnoreCase("IMPERSONATION", action)} would also match
 *       {@code NON_IMPERSONATION_*} or {@code FOO_IMPERSONATION_BAR} —
 *       fabricated codes that are NOT one of the canonical 5 must reject.</li>
 *   <li>The dedicated endpoint must accept (a) absent filter, (b) literal
 *       alias {@code "IMPERSONATION"}, (c) one of the 5 canonical codes —
 *       and ONLY those.</li>
 * </ul>
 */
class ImpersonationActionPredicateTest {

    @Test
    @DisplayName("isImpersonationAction: 5 canonical codes match")
    void allowsCanonicalCodes() {
        for (String code : ImpersonationActionPredicate.allActions()) {
            assertThat(ImpersonationActionPredicate.isImpersonationAction(code))
                    .as("canonical code %s should match", code)
                    .isTrue();
        }
        assertThat(ImpersonationActionPredicate.allActions()).hasSize(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Substring-but-not-canonical: NON_IMPERSONATION_* family — the
            // generic feed must NOT exclude these (they are legitimate
            // non-impersonation events that happen to embed the word).
            "NON_IMPERSONATION_STARTED",
            "NON_IMPERSONATION_STOPPED",
            // Fabricated codes containing the substring
            "FOO_IMPERSONATION_BAR",
            "IMPERSONATION_PRETEND",
            "PRETEND_IMPERSONATION",
            "impersonation_started",          // case-different
            "Impersonation_Started",
            // Adjacent unrelated audit codes
            "IMPERSONATION",                  // alias, NOT a real action code
            "ASSIGN_ROLE",
            "REPORT_ACCESS",
            "USER_LOGIN",
            ""
    })
    @DisplayName("isImpersonationAction: non-canonical codes reject")
    void rejectsNonCanonicalCodes(String value) {
        assertThat(ImpersonationActionPredicate.isImpersonationAction(value)).isFalse();
    }

    @Test
    @DisplayName("isImpersonationAction: null is non-impersonation (safe default)")
    void nullIsNotImpersonation() {
        assertThat(ImpersonationActionPredicate.isImpersonationAction(null)).isFalse();
    }

    @Test
    @DisplayName("isAllowedImpersonationFilter: null (absent filter) is allowed")
    void nullFilterIsAllowed() {
        assertThat(ImpersonationActionPredicate.isAllowedImpersonationFilter(null)).isTrue();
    }

    @Test
    @DisplayName("isAllowedImpersonationFilter: alias \"IMPERSONATION\" allowed")
    void aliasAllAllowed() {
        assertThat(ImpersonationActionPredicate.isAllowedImpersonationFilter(
                ImpersonationActionPredicate.ALIAS_ALL)).isTrue();
        assertThat(ImpersonationActionPredicate.ALIAS_ALL).isEqualTo("IMPERSONATION");
    }

    @Test
    @DisplayName("isAllowedImpersonationFilter: each canonical code allowed")
    void canonicalCodesAllowed() {
        for (String code : ImpersonationActionPredicate.allActions()) {
            assertThat(ImpersonationActionPredicate.isAllowedImpersonationFilter(code))
                    .as("canonical code %s allowed", code)
                    .isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "FOO_IMPERSONATION_BAR",
            "NON_IMPERSONATION_STARTED",
            "impersonation_started",
            "IMPERSONATION_FAKE",
            "ASSIGN_ROLE",
            "",
            " "
    })
    @DisplayName("isAllowedImpersonationFilter: fabricated/case-different/blank reject")
    void invalidFiltersReject(String value) {
        assertThat(ImpersonationActionPredicate.isAllowedImpersonationFilter(value)).isFalse();
    }
}
