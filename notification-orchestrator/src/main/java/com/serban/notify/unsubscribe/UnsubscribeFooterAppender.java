package com.serban.notify.unsubscribe;

import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.template.RenderedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * UnsubscribeFooterAppender — locale-aware unsubscribe link footer injection
 * for email channel subscriber targets (T1.1.8 acceptance, Faz 23.2.A).
 *
 * <p>Codex thread {@code 019e4476} REVISE absorb: this is the right
 * integration seam — NOT the {@link com.serban.notify.template.TemplateRenderer}
 * (which has a security-boundary contract around vars namespace + SSTI lint
 * and no per-recipient context), and NOT the channel adapter layer (which
 * would duplicate the logic across SMTP / Graph Mail / future SMTP providers).
 *
 * <p>Per-target invocation: {@link com.serban.notify.delivery.DeliveryDispatchService#dispatchSingleTarget}
 * calls {@link #appendIfRequired(NotificationIntent, DeliveryTarget, RenderedMessage)}
 * right before {@code adapter.send(target, message)} so the footer sees the
 * fully resolved recipient identity (subscriberId, orgId, topicKey).
 *
 * <p><b>Activation rules</b>: only email subscriber targets receive a footer.
 * <ul>
 *   <li>{@code target.channel() == "email"}</li>
 *   <li>{@code target.recipientType() == "subscriber"}</li>
 *   <li>{@code target.recipientId()} non-null/blank (token claim requires it)</li>
 * </ul>
 * Slack / webhook / SMS / in-app channels and external email recipients
 * (anonymous, no subscriber row) are no-ops — original {@link RenderedMessage}
 * returned unchanged.
 *
 * <p><b>Locale fallback</b>: {@code en*} → English, every other value
 * (including {@code tr-TR}, {@code tr}, unknown, blank) → Turkish. Mevcut
 * template gövdelerinin Türkçe baskınlığına uyum (Codex 019e4476 öneri).
 * İleride gerçek i18n message bundle gelirse bu mapping oradan beslenir.
 *
 * <p><b>Subject contract</b>: footer ONLY touches body parts; subject is
 * returned unchanged so CRLF normalization + RFC 5322 header injection
 * guards established by {@link com.serban.notify.template.TemplateRenderer}
 * are preserved.
 *
 * <p><b>Token signature</b>: {@link UnsubscribeUrlBuilder} generates a
 * fresh HMAC-SHA256 signed token per call (orgId + subscriberId + optional
 * topicKey + iat/exp claims) so the URL embedded in the email is the same
 * one {@link UnsubscribeController} verifies on click.
 */
@Service
public class UnsubscribeFooterAppender {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeFooterAppender.class);

    private static final String CHANNEL_EMAIL = "email";
    private static final String RECIPIENT_TYPE_SUBSCRIBER = "subscriber";

    private final UnsubscribeUrlBuilder urlBuilder;

    public UnsubscribeFooterAppender(UnsubscribeUrlBuilder urlBuilder) {
        this.urlBuilder = urlBuilder;
    }

    /**
     * Append a locale-aware unsubscribe footer to the message body parts
     * when the target is an email subscriber. No-op for every other
     * channel or recipient type — original message returned unchanged.
     *
     * @param intent  origin intent (orgId, topicKey, locale)
     * @param target  per-recipient delivery target (channel, recipient_*)
     * @param message rendered message from {@link com.serban.notify.template.TemplateRenderer}
     * @return immutable copy with footer appended, or original message when
     *     no footer is required. Subject is never mutated.
     */
    public RenderedMessage appendIfRequired(
        NotificationIntent intent, DeliveryTarget target, RenderedMessage message
    ) {
        if (!shouldAppend(target)) {
            return message;
        }

        String url;
        try {
            url = urlBuilder.build(
                intent.getOrgId(), target.recipientId(), intent.getTopicKey()
            );
        } catch (IllegalArgumentException ex) {
            // Defensive: shouldAppend() already screens for blank recipientId;
            // orgId presence is enforced upstream. If builder still rejects we
            // skip the footer rather than fail the send — better to deliver
            // without footer than to bounce the entire dispatch.
            log.warn(
                "unsubscribe footer skipped: builder rejected inputs intentId={} subscriberId={} reason={}",
                intent.getIntentId(), target.recipientId(), ex.getMessage()
            );
            return message;
        }

        boolean english = isEnglishLocale(message.locale());
        String htmlFooter = english ? englishHtmlFooter(url) : turkishHtmlFooter(url);
        String textFooter = english ? englishTextFooter(url) : turkishTextFooter(url);

        String newHtml = appendBody(message.bodyHtml(), htmlFooter);
        String newText = appendBody(message.bodyText(), textFooter);

        return new RenderedMessage(message.subject(), newHtml, newText, message.locale());
    }

    private boolean shouldAppend(DeliveryTarget target) {
        if (target == null) return false;
        if (!CHANNEL_EMAIL.equals(target.channel())) return false;
        if (!RECIPIENT_TYPE_SUBSCRIBER.equals(target.recipientType())) return false;
        String recipientId = target.recipientId();
        return recipientId != null && !recipientId.isBlank();
    }

    /**
     * Locale classifier — only {@code en*} (case-insensitive) is treated as
     * English. Every other value (including null, blank, unknown tags) maps
     * to Turkish per Codex 019e4476 guidance: avoid mixed-language emails
     * when the body itself is Turkish.
     */
    static boolean isEnglishLocale(String locale) {
        if (locale == null || locale.isBlank()) return false;
        String lower = locale.toLowerCase(Locale.ROOT);
        return lower.equals("en") || lower.startsWith("en-") || lower.startsWith("en_");
    }

    private static String appendBody(String body, String footer) {
        if (body == null || body.isEmpty()) {
            return footer;
        }
        return body + footer;
    }

    private static String turkishHtmlFooter(String url) {
        // Plain anchor — TemplateRenderer's SSTI/XSS lint runs on DB template
        // content BEFORE render, so this trusted footer string is appended
        // after render and contains no Thymeleaf expressions. The URL is
        // produced by UnsubscribeUrlBuilder (UriComponentsBuilder) so the
        // token query param is already encoded.
        return "<hr><p style=\"font-size:12px;color:#777\">"
            + "Bu iletiyi almak istemiyorsanız "
            + "<a href=\"" + url + "\">aboneliği iptal edebilirsiniz</a>."
            + "</p>";
    }

    private static String englishHtmlFooter(String url) {
        return "<hr><p style=\"font-size:12px;color:#777\">"
            + "Don't want these messages? "
            + "<a href=\"" + url + "\">Unsubscribe</a>."
            + "</p>";
    }

    private static String turkishTextFooter(String url) {
        return "\n\n--\nAboneliği iptal et: " + url + "\n";
    }

    private static String englishTextFooter(String url) {
        return "\n\n--\nUnsubscribe: " + url + "\n";
    }
}
