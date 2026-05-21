package com.serban.notify.adapter;

import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SlackBlockKitPayloadBuilder} (Faz 23.6 M7 T4.1.1).
 */
class SlackBlockKitPayloadBuilderTest {

    @Test
    void buildsHeaderSectionContextWithCriticalSeverity() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("severity", "critical");
        meta.put("org_id", "default");
        meta.put("topic_key", "ops.drift-alarm");
        meta.put("correlation_id", "corr-abc-123");
        meta.put("occurred_at", "2026-05-21T09:30:00Z");

        DeliveryTarget target = new DeliveryTarget(
            "slack", "channel", null, "rh-1",
            "https://hooks.slack/x", "slack-default", meta
        );
        RenderedMessage message = new RenderedMessage(
            "Vault is unreachable",
            null,
            "Backend cannot reach Vault — quarantine triggered.",
            null
        );

        Map<String, Object> payload =
            SlackBlockKitPayloadBuilder.build(target, message, "Backend cannot reach Vault");

        // text fallback present
        assertThat(payload.get("text")).asString().contains("Backend cannot reach Vault");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        assertThat(blocks).hasSize(3);

        // Header block — type + severity badge + subject
        Map<String, Object> header = blocks.get(0);
        assertThat(header.get("type")).isEqualTo("header");
        @SuppressWarnings("unchecked")
        Map<String, Object> headerText = (Map<String, Object>) header.get("text");
        assertThat(headerText.get("type")).isEqualTo("plain_text");
        assertThat(headerText.get("text")).asString()
            .contains("🔴", "[KRİTİK]", "Vault is unreachable");

        // Section block — type + mrkdwn body
        Map<String, Object> section = blocks.get(1);
        assertThat(section.get("type")).isEqualTo("section");
        @SuppressWarnings("unchecked")
        Map<String, Object> sectionText = (Map<String, Object>) section.get("text");
        assertThat(sectionText.get("type")).isEqualTo("mrkdwn");
        assertThat(sectionText.get("text")).asString()
            .contains("Backend cannot reach Vault");

        // Context block — provenance fields, NO PII
        Map<String, Object> context = blocks.get(2);
        assertThat(context.get("type")).isEqualTo("context");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> elements = (List<Map<String, String>>) context.get("elements");
        String joined = String.join(" ", elements.stream().map(e -> e.get("text")).toList());
        assertThat(joined).contains("org", "default");
        assertThat(joined).contains("topic", "ops.drift-alarm");
        assertThat(joined).contains("correlation", "corr-abc-123");
        // Time formatted to ISO-OFFSET-DATE-TIME UTC
        assertThat(joined).contains("2026-05-21T09:30:00Z");
    }

    @Test
    void severityBadgeMapping() {
        assertThat(SlackBlockKitPayloadBuilder.severityBadge("critical")).contains("🔴", "[KRİTİK]");
        assertThat(SlackBlockKitPayloadBuilder.severityBadge("warning")).contains("🟡", "[UYARI]");
        assertThat(SlackBlockKitPayloadBuilder.severityBadge("info")).contains("🟢", "[BİLGİ]");
        assertThat(SlackBlockKitPayloadBuilder.severityBadge(null)).isEqualTo("[BİLGİ]");
        assertThat(SlackBlockKitPayloadBuilder.severityBadge("INFO")).contains("🟢");
        assertThat(SlackBlockKitPayloadBuilder.severityBadge("custom")).isEqualTo("[CUSTOM]");
    }

    @Test
    void truncateRespectsLimit() {
        String input = "a".repeat(200);
        String result = SlackBlockKitPayloadBuilder.truncate(input, 50);
        assertThat(result).hasSize(50);
        assertThat(result.charAt(49)).isEqualTo('…');
    }

    @Test
    void truncatePreservesShortInput() {
        String input = "short";
        assertThat(SlackBlockKitPayloadBuilder.truncate(input, 100)).isEqualTo("short");
    }

    @Test
    void emptyRoutingMetadataOmitsContextBlock() {
        DeliveryTarget target = new DeliveryTarget(
            "slack", "channel", null, "rh-1",
            "https://hooks.slack/x", "slack-default"
            // 6-arg constructor: routingMetadata = Map.of()
        );
        RenderedMessage message = new RenderedMessage("Subject", null, "Body", null);

        Map<String, Object> payload =
            SlackBlockKitPayloadBuilder.build(target, message, "Body");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        // No context block when routing metadata is empty
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).get("type")).isEqualTo("header");
        assertThat(blocks.get(1).get("type")).isEqualTo("section");
    }

    @Test
    void nullSubjectFallsBackToBildirim() {
        DeliveryTarget target = new DeliveryTarget(
            "slack", "channel", null, "rh-1",
            "https://hooks.slack/x", "slack-default", Map.of("severity", "info")
        );
        RenderedMessage message = new RenderedMessage(null, null, "Body", null);

        Map<String, Object> payload =
            SlackBlockKitPayloadBuilder.build(target, message, "Body");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        @SuppressWarnings("unchecked")
        Map<String, Object> headerText = (Map<String, Object>) blocks.get(0).get("text");
        assertThat(headerText.get("text")).asString().contains("Bildirim");
    }

    @Test
    void textFallbackInPayloadAlongsideBlocks() {
        // Backward compat: text field always present even when blocks rendered.
        Map<String, Object> meta = Map.of("severity", "info");
        DeliveryTarget target = new DeliveryTarget(
            "slack", "channel", null, "rh-1",
            "https://hooks.slack/x", "slack-default", meta
        );
        RenderedMessage message = new RenderedMessage("Hi", null, "Hi body", null);

        Map<String, Object> payload =
            SlackBlockKitPayloadBuilder.build(target, message, "Hi body");

        assertThat(payload).containsKey("text");
        assertThat(payload).containsKey("blocks");
        assertThat(payload.get("text")).isEqualTo("Hi body");
    }

    @Test
    void contextBlockOmitsMissingFields() {
        // Only severity provided; org_id/topic_key/correlation_id absent.
        Map<String, Object> meta = Map.of("severity", "warning");
        DeliveryTarget target = new DeliveryTarget(
            "slack", "channel", null, "rh-1",
            "https://hooks.slack/x", "slack-default", meta
        );
        RenderedMessage message = new RenderedMessage("Subject", null, "Body", null);

        Map<String, Object> payload =
            SlackBlockKitPayloadBuilder.build(target, message, "Body");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        // Severity drives header but doesn't appear in context block
        // (context only includes org/topic/correlation/time). With only
        // severity present, context block is omitted.
        assertThat(blocks).hasSize(2);
    }

    @Test
    void malformedTimestampPassesThroughWithoutCrashing() {
        Map<String, Object> meta = Map.of(
            "severity", "info",
            "org_id", "default",
            "occurred_at", "not-a-valid-iso-8601"
        );
        DeliveryTarget target = new DeliveryTarget(
            "slack", "channel", null, "rh-1",
            "https://hooks.slack/x", "slack-default", meta
        );
        RenderedMessage message = new RenderedMessage("Subject", null, "Body", null);

        Map<String, Object> payload =
            SlackBlockKitPayloadBuilder.build(target, message, "Body");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        // Doesn't crash; context block still rendered with the raw timestamp value.
        assertThat(blocks).hasSize(3);
    }
}
