package com.example.audiogateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * audio-gateway-service — Faz 24 Meeting Intelligence Audio Gateway.
 *
 * <p>3-AI mutabakat (Claude + Codex {@code 019e879c} + Mavis {@code mvs_c922...} msg {@code 78})
 * + ADR-0031 two-server topology + Codex {@code 019e8c26} iter-2 AGREE PR-gw-01A scope:
 * Audio Gateway Contract 1.0 — path canonical {@code /api/v1/audio-gateway}; client→Gateway
 * public contract + Gateway→STT internal contract. STT compute worker (platform-ai) yalnız bu
 * Gateway üzerinden cross-server erişilir (mTLS/WireGuard); mobile/web doğrudan platform-ai'a
 * bağlanmaz.
 *
 * <p>Canonical kontrat: {@code audio-gateway-service/docs/contract-v1.md}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.example.audiogateway.config")
public class AudioGatewayApplication {

    public static void main(final String[] args) {
        SpringApplication.run(AudioGatewayApplication.class, args);
    }
}
