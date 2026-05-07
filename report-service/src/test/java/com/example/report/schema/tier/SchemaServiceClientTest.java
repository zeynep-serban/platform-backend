package com.example.report.schema.tier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.report.config.CacheConfig;
import com.example.report.config.WebClientConfig;
import com.example.report.schema.SchemaSnapshot;
import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthLookupPolicy;
import java.io.IOException;
import java.util.Optional;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Phase 2 Program 8a — SchemaServiceClient Tier 1 unit tests.
 *
 * <p>Spec §5.1:
 * <ul>
 *   <li>{@code SchemaServiceClient_cacheHitReturnsCached} — same call twice = 1 HTTP call</li>
 *   <li>{@code SchemaServiceClient_404DoesNotFallback} — 404 → Optional.empty()</li>
 *   <li>{@code SchemaServiceClient_5xxPropagatesException} — caller fail-soft için
 *       caller try/catch ile yakalar (Tier 2 fallback 8b'de)</li>
 * </ul>
 *
 * <p>{@link MockWebServer} schema-service stub'u — Spring Boot context'inde
 * gerçek WebClient + Caffeine cache wire'lı. Static init pattern: server
 * @BeforeAll ile başlatılır, @DynamicPropertySource statik field'tan
 * URL okur (context loading sırası: @DynamicPropertySource → @BeforeAll →
 * @BeforeEach → @Test).
 */
@SpringBootTest(classes = {SchemaServiceClient.class, CacheConfig.class, WebClientConfig.class})
class SchemaServiceClientTest {

    private static final MockWebServer MOCK_SERVER = new MockWebServer();

    @Autowired
    private SchemaServiceClient client;

    @Autowired
    private CacheManager cacheManager;

    @BeforeAll
    static void startServer() throws IOException {
        MOCK_SERVER.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        MOCK_SERVER.shutdown();
    }

    @BeforeEach
    void clearCache() {
        // Cache hit testi izole olsun: önceki test'in cache'i sızmasın
        var cache = cacheManager.getCache("schemaTruthSnapshot");
        if (cache != null) {
            cache.clear();
        }
    }

    @DynamicPropertySource
    static void overrideBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("schema.service.base-url", () ->
                MOCK_SERVER.url("/").toString().replaceAll("/$", ""));
    }

    @Test
    void fetchSnapshot_cacheHitReturnsCached_singleHttpCall() {
        // İlk request: 200 OK + body
        MOCK_SERVER.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"tables\":{\"ACCOUNT_CARD_ROWS\":{\"name\":\"ACCOUNT_CARD_ROWS\","
                        + "\"schema\":\"workcube_mikrolink_2026_35\","
                        // Codex iter-1 §2 absorb: production-shaped `dataType` field
                        + "\"columns\":[{\"name\":\"AMOUNT\",\"dataType\":\"DECIMAL(18,2)\"}]}}}"));

        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "test_consumer");

        int requestCountBefore = MOCK_SERVER.getRequestCount();
        Optional<SchemaSnapshot> first = client.fetchSnapshot(ctx, "workcube_mikrolink_2026_35");
        Optional<SchemaSnapshot> second = client.fetchSnapshot(ctx, "workcube_mikrolink_2026_35");

        assertThat(first).isPresent();
        assertThat(first.get().tables()).containsKey("ACCOUNT_CARD_ROWS");
        // Codex iter-1 §2 absorb: dataType deserializes correctly (production shape).
        assertThat(first.get().tables().get("ACCOUNT_CARD_ROWS").columns())
                .hasSize(1);
        assertThat(first.get().tables().get("ACCOUNT_CARD_ROWS").columns().get(0).dataType())
                .isEqualTo("DECIMAL(18,2)");
        assertThat(second).isPresent();
        // Cache hit: ikinci çağrı HTTP'ye gitmemeli — request count artışı = 1
        assertThat(MOCK_SERVER.getRequestCount() - requestCountBefore).isEqualTo(1);
    }

    @Test
    void fetchSnapshot_404DoesNotFallback_returnsEmptyOptional() {
        // 404: schema-service "schema bulunamadı" der; caller fall-back yapmaz, Optional.empty döner.
        MOCK_SERVER.enqueue(new MockResponse().setResponseCode(404));

        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "test_consumer");

        Optional<SchemaSnapshot> result = client.fetchSnapshot(ctx, "workcube_nonexistent_schema");

        assertThat(result).isEmpty();
    }

    @Test
    void fetchSnapshot_5xxPropagatesException_callerCatchesForFallback() {
        // 500: schema-service down; caller (SchemaTruthService 8b) bu exception'u yakalar
        // ve RUNTIME_DEGRADED_TYPE policy altında Tier 2'ye düşer; RUNTIME_STRICT_EXISTENCE
        // altında 503 schema_resolver_miss üretir.
        MOCK_SERVER.enqueue(new MockResponse().setResponseCode(500));

        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "test_consumer");

        assertThatThrownBy(() ->
                client.fetchSnapshot(ctx, "workcube_mikrolink_2026_35"))
                .isInstanceOf(RuntimeException.class);
    }
}
