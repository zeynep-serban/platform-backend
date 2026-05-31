package com.example.endpointadmin.model;

/**
 * BE-021 — terminal install post-verification outcome (Faz 22.5).
 *
 * <p>Records whether the backend-redacted agent
 * {@code details.install.postVerification} payload satisfied the catalog
 * item's detection rule after the install attempt terminated (BE-028:
 * the AG-027 InstallResult is shipped under the {@code details.install}
 * wrapper per COMMAND-CONTRACT §11.2). Independent from
 * {@link CommandResultStatus}: an
 * install can {@code SUCCEEDED} at the executor level but report
 * {@code UNSATISFIED} detection if the detection rule did not match
 * post-install (e.g. installer claimed success but app fingerprint
 * still missing).
 */
public enum InstallPostVerification {
    SATISFIED,
    UNSATISFIED,
    UNKNOWN
}
