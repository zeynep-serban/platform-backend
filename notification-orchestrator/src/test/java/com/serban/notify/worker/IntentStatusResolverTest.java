package com.serban.notify.worker;

import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IntentStatusResolver unit test (Codex 019dfa47 Q4 REVISE absorb).
 */
class IntentStatusResolverTest {

    private final IntentStatusResolver resolver = new IntentStatusResolver();

    @Test
    void emptyDeliveriesReturnsNull() {
        assertThat(resolver.resolve(List.of())).isNull();
        assertThat(resolver.resolve(null)).isNull();
    }

    @Test
    void allDeliveredCompleted() {
        var d1 = delivery(NotificationDelivery.Status.DELIVERED);
        var d2 = delivery(NotificationDelivery.Status.DELIVERED);
        assertThat(resolver.resolve(List.of(d1, d2)))
            .isEqualTo(NotificationIntent.Status.COMPLETED);
    }

    @Test
    void mixedDeliveredAndFailedPartiallyFailed() {
        var d1 = delivery(NotificationDelivery.Status.DELIVERED);
        var d2 = delivery(NotificationDelivery.Status.FAILED);
        assertThat(resolver.resolve(List.of(d1, d2)))
            .isEqualTo(NotificationIntent.Status.PARTIALLY_FAILED);
    }

    @Test
    void mixedDeliveredAndBouncedPartiallyFailed() {
        var d1 = delivery(NotificationDelivery.Status.DELIVERED);
        var d2 = delivery(NotificationDelivery.Status.BOUNCED);
        assertThat(resolver.resolve(List.of(d1, d2)))
            .isEqualTo(NotificationIntent.Status.PARTIALLY_FAILED);
    }

    @Test
    void allFailedReturnsFailed() {
        var d1 = delivery(NotificationDelivery.Status.FAILED);
        var d2 = delivery(NotificationDelivery.Status.BOUNCED);
        assertThat(resolver.resolve(List.of(d1, d2)))
            .isEqualTo(NotificationIntent.Status.FAILED);
    }

    @Test
    void anyRetryReturnsNull() {
        var d1 = delivery(NotificationDelivery.Status.DELIVERED);
        var d2 = delivery(NotificationDelivery.Status.RETRY);
        assertThat(resolver.resolve(List.of(d1, d2))).isNull();
    }

    @Test
    void anyPendingReturnsNull() {
        var d1 = delivery(NotificationDelivery.Status.DELIVERED);
        var d2 = delivery(NotificationDelivery.Status.PENDING);
        assertThat(resolver.resolve(List.of(d1, d2))).isNull();
    }

    @Test
    void blockedTreatedAsTerminalFailureWithDelivered() {
        var d1 = delivery(NotificationDelivery.Status.DELIVERED);
        var d2 = delivery(NotificationDelivery.Status.BLOCKED_BY_PREFERENCE);
        assertThat(resolver.resolve(List.of(d1, d2)))
            .isEqualTo(NotificationIntent.Status.PARTIALLY_FAILED);
    }

    @Test
    void allBlockedReturnsFailed() {
        var d1 = delivery(NotificationDelivery.Status.BLOCKED_BY_PREFERENCE);
        var d2 = delivery(NotificationDelivery.Status.BLOCKED_BY_AUTHZ);
        assertThat(resolver.resolve(List.of(d1, d2)))
            .isEqualTo(NotificationIntent.Status.FAILED);
    }

    // ─── Faz 23.4 PR-F: ACCEPTED state outstanding ──────────────────────

    @Test
    void anyAcceptedReturnsNullKeepsProcessing() {
        // ACCEPTED = provider queued, awaiting DLR. Intent must stay PROCESSING.
        var d1 = delivery(NotificationDelivery.Status.DELIVERED);
        var d2 = delivery(NotificationDelivery.Status.ACCEPTED);
        assertThat(resolver.resolve(List.of(d1, d2))).isNull();
    }

    @Test
    void allAcceptedReturnsNull() {
        // Multi-recipient SMS: hepsi ACCEPTED → DLR'leri bekliyoruz
        var d1 = delivery(NotificationDelivery.Status.ACCEPTED);
        var d2 = delivery(NotificationDelivery.Status.ACCEPTED);
        assertThat(resolver.resolve(List.of(d1, d2))).isNull();
    }

    @Test
    void mixedAcceptedAndFailedReturnsNull() {
        // ACCEPTED outstanding olduğu için failed kararını ertele
        var d1 = delivery(NotificationDelivery.Status.FAILED);
        var d2 = delivery(NotificationDelivery.Status.ACCEPTED);
        assertThat(resolver.resolve(List.of(d1, d2))).isNull();
    }

    // ─── Faz 23.7 M7 T4.2 PR-W2.5+W2.6 (Codex 019e4a3d iter-2 P1) ────────

    @Test
    void mixedDeliveredAndBlockedNoPushEndpointPartiallyFailed() {
        // email DELIVERED + push BLOCKED_NO_PUSH_ENDPOINT (subscriber Web
        // Push subscribe etmemiş) → resolver PARTIALLY_FAILED dönmeli;
        // önceki davranış COMPLETED (yanlış) idi.
        var d1 = delivery(NotificationDelivery.Status.DELIVERED);
        var d2 = delivery(NotificationDelivery.Status.BLOCKED_NO_PUSH_ENDPOINT);
        assertThat(resolver.resolve(List.of(d1, d2)))
            .isEqualTo(NotificationIntent.Status.PARTIALLY_FAILED);
    }

    @Test
    void allBlockedNoPushEndpointReturnsFailed() {
        // push-only intent + 0 endpoint → tek BLOCKED_NO_PUSH_ENDPOINT
        // delivery; resolver FAILED dönmeli (zombie state önlendi).
        var d1 = delivery(NotificationDelivery.Status.BLOCKED_NO_PUSH_ENDPOINT);
        assertThat(resolver.resolve(List.of(d1)))
            .isEqualTo(NotificationIntent.Status.FAILED);
    }

    @Test
    void mixedDeliveredAndBlockedBySuppressionPartiallyFailed() {
        // Faz 23.8 M7 T4.3.b scope gap: BLOCKED_BY_SUPPRESSION da
        // terminal-failure set'inde değildi. Codex 019e4a3d iter-2 absorb
        // ile aynı satıra eklendi.
        var d1 = delivery(NotificationDelivery.Status.DELIVERED);
        var d2 = delivery(NotificationDelivery.Status.BLOCKED_BY_SUPPRESSION);
        assertThat(resolver.resolve(List.of(d1, d2)))
            .isEqualTo(NotificationIntent.Status.PARTIALLY_FAILED);
    }

    private NotificationDelivery delivery(NotificationDelivery.Status status) {
        NotificationDelivery d = new NotificationDelivery();
        d.setStatus(status);
        return d;
    }
}
