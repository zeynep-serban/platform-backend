package com.serban.notify.adapter.sms;

import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SMS channel adapter facade test (Faz 23.3 multi-provider — PR-1 + PR-2).
 *
 * <p>{@link SmsAdapter} provider-agnostic facade davranışı: channel-level
 * validation, provider seçimi, failover matrisi (failover-eligible /
 * UNSUPPORTED_CHARSET pre-route / PROVIDER_CONFIG alert), {@link SmsSendResult}
 * → {@code DeliveryAttemptResult} map. Provider HTTP logic
 * {@code NetGsmProviderTest} / {@code JetSmsProviderTest}'te (fake provider
 * burada).
 */
class SmsAdapterTest {

    /** Test-only SmsProvider — providerKey + send + unicode + maxLen configurable. */
    private static final class FakeProvider implements SmsProvider {
        private final String key;
        private final boolean unicode;
        private final int maxLen;
        private final Function<String, SmsSendResult> sendFn;
        int sendCallCount = 0;

        /** 3-arg: maxMessageLength default 670 (concat-capable failover-target rolü). */
        FakeProvider(String key, boolean unicode, Function<String, SmsSendResult> sendFn) {
            this(key, unicode, 670, sendFn);
        }

        FakeProvider(String key, boolean unicode, int maxLen,
                     Function<String, SmsSendResult> sendFn) {
            this.key = key;
            this.unicode = unicode;
            this.maxLen = maxLen;
            this.sendFn = sendFn;
        }

        @Override public String providerKey() { return key; }
        @Override public SmsDlrMode dlrMode() { return SmsDlrMode.PUSH; }
        @Override public boolean supportsUnicode() { return unicode; }
        @Override public int maxMessageLength() { return maxLen; }
        @Override public SmsSendResult send(String e164Phone, String text) {
            sendCallCount++;
            return sendFn.apply(text);
        }
    }

    private static DeliveryTarget target(String phone) {
        return new DeliveryTarget("sms", "subscriber", "1", "hash-mock", phone, "sms");
    }

    private static RenderedMessage msg(String subject, String bodyText) {
        return new RenderedMessage(subject, null, bodyText, "tr-TR");
    }

    /** SmsAdapter — meterRegistry SimpleMeterRegistry, failoverOnProviderConfigError=false. */
    private static SmsAdapter adapter(List<SmsProvider> providers,
                                      String primary, String secondary) {
        return new SmsAdapter(providers, primary, secondary, false, new SimpleMeterRegistry());
    }

    private static SmsAdapter adapter(List<SmsProvider> providers, String primary,
                                      String secondary, boolean failoverOnConfig,
                                      MeterRegistry registry) {
        return new SmsAdapter(providers, primary, secondary, failoverOnConfig, registry);
    }

    // ─── Channel metadata ────────────────────────────────────────────────

    @Test
    void channelKeyIsSms() {
        SmsAdapter a = adapter(
            List.of(new FakeProvider("netgsm", true, t -> SmsSendResult.accepted("netgsm", "netgsm-1"))),
            "netgsm", "");
        assertThat(a.channelKey()).isEqualTo("sms");
    }

