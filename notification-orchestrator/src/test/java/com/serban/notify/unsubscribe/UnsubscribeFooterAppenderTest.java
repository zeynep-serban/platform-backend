package com.serban.notify.unsubscribe;

import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UnsubscribeFooterAppender unit tests — T1.1.8 acceptance (Codex thread
 * {@code 019e4476} REVISE absorb).
 *
 * <p>Verifies the channel + recipient-type gating, locale-aware footer text
 * selection, immutable copy semantics, subject non-mutation, and the
 * builder-failure soft-fallback path. Each scenario stubs
 * {@link UnsubscribeUrlBuilder} to return a stable URL so assertions can
 * pin the exact link string rendered into the footer.
 */
class UnsubscribeFooterAppenderTest {

    private static final String URL =
        "https://testai.acik.com/api/v1/notify/unsubscribe?token=tkn123";

    private UnsubscribeUrlBuilder urlBuilder;
    private UnsubscribeFooterAppender appender;

    @BeforeEach
    void setUp() {
        urlBuilder = mock(UnsubscribeUrlBuilder.class);
        appender = new UnsubscribeFooterAppender(urlBuilder);
    }

    @Test
    void emailSubscriberTurkishLocaleAppendsTurkishFooter() {
        when(urlBuilder.build(eq("default"), eq("sub-42"), eq("auth.password-reset")))
            .thenReturn(URL);

        var intent = makeIntent("default", "auth.password-reset");
        var target = subscriberEmailTarget("sub-42");
        var message = new RenderedMessage(
            "Şifre sıfırlama",
            "<p>Merhaba</p>",
            "Merhaba",
            "tr-TR"
        );

        var out = appender.appendIfRequired(intent, target, message);

        assertThat(out.subject())
            .as("subject must NEVER be mutated by the appender — CRLF/header "
                + "injection guards live in TemplateRenderer and stay valid")
            .isEqualTo("Şifre sıfırlama");
        assertThat(out.locale()).isEqualTo("tr-TR");
        assertThat(out.bodyHtml())
            .contains("<p>Merhaba</p>")
            .contains("aboneliği iptal edebilirsiniz")
            .contains("href=\"" + URL + "\"");
        assertThat(out.bodyText())
            .startsWith("Merhaba")
            .contains("Aboneliği iptal et: " + URL);
    }

    @Test
    void emailSubscriberEnglishLocaleAppendsEnglishFooter() {
        when(urlBuilder.build(eq("default"), eq("sub-42"), eq("topic.x"))).thenReturn(URL);

        var intent = makeIntent("default", "topic.x");
        var target = subscriberEmailTarget("sub-42");
        var message = new RenderedMessage(
            "Password reset",
            "<p>Hello</p>",
            "Hello",
            "en-US"
        );

        var out = appender.appendIfRequired(intent, target, message);

        assertThat(out.bodyHtml())
            .contains("<p>Hello</p>")
            .contains("Unsubscribe")
            .contains("href=\"" + URL + "\"");
        assertThat(out.bodyText())
            .startsWith("Hello")
            .contains("Unsubscribe: " + URL);
    }

    @Test
    void unknownLocaleFallsBackToTurkish() {
        when(urlBuilder.build(anyString(), anyString(), anyString())).thenReturn(URL);

        var intent = makeIntent("default", "topic.x");
        var target = subscriberEmailTarget("sub-42");
        var message = new RenderedMessage("subj", "<p>Bonjour</p>", "Bonjour", "fr-FR");

        var out = appender.appendIfRequired(intent, target, message);

        assertThat(out.bodyHtml())
            .as("unknown/non-English locale must fall back to Turkish per "
                + "Codex 019e4476 guidance — avoid mixed-language email")
            .contains("aboneliği iptal edebilirsiniz");
        assertThat(out.bodyText()).contains("Aboneliği iptal et: " + URL);
    }

