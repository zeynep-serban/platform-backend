package com.example.report.schema.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.schema.SchemaTruthLookupContext;
import com.example.report.schema.SchemaTruthLookupPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Phase 2 Program 8d — SchemaTruthLogContext MDC enrichment tests.
 */
class SchemaTruthLogContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void enter_setsMdcKeys_thenCloseRemoves() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "fin-muhasebe-detay", "yearly",
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "filter_translator");

        try (var mdc = SchemaTruthLogContext.enter(ctx, "tier_1")) {
            assertThat(MDC.get("schema_truth.tier")).isEqualTo("tier_1");
            assertThat(MDC.get("schema_truth.schema_mode")).isEqualTo("yearly");
            assertThat(MDC.get("schema_truth.report_key")).isEqualTo("fin-muhasebe-detay");
            assertThat(MDC.get("schema_truth.consumer")).isEqualTo("filter_translator");
        }

        // After close: MDC keys removed
        assertThat(MDC.get("schema_truth.tier")).isNull();
        assertThat(MDC.get("schema_truth.schema_mode")).isNull();
        assertThat(MDC.get("schema_truth.report_key")).isNull();
        assertThat(MDC.get("schema_truth.consumer")).isNull();
    }

    @Test
    void enter_skipsBlankValues() {
        SchemaTruthLookupContext ctx = new SchemaTruthLookupContext(
                "test", "", // blank schemaMode
                SchemaTruthLookupPolicy.RUNTIME_DEGRADED_TYPE, "test_consumer");

        try (var mdc = SchemaTruthLogContext.enter(ctx, null)) {
            // tier blank: not set
            assertThat(MDC.get("schema_truth.tier")).isNull();
            // schemaMode blank: not set
            assertThat(MDC.get("schema_truth.schema_mode")).isNull();
            // reportKey + consumer set
            assertThat(MDC.get("schema_truth.report_key")).isEqualTo("test");
            assertThat(MDC.get("schema_truth.consumer")).isEqualTo("test_consumer");
        }
    }

    @Test
    void enter_nullCtxDoesNotThrow() {
        try (var mdc = SchemaTruthLogContext.enter(null, "tier_2")) {
            assertThat(MDC.get("schema_truth.tier")).isEqualTo("tier_2");
            assertThat(MDC.get("schema_truth.schema_mode")).isNull();
        }
    }
}
