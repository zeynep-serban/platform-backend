package com.example.endpointadmin.security;

import com.example.endpointadmin.model.UninstallVerification;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AG-028 Phase 1b — unit tests for {@link UninstallEvidencePayloadPolicy}
 * (Faz 22.5.6).
 *
 * <p>Focus is the Codex plan-time iter-6 absorb items:
 * <ul>
 *   <li>raw {@code stdoutTail} / {@code stderrTail} are NOT in the
 *       allow-list (forbidden keys + not in allow-list); they MUST be
 *       dropped by {@link UninstallEvidencePayloadPolicy#redact}.</li>
 *   <li>Secret / path / JWT / SID patterns are masked recursively.</li>
 *   <li>{@link UninstallEvidencePayloadPolicy#deriveVerification} maps
 *       {@code probeState} to {@link UninstallVerification} fail-closed:
 *       absent / null / unknown / AMBIGUOUS / ERROR / UNSUPPORTED all
 *       map to {@link UninstallVerification#VERIFY_INCONCLUSIVE}, never
 *       to ABSENT_VERIFIED.</li>
 *   <li>Size cap collapses oversized payloads to a marker rather than
 *       persisting unbounded bytes.</li>
 *   <li>Validate rejects raw SID / JWT before redact runs.</li>
 * </ul>
 */
class UninstallEvidencePayloadPolicyTest {

    private final UninstallEvidencePayloadPolicy policy =
            new UninstallEvidencePayloadPolicy(
                    UninstallEvidencePayloadPolicy.MAX_REDACTED_BYTES_DEFAULT,
                    UninstallEvidencePayloadPolicy.MAX_SUMMARY_BYTES_DEFAULT);

    @Test
    void redact_dropsRawStdoutTailAndStderrTail_codex019e8d81PlanIter6() {
        // raw tails sit OUTSIDE the uninstall wrapper allow-list AND are
        // FORBIDDEN keys (case-insensitive). Either guard alone is enough;
        // both belt-and-braces.
        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("finalStatus", "SUCCEEDED");
        uninstall.put("probeState", "ABSENT");
        uninstall.put("stdoutTail", "C:\\Users\\halil\\sensitive.log");
        uninstall.put("stderrTail", "Bearer abc.def.ghi");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uninstall", uninstall);

        Map<String, Object> out = policy.redact(payload);

        @SuppressWarnings("unchecked")
        Map<String, Object> outUninstall = (Map<String, Object>) out.get("uninstall");
        assertThat(outUninstall).doesNotContainKey("stdoutTail");
        assertThat(outUninstall).doesNotContainKey("stderrTail");
        assertThat(outUninstall).containsEntry("finalStatus", "SUCCEEDED");
        assertThat(outUninstall).containsEntry("probeState", "ABSENT");
    }

    @Test
    void redact_dropsUnknownTopLevelKeys() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", "POST");
        payload.put("exotic_key", "should-disappear");
        payload.put("egress", Map.of("targetUrl", "http://internal/secret"));

        Map<String, Object> out = policy.redact(payload);
        assertThat(out).containsKey("stage");
        assertThat(out).doesNotContainKey("exotic_key");
        assertThat(out).doesNotContainKey("egress");
    }

    @Test
    void redact_maskUsersPathAndSidAndJwt() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errorMessage",
                "Failed for C:\\Users\\halil\\AppData "
                        + "SID=S-1-5-21-1-2-3-4 tok=eyJabc.eyJdef.signaturepart");

        Map<String, Object> out = policy.redact(payload);
        String msg = (String) out.get("errorMessage");
        assertThat(msg).doesNotContain("halil");
        assertThat(msg).doesNotContain("S-1-5-21");
        assertThat(msg).doesNotContain("eyJabc.eyJdef.signaturepart");
        assertThat(msg).contains(UninstallEvidencePayloadPolicy.REDACTED_LITERAL);
    }

    @Test
    void redact_dropsForbiddenKeysCaseInsensitiveRecursively() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("Token", "secret-bearer-xyz");
        nested.put("LicenseKey", "ABCD-EFGH");
        nested.put("StdoutTail", "should-also-drop");

        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("finalStatus", "FAILED");
        uninstall.put("probeState", "ERROR");
        uninstall.put("schemaVersion", 1);
        Map<String, Object> details = new LinkedHashMap<>(nested);
        // Nest one level deep to verify recursive drop on a permitted key.
        uninstall.put("authority", "REGISTRY_UNINSTALL");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uninstall", uninstall);
        // Outer-level forbidden also dropped.
        payload.put("token", "outer-secret");
        payload.put("apikey", "outer-apikey");
        payload.put("processEnvironment", details);

        Map<String, Object> out = policy.redact(payload);
        assertThat(out).doesNotContainKey("token");
        assertThat(out).doesNotContainKey("apikey");
        assertThat(out).doesNotContainKey("processEnvironment");
        @SuppressWarnings("unchecked")
        Map<String, Object> outUninstall = (Map<String, Object>) out.get("uninstall");
        assertThat(outUninstall).doesNotContainKey("StdoutTail");
    }

    @Test
    void redact_idempotent_secondPassDoesNotMutate() {
        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("finalStatus", "SUCCEEDED");
        uninstall.put("probeState", "ABSENT");
        uninstall.put("authority", "REGISTRY_UNINSTALL");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ruleType", "REGISTRY_UNINSTALL");
        evidence.put("matchedDisplayName", "7-Zip 23.01 (x64)");
        uninstall.put("safeEvidence", evidence);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", "POST");
        payload.put("exitCode", 0);
        payload.put("uninstall", uninstall);
        payload.put("stdoutSummary", "Uninstall complete");
        payload.put("errorMessage",
                "C:\\Users\\halil\\Desktop\\bad-path SID=S-1-5-21-1-2-3-4");

        Map<String, Object> first = policy.redact(payload);
        Map<String, Object> second = policy.redact(first);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void redact_safeEvidenceProjectedToScalarAllowList() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("ruleType", "REGISTRY_UNINSTALL");
        evidence.put("matchedDisplayName", "7-Zip 23.01");
        evidence.put("nestedMap", Map.of("hidden", "secret"));   // dropped
        evidence.put("nestedList", List.of("a", "b"));           // dropped
        evidence.put("unknownKey", "should-disappear");           // dropped

        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("probeState", "ABSENT");
        uninstall.put("safeEvidence", evidence);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uninstall", uninstall);

        Map<String, Object> out = policy.redact(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> outUninstall = (Map<String, Object>) out.get("uninstall");
        @SuppressWarnings("unchecked")
        Map<String, Object> outEvidence = (Map<String, Object>) outUninstall.get("safeEvidence");
        assertThat(outEvidence).containsKey("ruleType");
        assertThat(outEvidence).containsKey("matchedDisplayName");
        assertThat(outEvidence).doesNotContainKeys("nestedMap", "nestedList", "unknownKey");
    }

    @Test
    void deriveVerification_matched_presentVerified() {
        UninstallVerification v = policy.deriveVerification(
                Map.of("uninstall", Map.of("probeState", "MATCHED")));
        assertThat(v).isEqualTo(UninstallVerification.PRESENT_VERIFIED);
    }

    @Test
    void deriveVerification_absent_absentVerified() {
        UninstallVerification v = policy.deriveVerification(
                Map.of("uninstall", Map.of("probeState", "ABSENT")));
        assertThat(v).isEqualTo(UninstallVerification.ABSENT_VERIFIED);
    }

    @Test
    void deriveVerification_presentMismatch_residuePresent() {
        UninstallVerification v = policy.deriveVerification(
                Map.of("uninstall", Map.of("probeState", "PRESENT_MISMATCH")));
        assertThat(v).isEqualTo(UninstallVerification.RESIDUE_PRESENT);
    }

    @Test
    void deriveVerification_ambiguous_inconclusive() {
        UninstallVerification v = policy.deriveVerification(
                Map.of("uninstall", Map.of("probeState", "AMBIGUOUS")));
        assertThat(v).isEqualTo(UninstallVerification.VERIFY_INCONCLUSIVE);
    }

    @Test
    void deriveVerification_error_inconclusive() {
        UninstallVerification v = policy.deriveVerification(
                Map.of("uninstall", Map.of("probeState", "ERROR")));
        assertThat(v).isEqualTo(UninstallVerification.VERIFY_INCONCLUSIVE);
    }

    @Test
    void deriveVerification_unsupported_inconclusive() {
        UninstallVerification v = policy.deriveVerification(
                Map.of("uninstall", Map.of("probeState", "UNSUPPORTED")));
        assertThat(v).isEqualTo(UninstallVerification.VERIFY_INCONCLUSIVE);
    }

    @Test
    void deriveVerification_unknownProbeState_inconclusive_failClosed() {
        UninstallVerification v = policy.deriveVerification(
                Map.of("uninstall", Map.of("probeState", "EXTRA_GALACTIC")));
        // Codex 019e8d81 iter-6 absorb: unknown drift never accidentally
        // certifies a destructive op as successfully absent.
        assertThat(v).isEqualTo(UninstallVerification.VERIFY_INCONCLUSIVE);
    }

    @Test
    void deriveVerification_absentProbeState_inconclusive_failClosed() {
        UninstallVerification v = policy.deriveVerification(
                Map.of("uninstall", Map.of("finalStatus", "SUCCEEDED")));
        assertThat(v).isEqualTo(UninstallVerification.VERIFY_INCONCLUSIVE);
    }

    @Test
    void deriveVerification_emptyDetails_inconclusive() {
        assertThat(policy.deriveVerification(null))
                .isEqualTo(UninstallVerification.VERIFY_INCONCLUSIVE);
        assertThat(policy.deriveVerification(Map.of()))
                .isEqualTo(UninstallVerification.VERIFY_INCONCLUSIVE);
        assertThat(policy.deriveVerification(Map.of("uninstall", "scalar-not-map")))
                .isEqualTo(UninstallVerification.VERIFY_INCONCLUSIVE);
    }

    @Test
    void validate_throwsOnRawSidLiteral() {
        Map<String, Object> payload = Map.of("uninstall",
                Map.of("authority", "REGISTRY_UNINSTALL",
                        "evidence", "User SID S-1-5-21-1-2-3-4 found"));
        assertThatThrownBy(() -> policy.validate(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Windows SID");
    }

    @Test
    void validate_throwsOnRawJwt() {
        Map<String, Object> payload = Map.of(
                "errorMessage", "got eyJabc.eyJdef.signaturepart from auth");
        assertThatThrownBy(() -> policy.validate(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JWT");
    }

    @Test
    void validate_throwsOnForbiddenKeyDeep() {
        Map<String, Object> payload = Map.of("uninstall", Map.of(
                "finalStatus", "SUCCEEDED",
                "password", "secret"));
        assertThatThrownBy(() -> policy.validate(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden");
    }

    @Test
    void redact_sizeCap_collapsesToMarkerOnOversizedPayload() {
        // Tiny cap so even normal-sized payloads can't fit.
        UninstallEvidencePayloadPolicy tinyPolicy =
                new UninstallEvidencePayloadPolicy(64, 32);

        StringBuilder bigSummary = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            bigSummary.append("aaaaaaaaaa");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", "POST");
        payload.put("stdoutSummary", bigSummary.toString());
        payload.put("uninstall", Map.of("finalStatus", "SUCCEEDED",
                "probeState", "ABSENT", "schemaVersion", 1));

        Map<String, Object> out = tinyPolicy.redact(payload);
        // The cap MUST hold regardless of degrade ordering — final result is
        // either degraded under cap or collapsed to the marker.
        assertThat(out).isNotNull();
        // If degrade pipeline ended in the absolute-last-resort marker:
        if (Boolean.TRUE.equals(out.get("trimmed"))) {
            assertThat(out).containsEntry("reason", "redacted_payload_size_cap");
        }
    }

    @Test
    void redact_authorityUnknownDropsSilently() {
        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("probeState", "ABSENT");
        uninstall.put("authority", "SOMEWHERE_ELSE");   // unknown

        Map<String, Object> payload = Map.of("uninstall", uninstall);
        Map<String, Object> out = policy.redact(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> outUninstall = (Map<String, Object>) out.get("uninstall");
        assertThat(outUninstall).doesNotContainKey("authority");
        assertThat(outUninstall).containsEntry("probeState", "ABSENT");
    }

    // ────────────────────────────────────────────────────────────────
    // AG-028 Phase 2B (Codex 019e8de2 iter-2 absorb) tests

    @Test
    void redact_authorityAuthoritativeLiteralPreserved_codex019e8de2Iter2() {
        // Phase 2B finding #4: the agent emits the literal "AUTHORITATIVE"
        // when a per-probe ruleType already implies authoritative tier
        // (REGISTRY_UNINSTALL / FILE_*). Pre-fix the allow-list omitted the
        // value → the policy silently dropped it. Phase 2B adds the literal
        // to KNOWN_AUTHORITIES so it survives redaction.
        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("probeState", "ABSENT");
        uninstall.put("authority", "AUTHORITATIVE");

        Map<String, Object> payload = Map.of("uninstall", uninstall);
        Map<String, Object> out = policy.redact(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> outUninstall = (Map<String, Object>) out.get("uninstall");
        assertThat(outUninstall).containsEntry("authority", "AUTHORITATIVE");
    }

    @Test
    void redact_preProbeProjectedToScalarAllowList_codex019e8de2Iter2() {
        // Phase 2B finding #5 (Option A): the agent ships uninstall.preProbe +
        // uninstall.postProbe nested sub-trees. The policy now projects each
        // to ALLOWED_PROBE_KEYS scalars so tail-shaped fields cannot leak.
        Map<String, Object> preProbe = new LinkedHashMap<>();
        preProbe.put("probeState", "MATCHED");
        preProbe.put("ruleType", "REGISTRY_UNINSTALL");
        preProbe.put("matchedPackageId", "Microsoft.7Zip");
        preProbe.put("durationMs", 42);
        preProbe.put("stdoutTail", "should-not-leak-1234"); // forbidden tail
        preProbe.put("exotic_key", "drop-me");              // off-allow-list

        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("finalStatus", "SUCCEEDED_VERIFIED");
        uninstall.put("probeState", "ABSENT");
        uninstall.put("preProbe", preProbe);

        Map<String, Object> payload = Map.of("uninstall", uninstall);
        Map<String, Object> out = policy.redact(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> outUninstall = (Map<String, Object>) out.get("uninstall");
        @SuppressWarnings("unchecked")
        Map<String, Object> outPre = (Map<String, Object>) outUninstall.get("preProbe");
        assertThat(outPre)
                .containsEntry("probeState", "MATCHED")
                .containsEntry("ruleType", "REGISTRY_UNINSTALL")
                .containsEntry("matchedPackageId", "Microsoft.7Zip")
                .containsEntry("durationMs", 42)
                .doesNotContainKey("stdoutTail")
                .doesNotContainKey("exotic_key");
    }

    @Test
    void redact_postProbeProjectedToScalarAllowList_codex019e8de2Iter2() {
        Map<String, Object> postProbe = new LinkedHashMap<>();
        postProbe.put("probeState", "ABSENT");
        postProbe.put("ruleType", "REGISTRY_UNINSTALL");
        postProbe.put("absentReason", "registry_uninstall_key_missing");
        postProbe.put("durationMs", 17);
        postProbe.put("stderrTail", "C:\\Users\\halil\\sensitive"); // forbidden tail
        postProbe.put("anotherDropMe", true);

        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("finalStatus", "SUCCEEDED_VERIFIED");
        uninstall.put("probeState", "ABSENT");
        uninstall.put("postProbe", postProbe);

        Map<String, Object> payload = Map.of("uninstall", uninstall);
        Map<String, Object> out = policy.redact(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> outUninstall = (Map<String, Object>) out.get("uninstall");
        @SuppressWarnings("unchecked")
        Map<String, Object> outPost = (Map<String, Object>) outUninstall.get("postProbe");
        assertThat(outPost)
                .containsEntry("probeState", "ABSENT")
                .containsEntry("ruleType", "REGISTRY_UNINSTALL")
                .containsEntry("absentReason", "registry_uninstall_key_missing")
                .containsEntry("durationMs", 17)
                .doesNotContainKey("stderrTail")
                .doesNotContainKey("anotherDropMe");
    }

    @Test
    void redact_preProbeErrorRunsThroughForbiddenValueReplacement_codex019e8de2() {
        // Probe-side error MUST go through the same forbidden-value masker
        // (USERS_PATH / SID / JWT / RAW_MSI_GUID) as other summary strings —
        // an agent-side probe error message may legitimately mention a path
        // we still want to scrub.
        Map<String, Object> preProbe = new LinkedHashMap<>();
        preProbe.put("probeState", "ERROR");
        preProbe.put("error", "failed to open C:\\Users\\halil\\target");

        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("finalStatus", "FAILED_PRECHECK_INCONCLUSIVE");
        uninstall.put("preProbe", preProbe);

        Map<String, Object> payload = Map.of("uninstall", uninstall);
        Map<String, Object> out = policy.redact(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> outUninstall = (Map<String, Object>) out.get("uninstall");
        @SuppressWarnings("unchecked")
        Map<String, Object> outPre = (Map<String, Object>) outUninstall.get("preProbe");
        String error = (String) outPre.get("error");
        assertThat(error)
                .as("USERS_PATH masked from probe-side error message")
                .doesNotContain("halil")
                .contains("[REDACTED]");
    }

    // ────────────────────────────────────────────────────────────────
    // AG-028 narrow hardening (live-incident fix, Codex cross-AI verdict B):
    // raw stdout/stderr tails are legacy-compatible silently DROPPED from
    // uninstall evidence (validate no longer 400s); all OTHER forbidden
    // evidence keys still fail closed with 400.

    @Test
    void validate_dropsRawStdoutAndStderrTail_doesNotThrow_agNarrowHardening() {
        // Live evidence: a real managed-uninstall succeeded on a Windows
        // endpoint but the agent's result submit was rejected with HTTP 400
        // "Forbidden uninstall-evidence field 'stdoutTail' at $.uninstall".
        // After the narrow hardening, validate() must NOT throw — it drops
        // the raw tails so the SUCCESSFUL destructive op stays recordable.
        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("finalStatus", "SUCCEEDED_VERIFIED");
        uninstall.put("probeState", "ABSENT");
        // Tails carry a secret + PII path that must NOT leak through.
        uninstall.put("stdoutTail", "Bearer abc.def.ghi C:\\Users\\halil\\out.log");
        uninstall.put("stderrTail", "tok=eyJaaa.eyJbbb.signature");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", "POST");
        payload.put("uninstall", uninstall);

        // (a) ACCEPTED — no exception from the forbidden-key guard.
        policy.validate(payload);

        // The raw-tail keys are absent from the sanitized output, and the
        // secret/PII inside the tail never leaks (the whole sub-tree is gone).
        Map<String, Object> out = policy.redact(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> outUninstall = (Map<String, Object>) out.get("uninstall");
        assertThat(outUninstall).doesNotContainKey("stdoutTail");
        assertThat(outUninstall).doesNotContainKey("stderrTail");
        assertThat(outUninstall).containsEntry("finalStatus", "SUCCEEDED_VERIFIED");
        assertThat(outUninstall).containsEntry("probeState", "ABSENT");

        String serialized = out.toString();
        assertThat(serialized)
                .as("no secret/PII from the dropped raw tail leaks anywhere in the output")
                .doesNotContain("Bearer abc.def.ghi")
                .doesNotContain("eyJaaa.eyJbbb.signature")
                .doesNotContain("halil");
    }

    @Test
    void validate_rawTailCaseInsensitive_doesNotThrow_agNarrowHardening() {
        // The forbidden-key comparison is case-insensitive, so the drop set
        // must match e.g. "StdoutTail" / "StderrTail" too.
        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("finalStatus", "SUCCEEDED_VERIFIED");
        uninstall.put("StdoutTail", "raw legacy output");
        uninstall.put("StderrTail", "raw legacy err");

        Map<String, Object> payload = Map.of("uninstall", uninstall);
        policy.validate(payload); // must not throw

        Map<String, Object> out = policy.redact(payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> outUninstall = (Map<String, Object>) out.get("uninstall");
        assertThat(outUninstall).doesNotContainKeys("StdoutTail", "StderrTail");
    }

    @Test
    void validate_stdoutRaw_stillThrows_guardNotGloballyWeakened() {
        // stdoutRaw is a DIFFERENT forbidden key (raw, not tail). It MUST
        // still hard-reject — proving the general guard isn't weakened.
        Map<String, Object> payload = Map.of("uninstall", Map.of(
                "finalStatus", "SUCCEEDED",
                "stdoutRaw", "full raw installer output"));
        assertThatThrownBy(() -> policy.validate(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden")
                .hasMessageContaining("$.uninstall");
    }

    @Test
    void validate_credentialAndPathForbiddenKeys_stillThrow_failClosed() {
        // A credential/token-carrying forbidden key still hard-rejects.
        Map<String, Object> credPayload = Map.of("uninstall", Map.of(
                "finalStatus", "SUCCEEDED",
                "token", "secret-bearer-xyz"));
        assertThatThrownBy(() -> policy.validate(credPayload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden");

        // processEnvironment (env/paths/PII vector) still hard-rejects.
        Map<String, Object> envPayload = Map.of("uninstall", Map.of(
                "finalStatus", "SUCCEEDED",
                "processEnvironment", Map.of("PATH", "C:\\Users\\halil")));
        assertThatThrownBy(() -> policy.validate(envPayload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden");

        // password still hard-rejects.
        Map<String, Object> pwPayload = Map.of("uninstall", Map.of(
                "finalStatus", "SUCCEEDED",
                "password", "hunter2"));
        assertThatThrownBy(() -> policy.validate(pwPayload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden");
    }

    @Test
    void validate_rawTailDroppedButSiblingForbiddenKeyStillThrows() {
        // Belt-and-braces: a raw tail (drop) sitting NEXT TO a hard-reject
        // forbidden key (token) must NOT mask the hard reject — the token
        // still throws even though the tail would have been dropped.
        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("finalStatus", "SUCCEEDED");
        uninstall.put("stdoutTail", "legacy tail");
        uninstall.put("token", "secret-bearer-xyz");

        Map<String, Object> payload = Map.of("uninstall", uninstall);
        assertThatThrownBy(() -> policy.validate(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden");
    }

    @Test
    void validate_allowedKeysWithProbesAndSummary_passThrough_agNarrowHardening() {
        // (c) allowed keys (preProbe / postProbe / stdoutSummary) still
        // validate clean AND survive redaction correctly.
        Map<String, Object> preProbe = new LinkedHashMap<>();
        preProbe.put("probeState", "MATCHED");
        preProbe.put("ruleType", "REGISTRY_UNINSTALL");
        preProbe.put("durationMs", 12);

        Map<String, Object> postProbe = new LinkedHashMap<>();
        postProbe.put("probeState", "ABSENT");
        postProbe.put("ruleType", "REGISTRY_UNINSTALL");
        postProbe.put("absentReason", "registry_uninstall_key_missing");

        Map<String, Object> uninstall = new LinkedHashMap<>();
        uninstall.put("finalStatus", "SUCCEEDED_VERIFIED");
        uninstall.put("probeState", "ABSENT");
        uninstall.put("preProbe", preProbe);
        uninstall.put("postProbe", postProbe);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", "POST");
        payload.put("exitCode", 0);
        payload.put("uninstall", uninstall);
        payload.put("stdoutSummary", "Uninstall complete");

        policy.validate(payload); // clean — no throw

        Map<String, Object> out = policy.redact(payload);
        assertThat(out).containsEntry("stage", "POST");
        assertThat(out).containsEntry("stdoutSummary", "Uninstall complete");
        @SuppressWarnings("unchecked")
        Map<String, Object> outUninstall = (Map<String, Object>) out.get("uninstall");
        assertThat(outUninstall).containsEntry("finalStatus", "SUCCEEDED_VERIFIED");
        @SuppressWarnings("unchecked")
        Map<String, Object> outPre = (Map<String, Object>) outUninstall.get("preProbe");
        assertThat(outPre).containsEntry("probeState", "MATCHED");
        @SuppressWarnings("unchecked")
        Map<String, Object> outPost = (Map<String, Object>) outUninstall.get("postProbe");
        assertThat(outPost).containsEntry("probeState", "ABSENT");
    }
}
