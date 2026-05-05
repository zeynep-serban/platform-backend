package com.serban.notify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Notification Orchestrator — Faz 23 (ADR-0013-notification-orchestration).
 *
 * <p>Multi-channel transactional notification orchestration platform.
 * Codex thread {@code 019df86f-89aa-7200-bb6c-b7b903860148} (REVISE-then-AGREE
 * 4 tur). Custom Spring Boot baseline 9/10 skor; Novu / Knock / Courier deferred
 * lab/evaluation candidate.
 *
 * <h3>Faz 23.1 Kernel/Closed Beta scope</h3>
 * <ul>
 *   <li>3 kanal: email (SMTP) + Slack incoming webhook + webhook egress (HMAC)</li>
 *   <li>PG-only stateful (Mongo/Redis/RabbitMQ YASAK — ADR-0013 D39)</li>
 *   <li>Outbox pattern (domain-side): OutboxPoller PG advisory lock</li>
 *   <li>Retry exponential backoff + DLQ + manual replay</li>
 *   <li>OpenFGA hard-deny + org_id boundary (subscriber#can_receive)</li>
 *   <li>Vault/ESO provider credentials + no secret logging</li>
 *   <li>PII redaction + KVKK retention (90 gün default)</li>
 *   <li>Preference + critical bypass (severity=critical quiet'i geçer)</li>
 *   <li>Template versioning + safe Thymeleaf interpolation</li>
 *   <li>Observability + outage fallback (Alertmanager direct bypass)</li>
 * </ul>
 *
 * <h3>10 Must-Have (D46)</h3>
 * Detail: {@code platform-k8s-gitops/docs/notify/must-have-checklist.md}
 *
 * <h3>D29-NOTIFY ladder</h3>
 * <ul>
 *   <li>Up: Pod Ready + DB migration + Vault sync + outbox poller alive</li>
 *   <li>Functional: per kanal ayrı mezuniyet (email Mailpit + Slack test channel + webhook 2xx)</li>
 *   <li>Authorized: OpenFGA allow/deny + PII redaction + audit row</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class NotificationOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationOrchestratorApplication.class, args);
    }
}
