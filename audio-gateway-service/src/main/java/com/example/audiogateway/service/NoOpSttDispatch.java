package com.example.audiogateway.service;

import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Default no-op STT dispatch — PoC skeleton.
 *
 * <p>Gerçek Redis producer veya HTTP adapter PR-queue-01'de eklenecek.
 */
@Service
public class NoOpSttDispatch implements SttDispatchService {

    @Override
    public Mono<Void> enqueue(final String sessionId,
                              final long chunkSeq,
                              final byte[] chunkBytes,
                              final long chunkStartedAtMs,
                              final String language,
                              final Map<String, String> internalHeaders) {
        return Mono.empty();
    }
}
