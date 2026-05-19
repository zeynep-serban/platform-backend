package com.serban.notify.adapter.sms;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * JetSMS SMS provider test (Faz 23.3 multi-provider — PR-2).
 *
 * <p>JetSMS HTTP API: send (Status/MessageIDs plain-text parse) + ISO-8859-9
 * encoding + custom URL-encode + failover taxonomy mapping. DLR polling
 * PR-3 (bu PR'da pollDelivery default UnsupportedOperationException).
 */
class JetSmsProviderTest {

    @RegisterExtension
    static WireMockExtension jetsms = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private JetSmsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JetSmsProvider();
        ReflectionTestUtils.setField(provider, "apiUrl", jetsms.url("/SMS-Web/HttpSmsSend"));
        ReflectionTestUtils.setField(provider, "username", "test-user");
        ReflectionTestUtils.setField(provider, "password", "test-pass");
        ReflectionTestUtils.setField(provider, "originator", "Notify");
    }

    // ─── Provider metadata ───────────────────────────────────────────────

    @Test
    void providerKeyIsJetsms() {
        assertThat(provider.providerKey()).isEqualTo("jetsms");
    }

    @Test
    void dlrModeIsPoll() {
        assertThat(provider.dlrMode()).isEqualTo(SmsProvider.SmsDlrMode.POLL);
    }

    @Test
    void supportsUnicodeFalse() {
        // JetSMS ISO-8859-9 — Unicode desteklemez
        assertThat(provider.supportsUnicode()).isFalse();
    }

    // ─── Happy path ──────────────────────────────────────────────────────

    @Test
    void acceptedStatus0WithPositiveMessageId() {
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(200).withBody("Status=0 MessageIDs=756464245")));

        SmsSendResult r = provider.send("+905321111111", "Hello SMS");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        assertThat(r.providerMsgId()).isEqualTo("jetsms-756464245");
        assertThat(r.actualProviderKey()).isEqualTo("jetsms");

        // Phone "+" stripped, form-urlencoded body
        jetsms.verify(postRequestedFor(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
            .withRequestBody(containing("Msisdns=905321111111"))
            .withRequestBody(containing("Username=test-user"))
            .withRequestBody(containing("Originator=Notify")));
    }

    @Test
    void multiMessageIdTakesFirstSegment() {
        // Çoklu MessageIDs "|" ayraçlı — tek msisdn gönderiminde ilk segment
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(200).withBody("Status=0 MessageIDs=756464245|756464246")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        assertThat(r.providerMsgId()).isEqualTo("jetsms-756464245");
    }

    // ─── MessageID error codes (Status=0) ────────────────────────────────

    @Test
    void messageIdNegative1InvalidPhone() {
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(200).withBody("Status=0 MessageIDs=-1")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.INVALID_PHONE);
    }

    @Test
    void messageIdNegative2InvalidText() {
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(200).withBody("Status=0 MessageIDs=-2")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.INVALID_TEXT);
    }

    // ─── Status error codes ──────────────────────────────────────────────

    @Test
    void statusNegative5LoginProviderConfig() {
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(200).withBody("Status=-5")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
        assertThat(r.providerCode()).isEqualTo("-5");
    }

    @Test
    void statusNegative6DataProviderSystem() {
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(200).withBody("Status=-6")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_SYSTEM);
    }

    @Test
    void statusNegative8MsisdnInvalidPhone() {
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(200).withBody("Status=-8")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.INVALID_PHONE);
    }

    @Test
    void statusNegative9MessageEmptyMessage() {
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(200).withBody("Status=-9")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.EMPTY_MESSAGE);
    }

    @Test
    void statusNegative15SystemRetry() {
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(200).withBody("Status=-15")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_SYSTEM);
    }

    @Test
    void statusNegative99UnknownRetry() {
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(200).withBody("Status=-99")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.UNKNOWN_TRANSIENT);
    }

    // ─── HTTP-level dispatch ─────────────────────────────────────────────

    @Test
    void http500RetryTransient() {
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(503).withBody("unavailable")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.HTTP_5XX);
    }

    @Test
    void http401AuthProviderConfig() {
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(401).withBody("unauthorized")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
    }

    @Test
    void http200EmptyBodyRetries() {
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(200).withBody("")));

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.RETRY);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.UNKNOWN_TRANSIENT);
    }

    // ─── Pre-flight validation ───────────────────────────────────────────

    @Test
    void missingConfigFailsProviderConfig() {
        ReflectionTestUtils.setField(provider, "originator", "");

        SmsSendResult r = provider.send("+905321111111", "x");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
    }

    @Test
    void emptyTextFailsEmptyMessage() {
        SmsSendResult r = provider.send("+905321111111", "  ");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.EMPTY_MESSAGE);
    }

    @Test
    void messageTooLongFails() {
        String longText = "x".repeat(161);  // > 160 JetSMS limit

        SmsSendResult r = provider.send("+905321111111", longText);

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.MESSAGE_TOO_LONG);
    }

    @Test
    void emojiTextFailsUnsupportedCharset() {
        // ISO-8859-9 dışı karakter (emoji) → UNSUPPORTED_CHARSET (preflight,
        // HTTP çağrısı yapılmadan). Silent transliteration YOK.
        SmsSendResult r = provider.send("+905321111111", "Kutlama 🎉 mesaji");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(r.failureClass()).isEqualTo(SmsFailureClass.UNSUPPORTED_CHARSET);
    }

    @Test
    void turkishCharsAcceptedIso88599() {
        // Türkçe karakterler ISO-8859-9'da var → UNSUPPORTED_CHARSET DEĞİL
        jetsms.stubFor(post(urlEqualTo("/SMS-Web/HttpSmsSend"))
            .willReturn(aResponse().withStatus(200).withBody("Status=0 MessageIDs=900900900")));

        SmsSendResult r = provider.send("+905321111111", "Şifreniz güncellendi: İĞÜÇÖŞ");

        assertThat(r.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
    }

    // ─── Encoding helpers (golden) ───────────────────────────────────────

    @Test
    void jetEncodeReservedCharsToHex() {
        // JetSMS custom encode: reserved !*'();:@&=+$,/?%#[] → %XX, diğeri raw
        assertThat(JetSmsProvider.jetEncode("a&b")).isEqualTo("a%26b");
        assertThat(JetSmsProvider.jetEncode("x=y")).isEqualTo("x%3Dy");
        assertThat(JetSmsProvider.jetEncode("p+q")).isEqualTo("p%2Bq");
        assertThat(JetSmsProvider.jetEncode("100%")).isEqualTo("100%25");
        // Türkçe karakter raw (reserved değil) — byte-level ISO-8859-9 StringEntity'de
        assertThat(JetSmsProvider.jetEncode("Şifre")).isEqualTo("Şifre");
        // boşluk reserved değil → raw (URLEncoder + davranışı YOK)
        assertThat(JetSmsProvider.jetEncode("a b")).isEqualTo("a b");
        assertThat(JetSmsProvider.jetEncode("")).isEmpty();
        assertThat(JetSmsProvider.jetEncode(null)).isEmpty();
    }

    @Test
    void iso88599EncodesTurkishNotEmoji() {
        // Golden: Türkçe ISO-8859-9 encode edilebilir, emoji edilemez
        assertThat(JetSmsProvider.ISO_8859_9.newEncoder().canEncode("İĞÜŞÖÇ")).isTrue();
        assertThat(JetSmsProvider.ISO_8859_9.newEncoder().canEncode("🎉")).isFalse();
        assertThat(JetSmsProvider.ISO_8859_9.newEncoder().canEncode("中文")).isFalse();
    }

    @Test
    void extractFieldParsesKeyValue() {
        assertThat(JetSmsProvider.extractField("Status=0 MessageIDs=756", "Status")).isEqualTo("0");
        assertThat(JetSmsProvider.extractField("Status=0 MessageIDs=756", "MessageIDs")).isEqualTo("756");
        assertThat(JetSmsProvider.extractField("Status=-5", "Status")).isEqualTo("-5");
        assertThat(JetSmsProvider.extractField("Status=-5", "MessageIDs")).isEmpty();
    }

    @Test
    void statusFailureClassMapping() {
        assertThat(JetSmsProvider.statusFailureClass(-5)).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
        assertThat(JetSmsProvider.statusFailureClass(-6)).isEqualTo(SmsFailureClass.PROVIDER_SYSTEM);
        assertThat(JetSmsProvider.statusFailureClass(-7)).isEqualTo(SmsFailureClass.INVALID_TEXT);
        assertThat(JetSmsProvider.statusFailureClass(-8)).isEqualTo(SmsFailureClass.INVALID_PHONE);
        assertThat(JetSmsProvider.statusFailureClass(-9)).isEqualTo(SmsFailureClass.EMPTY_MESSAGE);
        assertThat(JetSmsProvider.statusFailureClass(-15)).isEqualTo(SmsFailureClass.PROVIDER_SYSTEM);
        assertThat(JetSmsProvider.statusFailureClass(-99)).isEqualTo(SmsFailureClass.UNKNOWN_TRANSIENT);
    }

    @Test
    void pollDeliveryUnsupportedInPr2() {
        // PR-2'de pollDelivery default UnsupportedOperationException;
        // PR-3 JetSmsDlrPollingWorker ile override edilir.
        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> provider.pollDelivery(java.util.List.of("756")));
    }
}
