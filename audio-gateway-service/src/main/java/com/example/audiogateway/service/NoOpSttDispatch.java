package com.example.audiogateway.service;

import java.util.Map;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Default no-op STT dispatch — PoC skeleton.
 *
 * <p>Gerçek Redis producer veya HTTP adapter PR-queue-01'de eklenecek.
 * Bu impl yalnız contract sürekliliği için var; admission ve correlation id
 * propagation test edilebilsin diye.
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
