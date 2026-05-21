package com.example.endpointadmin.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class EnrollmentAttemptLimiter {

    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxAttemptsPerMinute;

    public EnrollmentAttemptLimiter(
            Clock clock,
            @Value("${endpoint-admin.enrollment.consume-rate-limit-per-minute:5}") int maxAttemptsPerMinute
    ) {
        this.clock = clock;
        this.maxAttemptsPerMinute = Math.max(1, maxAttemptsPerMinute);
    }

    public void checkAllowed(String remoteAddress, String tokenHash) {
        String key = sanitize(remoteAddress) + ":" + sanitize(tokenHash);
        Instant now = Instant.now(clock);
        Instant cutoff = now.minus(Duration.ofMinutes(1));
        Deque<Instant> bucket = attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst().isBefore(cutoff)) {
                bucket.removeFirst();
            }
            if (bucket.size() >= maxAttemptsPerMinute) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Enrollment consume rate limit exceeded.");
            }
            bucket.addLast(now);
        }
    }

    private String sanitize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
