package com.example.audiogateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * audio-gateway-service — Faz 24 Meeting Intelligence Audio Gateway.
 *
 * <p>3-AI mutabakat (Claude + Codex {@code 019e879c}/{@code 019e887c} + Mavis msg {@code 78}):
 * Audio Gateway Contract 1.0 freeze; client→Gateway public contract + Gateway→STT internal
 * contract. STT compute worker (platform-ai) yalnız bu Gateway üzerinden erişilir.
 *
 * <p>Canonical kontrat: {@code audio-gateway-service/docs/contract-v1.md}.
 */
@SpringBootApplication
public class AudioGatewayApplication {

    public static void main(final String[] args) {
        SpringApplication.run(AudioGatewayApplication.class, args);
    }
}
