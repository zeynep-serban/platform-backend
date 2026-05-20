package com.serban.notify.adapter.sms;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SmsSendResult validation + multipart metadata test (Faz 23.3.2 PR-A1.1
 * — Codex thread {@code 019e4514}).
 *
 * <p>Önceki versiyonda factory'ler test'i JetSmsProviderTest/NetGsmProviderTest
 * içinde dolaylı yapılıyordu; PR-A1.1 record genişlemesi (segmentCount +
 * encoding) için doğrudan validation testi gerekir.
 */
class SmsSendResultTest {

    /* ─── Backward-compatible factory'ler ────────────────────────────── */

    @Test
    void acceptedBackwardCompatDefaultsToSingleSegment() {
        SmsSendResult r = SmsSendResult.accepted("jetsms", "jetsms-123");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.NONE);
        assertThat(r.actualProviderKey()).isEqualTo("jetsms");
        assertThat(r.providerMsgId()).isEqualTo("jetsms-123");
        assertThat(r.providerCode()).isNull();
        assertThat(r.segmentCount())
            .as("backward-compat factory default 1 segment (legacy callers)")
            .isEqualTo(1);
        assertThat(r.encoding())
            .as("backward-compat factory default null encoding")
            .isNull();
    }

    @Test
    void acceptedWithMultipartMetadataPropagatesSegmentAndEncoding() {
        SmsSendResult r = SmsSendResult.accepted("jetsms", "jetsms-456", 3, "ISO-8859-9");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        assertThat(r.segmentCount()).isEqualTo(3);
        assertThat(r.encoding()).isEqualTo("ISO-8859-9");
    }

    @Test
    void failedHasZeroSegmentCountAndNullEncoding() {
        // Codex absorb: FAILED → mesaj gönderilmedi → billed segment yok
        SmsSendResult r = SmsSendResult.failed("jetsms", SmsFailureClass.INVALID_PHONE, "31");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.INVALID_PHONE);
        assertThat(r.providerMsgId()).isNull();
        assertThat(r.providerCode()).isEqualTo("31");
        assertThat(r.segmentCount())
            .as("FAILED → 0 segment (billing yok)")
            .isZero();
        assertThat(r.encoding()).isNull();
    }

    @Test
    void retryHasZeroSegmentCountAndNullEncoding() {
        SmsSendResult r = SmsSendResult.retry("jetsms", SmsFailureClass.HTTP_5XX, "http503");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.segmentCount()).isZero();
        assertThat(r.encoding()).isNull();
    }

    /* ─── Record constructor validation ─────────────────────────────────── */

    @Test
    void acceptedRequiresNonBlankProviderMsgId() {
        assertThatThrownBy(() ->
            new SmsSendResult(SmsSendResult.SmsSendStatus.ACCEPTED,
                SmsFailureClass.NONE, "jetsms", null, null, 1, "ISO-8859-9", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("providerMsgId");

        assertThatThrownBy(() ->
            new SmsSendResult(SmsSendResult.SmsSendStatus.ACCEPTED,
                SmsFailureClass.NONE, "jetsms", "  ", null, 1, "ISO-8859-9", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void segmentCountCannotBeNegative() {
        assertThatThrownBy(() ->
            new SmsSendResult(SmsSendResult.SmsSendStatus.FAILED,
                SmsFailureClass.INVALID_PHONE, "jetsms", null, "31", -1, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("segmentCount negatif olamaz");
    }

    @Test
    void acceptedRequiresPositiveSegmentCount() {
        assertThatThrownBy(() ->
            new SmsSendResult(SmsSendResult.SmsSendStatus.ACCEPTED,
                SmsFailureClass.NONE, "jetsms", "jetsms-1", null, 0, "ISO-8859-9", null))
            .as("ACCEPTED+segmentCount=0 invariant ihlali")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ACCEPTED sonuç billed segment");
    }

    @Test
    void failedRejectsPositiveSegmentCount() {
        // Codex absorb: sadece ACCEPTED segmentCount > 0; FAILED/RETRY 0
        assertThatThrownBy(() ->
            new SmsSendResult(SmsSendResult.SmsSendStatus.FAILED,
                SmsFailureClass.INVALID_PHONE, "jetsms", null, "31", 1, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("FAILED/RETRY");
    }

    @Test
    void retryRejectsPositiveSegmentCount() {
        assertThatThrownBy(() ->
            new SmsSendResult(SmsSendResult.SmsSendStatus.RETRY,
                SmsFailureClass.HTTP_5XX, "jetsms", null, "http503", 2, "ISO-8859-9", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("FAILED/RETRY");
    }

    @Test
    void statusAndFailureClassRequiredNonNull() {
        assertThatThrownBy(() ->
            new SmsSendResult(null, SmsFailureClass.NONE, "jetsms", "jetsms-1", null, 1, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("status");

        assertThatThrownBy(() ->
            new SmsSendResult(SmsSendResult.SmsSendStatus.ACCEPTED, null, "jetsms", "jetsms-1", null, 1, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("failureClass");
    }

    /* ─── PR-A3.1.1: actualChannel propagation (Codex P2 absorb) ─────────── */

    @Test
    void acceptedPropagatesActualChannel() {
        SmsSendResult r = SmsSendResult.accepted("jetsms", "jetsms-200", 1, "ISO-8859-9", "VFO");
        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        assertThat(r.actualChannel()).isEqualTo("VFO");
    }

    @Test
    void acceptedDefaultsActualChannelToNullForLegacyCallers() {
        SmsSendResult r = SmsSendResult.accepted("netgsm", "netgsm-99", 1, "GSM-7");
        assertThat(r.actualChannel()).isNull();
    }

    @Test
    void failedAndRetryAlwaysNullActualChannel() {
        SmsSendResult f = SmsSendResult.failed("jetsms", SmsFailureClass.INVALID_PHONE, "31");
        SmsSendResult r = SmsSendResult.retry("jetsms", SmsFailureClass.HTTP_5XX, "http503");
        assertThat(f.actualChannel()).isNull();
        assertThat(r.actualChannel()).isNull();
    }

    @Test
    void failedRejectsActualChannel() {
        // Invariant: ACCEPTED dışında actualChannel olmamalı
        assertThatThrownBy(() ->
            new SmsSendResult(SmsSendResult.SmsSendStatus.FAILED,
                SmsFailureClass.INVALID_PHONE, "jetsms", null, "31", 0, null, "VFO"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("actualChannel");
    }
}
