package com.serban.notify.adapter;

import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Slack Block Kit payload builder (Faz 23.6 M7 T4.1.1 — Codex
 * {@code 019e493f} AGREE).
 *
 * <p>Generates the canonical Block Kit JSON payload for incoming
 * webhook POSTs:
 * <pre>
 * {
 *   "text": "<plain text fallback>",   // accessibility + notification preview
 *   "blocks": [
 *     {"type":"header", "text":{"type":"plain_text","text":"<severity badge>"}},
 *     {"type":"section","text":{"type":"mrkdwn","text":"<body>"}},
 *     {"type":"context","elements":[{"type":"mrkdwn","text":"<provenance>"}]}
 *   ]
 * }
 * </pre>
 *
 * <p>Codex 019e493f Q2 scope decisions:
 * <ul>
 *   <li>Header + Section + Context: yes (V1 closure)</li>
 *   <li>Actions block: deferred (backend callback + signing secret +
 *       idempotency + permission model needed; not v1)</li>
 *   <li>Threading: deferred (webhook path doesn't return {@code ts};
 *       Slack Web API path is a separate PR)</li>
 *   <li>Text fallback: always included (backward compat +
 *       accessibility + notification preview)</li>
 *   <li>PII: subscriber_id, email, phone NEVER in context block; only
 *       {@code org_id}, {@code topic_key}, {@code correlation_id},
 *       {@code occurred_at}</li>
 * </ul>
 *
 * <p>Slack Block Kit limits (truncated defensively):
 * <ul>
 *   <li>Header text: 150 chars</li>
 *   <li>Section mrkdwn: 3000 chars</li>
 *   <li>Context elements: each 150 chars</li>
 * </ul>
 */
public final class SlackBlockKitPayloadBuilder {

    private static final int HEADER_LIMIT = 150;
    private static final int SECTION_LIMIT = 3000;
    private static final int CONTEXT_ELEMENT_LIMIT = 150;
    private static final DateTimeFormatter TS_FMT =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private SlackBlockKitPayloadBuilder() {}

    /**
     * Builds the Block Kit payload as a Jackson-serializable Map.
     *
     * @param target delivery target (channel + routing metadata)
     * @param message rendered subject/body
     * @param fallbackText resolved plain text (for "text" field)
     * @return mutable map suitable for {@code objectMapper.writeValueAsString}
     */
    public static Map<String, Object> build(
        DeliveryTarget target,
        RenderedMessage message,
        String fallbackText
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // text field stays for notification preview + plain-text receivers +
        // backward compat with downstream Slack automation that didn't expect
        // the Block Kit shape.
        payload.put("text", truncate(fallbackText, SECTION_LIMIT));

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(headerBlock(target, message));
        blocks.add(sectionBlock(message, fallbackText));
        Map<String, Object> ctx = contextBlock(target);
        if (ctx != null) {
            blocks.add(ctx);
        }
        payload.put("blocks", blocks);
        return payload;
    }

    private static Map<String, Object> headerBlock(DeliveryTarget target, RenderedMessage message) {
        String severity = severityFromRouting(target.routingMetadata());
        String badge = severityBadge(severity);
        String headerText = badge + " " + (
            message.subject() != null && !message.subject().isBlank()
                ? message.subject()
                : "Bildirim"
        );
        return Map.of(
            "type", "header",
            "text", Map.of(
                "type", "plain_text",
                "text", truncate(headerText, HEADER_LIMIT)
            )
        );
    }

    private static Map<String, Object> sectionBlock(RenderedMessage message, String fallbackText) {
        // Use body_html-stripped variant if body_text empty; here we already
        // resolved fallbackText via the adapter caller. Prefer body_text
        // (markdown-safe) over subject.
        String body = message.bodyText() != null && !message.bodyText().isBlank()
            ? message.bodyText()
            : fallbackText;
        return Map.of(
            "type", "section",
            "text", Map.of(
                "type", "mrkdwn",
                "text", truncate(body, SECTION_LIMIT)
            )
        );
    }

    private static Map<String, Object> contextBlock(DeliveryTarget target) {
        Map<String, Object> meta = target.routingMetadata();
        if (meta == null || meta.isEmpty()) {
            return null;
        }

        List<Map<String, String>> elements = new ArrayList<>();
        addContextElement(elements, "org", stringValue(meta, "org_id"));
        addContextElement(elements, "topic", stringValue(meta, "topic_key"));
        addContextElement(elements, "correlation", stringValue(meta, "correlation_id"));
        String tsRaw = stringValue(meta, "occurred_at");
        String tsFormatted = formatTimestamp(tsRaw);
        if (tsFormatted != null) {
            addContextElement(elements, "time", tsFormatted);
        }

        if (elements.isEmpty()) {
            return null;
        }
        return Map.of(
            "type", "context",
            "elements", elements
        );
    }

    private static void addContextElement(
        List<Map<String, String>> elements, String label, String value
    ) {
        if (value == null || value.isBlank()) {
            return;
        }
        String formatted = "*" + label + ":* `"
            + truncate(value, CONTEXT_ELEMENT_LIMIT - label.length() - 8) + "`";
        elements.add(Map.of(
            "type", "mrkdwn",
            "text", truncate(formatted, CONTEXT_ELEMENT_LIMIT)
        ));
    }

    /**
     * Severity badge (visible in header). Falls back to a neutral marker
     * when severity is unknown so header always renders.
     */
    static String severityBadge(String severity) {
        if (severity == null) {
            return "[BİLGİ]";
        }
        return switch (severity.toLowerCase(java.util.Locale.ROOT)) {
            case "critical" -> "🔴 [KRİTİK]";
            case "warning"  -> "🟡 [UYARI]";
            case "info"     -> "🟢 [BİLGİ]";
            default         -> "[" + severity.toUpperCase(java.util.Locale.ROOT) + "]";
        };
    }

    private static String severityFromRouting(Map<String, Object> meta) {
        if (meta == null) return null;
        Object value = meta.get("severity");
        return value != null ? value.toString() : null;
    }

    private static String stringValue(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        return value != null ? value.toString() : null;
    }

    private static String formatTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(raw);
            return parsed.withOffsetSameInstant(ZoneOffset.UTC).format(TS_FMT);
        } catch (Exception ignored) {
            // Caller already passed a sane value; on parse error return as-is.
            return raw;
        }
    }

    static String truncate(String input, int limit) {
        if (input == null) return "";
        if (input.length() <= limit) return input;
        return input.substring(0, Math.max(0, limit - 1)) + "…";
    }
}
