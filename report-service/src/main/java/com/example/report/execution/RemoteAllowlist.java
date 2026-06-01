package com.example.report.execution;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Allowlist guard for remote-http executor (ADR-0015, PR-D2.1c1).
 *
 * <p>Validates incoming {@code (service, path)} tuples against the
 * deployment-time {@link RemoteExecutorProperties#allowlist()}. Exact
 * match required — service AND path must both be in the same allowlist
 * entry.
 *
 * <p>Codex 019e8306 iter-2 security boundary:
 * <ul>
 *   <li>Arbitrary URL execution YASAK</li>
 *   <li>Same {@code service} with different {@code path} → REJECT (each
 *       distinct path needs its own allowlist entry)</li>
 *   <li>Allowlist entry must declare {@code baseUrl} (host resolution
 *       authority stays in env config)</li>
 * </ul>
 *
 * <p>This is intentionally a small read-only lookup; runtime cost is
 * O(n) over the allowlist (typically n &lt; 20).
 */
@Component
public class RemoteAllowlist {

    private final RemoteExecutorProperties properties;

    public RemoteAllowlist(RemoteExecutorProperties properties) {
        this.properties = properties;
    }

    /**
     * Look up an exact {@code (service, path)} match.
     *
     * <p>Codex 019e8306 iter-3 Medium absorb: this method MUST fail-closed
     * when {@link RemoteExecutorProperties#enabled()} is {@code false},
     * even if the allowlist itself contains a matching entry. A deployment
     * that seeds an allowlist but leaves the feature gate off must NOT
     * propagate to downstream HTTP execution.
     *
     * @return matching {@link RemoteExecutorProperties.AllowlistEntry} when
     *         the tuple is allowed AND the feature gate is on;
     *         {@link Optional#empty()} otherwise. Empty result MUST result
     *         in a {@link RemoteAllowlistException} at the caller (executor)
     *         layer.
     */
    public Optional<RemoteExecutorProperties.AllowlistEntry> resolve(String service, String path) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        if (service == null || service.isBlank() || path == null || path.isBlank()) {
            return Optional.empty();
        }
        return properties.allowlist().stream()
                .filter(entry -> entry.service().equals(service) && entry.path().equals(path))
                .findFirst();
    }

    /**
     * Convenience guard variant: throws if the tuple is not allowed or
     * the feature gate is off (see {@link #resolve(String, String)}).
     */
    public RemoteExecutorProperties.AllowlistEntry require(String service, String path) {
        return resolve(service, path)
                .orElseThrow(() -> new RemoteAllowlistException(service, path));
    }

    /**
     * Lightweight feature-gate check: {@code true} when the executor is
     * enabled AND at least one allowlist entry exists.
     */
    public boolean isEnabled() {
        return properties.enabled() && !properties.allowlist().isEmpty();
    }
}
