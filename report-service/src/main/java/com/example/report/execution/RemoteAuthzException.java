package com.example.report.execution;

/**
 * Thrown when the downstream service returns 403 Forbidden for a
 * remote-http report execution (PR-D2.1c1).
 *
 * <p>Typical cause: the propagated user JWT is valid but lacks the
 * downstream authz scope (e.g. {@code reports.hr.salary-view} for
 * sensitive columns). The controller layer should re-emit a 403 to
 * the original caller; the frontend can hide the column / disable
 * the action.
 *
 * <p>Distinct from {@link RemoteAuthException} which signals an
 * authentication failure (401, refresh needed). 403 means
 * authenticated-but-unauthorized — the user IS who they say, but
 * not permitted.
 */
public class RemoteAuthzException extends RuntimeException {

    private final String service;
    private final String path;

    public RemoteAuthzException(String service, String path) {
        super("remote-http downstream 403 Forbidden: service=" + service + " path=" + path);
        this.service = service;
        this.path = path;
    }

    public RemoteAuthzException(String service, String path, Throwable cause) {
        super("remote-http downstream 403 Forbidden: service=" + service + " path=" + path, cause);
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
