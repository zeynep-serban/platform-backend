package com.example.endpointadmin.security;

/**
 * Faz 22.3 — Thrown by {@link MachineCertExtractor} when a presented mTLS
 * client cert fails structural validation (validity window, EKU, SAN URI
 * format, encoding). The caller maps {@link #getErrorCode()} to an HTTP
 * status (typically 401 for "cert not valid" and 400 for malformed input).
 */
public class MachineCertExtractionException extends RuntimeException {

    private final String errorCode;

    public MachineCertExtractionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public MachineCertExtractionException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
