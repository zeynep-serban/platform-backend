package com.example.report.execution;

/**
 * Thrown when a {@code (service, path)} tuple is not in the
 * {@link RemoteExecutorProperties#allowlist()} (PR-D2.1c1).
 *
 * <p>Security boundary: this must produce a structured 4xx response
 * (NOT 5xx — the request is rejected by policy, not a server fault).
 * Recommended HTTP mapping at the controller layer: 403 Forbidden
 * (or 400 Bad Request depending on the security posture).
 */
public class RemoteAllowlistException extends RuntimeException {

    private final String service;
    private final String path;

    public RemoteAllowlistException(String service, String path) {
        super("remote-http allowlist reject: service=" + service + " path=" + path);
        this.service = service;
        this.path = path;
    }

    public String service() {
        return service;
    }

    public String path() {
        return path;
    }
}
