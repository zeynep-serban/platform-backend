package com.example.audiogateway.service;

import reactor.core.publisher.Mono;

/**
 * STT dispatch interface — Gateway → STT internal contract.
 *
 * <p>STT compute worker (platform-ai) doğrudan client'a erişemez; yalnız Gateway üzerinden
 * çağrılır. Mock {@code NoOpSttDispatch} default impl; gerçek Redis producer veya HTTP adapter
 * PR-queue-01'de.
 *
 * <p>Internal headers (Gateway-derived) — client'tan ASLA trust edilmez:
 * <ul>
 *   <li>X-Correlation-Id</li>
 *   <li>X-Meeting-Id</li>
 *   <li>X-Session-Id</li>
 *   <li>X-Device-Id</li>
 *   <li>X-Tenant-Id (JWT-derived; default claim name {@code companyId})</li>
 *   <li>X-User-Id (JWT-derived; default claim name {@code userId})</li>
 * </ul>
 */
public interface SttDispatchService {

    /**
     * Enqueue a chunk for STT processing.
     *
     * @return Mono<Void> completes when admitted; errors with backpressure exception
     *         when queue full
     */
    Mono<Void> enqueue(String sessionId,
                       long chunkSeq,
                       byte[] chunkBytes,
                       long chunkStartedAtMs,
                       String language,
                       java.util.Map<String, String> internalHeaders);
}
