package com.serban.notify.fbl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FblFingerprint} — the ARF event idempotency key
 * (Faz 23.8 M7 T4.3.5 FBL).
 */
class FblFingerprintTest {

    @Test
    void computeIsDeterministic() {
        String a = FblFingerprint.compute("office365-fbl", "<r1@x>", "<o1@y>");
        String b = FblFingerprint.compute("office365-fbl", "<r1@x>", "<o1@y>");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void computeProduces64CharHex() {
        String fp = FblFingerprint.compute("reporter", "<r@x>", "<o@y>");
        assertThat(fp).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void angleBracketsAreNormalisedAway() {
        // <abc@x> and abc@x must fingerprint identically.
        String withBrackets = FblFingerprint.compute("rep", "<rep-msg@x>", "<orig@y>");
        String withoutBrackets = FblFingerprint.compute("rep", "rep-msg@x", "orig@y");
        assertThat(withBrackets).isEqualTo(withoutBrackets);
    }

    @Test
    void reporterAndMessageIdsAreCaseInsensitive() {
        String lower = FblFingerprint.compute("office365", "<msg@host>", "<orig@host>");
        String upper = FblFingerprint.compute("OFFICE365", "<MSG@HOST>", "<ORIG@HOST>");
        assertThat(lower).isEqualTo(upper);
    }

    @Test
    void differentInputsProduceDifferentFingerprints() {
        String first = FblFingerprint.compute("rep", "<r@x>", "<o1@y>");
        String second = FblFingerprint.compute("rep", "<r@x>", "<o2@y>");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void nullInputsNormaliseToEmptyAndStillHash() {
        String fp = FblFingerprint.compute(null, null, null);
        assertThat(fp).hasSize(64).matches("[0-9a-f]{64}");
        // null triplet is stable
        assertThat(fp).isEqualTo(FblFingerprint.compute(null, null, null));
    }

    @Test
    void messageIdNormalisationStripsOnlyMatchedBracketPair() {
        // Leading-only bracket is NOT stripped (not a matched pair).
        assertThat(FblFingerprint.normalizeMessageId("<unbalanced@x"))
            .isEqualTo("<unbalanced@x");
        assertThat(FblFingerprint.normalizeMessageId("<balanced@x>"))
            .isEqualTo("balanced@x");
        assertThat(FblFingerprint.normalizeMessageId("  <spaced@x>  "))
            .isEqualTo("spaced@x");
    }
}
