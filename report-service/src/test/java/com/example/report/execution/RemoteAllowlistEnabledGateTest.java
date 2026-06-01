package com.example.report.execution;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-D2.1c1 Codex iter-3 Medium absorb — feature gate enforcement at the
 * {@link RemoteAllowlist} layer (NOT just config posture).
 *
 * <p>Even when an env overlay seeds the allowlist, leaving
 * {@code report.remote-executor.enabled=false} must close ALL downstream
 * HTTP execution paths fail-closed.
 */
class RemoteAllowlistEnabledGateTest {

    @Test
    @DisplayName("resolve returns empty when enabled=false even with seeded allowlist")
    void resolveEmptyWhenDisabled() {
        var props = new RemoteExecutorProperties(
                false,
                Duration.ofSeconds(5),
                List.of(new RemoteExecutorProperties.AllowlistEntry(
                        "user-service", "http://user-service:8086",
                        "/api/v1/users", "style-api-paged-v1")));
        var allowlist = new RemoteAllowlist(props);
        assertThat(allowlist.resolve("user-service", "/api/v1/users")).isEmpty();
    }

    @Test
    @DisplayName("require throws when enabled=false even with seeded allowlist (Codex iter-3)")
    void requireThrowsWhenDisabled() {
        var props = new RemoteExecutorProperties(
                false,
                Duration.ofSeconds(5),
                List.of(new RemoteExecutorProperties.AllowlistEntry(
                        "user-service", "http://user-service:8086",
                        "/api/v1/users", "style-api-paged-v1")));
        var allowlist = new RemoteAllowlist(props);
        assertThatThrownBy(() -> allowlist.require("user-service", "/api/v1/users"))
                .isInstanceOf(RemoteAllowlistException.class);
    }

    @Test
    @DisplayName("resolve works normally when enabled=true with seeded allowlist")
    void resolveWorksWhenEnabled() {
        var props = new RemoteExecutorProperties(
                true,
                Duration.ofSeconds(5),
                List.of(new RemoteExecutorProperties.AllowlistEntry(
                        "user-service", "http://user-service:8086",
                        "/api/v1/users", "style-api-paged-v1")));
        var allowlist = new RemoteAllowlist(props);
        assertThat(allowlist.resolve("user-service", "/api/v1/users")).isPresent();
    }

    // ---- baseUrl host+port only validation (Codex iter-3 Medium absorb) - //

    @Test
    @DisplayName("AllowlistEntry rejects baseUrl with path component")
    void baseUrlWithPath() {
        assertThatThrownBy(() -> new RemoteExecutorProperties.AllowlistEntry(
                "user-service", "http://user-service:8086/api",
                "/api/v1/users", "style-api-paged-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl must be host+port only (no path)");
    }

    @Test
    @DisplayName("AllowlistEntry accepts baseUrl with trailing slash only")
    void baseUrlWithTrailingSlash() {
        // host+port + trailing slash is OK — common shape
        var entry = new RemoteExecutorProperties.AllowlistEntry(
                "user-service", "http://user-service:8086/",
                "/api/v1/users", "style-api-paged-v1");
        assertThat(entry.baseUrl()).isEqualTo("http://user-service:8086/");
    }

    @Test
    @DisplayName("AllowlistEntry rejects baseUrl with query string")
    void baseUrlWithQuery() {
        assertThatThrownBy(() -> new RemoteExecutorProperties.AllowlistEntry(
                "user-service", "http://user-service:8086?leak=1",
                "/api/v1/users", "style-api-paged-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl must not contain query");
    }

    @Test
    @DisplayName("AllowlistEntry rejects baseUrl with fragment")
    void baseUrlWithFragment() {
        assertThatThrownBy(() -> new RemoteExecutorProperties.AllowlistEntry(
                "user-service", "http://user-service:8086#frag",
                "/api/v1/users", "style-api-paged-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl must not contain fragment");
    }

    @Test
    @DisplayName("AllowlistEntry rejects baseUrl with userinfo")
    void baseUrlWithUserinfo() {
        assertThatThrownBy(() -> new RemoteExecutorProperties.AllowlistEntry(
                "user-service", "http://hacker:secret@user-service:8086",
                "/api/v1/users", "style-api-paged-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl must not contain userinfo");
    }

    @Test
    @DisplayName("AllowlistEntry rejects invalid URI baseUrl")
    void baseUrlInvalidUri() {
        // Malformed URI (contains unescaped illegal char)
        assertThatThrownBy(() -> new RemoteExecutorProperties.AllowlistEntry(
                "user-service", "http://user service:8086",
                "/api/v1/users", "style-api-paged-v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
