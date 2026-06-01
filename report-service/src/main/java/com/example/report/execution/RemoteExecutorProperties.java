package com.example.report.execution;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Report execution adapter configuration (ADR-0015, PR-D2.1c1).
 *
 * <p>Bound from {@code application.yml} prefix {@code report.remote-executor}:
 *
 * <pre>{@code
 * report:
 *   remote-executor:
 *     enabled: true
 *     timeout: 5s
 *     allowlist:
 *       - service: user-service
 *         base-url: http://user-service:8086
 *         path: /api/v1/users
 *         request-shape: style-api-paged-v1
 * }</pre>
 *
 * <h2>Security boundary</h2>
 *
 * <p>Codex 019e8306 iter-2: {@code base-url} field STRICTLY env config —
 * NEVER in {@link com.example.report.registry.ReportDefinition} JSON.
 * Host resolution authority stays in deployment config so that:
 * <ul>
 *   <li>k3d-test cluster routes to {@code http://user-service:8086}</li>
 *   <li>prod cluster routes to {@code http://user-service.platform-prod.svc.cluster.local:8086}</li>
 *   <li>local dev routes to {@code http://localhost:8086}</li>
 * </ul>
 *
 * <p>ReportDefinition.execution.service is purely a <em>logical key</em>
 * matched against this allowlist entry; not a hostname.
 *
 * <h2>Timeout default</h2>
 *
 * <p>Codex 019e8306 iter-2: {@code 5s} default (NOT 30s — connection/thread
 * exhaustion + slow-fail masking risk). Operator override via
 * {@code timeout: 10s} with hard {@code 30s} upper cap (validated below).
 *
 * @param enabled    Feature gate (default true)
 * @param timeout    Per-request total/response timeout (default 5s, max 30s)
 * @param allowlist  Allowed (service, path, base-url, request-shape) tuples
 */
@ConfigurationProperties(prefix = "report.remote-executor")
public record RemoteExecutorProperties(
        boolean enabled,
        Duration timeout,
        List<AllowlistEntry> allowlist
) {
    public RemoteExecutorProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(5);
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException(
                    "report.remote-executor.timeout must be positive: " + timeout);
        }
        if (timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException(
                    "report.remote-executor.timeout must not exceed 30s: " + timeout);
        }
        if (allowlist == null) {
            allowlist = List.of();
        }
        allowlist = List.copyOf(allowlist);
    }

    /**
     * One entry in the allowlist. {@link RemoteAllowlist} validates incoming
     * (service, path) tuples against this list.
     *
     * <p>Codex 019e8306 iter-3 Medium absorb: {@code baseUrl} MUST be
     * host+port only (no path, query, fragment, or userinfo). The
     * {@link RemoteReportExecutor} uses {@code fromHttpUrl(baseUrl).path(path)};
     * if baseUrl carries extra components the allowlist guarantee is leaky.
     *
     * @param service        Logical service key (matches
     *                       {@link com.example.report.registry.ExecutionConfig#service()})
     * @param baseUrl        HTTP base URL (host + port ONLY, no path)
     * @param path           Exact allowed path (matches
     *                       {@link com.example.report.registry.ExecutionConfig#path()})
     * @param requestShape   Request parameter mapping kind (e.g. {@code style-api-paged-v1})
     */
    public record AllowlistEntry(
            String service,
            String baseUrl,
            String path,
            String requestShape
    ) {
        public AllowlistEntry {
            if (service == null || service.isBlank()) {
                throw new IllegalArgumentException("AllowlistEntry.service must not be blank");
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException(
                        "AllowlistEntry.baseUrl must not be blank for service=" + service);
            }
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                throw new IllegalArgumentException(
                        "AllowlistEntry.baseUrl must start with http:// or https:// for service="
                                + service + ": " + baseUrl);
            }
            // Codex 019e8306 iter-3 Medium absorb: baseUrl host+port only.
            // URI parse must yield empty rawPath (or '/' only), no rawQuery,
            // rawFragment, or rawUserInfo. This prevents allowlist bypass via
            // baseUrl that smuggles an extra path segment.
            try {
                java.net.URI uri = new java.net.URI(baseUrl);
                if (uri.getRawUserInfo() != null) {
                    throw new IllegalArgumentException(
                            "AllowlistEntry.baseUrl must not contain userinfo for service="
                                    + service + ": " + baseUrl);
                }
                String rawPath = uri.getRawPath();
                if (rawPath != null && !rawPath.isEmpty() && !rawPath.equals("/")) {
                    throw new IllegalArgumentException(
                            "AllowlistEntry.baseUrl must be host+port only (no path) for service="
                                    + service + ": " + baseUrl);
                }
                if (uri.getRawQuery() != null) {
                    throw new IllegalArgumentException(
                            "AllowlistEntry.baseUrl must not contain query for service="
                                    + service + ": " + baseUrl);
                }
                if (uri.getRawFragment() != null) {
                    throw new IllegalArgumentException(
                            "AllowlistEntry.baseUrl must not contain fragment for service="
                                    + service + ": " + baseUrl);
                }
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new IllegalArgumentException(
                            "AllowlistEntry.baseUrl must have a non-blank host for service="
                                    + service + ": " + baseUrl);
                }
            } catch (java.net.URISyntaxException ex) {
                throw new IllegalArgumentException(
                        "AllowlistEntry.baseUrl is not a valid URI for service="
                                + service + ": " + baseUrl, ex);
            }
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException(
                        "AllowlistEntry.path must not be blank for service=" + service);
            }
            // Codex 019e8306 iter-2: path guard — startsWith("/"), no absolute URL,
            // no // double-slash, no .. parent-traversal, no query/hash.
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException(
                        "AllowlistEntry.path must start with '/': " + path);
            }
            if (path.contains("//") || path.contains("..") || path.contains("?")
                    || path.contains("#")) {
                throw new IllegalArgumentException(
                        "AllowlistEntry.path must not contain //, .., ?, or #: " + path);
            }
            if (requestShape == null || requestShape.isBlank()) {
                throw new IllegalArgumentException(
                        "AllowlistEntry.requestShape must not be blank for service="
                                + service);
            }
        }
    }
}
