package com.serban.notify.template;

/**
 * Rendered message — output of {@link TemplateRenderer#render}.
 *
 * <p>Contains rendered subject + body parts (HTML + text) + resolved locale.
 * Channel adapters use parts as appropriate:
 * <ul>
 *   <li>SMTP: multipart (subject + body_html + body_text)</li>
 *   <li>Slack: body_text only (Block Kit zenginleştirme PR5/v1)</li>
 *   <li>Webhook egress: body_html OR body_text (target spec'e göre)</li>
 * </ul>
 */
public record RenderedMessage(
    String subject,
    String bodyHtml,
    String bodyText,
    String locale
) {}
