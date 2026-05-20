package com.serban.notify.preference;

import com.serban.notify.api.dto.TopicCatalogEntryResponse;
import com.serban.notify.api.dto.TopicCatalogListResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read-only service that exposes the static topic catalog to the
 * subscriber preference UI (Faz 23.5 M5 G2).
 *
 * <p>This service is intentionally minimal — it owns no DB state, no
 * caching beyond the immutable {@link TopicCatalogProperties}
 * Spring bean, and no business logic. Its single responsibility is
 * mapping the config-layer {@link TopicCatalogProperties.CatalogEntry}
 * to the API-layer {@link TopicCatalogEntryResponse} DTO.
 *
 * <p>The catalog is identical across subscribers (org/subscriber-agnostic);
 * the {@code /me} suffix in the endpoint is a convention with the rest of
 * the subscriber-facing surface (parity with {@code /preferences/me},
 * {@code /audit/me}, etc.) rather than a per-subscriber filter.
 */
@Service
public class TopicCatalogService {

    private final TopicCatalogProperties properties;

    public TopicCatalogService(TopicCatalogProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns the full catalog wrapped in a {@link TopicCatalogListResponse}.
     * Returns an empty list (never null) if no catalog is configured —
     * the frontend treats empty catalog as "catalog unavailable" and
     * falls back to free-text topic entry.
     */
    public TopicCatalogListResponse listCatalog() {
        List<TopicCatalogEntryResponse> items = properties.catalog().stream()
            .map(this::toResponse)
            .toList();
        return new TopicCatalogListResponse(items);
    }

    private TopicCatalogEntryResponse toResponse(TopicCatalogProperties.CatalogEntry entry) {
        return new TopicCatalogEntryResponse(
            entry.topicKey(),
            entry.label(),
            entry.category(),
            entry.supportedChannels(),
            entry.criticalEligible(),
            entry.description(),
            entry.defaultFrequencyHint()
        );
    }
}
