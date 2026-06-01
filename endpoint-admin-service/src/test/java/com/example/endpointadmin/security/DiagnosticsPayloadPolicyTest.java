package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE — unit tests for {@link DiagnosticsPayloadPolicy} (Faz 22.5, AG-038-be).
 * Mirrors the AG-037 {@code HotfixPosturePayloadPolicyTest} structure.
 *
 * <p>Buckets:
 * <ol>
 *   <li>Golden — clean Windows + non-Windows stub.</li>
 *   <li>Top-level allowlist (strict REJECT).</li>
 *   <li>Required-keys enforcement (9 required; omitempty fields normalize).</li>
 *   <li>Forbidden top-level keys (defense-in-depth).</li>
 *   <li>configHash regex (lowercase hex or "unknown"; uppercase REJECT).</li>
 *   <li>agentVersion regex (semver+optional pre-release/build / "unknown").</li>
 *   <li>lastError triad strict (all 3 keys; nullable as whole block).</li>
 *   <li>probeErrors enum codes + bounded summary + cap.</li>
 *   <li>Canonical-form hash determinism + INCLUDE all persistable fields per Codex iter-3 P1 #4.</li>
 *   <li>Control-char REJECT (CR/LF/tab/other).</li>
 * </ol>
 */
class DiagnosticsPayloadPolicyTest {

    private final DiagnosticsPayloadPolicy policy = new DiagnosticsPayloadPolicy();

    // ------------------------------------------------------------------
    // 1. Golden — clean Windows
    // ------------------------------------------------------------------

    @Test
    void goldenWindowsProjectionSucceeds() {
        var proj = policy.projectAndHash(goldenWindows());
        assertThat(proj.schemaVersion()).isEqualTo(1);
        assertThat(proj.supported()).isTrue();
        assertThat(proj.probeComplete()).isTrue();
        assertThat(proj.agentVersion()).isEqualTo("0.7.2");
        assertThat(proj.configHash()).hasSize(64);
        assertThat(proj.lastPollLatencyMs()).isEqualTo(120);
        assertThat(proj.backendDnsReachable()).isTrue();
        assertThat(proj.backendTlsValid()).isTrue();
        assertThat(proj.lastError()).isNull();
        assertThat(proj.probeErrors()).isEmpty();
        assertThat(proj.probeDurationMs()).isEqualTo(450);
        assertThat(proj.payloadHashSha256()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void goldenUnsupportedPlatformProjectionSucceeds() {
        // Codex 019e82d7 iter-2 #8: supported=false must still carry the
        // 9 required scalar keys (agent emits default values, not omit).
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("schemaVersion", 1);
        p.put("supported", false);
        p.put("probeComplete", false);
        p.put("agentVersion", "0.7.2");
        p.put("configHash", "unknown");
        p.put("lastPollLatencyMs", 0);
        p.put("backendDNSReachable", false);
        p.put("backendTLSValid", false);
        p.put("probeDurationMs", 0);
        // probeErrors carries UNSUPPORTED_PLATFORM
        p.put("probeErrors", List.of(Map.of("code", "UNSUPPORTED_PLATFORM")));
        var proj = policy.projectAndHash(p);
        assertThat(proj.supported()).isFalse();
        assertThat(proj.probeComplete()).isFalse();
        assertThat(proj.probeErrors()).hasSize(1);
        assertThat(proj.probeErrors().get(0).code()).isEqualTo("UNSUPPORTED_PLATFORM");
        assertThat(proj.probeErrors().get(0).summary()).isNull();
    }

    // ------------------------------------------------------------------
    // 2. Top-level allowlist — strict REJECT
    // ------------------------------------------------------------------

    @Test
    void unknownTopLevelKeyRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("futureField", "anything");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown top-level key");
    }

    @Test
    void payloadLevelCollectedAtRejected() {
        // Codex 019e82d7 iter-2 #7: payload-level collectedAt → REJECT
        // (server-controlled from EndpointCommandResult.reportedAt).
        Map<String, Object> p = goldenWindows();
        p.put("collectedAt", "2026-06-01T12:00:00Z");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown top-level key: collectedAt");
    }

    // ------------------------------------------------------------------
    // 3. Required keys — missing fail-closed
    // ------------------------------------------------------------------

    @Test
    void missingSchemaVersionRejected() {
        Map<String, Object> p = goldenWindows();
        p.remove("schemaVersion");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required key: schemaVersion");
    }

    @Test
    void missingAgentVersionRejected() {
        Map<String, Object> p = goldenWindows();
        p.remove("agentVersion");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required key: agentVersion");
    }

    @Test
    void missingProbeDurationMsRejected() {
        Map<String, Object> p = goldenWindows();
        p.remove("probeDurationMs");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required key: probeDurationMs");
    }

    @Test
    void missingLastPollLatencyMsRejected() {
        Map<String, Object> p = goldenWindows();
        p.remove("lastPollLatencyMs");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required key: lastPollLatencyMs");
    }

    @Test
    void missingLastErrorIsOmitemptyOk() {
        // omitempty — absent → null.
        Map<String, Object> p = goldenWindows();
        p.remove("lastError");
        var proj = policy.projectAndHash(p);
        assertThat(proj.lastError()).isNull();
    }

    @Test
    void missingProbeErrorsIsOmitemptyOk() {
        // omitempty — absent → empty list.
        Map<String, Object> p = goldenWindows();
        p.remove("probeErrors");
        var proj = policy.projectAndHash(p);
        assertThat(proj.probeErrors()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 4. Forbidden top-level keys (defense-in-depth)
    // ------------------------------------------------------------------

    @Test
    void forbiddenApiUrlRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("apiURL", "https://leak.example.com");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key: apiURL");
    }

    @Test
    void forbiddenCredentialIdRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("credentialId", "cred-1");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key: credentialId");
    }

    @Test
    void forbiddenTokenRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("token", "Bearer xyz");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key: token");
    }

