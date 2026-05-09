package com.serban.notify.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.serban.notify.domain.NotificationIntent;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * KVKK §13 right-to-information audit history item response DTO.
 *
 * <p>Faz 23.2.B closure (M3 stale audit 2026-05-09 — Codex thread
 * {@code 019e0c28} subscriber self-service path).
 *
 * <p>PII surface: PII redacted per retention policy + PiiRedactor;
 * subscriber'ın kendi kaydı olduğu için {@code recipients_snapshot}
 * +{@code metadata} +{@code payload} alanları döndürülmez (admin scope
 * için ayrı path varsa görünür). Burada sadece intent metadata
 * (template_id, severity, classification, channels, status, timestamps)
 * exposed.
 *
 * <p>Justification: KVKK §13 right-to-information istekleri "veri var
 * mıdır + ne tür veri var" kanıtı için yeterli; ham PII access subject
 * olan kullanıcı için zaten kendisinin gönderdiği template + recipient
 * kayıtlarıdır (yeniden ifşa fayda yaratmaz, attack surface'ı genişletir).
 */
public record AuditHistoryItemResponse(
    @JsonProperty("intent_id")
    String intentId,

    @JsonProperty("correlation_id")
    String correlationId,

    @JsonProperty("topic_key")
    String topicKey,

    @JsonProperty("severity")
    String severity,

    @JsonProperty("data_classification")
    String dataClassification,

    @JsonProperty("template_id")
    String templateId,

    @JsonProperty("template_version")
    Integer templateVersion,

    @JsonProperty("locale")
    String locale,

    @JsonProperty("channels")
    List<String> channels,

    @JsonProperty("status")
    String status,

    @JsonProperty("created_at")
    OffsetDateTime createdAt,

    @JsonProperty("updated_at")
    OffsetDateTime updatedAt
) {
    public static AuditHistoryItemResponse fromEntity(NotificationIntent intent) {
        return new AuditHistoryItemResponse(
            intent.getIntentId(),
            intent.getCorrelationId(),
            intent.getTopicKey(),
            intent.getSeverity() != null ? intent.getSeverity().name() : null,
            intent.getDataClassification() != null ? intent.getDataClassification().name() : null,
            intent.getTemplateId(),
            intent.getTemplateVersion(),
            intent.getLocale(),
            intent.getChannels() != null ? List.of(intent.getChannels()) : List.of(),
            intent.getStatus() != null ? intent.getStatus().name() : null,
            intent.getCreatedAt(),
            intent.getUpdatedAt()
        );
    }
}
