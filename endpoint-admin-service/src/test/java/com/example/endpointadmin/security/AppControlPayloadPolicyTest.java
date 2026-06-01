package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE — unit tests for {@link AppControlPayloadPolicy} (Faz 22.5,
 * AG-041-be). Mirrors AG-040-be {@code StartupExposurePayloadPolicyTest}
 * and AG-039-be {@code ServicesPayloadPolicyTest} patterns with the
 * Codex 019e840e plan iter-2 AGREE invariants pinned.
 *
 * <p>Pinned invariants (Codex absorb):
 * <ul>
 *   <li>20-key stable wire shape (all REQUIRED, probeErrors empty-list-
 *   never-null + explicit-null REJECT — iter-2 #1 + #2)</li>
 *   <li>probeComplete=true ⇒ supported=true ∧ wdacQueryable=true ∧
 *   appLockerQueryable=true (iter-2 #3)</li>
 *   <li>AG-039 enum superset reuse: SERVICE_STATE_ENUM (RUNNING/STOPPED/
 *   DISABLED/UNKNOWN) + STARTUP_MODE_ENUM (AUTO/AUTO_DELAYED/MANUAL/
 *   DISABLED/UNKNOWN — AUTO_DELAYED present for AppIDSvc; iter-2 #4)</li>
 *   <li>Nullable evidence accepts explicit null; sentinel forbidden;
 *   hash preserves null literal (iter-2 #6)</li>
 *   <li>FORBIDDEN_TOP_KEYS rejects credential vectors + AG-041 leak
 *   vectors (policyName, ruleId, exePath, etc. — iter-2 #11)</li>
 *   <li>probeErrors PROBE_ERRORS_MAX=16 cap + V25 summary guards
 *   (length, CRLF, control char, URL/Bearer/IP/token denylist —
 *   iter-2 #8)</li>
 *   <li>Source 3-value lowercase enum: wdac, appLocker, filesystem
 *   (iter-2 #10)</li>
 *   <li>ProbeError code 8-value enum including PROBE_ERRORS_TRUNCATED
 *   sentinel (iter-2 #9)</li>
 * </ul>
 */
class AppControlPayloadPolicyTest {

    private final AppControlPayloadPolicy policy = new AppControlPayloadPolicy();

    // ─── 1. Golden ────────────────────────────────────────────────

    @Test
    void goldenWindowsOk() {
        var p = policy.projectAndHash(goldenWindows());
        assertThat(p.schemaVersion()).isEqualTo(1);
        assertThat(p.supported()).isTrue();
        assertThat(p.probeComplete()).isTrue();
        assertThat(p.wdacQueryable()).isTrue();
        assertThat(p.appLockerQueryable()).isTrue();
        assertThat(p.wdacMode()).isEqualTo("UNKNOWN");
        assertThat(p.wdacBootEnforcementPresent()).isFalse();
        assertThat(p.wdacActiveCipPolicyCount()).isZero();
        assertThat(p.appLockerExeRule()).isEqualTo("NOT_CONFIGURED");
        assertThat(p.appLockerAppIdSvcState()).isEqualTo("STOPPED");
        assertThat(p.appLockerAppIdSvcStartup()).isEqualTo("MANUAL");
        assertThat(p.appLockerAppIdSvcPresent()).isTrue();
        assertThat(p.probeErrors()).isEmpty();
        assertThat(p.payloadHashSha256()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void goldenUnsupportedOk() {
        Map<String, Object> p = new LinkedHashMap<>(goldenWindows());
        p.put("supported", false);
        p.put("probeComplete", false);
        p.put("wdacQueryable", false);
        p.put("appLockerQueryable", false);
        // Nullable evidence stays null when supported=false.
        p.put("wdacBootEnforcementPresent", null);
        p.put("wdacActiveCipPolicyCount", null);
        p.put("wdacLegacySipolicyPresent", null);
        p.put("wdacMultiPolicyMode", null);
        p.put("appLockerAppIdSvcPresent", null);
        p.put("probeErrors", List.of(Map.of("code", "NO_EVIDENCE")));
        var proj = policy.projectAndHash(p);
        assertThat(proj.supported()).isFalse();
        assertThat(proj.probeComplete()).isFalse();
        assertThat(proj.wdacBootEnforcementPresent()).isNull();
        assertThat(proj.wdacActiveCipPolicyCount()).isNull();
        assertThat(proj.appLockerAppIdSvcPresent()).isNull();
        assertThat(proj.probeErrors()).hasSize(1);
        assertThat(proj.probeErrors().get(0).code()).isEqualTo("NO_EVIDENCE");
    }

    // ─── 2. Strict allowlist ─────────────────────────────────────

    @Test
    void unknownTopLevelKeyRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("futureField", 1);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown top-level key");
    }

    @Test
    void payloadLevelCollectedAtRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("collectedAt", "2026-06-01T12:00:00Z");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingRequiredKeyRejected() {
        Map<String, Object> p = goldenWindows();
        p.remove("wdacMode");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required key");
    }

    @Test
    void probeErrorsRequiredEvenWhenEmpty() {
        Map<String, Object> p = goldenWindows();
        p.remove("probeErrors");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probeErrors");
    }

    @Test
    void probeErrorsExplicitNullRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", null);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probeErrors");
    }

    // ─── 3. Forbidden top keys ───────────────────────────────────

    @Test
    void forbiddenTokenRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("token", "secret");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key");
    }

    @Test
    void forbiddenPolicyNameRejected() {
        // Codex iter-2 #11 absorb — AG-041 leak vector.
        Map<String, Object> p = goldenWindows();
        p.put("policyName", "MyPolicy");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key");
    }

    @Test
    void forbiddenExePathRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("exePath", "C:\\Windows\\System32\\cmd.exe");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key");
    }

    @Test
    void forbiddenSignerThumbprintRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("signerThumbprint", "aabbcc11223344");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key");
    }

    // ─── 4. Enum allowlists ──────────────────────────────────────

    @Test
    void invalidWdacModeRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("wdacMode", "PARTIAL");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wdacMode");
    }

    @Test
    void invalidAppLockerEnforcementRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("appLockerDllRule", "PARTIAL_ENFORCE");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appLockerDllRule");
    }

    @Test
    void invalidAppIdSvcStateRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("appLockerAppIdSvcState", "PAUSED");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appLockerAppIdSvcState");
    }

    @Test
    void appIdSvcStartupAutoDelayedAccepted() {
        // Codex iter-2 #4 absorb — AG-039 enum superset preserved.
        Map<String, Object> p = goldenWindows();
        p.put("appLockerAppIdSvcStartup", "AUTO_DELAYED");
        var proj = policy.projectAndHash(p);
        assertThat(proj.appLockerAppIdSvcStartup()).isEqualTo("AUTO_DELAYED");
    }

    @Test
    void appIdSvcStateDisabledAccepted() {
        Map<String, Object> p = goldenWindows();
        p.put("appLockerAppIdSvcState", "DISABLED");
        var proj = policy.projectAndHash(p);
        assertThat(proj.appLockerAppIdSvcState()).isEqualTo("DISABLED");
    }

    @Test
    void wdacModeAllFourEnumValuesAccepted() {
        for (String mode : List.of("OFF", "AUDIT", "ENFORCE", "UNKNOWN")) {
            Map<String, Object> p = goldenWindows();
            p.put("wdacMode", mode);
            var proj = policy.projectAndHash(p);
            assertThat(proj.wdacMode()).isEqualTo(mode);
        }
    }

    // ─── 5. probeComplete invariant ──────────────────────────────

    @Test
    void probeCompleteRequiresAllQueryableAndSupported() {
        // Codex iter-2 #3 absorb — implication enforced.
        Map<String, Object> p = goldenWindows();
        p.put("wdacQueryable", false);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probeComplete=true requires");
    }

    @Test
    void probeCompleteRequiresAppLockerQueryable() {
        Map<String, Object> p = goldenWindows();
        p.put("appLockerQueryable", false);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probeComplete=true requires");
    }

    @Test
    void probeCompleteRequiresSupported() {
        Map<String, Object> p = goldenWindows();
        p.put("supported", false);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probeComplete=true requires");
    }

    @Test
    void probeCompleteFalseWithFacetUnqueryableOk() {
        // probeComplete=false is always valid regardless of queryability.
        Map<String, Object> p = goldenWindows();
        p.put("probeComplete", false);
        p.put("wdacQueryable", false);
        // Wire contract: wdacMode UNKNOWN dominant when not queryable.
        p.put("wdacMode", "UNKNOWN");
        p.put("probeErrors", List.of(Map.of("code", "WDAC_SCALAR_UNREADABLE",
                "source", "wdac",
                "summary", "registry key unreadable")));
        var proj = policy.projectAndHash(p);
        assertThat(proj.probeComplete()).isFalse();
        assertThat(proj.wdacQueryable()).isFalse();
    }

    @Test
    void probeCompleteTrueWithNonCriticalProbeErrorOk() {
        // Codex iter-2 #3 absorb — agent may emit non-decision-critical
        // probe errors while still reporting probeComplete=true.
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of("code", "FILESYSTEM_DENIED",
                "source", "filesystem")));
        // probeComplete is still true here — backend does NOT enforce
        // probeErrors.isEmpty().
        var proj = policy.projectAndHash(p);
        assertThat(proj.probeComplete()).isTrue();
        assertThat(proj.probeErrors()).hasSize(1);
    }

    // ─── 6. Nullable evidence ────────────────────────────────────

    @Test
    void nullableEvidenceExplicitNullAccepted() {
        Map<String, Object> p = goldenWindows();
        p.put("wdacBootEnforcementPresent", null);
        p.put("wdacActiveCipPolicyCount", null);
        p.put("wdacLegacySipolicyPresent", null);
        p.put("wdacMultiPolicyMode", null);
        p.put("appLockerAppIdSvcPresent", null);
        var proj = policy.projectAndHash(p);
        assertThat(proj.wdacBootEnforcementPresent()).isNull();
        assertThat(proj.wdacActiveCipPolicyCount()).isNull();
        assertThat(proj.wdacLegacySipolicyPresent()).isNull();
        assertThat(proj.wdacMultiPolicyMode()).isNull();
        assertThat(proj.appLockerAppIdSvcPresent()).isNull();
    }

    @Test
    void wdacActiveCipPolicyCountNegativeRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("wdacActiveCipPolicyCount", -1);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 0");
    }

    @Test
    void wdacActiveCipPolicyCountNonIntegerRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("wdacActiveCipPolicyCount", 2.5);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── 7. Hash determinism + null preservation ─────────────────

    @Test
    void hashStableAcrossKeyOrder() {
        Map<String, Object> a = goldenWindows();
        Map<String, Object> b = reorder(goldenWindows());
        assertThat(policy.projectAndHash(a).payloadHashSha256())
                .isEqualTo(policy.projectAndHash(b).payloadHashSha256());
    }

    @Test
    void hashDifferentForNullVsFalseEvidence() {
        // Codex iter-2 #6 absorb — canonical hash preserves null literal.
        Map<String, Object> nullEvidence = goldenWindows();
        nullEvidence.put("wdacBootEnforcementPresent", null);
        Map<String, Object> falseEvidence = goldenWindows();
        falseEvidence.put("wdacBootEnforcementPresent", false);
        assertThat(policy.projectAndHash(nullEvidence).payloadHashSha256())
                .isNotEqualTo(policy.projectAndHash(falseEvidence).payloadHashSha256());
    }

    @Test
    void hashChangesWithProbeErrorsOrder() {
        // Codex iter-2 #7 absorb — order is preserved by row_ordinal.
        Map<String, Object> p1 = goldenWindows();
        p1.put("probeErrors", List.of(
                Map.of("code", "REGISTRY_DENIED", "source", "wdac"),
                Map.of("code", "FILESYSTEM_DENIED", "source", "filesystem")));
        Map<String, Object> p2 = goldenWindows();
        p2.put("probeErrors", List.of(
                Map.of("code", "FILESYSTEM_DENIED", "source", "filesystem"),
                Map.of("code", "REGISTRY_DENIED", "source", "wdac")));
        // probeComplete=true requires queryable, which is true, but
        // these have non-decision-critical errors so probeComplete=true
        // stays valid.
        assertThat(policy.projectAndHash(p1).payloadHashSha256())
                .isNotEqualTo(policy.projectAndHash(p2).payloadHashSha256());
    }

    // ─── 8. ProbeError shape ─────────────────────────────────────

    @Test
    void probeErrorCodeAllEightAccepted() {
        // All 8 codes must round-trip through projectAndHash. NO_EVIDENCE
        // requires probeComplete=false (iter-3 P2 invariant); the other
        // 7 codes are non-critical and accepted alongside the golden
        // probeComplete=true.
        for (String code : List.of("NO_EVIDENCE", "REGISTRY_DENIED",
                "FILESYSTEM_DENIED", "CIP_POLICIES_DIR_UNREADABLE",
                "APPLOCKER_KEY_UNREADABLE", "APP_ID_SVC_QUERY_FAILED",
                "WDAC_SCALAR_UNREADABLE", "PROBE_ERRORS_TRUNCATED")) {
            Map<String, Object> p = goldenWindows();
            if ("NO_EVIDENCE".equals(code)) {
                p.put("supported", false);
                p.put("probeComplete", false);
                p.put("wdacQueryable", false);
                p.put("appLockerQueryable", false);
                p.put("wdacBootEnforcementPresent", null);
                p.put("wdacActiveCipPolicyCount", null);
                p.put("wdacLegacySipolicyPresent", null);
                p.put("wdacMultiPolicyMode", null);
                p.put("appLockerAppIdSvcPresent", null);
            }
            p.put("probeErrors", List.of(Map.of("code", code)));
            assertThatCode(() -> policy.projectAndHash(p))
                    .as("code %s should be accepted", code)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void probeErrorCodeUnknownRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of("code", "FUTURE_CODE")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }

    @Test
    void probeErrorSourceLowercaseAccepted() {
        // Iter-3 P2 absorb: use FILESYSTEM_DENIED (non-critical code)
        // so probeComplete=true (from goldenWindows) is compatible.
        for (String src : List.of("wdac", "appLocker", "filesystem")) {
            Map<String, Object> p = goldenWindows();
            p.put("probeErrors", List.of(Map.of("code", "FILESYSTEM_DENIED", "source", src)));
            assertThatCode(() -> policy.projectAndHash(p))
                    .as("source %s should be accepted", src)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void probeErrorSourceUppercaseRejected() {
        // Iter-3 P2 absorb: FILESYSTEM_DENIED to keep probeComplete
        // invariant happy; reject targets the source case.
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of("code", "FILESYSTEM_DENIED", "source", "WDAC")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    void probeErrorSummaryUrlDenylistRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "REGISTRY_DENIED",
                "summary", "lookup failed at https://example.com/api")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden value pattern");
    }

    @Test
    void probeErrorSummaryControlCharRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "REGISTRY_DENIED",
                "summary", "withbell")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control character");
    }

    @Test
    void probeErrorSummaryEmptyRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of("code", "REGISTRY_DENIED", "summary", "")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void probeErrorsCap16Enforced() {
        // Codex iter-3 P2 absorb: use FILESYSTEM_DENIED (non-critical) so
        // the cap check fires before the NO_EVIDENCE invariant. Cap
        // semantic is what we're asserting here.
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> errs = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            errs.add(Map.of("code", "FILESYSTEM_DENIED"));
        }
        p.put("probeErrors", errs);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cap of 16");
    }

    @Test
    void probeErrorsAtCapAccepted() {
        // Codex iter-3 P2 absorb: use FILESYSTEM_DENIED (a non-decision-
        // critical error code the agent may emit alongside
        // probeComplete=true). NO_EVIDENCE would now be rejected
        // here because it is the agent's "overall probe failed" sentinel.
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> errs = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            errs.add(Map.of("code", "FILESYSTEM_DENIED"));
        }
        p.put("probeErrors", errs);
        var proj = policy.projectAndHash(p);
        assertThat(proj.probeErrors()).hasSize(16);
    }

    @Test
    void probeCompleteTrueWithNoEvidenceRejected() {
        // Codex 019e840e iter-3 P2 absorb: NO_EVIDENCE is the agent's
        // "overall probe failed" sentinel; accepting it with
        // probeComplete=true would persist a contradictory snapshot.
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of("code", "NO_EVIDENCE")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NO_EVIDENCE");
    }

    @Test
    void probeCompleteFalseWithNoEvidenceAccepted() {
        Map<String, Object> p = goldenWindows();
        p.put("supported", false);
        p.put("probeComplete", false);
        p.put("wdacQueryable", false);
        p.put("appLockerQueryable", false);
        p.put("wdacBootEnforcementPresent", null);
        p.put("wdacActiveCipPolicyCount", null);
        p.put("wdacLegacySipolicyPresent", null);
        p.put("wdacMultiPolicyMode", null);
        p.put("appLockerAppIdSvcPresent", null);
        p.put("probeErrors", List.of(Map.of("code", "NO_EVIDENCE")));
        var proj = policy.projectAndHash(p);
        assertThat(proj.probeComplete()).isFalse();
        assertThat(proj.probeErrors().get(0).code()).isEqualTo("NO_EVIDENCE");
    }

    @Test
    void probeErrorsTruncatedSentinelAccepted() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of("code", "PROBE_ERRORS_TRUNCATED")));
        var proj = policy.projectAndHash(p);
        assertThat(proj.probeErrors().get(0).code()).isEqualTo("PROBE_ERRORS_TRUNCATED");
    }

    // ─── 9. Sanitize hook ────────────────────────────────────────

    @Test
    void sanitizeWithoutBlockNoOp() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", Map.of("software", List.of()));
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    @Test
    void sanitizeTopLevelAppControlOk() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("appControl", goldenWindows());
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    @Test
    void sanitizeNestedAppControlOk() {
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("appControl", goldenWindows());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", inventory);
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    @Test
    void sanitizeTypeConfusionRejected() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("appControl", "not-a-map");
        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a Map");
    }

    @Test
    void sanitizeExplicitNullRejected() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("appControl", null);
        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not null");
    }

    // ─── 10. Scalar guards ───────────────────────────────────────

    @Test
    void schemaVersionTwoRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("schemaVersion", 2);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void probeDurationNegativeRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeDurationMs", -1);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probeDurationMs");
    }

    @Test
    void probeDurationOver120000Rejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeDurationMs", 120001);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probeDurationMs");
    }

    @Test
    void probeDurationDecimalRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeDurationMs", 250.0);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("integer");
    }

    @Test
    void supportedNullRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("supported", null);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supported");
    }

    // ─── helpers ─────────────────────────────────────────────────

    private static Map<String, Object> goldenWindows() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("schemaVersion", 1);
        p.put("supported", true);
        p.put("probeComplete", true);
        p.put("wdacQueryable", true);
        p.put("appLockerQueryable", true);
        p.put("wdacMode", "UNKNOWN");
        p.put("wdacBootEnforcementPresent", false);
        p.put("wdacActiveCipPolicyCount", 0);
        p.put("wdacLegacySipolicyPresent", false);
        p.put("wdacMultiPolicyMode", false);
        p.put("appLockerExeRule", "NOT_CONFIGURED");
        p.put("appLockerDllRule", "NOT_CONFIGURED");
        p.put("appLockerScriptRule", "NOT_CONFIGURED");
        p.put("appLockerMsiRule", "NOT_CONFIGURED");
        p.put("appLockerAppxRule", "NOT_CONFIGURED");
        p.put("appLockerAppIdSvcState", "STOPPED");
        p.put("appLockerAppIdSvcStartup", "MANUAL");
        p.put("appLockerAppIdSvcPresent", true);
        p.put("probeDurationMs", 100);
        p.put("probeErrors", List.of());
        return p;
    }

    private static Map<String, Object> reorder(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        // reverse-ish ordering to confirm canonical-hash determinism.
        out.put("probeErrors", in.get("probeErrors"));
        out.put("probeDurationMs", in.get("probeDurationMs"));
        out.put("appLockerAppIdSvcPresent", in.get("appLockerAppIdSvcPresent"));
        out.put("appLockerAppIdSvcStartup", in.get("appLockerAppIdSvcStartup"));
        out.put("appLockerAppIdSvcState", in.get("appLockerAppIdSvcState"));
        out.put("appLockerAppxRule", in.get("appLockerAppxRule"));
        out.put("appLockerMsiRule", in.get("appLockerMsiRule"));
        out.put("appLockerScriptRule", in.get("appLockerScriptRule"));
        out.put("appLockerDllRule", in.get("appLockerDllRule"));
        out.put("appLockerExeRule", in.get("appLockerExeRule"));
        out.put("wdacMultiPolicyMode", in.get("wdacMultiPolicyMode"));
        out.put("wdacLegacySipolicyPresent", in.get("wdacLegacySipolicyPresent"));
        out.put("wdacActiveCipPolicyCount", in.get("wdacActiveCipPolicyCount"));
        out.put("wdacBootEnforcementPresent", in.get("wdacBootEnforcementPresent"));
        out.put("wdacMode", in.get("wdacMode"));
        out.put("appLockerQueryable", in.get("appLockerQueryable"));
        out.put("wdacQueryable", in.get("wdacQueryable"));
        out.put("probeComplete", in.get("probeComplete"));
        out.put("supported", in.get("supported"));
        out.put("schemaVersion", in.get("schemaVersion"));
        return out;
    }
}
