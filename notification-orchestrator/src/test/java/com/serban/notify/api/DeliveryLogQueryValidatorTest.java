package com.serban.notify.api;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DeliveryLogQueryValidator} (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE: validation is explicit
 * (no silent clamp). These tests pin every 400 condition.
 */
class DeliveryLogQueryValidatorTest {

    @Test
    void validateSize_acceptsValidValues() {
        DeliveryLogQueryValidator.validateSize(1);
        DeliveryLogQueryValidator.validateSize(20);
        DeliveryLogQueryValidator.validateSize(100);
    }

    @Test
    void validateSize_rejectsZeroOrNegative() {
        assertThatThrownBy(() -> DeliveryLogQueryValidator.validateSize(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(">= 1");
    }

    @Test
    void validateSize_rejectsAboveMax_noSilentClamp() {
        assertThatThrownBy(() -> DeliveryLogQueryValidator.validateSize(101))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("<= 100");
    }

    @Test
    void validatePage_rejectsNegative() {
        DeliveryLogQueryValidator.validatePage(0);
        assertThatThrownBy(() -> DeliveryLogQueryValidator.validatePage(-1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolveAdminWindow_appliesDefaultsWhenBothNull() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-07T10:00:00Z");
        OffsetDateTime[] window = DeliveryLogQueryValidator.resolveAdminWindow(null, null, now);
        assertThat(window[0]).isEqualTo(now.minusHours(24));
        assertThat(window[1]).isEqualTo(now);
    }

    @Test
    void resolveAdminWindow_appliesPartialDefault_whenToOmitted() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-07T10:00:00Z");
        OffsetDateTime from = now.minusHours(6);
        OffsetDateTime[] window = DeliveryLogQueryValidator.resolveAdminWindow(from, null, now);
        assertThat(window[0]).isEqualTo(from);
        assertThat(window[1]).isEqualTo(now);
    }

    @Test
    void resolveAdminWindow_rejectsFromAfterTo() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-07T10:00:00Z");
        OffsetDateTime later = now.plusHours(1);
        assertThatThrownBy(() -> DeliveryLogQueryValidator.resolveAdminWindow(later, now, now))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("from");
    }

    @Test
    void resolveAdminWindow_rejectsRangeAboveSevenDays() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-07T10:00:00Z");
        OffsetDateTime tooFarBack = now.minusDays(8);
        assertThatThrownBy(() -> DeliveryLogQueryValidator.resolveAdminWindow(tooFarBack, now, now))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("7");
    }

    @Test
    void resolveAdminWindow_acceptsRangeAtMaxBoundary() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-07T10:00:00Z");
        OffsetDateTime sevenDaysAgo = now.minusDays(7);
        OffsetDateTime[] window = DeliveryLogQueryValidator.resolveAdminWindow(
            sevenDaysAgo, now, now
        );
        assertThat(window[0]).isEqualTo(sevenDaysAgo);
        assertThat(window[1]).isEqualTo(now);
    }

    @Test
    void resolveAdminWindow_rejectsFutureBeyondSkewTolerance() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-07T10:00:00Z");
        OffsetDateTime tooFarFuture = now.plusDays(2);
        assertThatThrownBy(() ->
            DeliveryLogQueryValidator.resolveAdminWindow(now, tooFarFuture, now))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("future");
    }

    @Test
    void resolveAdminWindow_acceptsTimestampsWithinSkewTolerance() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-07T10:00:00Z");
        OffsetDateTime nearFuture = now.plusHours(1);
        OffsetDateTime[] window = DeliveryLogQueryValidator.resolveAdminWindow(
            now.minusHours(1), nearFuture, now
        );
        assertThat(window[1]).isEqualTo(nearFuture);
    }
}
