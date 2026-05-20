package com.serban.notify.api.dto;

import java.util.List;

/**
 * Response wrapper for {@code GET /api/v1/notify/topics/me} (Faz 23.5
 * M5 G2). Lists the universe of known topics for the subscriber-facing
 * preference editor UI.
 *
 * <p>Returned as a wrapped list (rather than a bare {@code List<...>})
 * so the schema can grow without a breaking change (e.g. adding
 * {@code totalCount} or {@code lastUpdated} later).
 */
public record TopicCatalogListResponse(
    List<TopicCatalogEntryResponse> items
) {}
