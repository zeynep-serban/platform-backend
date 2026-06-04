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
 * BE — unit tests for {@link ServicesPayloadPolicy} (Faz 22.5, AG-039-be).
 * Mirrors AG-038-be {@code DiagnosticsPayloadPolicyTest}.
 */
class ServicesPayloadPolicyTest {

    private final ServicesPayloadPolicy policy = new ServicesPayloadPolicy();

    // ─── 1. Golden ────────────────────────────────────────────────

    @Test
    void goldenWindowsExactSixOk() {
        var p = policy.projectAndHash(goldenWindows());
        assertThat(p.schemaVersion()).isEqualTo(1);
        assertThat(p.supported()).isTrue();
        assertThat(p.probeComplete()).isTrue();
        assertThat(p.services()).hasSize(6);
        assertThat(p.services().get(0).name()).isEqualTo("WinDefend");
        assertThat(p.services().get(5).name()).isEqualTo("MpsSvc");
        assertThat(p.probeErrors()).isEmpty();
        assertThat(p.payloadHashSha256()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void goldenUnsupportedEmptyServicesOk() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("schemaVersion", 1);
        p.put("supported", false);
        p.put("probeComplete", false);
        p.put("services", List.of());
        p.put("probeDurationMs", 0);
        p.put("probeErrors", List.of(Map.of("code", "UNSUPPORTED_PLATFORM")));
        var proj = policy.projectAndHash(p);
        assertThat(proj.supported()).isFalse();
        assertThat(proj.services()).isEmpty();
        assertThat(proj.probeErrors()).hasSize(1);
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
    void forbiddenApiUrlRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("apiURL", "https://leak");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden key");
    }

    // ─── 3. Required keys ────────────────────────────────────────

    @Test
    void missingServicesRejected() {
        Map<String, Object> p = goldenWindows();
        p.remove("services");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required key: services");
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
    void missingProbeErrorsIsOmitempty() {
        Map<String, Object> p = goldenWindows();
        p.remove("probeErrors");
        var proj = policy.projectAndHash(p);
        assertThat(proj.probeErrors()).isEmpty();
    }

    // ─── 4. probeErrors null vs absent (Codex iter-3 #2) ──────────

    @Test
    void probeErrorsExplicitNullRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", null);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a List or omitted, not null");
    }

