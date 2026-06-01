package com.example.report.execution;

/**
 * Thrown when the downstream service returns 5xx, times out, or produces
 * a malformed response that cannot be normalized (PR-D2.1c1).
 *
 * <p>Typical causes:
 * <ul>
 *   <li>Downstream service crashed (5xx)</li>
 *   <li>Network partition / cluster routing issue</li>
 *   <li>Timeout exceeded (Codex 019e8306 iter-2: 5s default)</li>
 *   <li>Response body shape didn't match the declared {@code responseShape}</li>
 * </ul>
 *
 * <p>The controller layer should re-emit a 502 Bad Gateway (or 503/504
 * depending on the precise downstream signal) — this is a server-side
 * dependency failure, NOT a user error. Telemetry/log includes
 * {@code reportKey/service/path/status/elapsedMs}; raw response body
 * and any propagated tokens are NEVER logged.
 */
public class RemoteExecutionException extends RuntimeException {

    private final String service;
    private final String path;
    private final Integer downstreamStatus;

    public RemoteExecutionException(String service, String path, Integer downstreamStatus, String message) {
        super("remote-http execution failure: service=" + service + " path=" + path
                + " downstreamStatus=" + downstreamStatus + " — " + message);
        this.service = service;
        this.path = path;
        this.downstreamStatus = downstreamStatus;
    }

    public RemoteExecutionException(String service, String path, Integer downstreamStatus,
            String message, Throwable cause) {
        super("remote-http execution failure: service=" + service + " path=" + path
                + " downstreamStatus=" + downstreamStatus + " — " + message, cause);
        this.service = service;
        this.path = path;
        this.downstreamStatus = downstreamStatus;
    }

    public String service() {
        return service;
    }

    public String path() {
        return path;
    }

    /**
     * Downstream HTTP status code when known; {@code null} for timeout /
     * connection-level failures.
     */
    public Integer downstreamStatus() {
        return downstreamStatus;
    }
}
