package com.example.endpointadmin.model;

/**
 * AG-028 Phase 1 — terminal-result taxonomy for {@link EndpointUninstallAudit}
 * (Faz 22.5.6).
 *
 * <p>Closed enum mirrors plan v6 iter-6 AGREE on the destructive-side
 * lifecycle. Distinct from AG-027 install {@code result_status} because the
 * uninstall verification semantics are absence-aware (a successful install
 * proves presence; a successful uninstall proves absence).
 *
 * <ul>
 *   <li>{@link #SUCCEEDED_VERIFIED} — winget uninstall exit=0 AND post-verify
 *       detection probe says authoritative ABSENT (file/registry gone).</li>
 *   <li>{@link #SKIP_ALREADY_ABSENT} — pre-check detection already says ABSENT;
 *       winget never invoked.</li>
 *   <li>{@link #FAILED_VERIFY_GHOST} — winget exit=0 but post-verify says
 *       PRESENT (package still detected after the uninstall). Strict failure.</li>
 *   <li>{@link #FAILED_EXIT} — winget non-zero exit AND post-verify could not
 *       confirm ABSENT.</li>
 *   <li>{@link #PARTIAL_RESIDUE} — post-verify file present but hash/version
 *       mismatch (residue scenarios — FILE_SHA256 / FILE_VERSION authoritative).</li>
 *   <li>{@link #PARTIAL_INCONCLUSIVE} — post-verify probe error / ambiguous /
 *       unsupported. Distinct from GHOST because we cannot assert presence;
 *       we only failed to assert absence.</li>
 *   <li>{@link #FAILED_PRECHECK_INCONCLUSIVE} — pre-check probe error;
 *       fail-closed (no mutation).</li>
 *   <li>{@link #FAILED_UNSUPPORTED_PLATFORM} — non-Windows / no winget; stub.</li>
 *   <li>{@link #FAILED_UNSUPPORTED_VERIFICATION} — detection rule type not
 *       supported for uninstall (WINGET_PACKAGE rejected at adapter top —
 *       Codex iter-1 P0 #8 absorb).</li>
 * </ul>
 *
 * <p>V32 DB CHECK {@code ck_endpoint_uninstall_audit_result_status} enforces
 * this exact closed set.
 */
public enum UninstallResultStatus {
    SUCCEEDED_VERIFIED,
    SKIP_ALREADY_ABSENT,
    FAILED_VERIFY_GHOST,
    FAILED_EXIT,
    PARTIAL_RESIDUE,
    PARTIAL_INCONCLUSIVE,
    FAILED_PRECHECK_INCONCLUSIVE,
    FAILED_UNSUPPORTED_PLATFORM,
    FAILED_UNSUPPORTED_VERIFICATION
}
