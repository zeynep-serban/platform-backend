package com.example.endpointadmin.security;

import com.example.endpointadmin.repository.EndpointRequestNonceRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Component
public class EndpointRequestNonceCleanupJob {

    private final EndpointRequestNonceRepository nonceRepository;
    private final Clock clock;

    public EndpointRequestNonceCleanupJob(EndpointRequestNonceRepository nonceRepository, Clock clock) {
        this.nonceRepository = nonceRepository;
        this.clock = clock;
    }

    @Transactional
    @Scheduled(
            fixedDelayString = "${endpoint-admin.agent-auth.nonce-cleanup-interval-ms:600000}",
            initialDelayString = "${endpoint-admin.agent-auth.nonce-cleanup-initial-delay-ms:600000}"
    )
    public int deleteExpiredNonces() {
        return nonceRepository.deleteExpiredBefore(Instant.now(clock));
    }
}
