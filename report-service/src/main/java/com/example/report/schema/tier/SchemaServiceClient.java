package com.example.report.schema.tier;

import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthLookupContext;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Phase 2 Program 8 — Tier 1 schema-service client.
 *
 * <p>Runtime'da primary kaynak; 5-min Caffeine cache ile fast hot-path.
 * Build-time mode'da {@link SchemaTruthLookupPolicy#BUILD_DETERMINISTIC}
 * policy ile **disabled** — caller sorumluluğunda.
 *
 * <p>Cache key: {@code schemaName} (snapshot tüm bir schema için yüklenir).
 * Per-schema invalidation via {@code @CacheEvict} (henüz wire'lı değil —
 * 8d'de eklenecek).
 *
 * <p>Spec: §2.1, §2.2 Tier 1 path.
 *
 * @see com.example.report.schema.SchemaTruthLookupPolicy
 * @see com.example.report.schema.SchemaTruthLookupContext
 */
@Component
public class SchemaServiceClient {

    private static final Logger log = LoggerFactory.getLogger(SchemaServiceClient.class);
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final WebClient webClient;
    private final String internalApiKey;

    public SchemaServiceClient(@Qualifier("plainWebClientBuilder") WebClient.Builder plainWebClientBuilder,
                                @Value("${schema.service.base-url:http://schema-service:8096}") String baseUrl,
                                @Value("${schema.service.internal-api-key:}") String internalApiKey) {
        // D7: K8s native DNS — plain builder, @LoadBalanced gerek yok.
        // Codex iter-1 §1 absorb: default port 8096 (mevcut SchemaMasterDataClient
        // pattern'iyle aligned; service.yaml port: 8096; application.yml default 8096).
        // Codex iter-1 §3 absorb: X-Internal-Api-Key header (mevcut MasterDataReadController
        // gate pattern'iyle simetrik; empty key dev/test'te open access, production
        // ESO/Vault üzerinden set edilir).
        this.webClient = plainWebClientBuilder
                .baseUrl(baseUrl)
                .build();
        this.internalApiKey = internalApiKey;
    }

    /**
     * Schema snapshot'ı schema-service'ten çeker; Caffeine cache hit/miss
     * yönetimi {@code @Cacheable("schemaTruthSnapshot")} ile.
     *
     * <p>Mock-friendly imza: cache miss durumunda HTTP çağrısı yapılır;
     * test'lerde cache manager ayrı configure edilebilir.
     *
     * @param ctx          lookup context (logging/MDC için; cache key'e dahil değil
     *                     çünkü context cardinality cache cardinality'sini şişirir)
     * @param schemaName   workcube schema adı (örn. {@code workcube_mikrolink_2026_35})
     * @return {@link Optional#empty()} HTTP 404 durumunda (column gerçekten yok);
     *         {@link Optional#of(Object)} cache hit / fresh fetch sonrası;
     *         RuntimeException network/timeout/5xx durumunda (caller fail-soft için
     *         try/catch tutar — Tier 2'ye düşme kararı facade tarafında).
     */
    @Cacheable(cacheNames = "schemaTruthSnapshot", key = "#schemaName")
    public Optional<SchemaSnapshot> fetchSnapshot(SchemaTruthLookupContext ctx, String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            return Optional.empty();
        }
        log.debug("schema-service snapshot fetch: schema={} consumer={}",
                schemaName, ctx != null ? ctx.consumer() : "unknown");
        try {
            WebClient.RequestHeadersSpec<?> spec = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/schema/snapshot")
                            .queryParam("schema", schemaName)
                            .build());
            // Codex iter-1 §3 absorb: internal API key (empty key dev/test passthrough)
            if (StringUtils.hasText(internalApiKey)) {
                spec = spec.header(INTERNAL_API_KEY_HEADER, internalApiKey);
            }
            SchemaSnapshot snapshot = spec
                    .retrieve()
                    .bodyToMono(SchemaSnapshot.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            return Optional.ofNullable(snapshot);
        } catch (WebClientResponseException.NotFound notFound) {
            log.debug("schema-service 404 for schema={}", schemaName);
            return Optional.empty();
        }
    }
}
