package com.example.audiogateway.service;

import org.springframework.stereotype.Service;

/**
 * No-op dispatcher — always returns {@link DispatchOutcome.Accepted}.
 *
 * <p>Codex {@code 019e8df2} iter-2 AGREE: default bean for PR-gw-01B3 PoC.
 * Real Redis Streams producer (PR-gw-01C) replaces this bean via
 * {@code @Primary} override (config-driven bean disambiguation).
 *
 * <p>Tests inject custom dispatcher via {@code @MockitoBean} or
 * {@code @TestConfiguration @Primary fakeAudioChunkDispatcher} pattern.
 */
@Service
public class NoOpAudioChunkDispatcher implements AudioChunkDispatcher {

    @Override
    public DispatchOutcome dispatch(final ChunkDispatchCommand cmd) {
        return new DispatchOutcome.Accepted();
    }
}
