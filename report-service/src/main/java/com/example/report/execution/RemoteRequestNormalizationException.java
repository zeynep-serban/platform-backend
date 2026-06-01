package com.example.report.execution;

/**
 * Thrown when {@link RemoteRequestNormalizer} cannot map an incoming
 * {@link RemoteReportRequest} to a downstream-shaped query payload
 * (PR-D2.1c5, Codex 019e83fd iter-1 amend).
 *
 * <p>Distinct from {@link RemoteExecutionException} (HTTP 502 downstream
 * fault) — this signals a CALLER-side validation failure that the
 * controller layer should map to {@code 400 REMOTE_FILTER_UNSUPPORTED}
 * or {@code 400 REMOTE_REQUEST_INVALID}. Without this typed split, the
 * executor's generic catch wraps the IAE into a 502 and the user sees
 * "downstream timeout" for what is actually a client-side input error.
 */
public class RemoteRequestNormalizationException extends RuntimeException {

    private final String code;

    public RemoteRequestNormalizationException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Structured error code for the controller-layer response envelope. */
    public String code() {
        return code;
    }
}
