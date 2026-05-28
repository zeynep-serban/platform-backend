package com.example.endpointadmin.service.compliance;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the BE-023 per-stream staleness classifier and
 * "worst" reducer. Codex 019e6bbf iter-3 critical_finding #1
 * regression guard.
 */
class StalenessSeverityTest {

    private static final Instant NOW = Instant.parse("2026-05-28T12:00:00Z");

    @Test
    void nullCollectedAtClassifiesAsUnavailable() {
        assertThat(StalenessSeverity.classify(null, NOW))
                .isEqualTo(StalenessSeverity.UNAVAILABLE);
    }

    @Test
    void zeroAgeIsFresh() {
        assertThat(StalenessSeverity.classify(NOW, NOW))
                .isEqualTo(StalenessSeverity.FRESH);
    }

    @Test
    void below24hIsFresh() {
        Instant just = NOW.minus(Duration.ofHours(23));
        assertThat(StalenessSeverity.classify(just, NOW))
                .isEqualTo(StalenessSeverity.FRESH);
    }

    @Test
    void exactly24hIsSoft() {
        Instant boundary = NOW.minus(Duration.ofHours(24));
        assertThat(StalenessSeverity.classify(boundary, NOW))
                .isEqualTo(StalenessSeverity.SOFT);
    }

    @Test
    void between24hAnd72hIsSoft() {
        Instant mid = NOW.minus(Duration.ofHours(48));
        assertThat(StalenessSeverity.classify(mid, NOW))
                .isEqualTo(StalenessSeverity.SOFT);
    }

    @Test
    void at72hIsHard() {
        Instant hard = NOW.minus(Duration.ofHours(72));
        assertThat(StalenessSeverity.classify(hard, NOW))
                .isEqualTo(StalenessSeverity.HARD);
    }

    @Test
    void above72hIsHard() {
        Instant hard = NOW.minus(Duration.ofDays(10));
        assertThat(StalenessSeverity.classify(hard, NOW))
                .isEqualTo(StalenessSeverity.HARD);
    }

    @Test
    void agentClockSkewYieldsFresh() {
        Instant future = NOW.plus(Duration.ofMinutes(5));
        assertThat(StalenessSeverity.classify(future, NOW))
                .isEqualTo(StalenessSeverity.FRESH);
    }

    @Test
    void worstIgnoresUnavailable() {
        StalenessSeverity worst = StalenessSeverity.worstOf(
                StalenessSeverity.FRESH,
                StalenessSeverity.UNAVAILABLE,
                StalenessSeverity.SOFT);
        assertThat(worst).isEqualTo(StalenessSeverity.SOFT);
    }

    @Test
    void worstReturnsHardWhenAnyStreamIsHard() {
        StalenessSeverity worst = StalenessSeverity.worstOf(
                StalenessSeverity.FRESH,
                StalenessSeverity.SOFT,
                StalenessSeverity.HARD);
        assertThat(worst).isEqualTo(StalenessSeverity.HARD);
    }

    @Test
    void worstReturnsUnavailableWhenAllStreamsUnavailable() {
        StalenessSeverity worst = StalenessSeverity.worstOf(
                StalenessSeverity.UNAVAILABLE,
                StalenessSeverity.UNAVAILABLE);
        assertThat(worst).isEqualTo(StalenessSeverity.UNAVAILABLE);
    }

    @Test
    void worstHandlesEmptyAndNullInputs() {
        assertThat(StalenessSeverity.worstOf()).isEqualTo(StalenessSeverity.UNAVAILABLE);
        assertThat(StalenessSeverity.worstOf((StalenessSeverity) null))
                .isEqualTo(StalenessSeverity.UNAVAILABLE);
    }
}
