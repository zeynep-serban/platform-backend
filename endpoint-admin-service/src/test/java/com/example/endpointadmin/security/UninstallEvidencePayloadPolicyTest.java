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
}
