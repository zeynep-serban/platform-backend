package com.serban.notify;

import com.serban.notify.preference.TopicCatalogProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Notification Orchestrator — Faz 23 (ADR-0013-notification-orchestration).
 *
 * <p>Multi-channel transactional notification orchestration platform. PG-only
 * stateful (Mongo/Redis/RabbitMQ YASAK — ADR-0013 D39). Custom Spring Boot
 * baseline 9/10 skor (Codex thread 019df86f).
 *
 * <p>Faz 23.1 Foundation scope: module skeleton + V1 schema (9 tablo) +
 * domain entity + repository + Testcontainers PG persist/retrieve test.
 * Channel adapters + workers + authz + REST API sub-PR sequence (D47).
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync  // Faz 23.3 PR-E.3: SSE event listener async dispatch (InboxSseController)
@EnableConfigurationProperties(TopicCatalogProperties.class)  // Faz 23.5 M5 G2: topic catalog
public class NotificationOrchestratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationOrchestratorApplication.class, args);
    }
}