    @Test
    void probeErrorsEmptyListOk() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of());
        var proj = policy.projectAndHash(p);
        assertThat(proj.probeErrors()).isEmpty();
    }

    // ─── 5. Exact-six invariant ──────────────────────────────────

    @Test
    void servicesListMissingEntryRejected() {
        Map<String, Object> p = goldenWindows();
        @SuppressWarnings("unchecked")
        List<Object> services = (List<Object>) p.get("services");
        services.remove(0);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MUST have exactly 6 entries");
    }

    @Test
    void servicesListExtraEntryRejected() {
        Map<String, Object> p = goldenWindows();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) p.get("services");
        services.add(entryOf("ExtraService", true, "RUNNING", "AUTO"));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MUST have exactly 6 entries");
    }

    @Test
    void servicesListReorderAcceptedAndCanonicalized() {
        // LIVE regression (2026-06-04): the agent enumerates the 6 services in
        // SCM/probe order (observed: BITS before WinDefend), which is a valid
        // SET in a non-canonical ORDER. The previous positional check rejected
        // it with HTTP 400 "violates canonical order", silently breaking AG-039
        // ingest end-to-end. A valid set in ANY order must now be accepted +
        // normalized to canonical order (validate-then-sort per the class doc).
        Map<String, Object> p = goldenWindows();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) p.get("services");
        java.util.Collections.reverse(services); // fully non-canonical input order
        var proj = policy.projectAndHash(p);
        assertThat(proj.services()).hasSize(6);
        assertThat(proj.services().stream()
                .map(ServicesPayloadPolicy.EntryProjection::name))
                .containsExactly("WinDefend", "wuauserv", "BITS", "EventLog", "EndpointAgent", "MpsSvc");
        // rowOrdinal renumbered to the canonical position (deterministic)
        assertThat(proj.services().get(0).rowOrdinal()).isEqualTo(0);
        assertThat(proj.services().get(5).rowOrdinal()).isEqualTo(5);
        assertThat(proj.payloadHashSha256()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void servicesPayloadHashIsAgentOrderIndependent() {
        // The canonical hash must be identical whether the agent sends the six
        // services in canonical order or any other order — the sort makes the
        // hash a deterministic function of the SET, not the wire order.
        var canonical = policy.projectAndHash(goldenWindows());
        Map<String, Object> reordered = goldenWindows();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> svc = (List<Map<String, Object>>) reordered.get("services");
        java.util.Collections.reverse(svc);
        var shuffled = policy.projectAndHash(reordered);
        assertThat(shuffled.payloadHashSha256()).isEqualTo(canonical.payloadHashSha256());
    }

    @Test
    void servicesListDuplicateNameRejected() {
        // exact-six size passes but a duplicate name (⇒ a missing canonical
        // name) must still be rejected by the set-membership invariant.
        Map<String, Object> p = goldenWindows();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) p.get("services");
        services.get(1).put("name", services.get(0).get("name")); // duplicate WinDefend
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    // ─── 6. Per-entry enum + null ────────────────────────────────

    @Test
    void invalidStateRejected() {
        Map<String, Object> p = goldenWindows();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) p.get("services");
        services.get(0).put("state", "PAUSED");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
    }

    @Test
    void invalidStartupModeRejected() {
        Map<String, Object> p = goldenWindows();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) p.get("services");
        services.get(0).put("startupMode", "BOOT_START");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startupMode");
    }

    @Test
    void serviceEntryUnknownKeyRejected() {
        Map<String, Object> p = goldenWindows();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) p.get("services");
        services.get(0).put("description", "leak");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown key");
    }

    @Test
    void serviceEntryPresentMustBeBoolean() {
        Map<String, Object> p = goldenWindows();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) p.get("services");
        services.get(0).put("present", "true");
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Boolean");
    }

    @Test
    void autoDelayedDistinctFromAutoInHash() {
        Map<String, Object> p1 = goldenWindows();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services1 = (List<Map<String, Object>>) p1.get("services");
        services1.get(0).put("startupMode", "AUTO");

        Map<String, Object> p2 = goldenWindows();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services2 = (List<Map<String, Object>>) p2.get("services");
        services2.get(0).put("startupMode", "AUTO_DELAYED");

        assertThat(policy.projectAndHash(p1).payloadHashSha256())
                .isNotEqualTo(policy.projectAndHash(p2).payloadHashSha256());
    }

    // ─── 7. probeErrors bounded ──────────────────────────────────

    @Test
    void probeErrorInvalidCodeRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of("code", "FUTURE_ERROR")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code must be in");
    }

    @Test
    void probeErrorServiceNameAllowlistOnly() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "SERVICE_NOT_FOUND",
                "serviceName", "Spooler")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical allowlist");
    }

    @Test
    void probeErrorServiceNameAllowlistAccept() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "SERVICE_QUERY_FAILED",
                "serviceName", "BITS")));
        var proj = policy.projectAndHash(p);
        assertThat(proj.probeErrors().get(0).serviceName()).isEqualTo("BITS");
    }

    @Test
    void probeErrorSummaryUrlRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "NO_EVIDENCE",
                "summary", "see https://leak.example.com")));
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden value pattern");
    }

    @Test
    void probeErrorSummaryBoundedTextOk() {
        Map<String, Object> p = goldenWindows();
        p.put("probeErrors", List.of(Map.of(
                "code", "SCM_UNAVAILABLE",
                "summary", "Service Control Manager connection failed")));
        assertThatCode(() -> policy.projectAndHash(p)).doesNotThrowAnyException();
    }

    @Test
    void probeErrorsCapEnforced() {
        Map<String, Object> p = goldenWindows();
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            tooMany.add(Map.of("code", "NO_EVIDENCE"));
        }
        p.put("probeErrors", tooMany);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds cap");
    }

    // ─── 8. Scalar guards ────────────────────────────────────────

    @Test
    void probeDurationMsNegativeRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeDurationMs", -1);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void probeDurationMsOverUpperBoundRejected() {
        Map<String, Object> p = goldenWindows();
        p.put("probeDurationMs", 130000);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void schemaVersionMustBeOne() {
        Map<String, Object> p = goldenWindows();
        p.put("schemaVersion", 2);
        assertThatThrownBy(() -> policy.projectAndHash(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported schemaVersion");
    }

    // ─── 9. Canonical hash determinism ───────────────────────────

    @Test
    void canonicalHashDeterministic() {
        Map<String, Object> p1 = goldenWindows();
        Map<String, Object> p2 = goldenWindows();
        assertThat(policy.projectAndHash(p1).payloadHashSha256())
                .isEqualTo(policy.projectAndHash(p2).payloadHashSha256());
    }

    @Test
    void probeDurationMsIncludedInHash() {
        Map<String, Object> p1 = goldenWindows();
        p1.put("probeDurationMs", 100);
        Map<String, Object> p2 = goldenWindows();
        p2.put("probeDurationMs", 5000);
        assertThat(policy.projectAndHash(p1).payloadHashSha256())
                .isNotEqualTo(policy.projectAndHash(p2).payloadHashSha256());
    }

    @Test
    void stateChangeAppendsNewHash() {
        Map<String, Object> p1 = goldenWindows();
        Map<String, Object> p2 = goldenWindows();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services2 = (List<Map<String, Object>>) p2.get("services");
        services2.get(0).put("state", "STOPPED");
        assertThat(policy.projectAndHash(p1).payloadHashSha256())
                .isNotEqualTo(policy.projectAndHash(p2).payloadHashSha256());
    }

    // ─── 10. Sanitize hook ────────────────────────────────────────

    @Test
    void sanitizeAcceptsValidNestedServices() {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("services", goldenWindows());
        details.put("inventory", inv);
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    @Test
    void sanitizeRejectsServicesAsList() {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("services", List.of("not-a-map"));
        details.put("inventory", inv);
        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a Map or absent");
    }

    @Test
    void sanitizeAcceptsAbsentServices() {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("software", List.of());
        details.put("inventory", inv);
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private Map<String, Object> goldenWindows() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("schemaVersion", 1);
        p.put("supported", true);
        p.put("probeComplete", true);
        List<Map<String, Object>> services = new ArrayList<>();
        for (String name : ServicesPayloadPolicy.CANONICAL_ALLOWLIST) {
            services.add(entryOf(name, true, "RUNNING", "AUTO"));
        }
        p.put("services", services);
        p.put("probeDurationMs", 250);
        return p;
    }

    private Map<String, Object> entryOf(String name, boolean present, String state, String startupMode) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("name", name);
        e.put("present", present);
        e.put("state", state);
        e.put("startupMode", startupMode);
        return e;
    }
}
