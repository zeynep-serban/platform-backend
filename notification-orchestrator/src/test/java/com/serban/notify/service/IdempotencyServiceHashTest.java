package com.serban.notify.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyServiceHashTest {

    @Test
    void stable64BitHashDeterministic() {
        long h1 = IdempotencyService.stable64BitHash("default", "test-key-1");
        long h2 = IdempotencyService.stable64BitHash("default", "test-key-1");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void stable64BitHashCrossOrgDifferent() {
        long orgA = IdempotencyService.stable64BitHash("org-a", "key-1");
        long orgB = IdempotencyService.stable64BitHash("org-b", "key-1");
        assertThat(orgA).isNotEqualTo(orgB);
    }

    @Test
    void stable64BitHashRecordSeparatorAmbiguityPrevention() {
        // RecordSeparator (U+001F) ile (org="abc", key="def") ile (org="abcd", key="ef")
        // ayrı hash üretmeli — concatenation collision yok.
        long h1 = IdempotencyService.stable64BitHash("abc", "def");
        long h2 = IdempotencyService.stable64BitHash("abcd", "ef");
        assertThat(h1).isNotEqualTo(h2);
    }
}
