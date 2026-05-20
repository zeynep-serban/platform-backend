package com.serban.notify.preference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Configuration properties for the subscriber-facing topic catalog
 * (Faz 23.5 M5 G2). Bound to {@code notify.topics.catalog} in
 * {@code application.yml}.
 *
 * <p>Catalog entries are static (deploy-time config, not DB-backed).
 * To add/remove/edit a topic, modify {@code application.yml} and
 * re-deploy; the catalog endpoint reflects the new state on next
 * pod startup.
 *
 * <p>Validation:
 * <ul>
 *   <li>{@code topicKey} is required (NotBlank); other fields may be
 *       null but typically all are populated.</li>
 *   <li>No uniqueness check on {@code topicKey} at bind time — operator
 *       responsibility (last-wins semantically; tests cover this).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "notify.topics")
@Validated
public record TopicCatalogProperties(
    @Valid List<CatalogEntry> catalog
) {

    public TopicCatalogProperties {
        if (catalog == null) {
            catalog = List.of();
        }
    }

    /**
     * Single catalog entry — mirrors {@link com.serban.notify.api.dto.TopicCatalogEntryResponse}
     * but lives in the config layer (responses are produced by a
     * dedicated mapper in {@link TopicCatalogService}).
     */
    public record CatalogEntry(
        @NotBlank String topicKey,
        String label,
        String category,
        List<String> supportedChannels,
        Boolean criticalEligible,
        String description,
        Integer defaultFrequencyHint
    ) {
        public CatalogEntry {
            if (supportedChannels == null) {
                supportedChannels = List.of();
            }
            if (criticalEligible == null) {
                criticalEligible = false;
            }
        }
    }
}
