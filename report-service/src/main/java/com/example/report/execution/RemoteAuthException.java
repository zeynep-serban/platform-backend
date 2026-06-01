package com.example.report.execution;

/**
 * Thrown when the downstream service returns 401 Unauthorized for a
 * remote-http report execution (PR-D2.1c1).
 *
 * <p>Typical cause: incoming user JWT expired or not propagated correctly.
 * The controller layer should re-emit a 401 to the original caller so the
 * frontend can refresh / re-authenticate.
 *
 * <p>Codex 019e8306 iter-2: do NOT swap user JWT for a service-to-service
 * token to "fix" this — that would bypass user-level authz/audit at the
 * downstream service. If audience mismatch is the real cause, the proper
 * solution is OBO (on-behalf-of) / token-exchange — separate PR.
 */
public class RemoteAuthException extends RuntimeException {

    private final String service;
    private final String path;

    public RemoteAuthException(String service, String path) {
        super("remote-http downstream 401 Unauthorized: service=" + service + " path=" + path);
        this.service = service;
        this.path = path;
    }

    public RemoteAuthException(String service, String path, Throwable cause) {
        super("remote-http downstream 401 Unauthorized: service=" + service + " path=" + path, cause);
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
