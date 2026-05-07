package com.example.report.schema;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Phase 2 Program 8d — Schema Truth Spring config.
 *
 * <p>{@link EnableScheduling} {@link com.example.report.schema.observability.SchemaTruthMetrics}
 * {@code @Scheduled} gauge refresh için gerekli.
 *
 * <p>{@link Clock} bean injectable test deterministic age calculations
 * için ({@link com.example.report.schema.tier.CommittedSnapshotLoader#snapshotAgeDays(Clock)} +
 * {@link com.example.report.schema.observability.SchemaTruthMetrics#refreshSnapshotAgeGauge()}).
 */
@Configuration
@EnableScheduling
public class SchemaTruthConfig {

    @Bean
    public Clock schemaTruthClock() {
        return Clock.systemUTC();
    }
}