    @Test
    void forbiddenPasswordRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("password", "secret123");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key: password");
    }

    @Test
    void forbiddenHostRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("host", "backend.local");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key: host");
    }

    // ------------------------------------------------------------------
    // 5. configHash regex (lowercase only; "unknown" sentinel)
    // ------------------------------------------------------------------

    @Test
    void configHashUppercaseRejected() {
        // Codex 019e82d7 iter-2 #6: uppercase → REJECT, NOT silently
        // normalized. Backend doesn't fix mis-formatted contract payload.
        Map<String, Object> p = goldenWindows();
        p.put("configHash", "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configHash");
    }

    @Test
    void configHashShortRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("configHash", "abc123");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configHash");
    }

    @Test
    void configHashUnknownSentinelOk() {
        Map<String, Object> p = goldenWindows();
        p.put("configHash", "unknown");
        var proj = policy.projectAndHash(p);
        assertThat(proj.configHash()).isEqualTo("unknown");
    }

    // ------------------------------------------------------------------
    // 6. agentVersion regex
    // ------------------------------------------------------------------

    @Test
    void agentVersionSemverOk() {
        Map<String, Object> p = goldenWindows();
        p.put("agentVersion", "1.2.3");
        assertThatCode(() -> policy.projectAndHash(p)).doesNotThrowAnyException();
    }

    @Test
    void agentVersionPreReleaseOk() {
        // Codex 019e82d7 iter-2 #5: prerelease/build metadata accepted.
        Map<String, Object> p = goldenWindows();
        p.put("agentVersion", "1.2.3-dev.5");
        assertThatCode(() -> policy.projectAndHash(p)).doesNotThrowAnyException();
    }

    @Test
    void agentVersionBuildMetadataOk() {
        Map<String, Object> p = goldenWindows();
        p.put("agentVersion", "1.2.3+sha.abc123");
        assertThatCode(() -> policy.projectAndHash(p)).doesNotThrowAnyException();
    }

    @Test
    void agentVersionUnknownSentinelOk() {
        Map<String, Object> p = goldenWindows();
        p.put("agentVersion", "unknown");
        assertThatCode(() -> policy.projectAndHash(p)).doesNotThrowAnyException();
    }

    @Test
    void agentVersionMalformedRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("agentVersion", "not-a-version");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentVersion");
    }

    @Test
    void agentVersionTooLongRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("agentVersion", "1.2.3-" + "a".repeat(70));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // 7. lastError triad (Codex 019e82d7 iter-1 #2 / iter-2 #3)
    // ------------------------------------------------------------------

    @Test
    void lastErrorAllThreeKeysPresentOk() {
        Map<String, Object> p = goldenWindows();
        p.put("lastError", Map.of(
                "occurredAt", "2026-06-01T08:00:00Z",
                "code", "NEXT_COMMAND_TIMEOUT",
                "summary", "poll timeout after 30s"));
        var proj = policy.projectAndHash(p);
        assertThat(proj.lastError()).isNotNull();
        assertThat(proj.lastError().occurredAt()).isEqualTo(Instant.parse("2026-06-01T08:00:00Z"));
        assertThat(proj.lastError().code()).isEqualTo("NEXT_COMMAND_TIMEOUT");
        assertThat(proj.lastError().summary()).isEqualTo("poll timeout after 30s");
    }

    @Test
    void lastErrorMissingOccurredAtRejected() {
        Map<String, Object> p = goldenWindows();
        Map<String, Object> le = new LinkedHashMap<>();
        le.put("code", "X_Y_Z");
        le.put("summary", "ok");
        p.put("lastError", le);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastError missing required key: occurredAt");
    }

    @Test
    void lastErrorMissingCodeRejected() {
        Map<String, Object> p = goldenWindows();
        Map<String, Object> le = new LinkedHashMap<>();
        le.put("occurredAt", "2026-06-01T08:00:00Z");
        le.put("summary", "ok");
        p.put("lastError", le);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastError missing required key: code");
    }

    @Test
    void lastErrorMissingSummaryRejected() {
        Map<String, Object> p = goldenWindows();
        Map<String, Object> le = new LinkedHashMap<>();
        le.put("occurredAt", "2026-06-01T08:00:00Z");
        le.put("code", "X_Y_Z");
        p.put("lastError", le);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastError missing required key: summary");
    }

    @Test
    void lastErrorUnknownKeyRejected() {
        Map<String, Object> p = goldenWindows();
        Map<String, Object> le = new LinkedHashMap<>();
        le.put("occurredAt", "2026-06-01T08:00:00Z");
        le.put("code", "X_Y_Z");
        le.put("summary", "ok");
        le.put("futureField", "leak");
        p.put("lastError", le);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastError contains unknown key");
    }

    @Test
    void lastErrorEmptySummaryRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("lastError", Map.of(
                "occurredAt", "2026-06-01T08:00:00Z",
                "code", "X_Y_Z",
                "summary", ""));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastError.summary must be non-empty");
    }

    @Test
    void lastErrorSummaryTooLongRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("lastError", Map.of(
                "occurredAt", "2026-06-01T08:00:00Z",
                "code", "X_Y_Z",
                "summary", "a".repeat(201)));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("length cap");
    }

    @Test
    void lastErrorCodeMalformedRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("lastError", Map.of(
                "occurredAt", "2026-06-01T08:00:00Z",
                "code", "lowercase",
                "summary", "ok"));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastError.code");
    }

    @Test
    void lastErrorSummaryCrLfRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("lastError", Map.of(
                "occurredAt", "2026-06-01T08:00:00Z",
                "code", "X_Y_Z",
                "summary", "line1\nline2"));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control character");
    }

    @Test
    void lastErrorSummaryTabRejected() {
        // Codex 019e82d7 iter-2 #4: policy stricter than DB CHECK ('\\r\\n'
        // only) — tab + other control chars also REJECT.
        Map<String, Object> p = goldenWindows();
        p.put("lastError", Map.of(
                "occurredAt", "2026-06-01T08:00:00Z",
                "code", "X_Y_Z",
                "summary", "tab\there"));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("control character");
    }

    // ------------------------------------------------------------------
    // 8. probeErrors — codes + summaries + cap
    // ------------------------------------------------------------------

    @Test
    void probeErrorWithoutSummaryOk() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of("code", "DNS_TIMEOUT")));
        var proj = policy.projectAndHash(p);
        assertThat(proj.probeErrors()).hasSize(1);
        assertThat(proj.probeErrors().get(0).code()).isEqualTo("DNS_TIMEOUT");
        assertThat(proj.probeErrors().get(0).summary()).isNull();
    }

    @Test
    void probeErrorWithSummaryOk() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "DNS_TIMEOUT",
                "summary", "dns lookup timed out")));
        var proj = policy.projectAndHash(p);
        assertThat(proj.probeErrors()).hasSize(1);
        assertThat(proj.probeErrors().get(0).summary()).isEqualTo("dns lookup timed out");
    }

    @Test
    void probeErrorEmptySummaryRejected() {
        // Codex 019e82d7 iter-2 #5: "" → REJECT (not normalize-to-null).
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "DNS_TIMEOUT",
                "summary", "")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be non-empty");
    }

    @Test
    void probeErrorMissingCodeRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of("summary", "x")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required key: code");
    }

    @Test
    void probeErrorMalformedCodeRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of("code", "bad-code")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("violates bounded regex");
    }

    @Test
    void probeErrorUnknownKeyRejected() {
        Map<String, Object> p = goldenWindows();
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", "DNS_TIMEOUT");
        err.put("futureField", "x");
        p.put("probeErrors", List.of(err));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown key");
    }

    @Test
    void probeErrorsCapEnforced() {
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            tooMany.add(Map.of("code", "X_Y_Z"));
        }
        p.put("probeErrors", tooMany);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds cap");
    }

    @Test
    void probeErrorsAtCapBoundaryOk() {
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> atCap = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            atCap.add(Map.of("code", "X_Y_Z"));
        }
        p.put("probeErrors", atCap);
        assertThatCode(() -> policy.projectAndHash(p)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // 9. Canonical-form hash determinism + INCLUDED persistable fields (iter-3 P1 #4)
    // ------------------------------------------------------------------

    @Test
    void canonicalFormHashDeterministic() {
        Map<String, Object> p1 = goldenWindows();
        Map<String, Object> p2 = goldenWindows();
        assertThat(policy.projectAndHash(p1).payloadHashSha256())
                .isEqualTo(policy.projectAndHash(p2).payloadHashSha256());
    }

    @Test
    void lastPollLatencyMsIncludedInHash() {
        // Codex 019e82d7 iter-3 P1 #4 revise: latency is INCLUDED in
        // canonical hash so each fresh observation appends a new snapshot
        // and /latest reflects the most recent measured latency.
        Map<String, Object> p1 = goldenWindows();
        Map<String, Object> p2 = goldenWindows();
        p2.put("lastPollLatencyMs", 999);
        assertThat(policy.projectAndHash(p1).payloadHashSha256())
                .isNotEqualTo(policy.projectAndHash(p2).payloadHashSha256());
    }

    @Test
    void probeDurationMsIncludedInHash() {
        Map<String, Object> p1 = goldenWindows();
        Map<String, Object> p2 = goldenWindows();
        p2.put("probeDurationMs", 9999);
        assertThat(policy.projectAndHash(p1).payloadHashSha256())
                .isNotEqualTo(policy.projectAndHash(p2).payloadHashSha256());
    }

    @Test
    void agentVersionIncludedInHash() {
        Map<String, Object> p1 = goldenWindows();
        Map<String, Object> p2 = goldenWindows();
        p2.put("agentVersion", "1.0.0");
        assertThat(policy.projectAndHash(p1).payloadHashSha256())
                .isNotEqualTo(policy.projectAndHash(p2).payloadHashSha256());
    }

    @Test
    void configHashIncludedInHash() {
        Map<String, Object> p1 = goldenWindows();
        Map<String, Object> p2 = goldenWindows();
        p2.put("configHash", "0".repeat(64));
        assertThat(policy.projectAndHash(p1).payloadHashSha256())
                .isNotEqualTo(policy.projectAndHash(p2).payloadHashSha256());
    }

    @Test
    void backendReachabilityIncludedInHash() {
        Map<String, Object> p1 = goldenWindows();
        Map<String, Object> p2 = goldenWindows();
        p2.put("backendDNSReachable", false);
        assertThat(policy.projectAndHash(p1).payloadHashSha256())
                .isNotEqualTo(policy.projectAndHash(p2).payloadHashSha256());
    }

    @Test
    void lastErrorIncludedInHash() {
        // Codex 019e82d7 iter-1 #4 / iter-2 #2: lastError.occurredAt INCLUDED
        // (operational evidence, not timing drift).
        Map<String, Object> p1 = goldenWindows();
        Map<String, Object> p2 = goldenWindows();
        p2.put("lastError", Map.of(
                "occurredAt", "2026-06-01T08:00:00Z",
                "code", "NEXT_COMMAND_TIMEOUT",
                "summary", "poll timeout"));
        assertThat(policy.projectAndHash(p1).payloadHashSha256())
                .isNotEqualTo(policy.projectAndHash(p2).payloadHashSha256());
    }

    @Test
    void probeErrorsIncludedInHash() {
        Map<String, Object> p1 = goldenWindows();
        Map<String, Object> p2 = goldenWindows();
        p2.put("probeErrors", List.of(Map.of("code", "DNS_TIMEOUT")));
        assertThat(policy.projectAndHash(p1).payloadHashSha256())
                .isNotEqualTo(policy.projectAndHash(p2).payloadHashSha256());
    }

    // ------------------------------------------------------------------
    // 10. Type-confusion REJECT
    // ------------------------------------------------------------------

    @Test
    void schemaVersionMustBeInteger() {
        Map<String, Object> p = goldenWindows();
        p.put("schemaVersion", "1");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportedMustBeBoolean() {
        Map<String, Object> p = goldenWindows();
        p.put("supported", "true");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agentVersionMustBeString() {
        Map<String, Object> p = goldenWindows();
        p.put("agentVersion", 123);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lastErrorMustBeMap() {
        Map<String, Object> p = goldenWindows();
        p.put("lastError", "not-a-map");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Map or null");
    }

    @Test
    void probeErrorsMustBeList() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", "not-a-list");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("List");
    }

    @Test
    void schemaVersionMustBeOne() {
        Map<String, Object> p = goldenWindows();
        p.put("schemaVersion", 2);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported schemaVersion");
    }

    @Test
    void nullDiagnosticsBlockRejected() {
        assertThatThrownBy(() -> policy.projectAndHash(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void negativeLatencyRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("lastPollLatencyMs", -1);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lastPollLatencyMs must be >= 0");
    }

    @Test
    void negativeDurationRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeDurationMs", -1);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probeDurationMs must be >= 0");
    }

    // ------------------------------------------------------------------
    // 11. Sanitize hook — Codex 019e82d7 iter-3 P1 #1 absorb
    //     (type-confusion bypass closed)
    // ------------------------------------------------------------------

    @Test
    void sanitizeAcceptsAbsentDiagnostics() {
        // No diagnostics block — sanitize passes through unchanged.
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("software", List.of());
        details.put("inventory", inventory);
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    @Test
    void sanitizeAcceptsValidNestedDiagnostics() {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("diagnostics", goldenWindows());
        details.put("inventory", inventory);
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    @Test
    void sanitizeRejectsDiagnosticsAsList() {
        // Type-confusion bypass: a List would bypass the strict-allowlist
        // if not sanitized at the entry layer.
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("diagnostics", List.of("not-a-map"));
        details.put("inventory", inventory);
        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a Map or absent");
    }

    @Test
    void sanitizeRejectsDiagnosticsAsString() {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("diagnostics", "string-payload");
        details.put("inventory", inventory);
        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a Map or absent");
    }

    @Test
    void sanitizeRejectsTopLevelDiagnosticsAsList() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("diagnostics", List.of("not-a-map"));
        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a Map or absent");
    }

    @Test
    void sanitizeRejectsDiagnosticsWithForbiddenKey() {
        Map<String, Object> diag = goldenWindows();
        diag.put("apiURL", "https://leak.example.com");
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("diagnostics", diag);
        details.put("inventory", inventory);
        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key");
    }

    // ------------------------------------------------------------------
    // 12. Summary value-level denylist — Codex 019e82d7 iter-3 P1 #2
    // ------------------------------------------------------------------

    @Test
    void lastErrorSummaryWithUrlRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("lastError", Map.of(
                "occurredAt", "2026-06-01T08:00:00Z",
                "code", "X_Y_Z",
                "summary", "see https://backend.example.com for details"));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden value pattern");
    }

    @Test
    void lastErrorSummaryWithBearerRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("lastError", Map.of(
                "occurredAt", "2026-06-01T08:00:00Z",
                "code", "X_Y_Z",
                "summary", "received Bearer abc123 in response"));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden value pattern");
    }

    @Test
    void lastErrorSummaryWithIpRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("lastError", Map.of(
                "occurredAt", "2026-06-01T08:00:00Z",
                "code", "X_Y_Z",
                "summary", "connection to 192.168.1.100 refused"));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden value pattern");
    }

    @Test
    void probeErrorSummaryWithTokenRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "DNS_TIMEOUT",
                "summary", "api_key=secret123")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden value pattern");
    }

    @Test
    void probeErrorSummaryStaticBoundedTextOk() {
        // Allowed static bounded operator phrasing.
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "DNS_TIMEOUT",
                "summary", "dns lookup exceeded 5 second deadline")));
        assertThatCode(() -> policy.projectAndHash(p)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> goldenWindows() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("schemaVersion", 1);
        p.put("supported", true);
        p.put("probeComplete", true);
        p.put("agentVersion", "0.7.2");
        p.put("configHash", "abc1234567890def" + "abc1234567890def" + "abc1234567890def" + "abc1234567890def");
        p.put("lastPollLatencyMs", 120);
        p.put("backendDNSReachable", true);
        p.put("backendTLSValid", true);
        p.put("probeDurationMs", 450);
        return p;
    }
}
