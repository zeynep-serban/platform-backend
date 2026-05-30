package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.model.EndpointProhibitedSoftwareRule;
import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchMode;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-025 — pure match-semantics tests for {@link ProhibitedSoftwareMatcher}
 * (Faz 22.5). No JPA / Spring context; locks the EXACT / CONTAINS,
 * NAME / PUBLISHER / NAME_AND_PUBLISHER, case-insensitivity, and
 * fail-no-match behaviour.
 */
class ProhibitedSoftwareMatcherTest {

    private static EndpointProhibitedSoftwareRule rule(
            ProhibitedSoftwareMatchType type, ProhibitedSoftwareMatchMode mode,
            String namePattern, String publisherPattern) {
        EndpointProhibitedSoftwareRule r = new EndpointProhibitedSoftwareRule();
        r.setMatchType(type);
        r.setMatchMode(mode);
        r.setNamePattern(namePattern);
        r.setPublisherPattern(publisherPattern);
        r.setEnabled(true);
        return r;
    }

    private static EndpointSoftwareInventoryItem item(
            String displayName, String publisher, String version) {
        EndpointSoftwareInventoryItem i = new EndpointSoftwareInventoryItem();
        i.setDisplayName(displayName);
        i.setPublisher(publisher);
        i.setDisplayVersion(version);
        return i;
    }

    @Test
    void nameExactMatchesCaseInsensitively() {
        EndpointProhibitedSoftwareRule r = rule(
                ProhibitedSoftwareMatchType.NAME, ProhibitedSoftwareMatchMode.EXACT,
                "uTorrent", null);
        assertThat(ProhibitedSoftwareMatcher.matches(r, item("UTORRENT", "X", "1"))).isTrue();
        assertThat(ProhibitedSoftwareMatcher.matches(r, item("utorrent web", "X", "1"))).isFalse();
    }

    @Test
    void nameExactRejectsSubstring() {
        EndpointProhibitedSoftwareRule r = rule(
                ProhibitedSoftwareMatchType.NAME, ProhibitedSoftwareMatchMode.EXACT,
                "Zoom", null);
        // EXACT must not fire on a longer string that merely contains it.
        assertThat(ProhibitedSoftwareMatcher.matches(r, item("Zoom Outlook Plugin", "Z", "1")))
                .isFalse();
    }

    @Test
    void nameContainsMatchesSubstring() {
        EndpointProhibitedSoftwareRule r = rule(
                ProhibitedSoftwareMatchType.NAME, ProhibitedSoftwareMatchMode.CONTAINS,
                "torrent", null);
        assertThat(ProhibitedSoftwareMatcher.matches(r, item("qBittorrent", "X", "1"))).isTrue();
        assertThat(ProhibitedSoftwareMatcher.matches(r, item("7-Zip", "Igor", "24"))).isFalse();
    }

    @Test
    void publisherExactMatches() {
        EndpointProhibitedSoftwareRule r = rule(
                ProhibitedSoftwareMatchType.PUBLISHER, ProhibitedSoftwareMatchMode.EXACT,
                null, "Sketchy Software LLC");
        assertThat(ProhibitedSoftwareMatcher.matches(r,
                item("Anything", "sketchy software llc", "1"))).isTrue();
        assertThat(ProhibitedSoftwareMatcher.matches(r,
                item("Anything", "Trusted Vendor", "1"))).isFalse();
    }

    @Test
    void nameAndPublisherRequiresBoth() {
        EndpointProhibitedSoftwareRule r = rule(
                ProhibitedSoftwareMatchType.NAME_AND_PUBLISHER,
                ProhibitedSoftwareMatchMode.CONTAINS,
                "vpn", "shady");
        // both substrings present → match
        assertThat(ProhibitedSoftwareMatcher.matches(r,
                item("FreeVPN Pro", "Shady Networks", "2"))).isTrue();
        // only name matches → no match (AND, not OR)
        assertThat(ProhibitedSoftwareMatcher.matches(r,
                item("FreeVPN Pro", "Reputable Inc", "2"))).isFalse();
        // only publisher matches → no match
        assertThat(ProhibitedSoftwareMatcher.matches(r,
                item("Calculator", "Shady Networks", "2"))).isFalse();
    }

    @Test
    void nullInstalledFieldFailsNoMatch() {
        EndpointProhibitedSoftwareRule r = rule(
                ProhibitedSoftwareMatchType.PUBLISHER, ProhibitedSoftwareMatchMode.CONTAINS,
                null, "vendor");
        // A null publisher must not throw and must not match.
        assertThat(ProhibitedSoftwareMatcher.matches(r, item("App", null, "1"))).isFalse();
    }

    @Test
    void nullRuleOrItemIsFalse() {
        EndpointProhibitedSoftwareRule r = rule(
                ProhibitedSoftwareMatchType.NAME, ProhibitedSoftwareMatchMode.EXACT, "x", null);
        assertThat(ProhibitedSoftwareMatcher.matches(null, item("x", null, null))).isFalse();
        assertThat(ProhibitedSoftwareMatcher.matches(r, null)).isFalse();
    }

    @Test
    void blankPatternNeverMatchesUnderContains() {
        // Defensive: a blank pattern (which the validator + DB reject for a
        // real rule) must NOT match every app under CONTAINS.
        EndpointProhibitedSoftwareRule r = rule(
                ProhibitedSoftwareMatchType.NAME, ProhibitedSoftwareMatchMode.CONTAINS,
                "   ", null);
        assertThat(ProhibitedSoftwareMatcher.matches(r, item("Anything At All", "X", "1")))
                .isFalse();
    }
}
