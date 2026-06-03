package com.example.audiogateway.service;

import org.springframework.stereotype.Service;

/**
 * No-op audit sink — discards events.
 *
 * <p>Codex {@code 019e8df2} iter-2 AGREE: default bean for PR-gw-01B3 PoC.
 * Real persistence (KVKK Madde 12 7yr immutable retention) ayrı audit PR scope.
 * Tests override via {@code @TestConfiguration @Primary recordingAudioGatewayAuditSink}.
 */
@Service
public class NoOpAudioGatewayAuditSink implements AudioGatewayAuditSink {

    @Override
    public void emit(final AuditEvent event) {
        // intentionally no-op
    }
}
