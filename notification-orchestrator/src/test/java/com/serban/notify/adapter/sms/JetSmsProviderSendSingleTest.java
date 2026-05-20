package com.serban.notify.adapter.sms;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingXPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * JetSMS SendSMSSingle operasyonu test — Faz 23.3.2 PR-A3.0 (Codex thread
 * {@code 019e4514}). Biotekno operator channel ayrımı (VFO=OTP, VF=BULK)
 * için yeni SOAP operation desteği.
 *
 * <p><b>PR-A3.0 scope</b>: config-gated SendSMSSingle support, default
 * {@code soap-operation=sendSMS} (behavior-neutral). GitOps overlay PR-A3.2
 * ile {@code sendSMSSingle}'a flip edilir. PR-A3.1'de typed
 * {@code SmsSendContext} + topic/template allowlist routing eklenecek.
 *
 * <p><b>WSDL evidence (2026-05-20 canary)</b>:
 * <ul>
 *   <li>{@code SendSMSSingle} + {@code channel=VF} + {@code onlengthproblem=SendAllPackage}
 *       + 258 char body → HTTP 200 ErrorCode=00 ID=2605201759215001078</li>
 *   <li>Provider'a 2 gerçek multipart SMS gönderildi (DLR poll DELIVERED)</li>
 * </ul>
 */
