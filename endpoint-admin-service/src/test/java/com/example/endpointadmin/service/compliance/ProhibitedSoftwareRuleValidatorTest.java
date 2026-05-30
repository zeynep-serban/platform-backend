package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.dto.v1.admin.ProhibitedSoftwareRuleRequest;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchMode;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-025 — cross-field validation tests for
 * {@link ProhibitedSoftwareRuleValidator} (Faz 22.5). These mirror the V19
 * DB CHECK constraints so the operator gets a clean 400 instead of a 500.
 */
class ProhibitedSoftwareRuleValidatorTest {

    private final ProhibitedSoftwareRuleValidator validator =
            new ProhibitedSoftwareRuleValidator();

    private static ProhibitedSoftwareRuleRequest req(
            ProhibitedSoftwareMatchType type, ProhibitedSoftwareMatchMode mode,
            String name, String publisher) {
        return new ProhibitedSoftwareRuleRequest(type, mode, name, publisher, true, null);
    }

    @Test
    void validNameRulePasses() {
        assertThatCode(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "uTorrent", null)))
                .doesNotThrowAnyException();
    }

    @Test
    void nameRuleWithPublisherRejected() {
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "uTorrent", "Vendor")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publisherPattern must be absent");
    }

    @Test
    void nameRuleWithoutNameRejected() {
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namePattern is required");
    }

    @Test
    void blankNameRejected() {
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "   ", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namePattern is required");
    }

    @Test
    void publisherRuleWithoutPublisherRejected() {
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.PUBLISHER,
                        ProhibitedSoftwareMatchMode.EXACT, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publisherPattern is required");
    }

    @Test
    void nameAndPublisherRequiresBoth() {
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME_AND_PUBLISHER,
                        ProhibitedSoftwareMatchMode.EXACT, "vpn", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publisherPattern is required");
    }

    @Test
    void containsBelowMinLengthRejected() {
        // "ab" trimmed length 2 < MIN_CONTAINS_LENGTH (3)
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.CONTAINS, "ab", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least " + ProhibitedSoftwareRuleValidator.MIN_CONTAINS_LENGTH);
    }

    @Test
    void containsAtMinLengthPasses() {
        assertThatCode(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.CONTAINS, "vpn", null)))
                .doesNotThrowAnyException();
    }

    @Test
    void exactShortNameAllowed() {
        // EXACT is exempt from the CONTAINS min length — an exact 2-char name
        // is legitimate.
        assertThatCode(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "qq", null)))
                .doesNotThrowAnyException();
    }

    @Test
    void normalizeIsLowerTrimLocaleRoot() {
        assertThat(ProhibitedSoftwareRuleValidator.normalize("  uTorrent  "))
                .isEqualTo("utorrent");
        assertThat(ProhibitedSoftwareRuleValidator.normalize(null)).isEmpty();
        assertThat(ProhibitedSoftwareRuleValidator.normalize("   ")).isEmpty();
    }

    // ── Normalization parity with the DB CHECK (Codex 019e763a REVISE #2) ──
    // Java String.trim() strips EVERY char <= U+0020 (tab \t, newline \n,
    // CR \r, space). The V19 backstop now trims with the same [\x00-\x20]
    // range, so a pattern that the validator accepts/rejects on its trimmed
    // form is treated identically by the DB.

    @Test
    void containsWithTrailingTabBelowMinLengthRejected() {
        // "ab\t" → Java trim → "ab" (length 2) < MIN_CONTAINS_LENGTH. The DB
        // CHECK over the [\x00-\x20] range rejects the same value identically
        // (the old bare btrim() would have kept the tab and let length 3 pass
        // — the divergence Codex flagged).
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.CONTAINS, "ab\t", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least " + ProhibitedSoftwareRuleValidator.MIN_CONTAINS_LENGTH);
    }

    @Test
    void whitespaceOnlyTabNewlinePatternTreatedAsBlank() {
        // A pattern of only tab/newline trims to empty → treated as absent →
        // NAME type without a name → 400 (matches the DB blank CHECK).
        assertThatThrownBy(() -> validator.validate(
                req(ProhibitedSoftwareMatchType.NAME,
                        ProhibitedSoftwareMatchMode.EXACT, "\t\n", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namePattern is required");
    }

    @Test
    void normalizeStripsTabAndNewlineLikeJavaTrim() {
        // The shared normalizer used by the matcher + dedup key folds
        // tab/newline-padded variants to the same key (parity with the DB
        // dedup index's [\x00-\x20] trim).
        assertThat(ProhibitedSoftwareRuleValidator.normalize("\tuTorrent\n"))
                .isEqualTo("utorrent");
        assertThat(ProhibitedSoftwareRuleValidator.normalize("\t\n\r ")).isEmpty();
    }

    @Test
    void trimToNullStripsTabAndNewline() {
        assertThat(ProhibitedSoftwareRuleValidator.trimToNull("\tx\n")).isEqualTo("x");
        assertThat(ProhibitedSoftwareRuleValidator.trimToNull("\t\n\r")).isNull();
    }

    @Test
    void trimToNullCollapsesBlank() {
        assertThat(ProhibitedSoftwareRuleValidator.trimToNull("  x ")).isEqualTo("x");
        assertThat(ProhibitedSoftwareRuleValidator.trimToNull("   ")).isNull();
        assertThat(ProhibitedSoftwareRuleValidator.trimToNull(null)).isNull();
    }
}