    @Test
    void duplicateProviderKeyFailsFast() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
            adapter(List.of(
                new FakeProvider("netgsm", true, t -> SmsSendResult.accepted("netgsm", "netgsm-1")),
                new FakeProvider("netgsm", true, t -> SmsSendResult.accepted("netgsm", "netgsm-2"))),
                "netgsm", ""));
    }

    // ─── Validation (facade-level) ───────────────────────────────────────

    @Test
    void invalidPhoneFormatFailsBeforeProvider() {
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-1"));
        SmsAdapter a = adapter(List.of(netgsm), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("905321234567"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("E.164");
        assertThat(r.failureReason()).doesNotContain("905321234567");
        assertThat(netgsm.sendCallCount).isZero();
    }

    @Test
    void emptyMessageFailsBeforeProvider() {
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-1"));
        SmsAdapter a = adapter(List.of(netgsm), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321234567"), msg(null, null));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("empty message");
        assertThat(netgsm.sendCallCount).isZero();
    }

    @Test
    void fallbackToSubjectWhenBodyNull() {
        FakeProvider netgsm = new FakeProvider("netgsm", true, text -> {
            assertThat(text).isEqualTo("Subject only");
            return SmsSendResult.accepted("netgsm", "netgsm-sub");
        });
        SmsAdapter a = adapter(List.of(netgsm), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321234567"), msg("Subject only", null));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.ACCEPTED);
        assertThat(netgsm.sendCallCount).isEqualTo(1);
    }

    // ─── Provider routing + result mapping ───────────────────────────────

    @Test
    void primaryProviderAcceptedMapsWithActualProviderKey() {
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-abc"));
        SmsAdapter a = adapter(List.of(netgsm), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", "Hello"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.ACCEPTED);
        assertThat(r.providerMessageId()).isEqualTo("netgsm-abc");
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");
    }

    @Test
    void primaryNotRegisteredFails() {
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-1"));
        SmsAdapter a = adapter(List.of(netgsm), "jetsms", "");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.failureReason()).contains("primary provider not registered");
    }

    @Test
    void providerFailedMapsToFailedWithProviderKeyAndNumericCode() {
        // Codex `019e3fc5` P2: numeric providerCode → providerResponseCode
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.failed("netgsm", SmsFailureClass.INVALID_PHONE, "50"));
        SmsAdapter a = adapter(List.of(netgsm), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");
        assertThat(r.failureReason()).contains("INVALID_PHONE");
        assertThat(r.providerResponseCode()).isEqualTo(50);  // numeric code structured
    }

    @Test
    void nonNumericProviderCodeLeavesResponseCodeNull() {
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.retry("netgsm", SmsFailureClass.HTTP_5XX, "http503"));
        SmsAdapter a = adapter(List.of(netgsm), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.providerResponseCode()).isNull();  // "http503" non-numeric
        assertThat(r.failureReason()).contains("http503");  // korunur string'de
    }

    // ─── Failover matrisi ────────────────────────────────────────────────

    @Test
    void failoverEligibleRetriesSecondary() {
        FakeProvider primary = new FakeProvider("jetsms", false,
            t -> SmsSendResult.retry("jetsms", SmsFailureClass.TIMEOUT, "io"));
        FakeProvider secondary = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-fb"));
        SmsAdapter a = adapter(List.of(primary, secondary), "jetsms", "netgsm");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.ACCEPTED);
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");
        assertThat(r.providerMessageId()).isEqualTo("netgsm-fb");
        assertThat(primary.sendCallCount).isEqualTo(1);
        assertThat(secondary.sendCallCount).isEqualTo(1);
    }

    @Test
    void failoverNotEligibleDoesNotRetrySecondary() {
        FakeProvider primary = new FakeProvider("jetsms", false,
            t -> SmsSendResult.failed("jetsms", SmsFailureClass.INVALID_PHONE, "x"));
        FakeProvider secondary = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-fb"));
        SmsAdapter a = adapter(List.of(primary, secondary), "jetsms", "netgsm");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.actualProviderKey()).isEqualTo("jetsms");
        assertThat(secondary.sendCallCount).isZero();
    }

    @Test
    void unsupportedCharsetPreRoutesToUnicodeSecondary() {
        // Codex absorb: JetSMS ISO-8859-9 UNSUPPORTED_CHARSET → NetGSM UCS-2
        // secondary (supportsUnicode=true) charset-capability pre-route.
        FakeProvider jetsms = new FakeProvider("jetsms", false,
            t -> SmsSendResult.failed("jetsms", SmsFailureClass.UNSUPPORTED_CHARSET, "charset"));
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-uc"));
        SmsAdapter a = adapter(List.of(jetsms, netgsm), "jetsms", "netgsm");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", "emoji 🎉 text"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.ACCEPTED);
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");  // Unicode secondary
        assertThat(netgsm.sendCallCount).isEqualTo(1);
    }

    @Test
    void unsupportedCharsetNoUnicodeSecondaryStaysFailed() {
        // secondary de Unicode desteklemiyorsa pre-route YOK — primary FAILED kalır
        FakeProvider jetsms = new FakeProvider("jetsms", false,
            t -> SmsSendResult.failed("jetsms", SmsFailureClass.UNSUPPORTED_CHARSET, "charset"));
        FakeProvider other = new FakeProvider("other", false,
            t -> SmsSendResult.accepted("other", "other-1"));
        SmsAdapter a = adapter(List.of(jetsms, other), "jetsms", "other");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", "emoji 🎉"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.actualProviderKey()).isEqualTo("jetsms");
        assertThat(other.sendCallCount).isZero();
    }

    @Test
    void providerConfigErrorEmitsAlertMetricNoFailoverByDefault() {
        // PROVIDER_CONFIG → alert metric emit; default failover YOK
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeProvider jetsms = new FakeProvider("jetsms", false,
            t -> SmsSendResult.failed("jetsms", SmsFailureClass.PROVIDER_CONFIG, "-5"));
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-1"));
        SmsAdapter a = adapter(List.of(jetsms, netgsm), "jetsms", "netgsm", false, registry);

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", "x"));

        // default: failover YOK — primary FAILED kalır
        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.actualProviderKey()).isEqualTo("jetsms");
        assertThat(netgsm.sendCallCount).isZero();
        // alert metric emit edildi
        assertThat(registry.counter("notify_sms_provider_config_error_total",
            "provider", "jetsms", "code", "-5").count()).isEqualTo(1.0);
    }

    @Test
    void providerConfigErrorEmitsMetricEvenWithoutSecondary() {
        // Codex `019e3fd9` P1 absorb: PROVIDER_CONFIG alert metric, secondary
        // boş olsa bile emit edilmeli (source default secondary="" — primary
        // credential hatası sessiz failed row'a gömülmemeli).
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeProvider jetsms = new FakeProvider("jetsms", false,
            t -> SmsSendResult.failed("jetsms", SmsFailureClass.PROVIDER_CONFIG, "-5"));
        SmsAdapter a = adapter(List.of(jetsms), "jetsms", "", false, registry);

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        // secondary YOK ama metric yine emit edildi
        assertThat(registry.counter("notify_sms_provider_config_error_total",
            "provider", "jetsms", "code", "-5").count()).isEqualTo(1.0);
    }

    @Test
    void messageTooLongRoutesToConcatCapableSecondary() {
        // Codex `019e3fd9` P1 absorb: MESSAGE_TOO_LONG capability route —
        // JetSMS 160 limit fail → NetGSM concat secondary (maxMessageLength
        // 670) mesajı kaldırabiliyorsa route.
        String text161 = "x".repeat(161);
        FakeProvider jetsms = new FakeProvider("jetsms", false, 160,
            t -> SmsSendResult.failed("jetsms", SmsFailureClass.MESSAGE_TOO_LONG, "len161"));
        FakeProvider netgsm = new FakeProvider("netgsm", true, 670,
            t -> SmsSendResult.accepted("netgsm", "netgsm-long"));
        SmsAdapter a = adapter(List.of(jetsms, netgsm), "jetsms", "netgsm");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", text161));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.ACCEPTED);
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");  // concat-capable secondary
        assertThat(netgsm.sendCallCount).isEqualTo(1);
    }

    @Test
    void messageTooLongNoCapableSecondaryStaysFailed() {
        // secondary de mesajı kaldıramıyorsa (maxMessageLength < textLength) →
        // primary FAILED kalır, route YOK.
        String text500 = "x".repeat(500);
        FakeProvider jetsms = new FakeProvider("jetsms", false, 160,
            t -> SmsSendResult.failed("jetsms", SmsFailureClass.MESSAGE_TOO_LONG, "len500"));
        FakeProvider shortSecondary = new FakeProvider("other", true, 160,
            t -> SmsSendResult.accepted("other", "other-1"));
        SmsAdapter a = adapter(List.of(jetsms, shortSecondary), "jetsms", "other");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", text500));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.FAILED);
        assertThat(r.actualProviderKey()).isEqualTo("jetsms");
        assertThat(shortSecondary.sendCallCount).isZero();
    }

    @Test
    void providerConfigErrorOptInEnablesFailover() {
        // failover-on-provider-config-error=true → secondary denenir + alert yine emit
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FakeProvider jetsms = new FakeProvider("jetsms", false,
            t -> SmsSendResult.failed("jetsms", SmsFailureClass.PROVIDER_CONFIG, "-5"));
        FakeProvider netgsm = new FakeProvider("netgsm", true,
            t -> SmsSendResult.accepted("netgsm", "netgsm-cfg-fb"));
        SmsAdapter a = adapter(List.of(jetsms, netgsm), "jetsms", "netgsm", true, registry);

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.ACCEPTED);
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");
        assertThat(netgsm.sendCallCount).isEqualTo(1);
        assertThat(registry.counter("notify_sms_provider_config_error_total",
            "provider", "jetsms", "code", "-5").count()).isEqualTo(1.0);
    }

    @Test
    void noSecondaryConfiguredPrimaryOnly() {
        FakeProvider primary = new FakeProvider("netgsm", true,
            t -> SmsSendResult.retry("netgsm", SmsFailureClass.HTTP_5XX, "503"));
        SmsAdapter a = adapter(List.of(primary), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");
        assertThat(primary.sendCallCount).isEqualTo(1);
    }

    @Test
    void providerThrowsHandledAsRetry() {
        FakeProvider primary = new FakeProvider("netgsm", true, t -> {
            throw new RuntimeException("unexpected");
        });
        SmsAdapter a = adapter(List.of(primary), "netgsm", "");

        ChannelAdapter.DeliveryAttemptResult r = a.send(
            target("+905321111111"), msg("S", "x"));

        assertThat(r.status()).isEqualTo(ChannelAdapter.DeliveryAttemptResult.Status.RETRY);
        assertThat(r.actualProviderKey()).isEqualTo("netgsm");
    }
}