class JetSmsProviderSendSingleTest {

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
        ReflectionTestUtils.setField(provider, "soapOperation", "sendSMS");
        ReflectionTestUtils.setField(provider, "channel", "VF");
        ReflectionTestUtils.setField(provider, "channelAllowedCsv", "VF,VFO");
    }

    /* ─── isSoapSingleOperation truth table ─────────────────────────────── */

    @Test
    void soapSingleOperationDefaultFalseLegacy() {
        // setUp soap-operation=sendSMS (default, behavior-neutral)
        assertThat(provider.isSoapSingleOperation()).isFalse();
    }

    @Test
    void soapSingleOperationTrueWhenConfigured() {
        ReflectionTestUtils.setField(provider, "soapOperation", "sendSMSSingle");
        assertThat(provider.isSoapSingleOperation()).isTrue();
        // Case-insensitive
        ReflectionTestUtils.setField(provider, "soapOperation", "SENDSMSSINGLE");
        assertThat(provider.isSoapSingleOperation()).isTrue();
    }

    @Test
    void soapSingleOperationBlankOrUnknownFallsToDefault() {
        ReflectionTestUtils.setField(provider, "soapOperation", "");
        assertThat(provider.isSoapSingleOperation()).isFalse();
        ReflectionTestUtils.setField(provider, "soapOperation", "unknownOp");
        assertThat(provider.isSoapSingleOperation()).isFalse();
        ReflectionTestUtils.setField(provider, "soapOperation", null);
        assertThat(provider.isSoapSingleOperation()).isFalse();
    }

    /* ─── isChannelAllowed truth table ──────────────────────────────────── */

    @Test
    void channelAllowedDefaultSet() {
        // channelAllowedCsv = "VF,VFO"
        assertThat(provider.isChannelAllowed("VF")).isTrue();
        assertThat(provider.isChannelAllowed("VFO")).isTrue();
        assertThat(provider.isChannelAllowed("vf")).isTrue();  // case-insensitive
        assertThat(provider.isChannelAllowed("vfo")).isTrue();
    }

    @Test
    void channelAllowedRejectsInvalid() {
        assertThat(provider.isChannelAllowed("INVALID")).isFalse();
        assertThat(provider.isChannelAllowed("SplitMessage")).isFalse();
        assertThat(provider.isChannelAllowed("")).isFalse();
        assertThat(provider.isChannelAllowed(null)).isFalse();
        assertThat(provider.isChannelAllowed("  ")).isFalse();
    }

    @Test
    void channelAllowedHonoursCustomCsv() {
        ReflectionTestUtils.setField(provider, "channelAllowedCsv", "VF");
        assertThat(provider.isChannelAllowed("VF")).isTrue();
        assertThat(provider.isChannelAllowed("VFO")).isFalse();  // VFO not in CSV
    }

    /* ─── Backward-compat: default sendSMS path preserved ────────────────── */

    @Test
    void defaultSoapOperationSendSMSPreservesLegacyEnvelope() {
        // setUp soap-operation=sendSMS — outbound body sendSMS BULK array
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "100000001"))));

        SmsSendResult result = provider.send("+905321234567", "Test message");

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        // SOAP body: BULK array <messages><string>...</string></messages>
        jetsms.verify(1, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withHeader("SOAPAction", containing("SendSMS\"")));
        // sendSMSSingle string field DEĞIL
        jetsms.verify(0, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withHeader("SOAPAction", containing("SendSMSSingle")));
    }

    /* ─── sendSMSSingle operasyonu (config-gated) ───────────────────────── */

    @Test
    void sendSMSSingleOperationUsesNewEnvelopeAndSoapAction() {
        ReflectionTestUtils.setField(provider, "soapOperation", "sendSMSSingle");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "100000002"))));

        SmsSendResult result = provider.send("+905321234567", "Test message");

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
        jetsms.verify(1, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withHeader("SOAPAction", containing("SendSMSSingle")));
    }

    @Test
    void sendSMSSingleEnvelopeContainsChannelAndOnLengthProblem() {
        ReflectionTestUtils.setField(provider, "soapOperation", "sendSMSSingle");
        ReflectionTestUtils.setField(provider, "channel", "VF");
        // Operational multipart açık (flag + non-default onLengthProblem)
        // çünkü test mesajı 161+ char (multipart estimate'i aktive et)
        ReflectionTestUtils.setField(provider, "multipartEnabled", true);
        ReflectionTestUtils.setField(provider, "onLengthProblem", "SendAllPackage");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "100000003"))));

        provider.send("+905321234567", "Multipart test 200 char " + "x".repeat(180));

        jetsms.verify(1, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withRequestBody(containing("<channel>VF</channel>"))
            .withRequestBody(containing("<onlengthproblem>SendAllPackage</onlengthproblem>")));
    }

    @Test
    void sendSMSSingleEnvelopeUsesSingleStringNotBulkArray() {
        ReflectionTestUtils.setField(provider, "soapOperation", "sendSMSSingle");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "100000004"))));

        provider.send("+905321234567", "Single message");

        // SendSMSSingle: <messages>...</messages> (no <string> wrap)
        jetsms.verify(1, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withRequestBody(containing("<messages>Single message</messages>"))
            .withRequestBody(containing("<receipents>905321234567</receipents>")));
    }

    @Test
    void sendSMSSingleHonoursCustomChannelVFO() {
        ReflectionTestUtils.setField(provider, "soapOperation", "sendSMSSingle");
        ReflectionTestUtils.setField(provider, "channel", "VFO");
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "100000005"))));

        provider.send("+905321234567", "OTP 123456");

        jetsms.verify(1, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx"))
            .withRequestBody(containing("<channel>VFO</channel>")));
    }

    /* ─── Channel preflight (Codex P1 fail-closed) ──────────────────────── */

    @Test
    void sendSMSSingleInvalidChannelLocalFailNoOutbound() {
        // Codex P1: invalid channel → local PROVIDER_CONFIG, outbound çağrılmaz
        ReflectionTestUtils.setField(provider, "soapOperation", "sendSMSSingle");
        ReflectionTestUtils.setField(provider, "channel", "INVALID");

        SmsSendResult result = provider.send("+905321234567", "Test");

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(result.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
        assertThat(result.providerCode()).isEqualTo("channel-invalid");
        jetsms.verify(0, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx")));
    }

    @Test
    void sendSMSSingleBlankChannelLocalFailNoOutbound() {
        ReflectionTestUtils.setField(provider, "soapOperation", "sendSMSSingle");
        ReflectionTestUtils.setField(provider, "channel", "");

        SmsSendResult result = provider.send("+905321234567", "Test");

        assertThat(result.status()).isEqualTo(SmsSendResult.SmsSendStatus.FAILED);
        assertThat(result.failureClass()).isEqualTo(SmsFailureClass.PROVIDER_CONFIG);
        jetsms.verify(0, postRequestedFor(urlEqualTo("/ws/soapSMS.asmx")));
    }

    @Test
    void sendSMSDefaultOperationSkipsChannelPreflight() {
        // Codex absorb: default sendSMS path channel kullanmaz; preflight de
        // tetiklenmez (behavior-neutral default).
        ReflectionTestUtils.setField(provider, "channel", "INVALID");
        // soapOperation default sendSMS — channel check skip
        jetsms.stubFor(post(urlEqualTo("/ws/soapSMS.asmx"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "text/xml; charset=utf-8")
                .withBody(soapAcceptedEnvelope("00", "100000006"))));

        SmsSendResult result = provider.send("+905321234567", "Test");

        assertThat(result.status())
            .as("legacy sendSMS path channel preflight tetiklemez")
            .isEqualTo(SmsSendResult.SmsSendStatus.ACCEPTED);
    }

    /* ─── Envelope static helper golden ─────────────────────────────────── */

    @Test
    void buildSendSMSSingleEnvelopeContainsAllRequiredFields() {
        String envelope = JetSmsProvider.buildSendSMSSingleSoapEnvelope(
            "u", "p", "Notify", "905321111111", "Hello", "VF", "SendAllPackage");

        assertThat(envelope).contains(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<SendSMSSingle xmlns=\"http://tempuri.org/\">",
            "<user>u</user>",
            "<password>p</password>",
            "<originator>Notify</originator>",
            "<messages>Hello</messages>",
            "<receipents>905321111111</receipents>",
            "<channel>VF</channel>",
            "<onlengthproblem>SendAllPackage</onlengthproblem>"
        );
    }

    @Test
    void buildSendSMSSingleEnvelopeXmlEscapesChannelAndOnLengthProblem() {
        String envelope = JetSmsProvider.buildSendSMSSingleSoapEnvelope(
            "u", "p", "Notify", "905321111111", "Hello",
            "Inject<&>", "Path&Path");

        assertThat(envelope)
            .contains("<channel>Inject&lt;&amp;&gt;</channel>")
            .contains("<onlengthproblem>Path&amp;Path</onlengthproblem>");
    }

    @Test
    void buildSendSMSSingleEnvelopeBlankChannelFallsToDefault() {
        String envelopeNull = JetSmsProvider.buildSendSMSSingleSoapEnvelope(
            "u", "p", "Notify", "905321111111", "Hello", null, "SendAllPackage");
        String envelopeBlank = JetSmsProvider.buildSendSMSSingleSoapEnvelope(
            "u", "p", "Notify", "905321111111", "Hello", "  ", "SendAllPackage");

        // Default = CHANNEL_DEFAULT = "VF"
        assertThat(envelopeNull).contains("<channel>VF</channel>");
        assertThat(envelopeBlank).contains("<channel>VF</channel>");
    }

    @Test
    void buildSendSMSSingleEnvelopeContainsAllWsdlOrderedFields() {
        // WSDL parameter sırası birebir — ASP.NET SOAP servislerinde sıra
        // pratikte önemli olabilir (Codex absorb).
        String envelope = JetSmsProvider.buildSendSMSSingleSoapEnvelope(
            "u", "p", "Notify", "905321111111", "Hello", "VFO", "RejectAllPackage");

        int idxUser = envelope.indexOf("<user>");
        int idxPassword = envelope.indexOf("<password>");
        int idxOriginator = envelope.indexOf("<originator>");
        int idxMessages = envelope.indexOf("<messages>");
        int idxReceipents = envelope.indexOf("<receipents>");
        int idxChannel = envelope.indexOf("<channel>");
        int idxOnLength = envelope.indexOf("<onlengthproblem>");

        assertThat(idxUser).isLessThan(idxPassword);
        assertThat(idxPassword).isLessThan(idxOriginator);
        assertThat(idxOriginator).isLessThan(idxMessages);
        assertThat(idxMessages).isLessThan(idxReceipents);
        assertThat(idxReceipents).isLessThan(idxChannel);
        assertThat(idxChannel).isLessThan(idxOnLength);
    }

    /* ─── Helpers ────────────────────────────────────────────────────────── */

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
