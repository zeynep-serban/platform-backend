package com.serban.notify.api.dto;

import com.serban.notify.domain.NotificationIntent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Submit notification intent request (Faz 23.1 PR2 — ADR-0013 D46 #1, #2).
 *
 * <p>Intent contract: {@code platform-k8s-gitops/docs/notify/event-contract.md}
 *
 * <p>Codex 019df9ae plan-time absorb:
 * <ul>
 *   <li>Q1 — Idempotency: required, 24h TTL window via IdempotencyService advisory lock</li>
 *   <li>Q4 — PII payload Map; PiiRedactor whitelist policy applied at audit publish</li>
 *   <li>Non-neg #1 — org_id required, controller enforces cross-tenant deny</li>
 * </ul>
 */
public record SubmitIntentRequest(

    @NotBlank(message = "intent_id required")
    @Size(max = 64)
    String intentId,

    @NotBlank(message = "idempotency_key required")
    @Size(max = 255)
    String idempotencyKey,

    @Size(max = 128)
    String correlationId,

    @NotBlank(message = "org_id required")
    @Size(max = 64)
    String orgId,

    @NotBlank(message = "topic_key required")
    @Size(max = 128)
    @Pattern(regexp = "^[a-z][a-z0-9.-]*$",
             message = "topic_key must be dot-notation lowercase kebab-case")
    String topicKey,

    @NotNull(message = "severity required")
    NotificationIntent.Severity severity,

    @NotNull(message = "data_classification required")
    NotificationIntent.DataClassification dataClassification,

    @NotEmpty(message = "recipients min 1")
    @Valid
    List<RecipientRef> recipients,

    @NotNull(message = "template required")
    @Valid
    TemplateRef template,

    @NotEmpty(message = "channels min 1")
    List<@NotBlank @Size(max = 32) String> channels,

    @NotNull(message = "payload required (may be empty Map for parameter-less templates)")
    Map<String, Object> payload,

    Map<String, Object> channelRouting,

    @Future(message = "scheduled_at must be in future if provided")
    OffsetDateTime scheduledAt,

    @Future(message = "expire_at must be in future")
    OffsetDateTime expireAt,

    Map<String, Object> metadata,

    Map<String, Object> preferenceOverride
) {

    public record RecipientRef(

        @NotNull(message = "recipient.type required")
        Type type,

        @Size(max = 128)
        String subscriberId,

        @Email(message = "external recipient.email must be valid RFC 5322")
        @Size(max = 254)
        String email,

        @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$",
                 message = "external recipient.phone must be E.164 (+ + 8-15 digits)")
        String phone,

        @Size(max = 128)
        String name,

        @Size(max = 16)
        String locale
    ) {
        public enum Type { subscriber, external }
    }

    public record TemplateRef(

        @NotBlank(message = "template.template_id required")
        @Size(max = 128)
        String templateId,

        Integer version,

        @NotBlank(message = "template.locale required")
        @Size(max = 16)
        String locale
    ) {}
}
