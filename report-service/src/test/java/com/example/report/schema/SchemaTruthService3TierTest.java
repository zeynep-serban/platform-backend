package com.example.report.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.report.schema.tier.CommittedSnapshotLoader;
import com.example.report.schema.tier.RegistryTypeFallback;
import com.example.report.schema.tier.SchemaServiceClient;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Program 8b — SchemaTruthService 3-tier orchestration tests.
 *
 * <p>Spec §5.1:
 * {@code SchemaTruthService_3tier_metricsIncrement}: Tier 1 fail-soft → Tier 2 hit
 * (RUNTIME_DEGRADED_TYPE policy ile fallback chain).
 *
 * <p>Plus policy isolation:
 * <ul>
 *   <li>BUILD_DETERMINISTIC: Tier 2 only, Tier 1 not invoked</li>
 *   <li>RUNTIME_STRICT_EXISTENCE: Tier 1 only, exception propagates</li>
 *   <li>RUNTIME_DEGRADED_TYPE: Tier 1 → Tier 2 fallback</li>
 * </ul>
 */
class SchemaTruthService3TierTest {

    private SchemaServiceClient tier1;
    private CommittedSnapshotLoader tier2;
    private RegistryTypeFallback tier3;
    private SchemaTruthService service;

    private static final String SCHEMA = "workcube_mikrolink_2026_35";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        tier1 = mock(SchemaServiceClient.class);
        tier2 = mock(CommittedSnapshotLoader.class);
        tier3 = mock(RegistryTypeFallback.class);
        // 8d: ObjectProvider<SchemaTruthMetrics> null-safe (metrics absent in unit tests)
        org.springframework.beans.factory.ObjectProvider<com.example.report.schema.observability.SchemaTruthMetrics> metricsProvider =
                (org.springframework.beans.factory.ObjectProvider<com.example.report.schema.observability.SchemaTruthMetrics>)
                        mock(org.springframework.beans.factory.ObjectProvider.class);
        when(metricsProvider.getIfAvailable()).thenReturn(null);
        service = new SchemaTruthService(tier1, tier2, tier3, metricsProvider);
    }

    @Test
    void buildDeterministic_usesTier2_skipsTier1() {
        SchemaSnapshot snapshot = new SchemaSnapshot(Map.of());
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.BUILD_DETERMINISTIC, "contract_validator");

        when(tier2.lookup(ctx, SCHEMA)).thenReturn(Optional.of(snapshot));

        Optional<SchemaSnapshot> result = service.fetchSnapshot(ctx, SCHEMA);

        assertThat(result).isPresent();
        verify(tier1, never()).fetchSnapshot(ctx, SCHEMA);
        verify(tier2, times(1)).lookup(ctx, SCHEMA);
    }

    @Test
    void runtimeStrictExistence_tier1ExceptionPropagates() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_STRICT_EXISTENCE, "tenant_boundary_guard");

        when(tier1.fetchSnapshot(ctx, SCHEMA))
                .thenThrow(new RuntimeException("schema-service unreachable"));

        // Strict mode: Tier 1 fail-soft → exception propagates (caller 503).
        assertThatThrownBy(() -> service.fetchSnapshot(ctx, SCHEMA))
                .isInstanceOf(RuntimeException.class);
        verify(tier2, never()).lookup(ctx, SCHEMA);
    }

    @Test
    void runtimeDegradedType_tier1ExceptionFallsToTier2_returnsTier2Result() {
        SchemaSnapshot snapshot = new SchemaSnapshot(Map.of());
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "filter_translator");

        when(tier1.fetchSnapshot(ctx, SCHEMA))
                .thenThrow(new RuntimeException("schema-service unreachable"));
        when(tier2.lookup(ctx, SCHEMA)).thenReturn(Optional.of(snapshot));

        Optional<SchemaSnapshot> result = service.fetchSnapshot(ctx, SCHEMA);

        assertThat(result).isPresent();
        verify(tier1, times(1)).fetchSnapshot(ctx, SCHEMA);
        verify(tier2, times(1)).lookup(ctx, SCHEMA);
    }

    @Test
    void runtimeDegradedType_tier1Empty_fallsToTier2() {
        // Tier 1 returns empty (404) → fall to Tier 2
        SchemaSnapshot snapshot = new SchemaSnapshot(Map.of());
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "sql_builder_discovery");

        when(tier1.fetchSnapshot(ctx, SCHEMA)).thenReturn(Optional.empty());
        when(tier2.lookup(ctx, SCHEMA)).thenReturn(Optional.of(snapshot));

        Optional<SchemaSnapshot> result = service.fetchSnapshot(ctx, SCHEMA);

        assertThat(result).isPresent();
        verify(tier2, times(1)).lookup(ctx, SCHEMA);
    }
}
