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
 * JetSMS context-aware routing test — Faz 23.3.2 PR-A3.1.1 (Codex thread
 * {@code 019e4514}). Topic/template allowlist → VFO/VF channel resolution.
 *
 * <p><b>Codex absorb invariants</b>:
 * <ul>
 *   <li>Source default allowlist BOŞ — operator GitOps overlay'inde set</li>
 *   <li>Severity channel routing'inde KULLANILMAZ (VFO OTP-only kanal)</li>
 *   <li>Overlength guard: VFO match + text > otpMaxLength → VF fallback</li>
 *   <li>Topic match öncelikli (template match sonra)</li>
 *   <li>Default fallback config {@link JetSmsProvider#channel} (VF)</li>
 * </ul>
 */
class JetSmsProviderContextRoutingTest {

    @RegisterExtension
    static WireMockExtension jetsms = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())
        .build();

    private JetSmsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JetSmsProvider();
        ReflectionTestUtils.setField(provider, "transport", "soap");
        ReflectionTestUtils.setField(provider, "apiUrl", jetsms.url("/SMS-Web/HttpSmsSend"));
        ReflectionTestUtils.setField(provider, "reportUrl", jetsms.url("/SMS-Web/HttpSmsReport"));
        ReflectionTestUtils.setField(provider, "soapUrl", jetsms.url("/ws/soapSMS.asmx"));
        ReflectionTestUtils.setField(provider, "username", "test-user");
        ReflectionTestUtils.setField(provider, "password", "test-pass");
        ReflectionTestUtils.setField(provider, "originator", "Notify");
        ReflectionTestUtils.setField(provider, "multipartEnabled", false);
        ReflectionTestUtils.setField(provider, "maxSegments", 6);
        ReflectionTestUtils.setField(provider, "onLengthProblem", "RejectAllPackage");
        ReflectionTestUtils.setField(provider, "soapOperation", "sendSMSSingle");
        ReflectionTestUtils.setField(provider, "channel", "VF");
        ReflectionTestUtils.setField(provider, "channelAllowedCsv", "VF,VFO");
        ReflectionTestUtils.setField(provider, "otpTopicKeysCsv", "");
        ReflectionTestUtils.setField(provider, "otpTemplateIdsCsv", "");
        ReflectionTestUtils.setField(provider, "otpMaxLength", 160);
    }

    /* ─── isInCsvSet helper ──────────────────────────────────────────────── */

    @Test
    void isInCsvSetMatchesExactCaseInsensitiveTrimmed() {
        assertThat(JetSmsProvider.isInCsvSet("auth.password-reset-otp,auth.mfa-otp", "auth.mfa-otp")).isTrue();
        assertThat(JetSmsProvider.isInCsvSet("auth.password-reset-otp,auth.mfa-otp", "AUTH.MFA-OTP")).isTrue();
        assertThat(JetSmsProvider.isInCsvSet("  auth.mfa-otp  ,  auth.password-reset-otp  ", "auth.mfa-otp")).isTrue();
    }

    @Test
    void isInCsvSetRejectsNonMatch() {
        assertThat(JetSmsProvider.isInCsvSet("auth.mfa-otp", "auth.password-reset")).isFalse();
        assertThat(JetSmsProvider.isInCsvSet("", "auth.mfa-otp")).isFalse();
        assertThat(JetSmsProvider.isInCsvSet(null, "auth.mfa-otp")).isFalse();
        assertThat(JetSmsProvider.isInCsvSet("auth.mfa-otp", null)).isFalse();
        assertThat(JetSmsProvider.isInCsvSet("auth.mfa-otp", "")).isFalse();
        assertThat(JetSmsProvider.isInCsvSet("auth.mfa-otp", "  ")).isFalse();
    }

    /* ─── resolveChannel truth table ─────────────────────────────────────── */

    @Test
    void resolveChannelDefaultEmptyAllowlistReturnsVF() {
        // Default state: allowlist boş → her zaman VF
        SmsSendContext ctx = new SmsSendContext("critical", "auth.password-reset-otp", "otp-template-v1");
        assertThat(provider.resolveChannel(ctx, "Short OTP 123456")).isEqualTo("VF");
    }

    @Test
    void resolveChannelEmptyContextReturnsConfigDefault() {
        assertThat(provider.resolveChannel(SmsSendContext.empty(), "Hello")).isEqualTo("VF");
    }

    @Test
    void resolveChannelNullContextReturnsConfigDefault() {
        assertThat(provider.resolveChannel(null, "Hello")).isEqualTo("VF");
    }

    @Test
    void resolveChannelTopicAllowlistMatchReturnsVFO() {
        ReflectionTestUtils.setField(provider, "otpTopicKeysCsv", "auth.password-reset-otp,auth.mfa-otp");
        SmsSendContext ctx = new SmsSendContext("info", "auth.mfa-otp", null);
        assertThat(provider.resolveChannel(ctx, "OTP 123456")).isEqualTo("VFO");
    }

    @Test
    void resolveChannelTemplateAllowlistMatchReturnsVFO() {
        ReflectionTestUtils.setField(provider, "otpTemplateIdsCsv", "otp-template-v1,mfa-template-v2");
        SmsSendContext ctx = new SmsSendContext("info", "any.topic", "otp-template-v1");
        assertThat(provider.resolveChannel(ctx, "OTP 654321")).isEqualTo("VFO");
    }

    @Test
    void resolveChannelTopicMatchPriorityOverTemplate() {
        // Codex absorb: topic match önce kontrol edilir; her ikisi de match
        // olursa topic kazanır (specific over template general)
        ReflectionTestUtils.setField(provider, "otpTopicKeysCsv", "auth.mfa-otp");
        ReflectionTestUtils.setField(provider, "otpTemplateIdsCsv", "otp-template-v1");
        SmsSendContext ctx = new SmsSendContext("info", "auth.mfa-otp", "otp-template-v1");
        assertThat(provider.resolveChannel(ctx, "OTP 999")).isEqualTo("VFO");
    }

    @Test
    void resolveChannelNoMatchFallsBackToVF() {
        ReflectionTestUtils.setField(provider, "otpTopicKeysCsv", "auth.mfa-otp");
        ReflectionTestUtils.setField(provider, "otpTemplateIdsCsv", "otp-template-v1");
        SmsSendContext ctx = new SmsSendContext("info", "marketing.campaign", "promo-v3");
        assertThat(provider.resolveChannel(ctx, "Long marketing message")).isEqualTo("VF");
    }

    /* ─── Severity invariant (Codex absorb: severity DOES NOT route) ───── */

    @Test
    void resolveChannelSeverityCriticalDoesNotRouteVFO() {
        // Codex absorb: severity=critical otomatik VFO YAPAR. VFO OTP-only
        // kanal, kritik+uzun mesaj OTP olmamalı. Allowlist match yoksa VF.
        SmsSendContext ctx = new SmsSendContext("critical", "system.alert", null);
        assertThat(provider.resolveChannel(ctx, "Critical alert message")).isEqualTo("VF");
    }

    /* ─── Overlength guard (Codex absorb) ─────────────────────────────── */

    @Test
    void resolveChannelVFOMatchButOverlengthFallsBackToVF() {
        // Codex absorb: VFO OTP kanal kısa mesaj bekler. Allowlist match
        // olsa bile text > otpMaxLength ise default channel'a fallback +
        // warn log. Yanlış sınıflandırılmış uzun mesaj BULK'tan gider.
        ReflectionTestUtils.setField(provider, "otpTopicKeysCsv", "auth.mfa-otp");
        ReflectionTestUtils.setField(provider, "otpMaxLength", 160);
        SmsSendContext ctx = new SmsSendContext("info", "auth.mfa-otp", null);
        String longText = "x".repeat(161);

        assertThat(provider.resolveChannel(ctx, longText))
            .as("VFO match + text > otpMaxLength → VF fallback (Codex absorb)")
            .isEqualTo("VF");
    }

    @Test
    void resolveChannelVFOMatchExactBoundaryAcceptsVFO() {
        ReflectionTestUtils.setField(provider, "otpTopicKeysCsv", "auth.mfa-otp");
        ReflectionTestUtils.setField(provider, "otpMaxLength", 160);
        SmsSendContext ctx = new SmsSendContext("info", "auth.mfa-otp", null);
        String exactBoundary = "x".repeat(160);

        assertThat(provider.resolveChannel(ctx, exactBoundary)).isEqualTo("VFO");
    }

    @Test
    void resolveChannelOverlengthGuardConfigurable() {
        // Operator overlay otpMaxLength=70 (UCS-2 single-segment) ile daraltabilir
        ReflectionTestUtils.setField(provider, "otpTopicKeysCsv", "auth.mfa-otp");
        ReflectionTestUtils.setField(provider, "otpMaxLength", 70);
        SmsSendContext ctx = new SmsSendContext("info", "auth.mfa-otp", null);

        assertThat(provider.resolveChannel(ctx, "x".repeat(70))).isEqualTo("VFO");
        assertThat(provider.resolveChannel(ctx, "x".repeat(71))).isEqualTo("VF");
    }

    /* ─── End-to-end pipeline (send 3-arg + outbound SOAP body) ──────── */

    @Test
    void sendWithVFOContextSendsCorrectChannelInSoapBody() {
        ReflectionTestUtils.setField(provider, "otpTopicKeysCsv", "auth.mfa-otp");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "200000001"))));
        SmsSendContext ctx = new SmsSendContext("info", "auth.mfa-otp", null);

        SmsSendResult result = provider.send("+905321234567", "OTP 123456", ctx);

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        jetsms.verify(1, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withRequestBody(containing("<channel>VFO</channel>")));
    }

    @Test
    void sendWithVFContextSendsDefaultChannelInSoapBody() {
        // Allowlist boş → context match yok → default VF
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "200000002"))));
        SmsSendContext ctx = new SmsSendContext("info", "marketing.campaign", null);

        provider.send("+905321234567", "Marketing message", ctx);

        jetsms.verify(1, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withRequestBody(containing("<channel>VF</channel>")));
    }

    @Test
    void sendWithLegacy2ArgUsesEmptyContextDefault() {
        // Legacy 2-arg path → SmsSendContext.empty() → default VF channel
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "200000003"))));

        provider.send("+905321234567", "Legacy call");

        jetsms.verify(1, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withRequestBody(containing("<channel>VF</channel>")));
    }

    /* ─── PR-A3.1.1 Codex absorb (P2 + P3) ──────────────────────────────── */

    @Test
    void overlengthFallbackReturnsExplicitVF_NotConfigDefault() {
        // Codex P3 absorb: operator yanlışlıkla channel=VFO set ederse, overlength
        // guard yine de explicit "VF" döndürmeli (config drift hardening).
        // Bu test config'i intentionally misconfigured ediyor (channel=VFO) ve
        // overlength path'inin yine de "VF" döndürdüğünü doğruluyor.
        ReflectionTestUtils.setField(provider, "channel", "VFO");  // misconfigured default
        ReflectionTestUtils.setField(provider, "otpTopicKeysCsv", "auth.mfa-otp");
        ReflectionTestUtils.setField(provider, "otpMaxLength", 160);
        SmsSendContext ctx = new SmsSendContext("info", "auth.mfa-otp", null);
        String longText = "x".repeat(161);

        assertThat(provider.resolveChannel(ctx, longText))
            .as("overlength fallback explicit VF, config drift'e direnir")
            .isEqualTo("VF");
    }

    @Test
    void sendWithVFOContextPropagatesActualChannelInAcceptedResult() {
        // Codex P2 absorb: SOAP accepted sonucunda actualChannel=VFO taşınır
        // → SmsAdapter audit metadata DELIVERY_ACCEPTED event'inde görünür.
        ReflectionTestUtils.setField(provider, "otpTopicKeysCsv", "auth.mfa-otp");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "200000010"))));
        SmsSendContext ctx = new SmsSendContext("info", "auth.mfa-otp", null);

        SmsSendResult result = provider.send("+905321234567", "OTP 999999", ctx);

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        assertThat(result.actualChannel())
            .as("VFO routing audit kanıtı SmsSendResult'a propagate")
            .isEqualTo("VFO");
    }

    @Test
    void sendWithDefaultContextPropagatesVFActualChannel() {
        // Allowlist boş → default channel (VF) → actualChannel=VF propagate
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "200000011"))));
        SmsSendContext ctx = new SmsSendContext("info", "marketing.campaign", null);

        SmsSendResult result = provider.send("+905321234567", "Bulk msg", ctx);

        assertThat(result.actualChannel()).isEqualTo("VF");
    }

    @Test
    void sendWithLegacySendSMSOpReturnsNullActualChannel() {
        // SendSMS (BULK array, channel-agnostic) → actualChannel=null
        ReflectionTestUtils.setField(provider, "soapOperation", "sendSMS");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelopeSendSMS("00", "200000012"))));

        SmsSendResult result = provider.send("+905321234567", "Legacy SendSMS path");

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        assertThat(result.actualChannel())
            .as("Legacy SendSMS path channel-agnostic, actualChannel=null")
            .isNull();
    }

    /* ─── Helpers ────────────────────────────────────────────────────────── */

    private static String soapAcceptedEnvelopeSendSMS(String errorCode, String id) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body>"
            + "<SendSMSResponse xmlns=\"http://tempuri.org/\">"
            + "<SendSMSResult>"
            + "<ErrorCode>" + errorCode + "</ErrorCode>"
            + "<ID>" + id + "</ID>"
            + "</SendSMSResult>"
            + "</SendSMSResponse>"
            + "</soap:Body>"
            + "</soap:Envelope>";
    }

    private static String soapAcceptedEnvelope(String errorCode, String id) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
            + "<soap:Body>"
            + "<SendSMSSingleResponse xmlns=\"http://tempuri.org/\">"
            + "<SendSMSSingleResult>"
            + "<ErrorCode>" + errorCode + "</ErrorCode>"
            + "<ID>" + id + "</ID>"
            + "</SendSMSSingleResult>"
            + "</SendSMSSingleResponse>"
            + "</soap:Body>"
            + "</soap:Envelope>";
    }
}
