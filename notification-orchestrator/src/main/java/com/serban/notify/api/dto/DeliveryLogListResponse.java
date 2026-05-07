package com.serban.notify.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Pagination wrapper for delivery log queries (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-2 AGREE: do not leak the Spring
 * {@code Page} JSON shape; use a stable wrapper that mirrors
 * {@link InboxListResponse}. {@code redactionPolicy} is a versioned hint so
 * frontend can grow with future redaction strategy changes without breaking
 * old clients.
 *
 * <p>{@code fromTimestamp} / {@code toTimestamp} echo the resolved time
 * window (admin endpoint defaults to the last 24h when the caller omits
 * them; intent endpoint omits both).
 */
public record DeliveryLogListResponse(
    @JsonProperty("items") List<DeliveryLogResponse> items,
    @JsonProperty("page") int page,
    @JsonProperty("size") int size,
    @JsonProperty("total_elements") long totalElements,
    @JsonProperty("total_pages") int totalPages,
    @JsonProperty("from") OffsetDateTime fromTimestamp,
    @JsonProperty("to") OffsetDateTime toTimestamp,
    @JsonProperty("redaction_policy") String redactionPolicy
) {}