    @Test
    void blankLocaleFallsBackToTurkish() {
        when(urlBuilder.build(anyString(), anyString(), anyString())).thenReturn(URL);

        var intent = makeIntent("default", "topic.x");
        var target = subscriberEmailTarget("sub-42");
        var message = new RenderedMessage("subj", "<p>x</p>", "x", "");

        var out = appender.appendIfRequired(intent, target, message);

        assertThat(out.bodyHtml()).contains("aboneliği iptal edebilirsiniz");
    }

    @Test
    void slackChannelIsNoOp() {
        var intent = makeIntent("default", "topic.x");
        var target = new DeliveryTarget(
            "slack", "subscriber", "sub-42", "hash-1",
            "https://slack.com/webhook", "slack-workspace-1"
        );
        var message = new RenderedMessage("subj", "<p>html</p>", "text", "tr-TR");

        var out = appender.appendIfRequired(intent, target, message);

        assertThat(out)
            .as("non-email channels must return the original message unchanged "
                + "(reference equality preserved; no URL lookup)")
            .isSameAs(message);
        verify(urlBuilder, never()).build(anyString(), anyString(), anyString());
    }

    @Test
    void webhookChannelIsNoOp() {
        var target = new DeliveryTarget(
            "webhook", "subscriber", "sub-42", "hash-1",
            "https://example.com/webhook", "webhook-generic"
        );
        var message = new RenderedMessage("subj", "<p>html</p>", "text", "tr-TR");

        var out = appender.appendIfRequired(makeIntent("default", "topic.x"), target, message);

        assertThat(out).isSameAs(message);
        verify(urlBuilder, never()).build(anyString(), anyString(), anyString());
    }

    @Test
    void smsChannelIsNoOp() {
        var target = new DeliveryTarget(
            "sms", "subscriber", "sub-42", "hash-1",
            "+905551112233", "jetsms-primary"
        );
        var message = new RenderedMessage(null, null, "OTP 123456", "tr-TR");

        var out = appender.appendIfRequired(makeIntent("default", "topic.x"), target, message);

        assertThat(out).isSameAs(message);
        verify(urlBuilder, never()).build(anyString(), anyString(), anyString());
    }

    @Test
    void externalEmailRecipientIsNoOp() {
        // External email recipients have no subscriber row, so the unsubscribe
        // token cannot be issued (no sub claim). Adapter still sends the
        // original message; no footer.
        var target = new DeliveryTarget(
            "email", "external", null, "hash-extern",
            "ext@example.com", "smtp-corporate"
        );
        var message = new RenderedMessage("subj", "<p>html</p>", "text", "tr-TR");

        var out = appender.appendIfRequired(makeIntent("default", "topic.x"), target, message);

        assertThat(out).isSameAs(message);
        verify(urlBuilder, never()).build(anyString(), anyString(), anyString());
    }

    @Test
    void blankRecipientIdIsNoOp() {
        var target = new DeliveryTarget(
            "email", "subscriber", "  ", "hash-1",
            "user@example.com", "smtp-corporate"
        );
        var message = new RenderedMessage("subj", "<p>html</p>", "text", "tr-TR");

        var out = appender.appendIfRequired(makeIntent("default", "topic.x"), target, message);

        assertThat(out)
            .as("blank recipientId means token cannot encode a sub claim — "
                + "no-op rather than throw")
            .isSameAs(message);
        verify(urlBuilder, never()).build(anyString(), anyString(), anyString());
    }

    @Test
    void globalTopicNullPropagatesNullThroughBuilder() {
        when(urlBuilder.build(eq("default"), eq("sub-42"), eq(null))).thenReturn(URL);

        var intent = makeIntent("default", /*topicKey=*/ null);
        var target = subscriberEmailTarget("sub-42");
        var message = new RenderedMessage("subj", "<p>x</p>", "x", "tr-TR");

        var out = appender.appendIfRequired(intent, target, message);

        assertThat(out.bodyHtml())
            .as("global (topic-less) unsubscribe must still build a URL — "
                + "the topicKey claim is optional in UnsubscribeTokenService; "
                + "builder receives null and returns a token without topic")
            .contains(URL);
        verify(urlBuilder).build("default", "sub-42", null);
    }

