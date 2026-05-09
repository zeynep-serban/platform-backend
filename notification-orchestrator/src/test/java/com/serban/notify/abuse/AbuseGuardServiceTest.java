package com.serban.notify.abuse;

import com.serban.notify.domain.NotificationIntent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AbuseGuardService unit test (Faz 23.2.F T1.6 — Codex thread `019e0c28`
 * abuse guards backend implementation).
 *
 * <p>7 test:
 * <ol>
 *   <li>Within rate limit returns ALLOWED</li>
 *   <li>Exceeding rate limit returns BLOCKED + RATE_LIMITED event type</li>
 *   <li>Critical severity bypasses rate limit</li>
 *   <li>Security data classification bypasses rate limit</li>
 *   <li>Webhook fan-out cap exceeded returns BLOCKED + WEBHOOK_FANOUT_CAPPED</li>
 *   <li>Different (orgId, topicKey) keys are independent windows</li>
 *   <li>Window reset after windowMillis expires</li>
 * </ol>
 */
class AbuseGuardServiceTest {

    private SimpleMeterRegistry registry;
    private AbuseGuardService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // Test config: window=60s, limit=5, fanoutCap=3 (low limits for fast test)
        service = new AbuseGuardService(registry, 60L, 5L, 3);
    }

    @Test
    void withinRateLimitReturnsAllowed() {
        for (int i = 0; i < 5; i++) {
            AbuseGuardService.Decision d = service.check(
                "acme", "topic.test",
                NotificationIntent.Severity.info,
                NotificationIntent.DataClassification.transactional,
                List.of("email")
            );
            assertThat(d.allowed()).isTrue();
        }
    }

    @Test
    void exceedingRateLimitReturnsBlocked() {
        // Fill window to limit
        for (int i = 0; i < 5; i++) {
            service.check("acme", "topic.test",
                NotificationIntent.Severity.info,
                NotificationIntent.DataClassification.transactional,
                List.of("email"));
        }
        // 6th request → blocked
        AbuseGuardService.Decision d = service.check(
            "acme", "topic.test",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.transactional,
            List.of("email")
        );
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("rate_limit_exceeded");
        assertThat(d.auditEventType()).isEqualTo(AbuseGuardService.EVENT_RATE_LIMITED);
        assertThat(d.auditDetails()).containsKey("count");
        assertThat(d.auditDetails()).containsKey("limit");
    }

    @Test
    void criticalSeverityBypassesRateLimit() {
        // Fill window
        for (int i = 0; i < 5; i++) {
            service.check("acme", "topic.test",
                NotificationIntent.Severity.info,
                NotificationIntent.DataClassification.transactional,
                List.of("email"));
        }
        // Critical severity → bypass
        AbuseGuardService.Decision d = service.check(
            "acme", "topic.test",
            NotificationIntent.Severity.critical,
            NotificationIntent.DataClassification.transactional,
            List.of("email")
        );
        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isEqualTo("critical_bypass");
    }

    @Test
    void securityDataClassificationDoesNotBypassRateLimit() {
        // Codex `019e0c28` P1 absorb: data_classification=security bypass kaldırıldı
        // (request DTO client-controlled, trusted producer authority yok).
        // Sadece severity=critical bypass kalır.
        for (int i = 0; i < 5; i++) {
            service.check("acme", "topic.test",
                NotificationIntent.Severity.info,
                NotificationIntent.DataClassification.transactional,
                List.of("email"));
        }
        // Security classification + non-critical severity → BLOCKED (no longer bypass)
        AbuseGuardService.Decision d = service.check(
            "acme", "topic.test",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.security,
            List.of("email")
        );
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("rate_limit_exceeded");
        assertThat(d.auditEventType()).isEqualTo(AbuseGuardService.EVENT_RATE_LIMITED);
    }

    @Test
    void webhookFanoutCapNotBypassedByCriticalSeverity() {
        // Codex P1 absorb: fan-out cap hard safety limit; severity=critical bile bypass etmez
        AbuseGuardService.Decision d = service.check(
            "acme", "topic.test",
            NotificationIntent.Severity.critical,
            NotificationIntent.DataClassification.transactional,
            List.of("webhook", "webhook", "webhook", "webhook")  // 4 > cap=3
        );
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("webhook_fanout_cap_exceeded");
    }

    @Test
    void webhookFanoutCapExceededReturnsBlocked() {
        // 4 webhook channels (cap=3) → blocked
        AbuseGuardService.Decision d = service.check(
            "acme", "topic.test",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.transactional,
            List.of("webhook", "webhook", "webhook", "webhook")
        );
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).isEqualTo("webhook_fanout_cap_exceeded");
        assertThat(d.auditEventType()).isEqualTo(AbuseGuardService.EVENT_WEBHOOK_FANOUT_CAPPED);
        assertThat(d.auditDetails()).containsEntry("cap", 3L);
    }

    @Test
    void differentKeysAreIndependentWindows() {
        // Fill window for topic.A
        for (int i = 0; i < 5; i++) {
            service.check("acme", "topic.A",
                NotificationIntent.Severity.info,
                NotificationIntent.DataClassification.transactional,
                List.of("email"));
        }
        // topic.B should still be allowed
        AbuseGuardService.Decision d = service.check(
            "acme", "topic.B",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.transactional,
            List.of("email")
        );
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void resetWindowsClearsState() {
        // Fill window
        for (int i = 0; i < 5; i++) {
            service.check("acme", "topic.test",
                NotificationIntent.Severity.info,
                NotificationIntent.DataClassification.transactional,
                List.of("email"));
        }
        // Reset → should allow again
        service.resetWindows();
        AbuseGuardService.Decision d = service.check(
            "acme", "topic.test",
            NotificationIntent.Severity.info,
            NotificationIntent.DataClassification.transactional,
            List.of("email")
        );
        assertThat(d.allowed()).isTrue();
    }
}
