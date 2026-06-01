package com.example.report.execution;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-D2.1c1 — {@link RemoteAllowlist} unit tests.
 *
 * <p>Codex 019e8306 iter-2 security guard: exact (service, path) match;
 * arbitrary URL execution rejected.
 */
class RemoteAllowlistTest {

    private final RemoteExecutorProperties props = new RemoteExecutorProperties(
            true,
            Duration.ofSeconds(5),
            List.of(
                    new RemoteExecutorProperties.AllowlistEntry(
                            "user-service", "http://user-service:8086",
                            "/api/v1/users", "style-api-paged-v1"),
                    new RemoteExecutorProperties.AllowlistEntry(
                            "permission-service", "http://permission-service:8087",
                            "/api/v1/roles", "style-api-paged-v1")));

    private final RemoteAllowlist allowlist = new RemoteAllowlist(props);

    @Test
    @DisplayName("resolve happy path returns matching entry")
    void resolveHappyPath() {
        var entry = allowlist.resolve("user-service", "/api/v1/users");
        assertThat(entry).isPresent();
        assertThat(entry.get().baseUrl()).isEqualTo("http://user-service:8086");
        assertThat(entry.get().requestShape()).isEqualTo("style-api-paged-v1");
    }

    @Test
    @DisplayName("resolve returns empty for wrong service")
    void resolveWrongService() {
        assertThat(allowlist.resolve("audit-service", "/api/v1/users")).isEmpty();
    }

    @Test
    @DisplayName("resolve returns empty for wrong path (same service)")
    void resolveWrongPath() {
        assertThat(allowlist.resolve("user-service", "/api/v1/admins")).isEmpty();
    }

    @Test
    @DisplayName("resolve null / blank inputs return empty (no fallthrough)")
    void resolveNullBlank() {
        assertThat(allowlist.resolve(null, "/api/v1/users")).isEmpty();
        assertThat(allowlist.resolve("", "/api/v1/users")).isEmpty();
        assertThat(allowlist.resolve("user-service", null)).isEmpty();
        assertThat(allowlist.resolve("user-service", "  ")).isEmpty();
    }

    @Test
    @DisplayName("require throws RemoteAllowlistException for missing tuple")
    void requireThrows() {
        assertThatThrownBy(() -> allowlist.require("audit-service", "/api/v1/audit"))
                .isInstanceOf(RemoteAllowlistException.class)
                .extracting(e -> ((RemoteAllowlistException) e).service(),
                        e -> ((RemoteAllowlistException) e).path())
                .contains("audit-service", "/api/v1/audit");
    }

    @Test
    @DisplayName("require returns entry for matching tuple")
    void requireHappyPath() {
        var entry = allowlist.require("permission-service", "/api/v1/roles");
        assertThat(entry.service()).isEqualTo("permission-service");
        assertThat(entry.path()).isEqualTo("/api/v1/roles");
    }

    @Test
    @DisplayName("isEnabled checks feature gate + allowlist non-empty")
    void isEnabledTrue() {
        assertThat(allowlist.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("isEnabled false when allowlist empty")
    void isEnabledFalseEmptyAllowlist() {
        var emptyProps = new RemoteExecutorProperties(true, Duration.ofSeconds(5), List.of());
        var emptyAllowlist = new RemoteAllowlist(emptyProps);
        assertThat(emptyAllowlist.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("isEnabled false when feature gate off")
    void isEnabledFalseFeatureGateOff() {
        var disabledProps = new RemoteExecutorProperties(false, Duration.ofSeconds(5),
                List.of(new RemoteExecutorProperties.AllowlistEntry(
                        "user-service", "http://user-service:8086",
                        "/api/v1/users", "style-api-paged-v1")));
        var disabledAllowlist = new RemoteAllowlist(disabledProps);
        assertThat(disabledAllowlist.isEnabled()).isFalse();
    }

    // ---- Path guard validation (in AllowlistEntry constructor) ---------- //

    @Test
    @DisplayName("AllowlistEntry rejects path without leading slash")
    void pathNoLeadingSlash() {
        assertThatThrownBy(() -> new RemoteExecutorProperties.AllowlistEntry(
                "user-service", "http://x:1", "api/v1/users", "style-api-paged-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with '/'");
    }

    @Test
    @DisplayName("AllowlistEntry rejects path with parent traversal '..'")
    void pathParentTraversal() {
        assertThatThrownBy(() -> new RemoteExecutorProperties.AllowlistEntry(
                "user-service", "http://x:1", "/api/../etc/passwd", "style-api-paged-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("//, .., ?, or #");
    }

    @Test
    @DisplayName("AllowlistEntry rejects path with double slash")
    void pathDoubleSlash() {
        assertThatThrownBy(() -> new RemoteExecutorProperties.AllowlistEntry(
                "user-service", "http://x:1", "/api//v1/users", "style-api-paged-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("//, .., ?, or #");
    }

    @Test
    @DisplayName("AllowlistEntry rejects path with query string")
    void pathWithQuery() {
        assertThatThrownBy(() -> new RemoteExecutorProperties.AllowlistEntry(
                "user-service", "http://x:1", "/api/v1/users?leak=1", "style-api-paged-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("//, .., ?, or #");
    }

    @Test
    @DisplayName("AllowlistEntry rejects baseUrl without http/https scheme")
    void baseUrlNoScheme() {
        assertThatThrownBy(() -> new RemoteExecutorProperties.AllowlistEntry(
                "user-service", "user-service:8086", "/api/v1/users", "style-api-paged-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with http:// or https://");
    }
}