    @Test
    void builderRejectionFallsBackToOriginalMessage() {
        when(urlBuilder.build(anyString(), anyString(), anyString()))
            .thenThrow(new IllegalArgumentException("orgId required"));

        var intent = makeIntent("default", "topic.x");
        var target = subscriberEmailTarget("sub-42");
        var message = new RenderedMessage("subj", "<p>html</p>", "text", "tr-TR");

        var out = appender.appendIfRequired(intent, target, message);

        assertThat(out)
            .as("a builder rejection must NOT fail the dispatch — better to "
                + "deliver without a footer than to bounce the entire send")
            .isSameAs(message);
    }

    @Test
    void nullHtmlBodyOnlyAppendsTextFooter() {
        // Text-only template (e.g. SMS would NOT reach here due to channel
        // gate, but text-only email templates exist). bodyHtml=null returns
        // null on the html side and only the text footer is applied.
        when(urlBuilder.build(anyString(), anyString(), anyString())).thenReturn(URL);

        var intent = makeIntent("default", "topic.x");
        var target = subscriberEmailTarget("sub-42");
        var message = new RenderedMessage("subj", null, "Plain", "tr-TR");

        var out = appender.appendIfRequired(intent, target, message);

        assertThat(out.bodyHtml())
            .as("when bodyHtml is null the appended footer becomes the entire "
                + "html body — this is a fail-safe so HTML-aware adapters still "
                + "get the unsubscribe link even if the DB template only ships "
                + "a text variant")
            .contains("aboneliği iptal edebilirsiniz");
        assertThat(out.bodyText())
            .startsWith("Plain")
            .contains("Aboneliği iptal et: " + URL);
    }

    @Test
    void isEnglishLocaleClassifier() {
        // Direct check on the locale classifier — guards against false
        // positives on locales that happen to start with 'e' (e.g. 'es-ES').
        assertThat(UnsubscribeFooterAppender.isEnglishLocale("en")).isTrue();
        assertThat(UnsubscribeFooterAppender.isEnglishLocale("EN")).isTrue();
        assertThat(UnsubscribeFooterAppender.isEnglishLocale("en-US")).isTrue();
        assertThat(UnsubscribeFooterAppender.isEnglishLocale("en-GB")).isTrue();
        assertThat(UnsubscribeFooterAppender.isEnglishLocale("en_US")).isTrue();

        assertThat(UnsubscribeFooterAppender.isEnglishLocale("tr-TR")).isFalse();
        assertThat(UnsubscribeFooterAppender.isEnglishLocale("tr")).isFalse();
        assertThat(UnsubscribeFooterAppender.isEnglishLocale("es-ES")).isFalse();
        assertThat(UnsubscribeFooterAppender.isEnglishLocale("entirely-unrelated")).isFalse();
        assertThat(UnsubscribeFooterAppender.isEnglishLocale("")).isFalse();
        assertThat(UnsubscribeFooterAppender.isEnglishLocale(null)).isFalse();
    }

    /* ----- Helpers --------------------------------------------------- */

    private static DeliveryTarget subscriberEmailTarget(String subscriberId) {
        return new DeliveryTarget(
            "email", "subscriber", subscriberId, "hash-" + subscriberId,
            subscriberId + "@example.com", "smtp-corporate"
        );
    }

    private static NotificationIntent makeIntent(String orgId, String topicKey) {
        NotificationIntent i = new NotificationIntent();
        i.setIntentId("it-" + topicKey);
        i.setOrgId(orgId);
        i.setTopicKey(topicKey);
        return i;
    }
}
