package com.example.schema.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.schema.model.SchemaSnapshot;
import com.example.schema.service.SchemaSnapshotService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Phase 2 Program 8a — SchemaController.getSnapshot auth path coverage.
 *
 * <p>Codex iter-2 §1 absorb: snapshot endpoint guard "internal key OR
 * valid JWT" — testlerle 4 auth kombinasyonu kilitlenir:
 * <ul>
 *   <li>Empty configured key + no caller key + no JWT → 200 (dev/test passthrough)</li>
 *   <li>Configured key + correct internal key header → 200 (report-service Tier 1 path)</li>
 *   <li>Configured key + no internal key + valid JWT → 200 (frontend JWT path preserved)</li>
 *   <li>Configured key + no internal key + no JWT → 401</li>
 * </ul>
 *
 * <p>Direct controller method invocation (no MockMvc / Spring context) — fast
 * unit test of pure auth logic. Spring Security filter chain integration
 * Phase-2-Program-8d'de @SpringBootTest IT'lerinde doğrulanır.
 */
class SchemaControllerAuthTest {

    private SchemaController controller;
    private SchemaSnapshotService snapshotService;

    @BeforeEach
    void setUp() {
        snapshotService = mock(SchemaSnapshotService.class);
        SchemaSnapshot fakeSnapshot = SchemaSnapshot.builder()
                .version("v1")
                .metadata(new SchemaSnapshot.Metadata("mssql", "host", "db", "schema",
                        Instant.now(), 0, 0, 0, 0))
                .tables(Map.of())
                .relationships(List.of())
                .domains(Map.of())
                .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                .build();
        when(snapshotService.buildSnapshot(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(fakeSnapshot);

        controller = new SchemaController(
                mock(com.example.schema.service.SchemaExtractService.class),
                snapshotService,
                mock(com.example.schema.service.SchemaLookupService.class),
                mock(com.example.schema.service.PathFinderService.class),
                mock(com.example.schema.service.SchemaHealthService.class),
                mock(com.example.schema.service.SchemaDriftService.class),
                mock(com.example.schema.service.QuerySuggestionService.class),
                mock(com.example.schema.service.ReportingContractService.class));
        ReflectionTestUtils.setField(controller, "defaultSchema", "workcube_mikrolink");
        ReflectionTestUtils.setField(controller, "cacheTtlMinutes", 60);
    }

    @Test
    void getSnapshot_emptyKeyConfigured_passesThrough() {
        // Dev/test profile — empty key config = open access (regardless of caller).
        ReflectionTestUtils.setField(controller, "snapshotInternalApiKey", "");

        ResponseEntity<SchemaSnapshot> response =
                controller.getSnapshot(null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getSnapshot_configuredKey_correctInternalHeader_returns200_internalPath() {
        // Production internal service-to-service path (report-service SchemaTruthService Tier 1).
        ReflectionTestUtils.setField(controller, "snapshotInternalApiKey", "vault-secret-key");

        ResponseEntity<SchemaSnapshot> response =
                controller.getSnapshot(null, "vault-secret-key", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getSnapshot_configuredKey_noInternalHeader_validJwt_returns200_frontendPath() {
        // Frontend / schema-explorer existing JWT path — Codex iter-2 §1 absorb:
        // internal key configured ama frontend JWT'siyle hâlâ erişebilmeli.
        ReflectionTestUtils.setField(controller, "snapshotInternalApiKey", "vault-secret-key");
        Jwt jwt = mock(Jwt.class);

        ResponseEntity<SchemaSnapshot> response =
                controller.getSnapshot(null, null, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getSnapshot_configuredKey_noInternalHeader_noJwt_returns401() {
        // Production unauthenticated request — neither internal key nor JWT.
        ReflectionTestUtils.setField(controller, "snapshotInternalApiKey", "vault-secret-key");

        ResponseEntity<SchemaSnapshot> response =
                controller.getSnapshot(null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getSnapshot_configuredKey_wrongInternalHeader_noJwt_returns401() {
        // Production — wrong internal key, no JWT → 401.
        ReflectionTestUtils.setField(controller, "snapshotInternalApiKey", "vault-secret-key");

        ResponseEntity<SchemaSnapshot> response =
                controller.getSnapshot(null, "wrong-key", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
