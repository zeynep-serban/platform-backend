package com.example.report.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * Codex 019e0c99 iter-3 §C absorb: header dedupes by warning code.
 * Multi-branch reports (multi-year same tenant) must collapse to a single
 * header value while leaving the underlying warning list with detail for
 * log/metric.
 */
class DegradationHeadersTest {

    @Test
    void emptyList_emitsNoHeader() {
        HttpHeaders h = DegradationHeaders.of(List.of());
        assertThat(h.getFirst(DegradationHeaders.HEADER_NAME)).isNull();
    }

    @Test
    void nullList_emitsNoHeader() {
        HttpHeaders h = DegradationHeaders.of(null);
        assertThat(h.getFirst(DegradationHeaders.HEADER_NAME)).isNull();
    }

    @Test
    void singleWarning_emitsCode() {
        HttpHeaders h = DegradationHeaders.of(List.of(
                DegradationWarning.tenantLookupUnavailable(50, "fin-muhasebe-detay", "SETUP_PROCESS_CAT")));
        assertThat(h.getFirst(DegradationHeaders.HEADER_NAME))
                .isEqualTo("tenant_lookup_unavailable");
    }

    @Test
    void multipleSameCode_dedupesToOneHeaderValue() {
        // Multi-year same tenant — 3 branches, 3 warnings with same code.
        // Header should only carry the code once; internal list keeps detail.
        var w1 = DegradationWarning.tenantLookupUnavailable(50, "fin-muhasebe-detay", "SETUP_PROCESS_CAT");
        var w2 = DegradationWarning.tenantLookupUnavailable(50, "fin-muhasebe-detay", "SETUP_PROCESS_CAT");
        var w3 = DegradationWarning.tenantLookupUnavailable(50, "fin-muhasebe-detay", "SETUP_PROCESS_CAT");
        HttpHeaders h = DegradationHeaders.of(List.of(w1, w2, w3));
        assertThat(h.getFirst(DegradationHeaders.HEADER_NAME))
                .isEqualTo("tenant_lookup_unavailable");
    }

    @Test
    void distinctCodes_joinedComma() {
        // Hypothetical second code (not yet emitted but contract supports it).
        var a = new DegradationWarning("tenant_lookup_unavailable",
                "50", "fin-muhasebe-detay", "SETUP_PROCESS_CAT", "msg-a");
        var b = new DegradationWarning("currency_history_partial",
                "50", "fin-muhasebe-detay", "MONEY_HISTORY", "msg-b");
        HttpHeaders h = DegradationHeaders.of(List.of(a, b));
        // LinkedHashSet preserves insertion order.
        assertThat(h.getFirst(DegradationHeaders.HEADER_NAME))
                .isEqualTo("tenant_lookup_unavailable,currency_history_partial");
    }

    @Test
    void nullCode_skipped() {
        var nullCode = new DegradationWarning(null, "50", "rep", "T", "msg");
        var valid = DegradationWarning.tenantLookupUnavailable(50, "rep", "T");
        HttpHeaders h = DegradationHeaders.of(List.of(nullCode, valid));
        assertThat(h.getFirst(DegradationHeaders.HEADER_NAME))
                .isEqualTo("tenant_lookup_unavailable");
    }

    @Test
    void emptyCode_skipped() {
        var emptyCode = new DegradationWarning("", "50", "rep", "T", "msg");
        HttpHeaders h = DegradationHeaders.of(List.of(emptyCode));
        assertThat(h.getFirst(DegradationHeaders.HEADER_NAME)).isNull();
    }
}
