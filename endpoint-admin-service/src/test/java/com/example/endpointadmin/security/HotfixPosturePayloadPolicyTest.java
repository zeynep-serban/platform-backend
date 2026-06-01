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
 * BE — unit tests for {@link HotfixPosturePayloadPolicy} (Faz 22.5,
 * AG-037 ingest). Mirrors the AG-036 {@code OutdatedSoftwarePayloadPolicyTest}
 * structure, enforcing the AG-037 contract redaction boundary (platform-
 * agent docs/COMMAND-CONTRACT.md §16; Codex 019e81fe iter-3 + iter-4
 * absorbed).
 *
 * <p>Buckets:
 * <ol>
 *   <li>Golden — contract example survives sanitize verbatim.</li>
 *   <li>Top-level allowlist (contract-freeze REJECT not silent drop).</li>
 *   <li>Per-row allowlists (installed / pending / pendingByCategory /
 *       agentHealth / probeErrors) — REJECT forbidden + case-variant.</li>
 *   <li>KbId regex enforced; schemaVersion const; sourceUsed enums;
 *       ServiceState enums; Category/Severity enums.</li>
 *   <li>Count/cap invariants — Codex iter-3 P1.3: pre-truncation total
 *       semantics + sum(pendingByCategory) invariant + unique category.</li>
 *   <li>notification_level — Codex iter-3 P1.4: verbatim bounded;
 *       missing/empty/present/malformed/out-of-bounds branches.</li>
 *   <li>Raw-subtree denylist (secrets + forbidden MS-update fields).</li>
 *   <li>Bounded probeError summary — CRLF/tab strip + length cap.</li>
 * </ol>
 */
class HotfixPosturePayloadPolicyTest {

    private final HotfixPosturePayloadPolicy policy = new HotfixPosturePayloadPolicy();

    // ------------------------------------------------------------------
    // 1. Golden — contract example survives sanitize verbatim.
    // ------------------------------------------------------------------

    @Test
    void goldenExampleSurvivesSanitize() {
        Map<String, Object> sanitized = policy.sanitize(wrap(golden()));
        Map<String, Object> hp = hpOf(sanitized);

        assertThat(hp.get("schemaVersion")).isEqualTo(1);
        assertThat(hp.get("supported")).isEqualTo(true);
        assertThat(hp.get("probeComplete")).isEqualTo(true);
        assertThat(hp.get("installedSourceUsed")).isEqualTo("wua");
        assertThat(hp.get("installedCount")).isEqualTo(1);
        assertThat(hp.get("installedTruncated")).isEqualTo(false);
        // golden() has zero pending — pendingByCategory empty + sum invariant.
        assertThat(hp.get("pendingTotalCount")).isEqualTo(0);
        assertThat(hp.get("pendingTruncated")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> installed = (List<Map<String, Object>>) hp.get("installedHotfixes");
        assertThat(installed).hasSize(1);
        assertThat(installed.get(0)).containsOnlyKeys("kbId", "installedOn", "description");
        assertThat(installed.get(0).get("kbId")).isEqualTo("KB5034122");
    }

    // ------------------------------------------------------------------
    // 2. Top-level allowlist — REJECT unknown (contract-freeze).
    // ------------------------------------------------------------------

    @Test
    void unknownTopLevelKeyIsFailClosedRejected() {
        Map<String, Object> hp = golden();
        hp.put("evilExtra", "boom");
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden hotfix-posture top-level key 'evilExtra'");
    }

    @Test
    void unsupportedSchemaVersionIsFailClosedRejected() {
        Map<String, Object> hp = golden();
        hp.put("schemaVersion", 2);
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported hotfix-posture schemaVersion=2");
    }

    @Test
    void nonMapHotfixPostureIsFailClosedRejected() {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("hotfixPosture", List.of("bypass"));
        details.put("inventory", inventory);
        assertThatThrownBy(() -> policy.sanitize(details))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("present hotfix-posture block MUST be the contract object");
    }

    // ------------------------------------------------------------------
    // 3. Per-row allowlist — installed / pending / agentHealth / etc.
    // ------------------------------------------------------------------

    @Test
    void installedTitleIsFailClosedRejected() {
        Map<String, Object> hp = golden();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> installed = (List<Map<String, Object>>) hp.get("installedHotfixes");
        installed.get(0).put("title", "Security Update for Windows");
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingTitleIsFailClosedRejected() {
        Map<String, Object> hp = goldenWithPending();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pending = (List<Map<String, Object>>) hp.get("pendingUpdates");
        pending.get(0).put("title", "2026-05 Cumulative Update for Windows 11");
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pendingDeploymentActionIsFailClosedRejected() {
        Map<String, Object> hp = goldenWithPending();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pending = (List<Map<String, Object>>) hp.get("pendingUpdates");
        pending.get(0).put("deploymentAction", "Install");
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agentHealthUnknownKeyIsFailClosedRejected() {
        Map<String, Object> hp = golden();
        @SuppressWarnings("unchecked")
        Map<String, Object> ah = (Map<String, Object>) hp.get("agentHealth");
        ah.put("rawRegistry", "HKLM\\SOFTWARE\\Policies\\...");
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // 4. Enum / regex enforcement
    // ------------------------------------------------------------------

    @Test
    void malformedKbIdIsFailClosedRejected() {
        Map<String, Object> hp = golden();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> installed = (List<Map<String, Object>>) hp.get("installedHotfixes");
        installed.get(0).put("kbId", "5034122"); // missing 'KB' prefix
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected ^KB[0-9]{4,10}$");
    }

    @Test
    void unsupportedInstalledSourceUsedIsFailClosedRejected() {
        Map<String, Object> hp = golden();
        hp.put("installedSourceUsed", "psgallery");
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported value 'psgallery'");
    }

    @Test
    void unsupportedServiceStateIsFailClosedRejected() {
        Map<String, Object> hp = golden();
        @SuppressWarnings("unchecked")
        Map<String, Object> ah = (Map<String, Object>) hp.get("agentHealth");
        ah.put("wuaServiceState", "PAUSED");
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedPendingCategoryIsFailClosedRejected() {
        Map<String, Object> hp = goldenWithPending();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pending = (List<Map<String, Object>>) hp.get("pendingUpdates");
        pending.get(0).put("primaryCategory", "QUARANTINE");
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedSeverityIsFailClosedRejected() {
        Map<String, Object> hp = goldenWithPending();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pending = (List<Map<String, Object>>) hp.get("pendingUpdates");
        pending.get(0).put("severity", "CATASTROPHIC");
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // 5. Count/cap invariants — Codex iter-3 P1.3.
    // ------------------------------------------------------------------

    @Test
    void installedTruncatedFalseRequiresExactCountMatch() {
        Map<String, Object> hp = golden();
        hp.put("installedCount", 99); // claim 99 but only 1 child row
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("installedTruncated=false but installedCount=99");
    }

    @Test
    void installedTruncatedTrueAllowsCountAboveChildCount() {
        Map<String, Object> hp = golden();
        hp.put("installedCount", 600);
        hp.put("installedTruncated", true);
        assertThatCode(() -> policy.sanitize(wrap(hp))).doesNotThrowAnyException();
    }

    @Test
    void pendingByCategorySumMustMatchPendingTotalCount() {
        Map<String, Object> hp = goldenWithPending();
        // pendingTotalCount=1; pendingByCategory[].count claims 5 → mismatch
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byCat = (List<Map<String, Object>>) hp.get("pendingByCategory");
        byCat.get(0).put("count", 5);
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pendingByCategory rollup mismatch");
    }

    @Test
    void duplicatePendingByCategoryCategoryIsFailClosedRejected() {
        Map<String, Object> hp = goldenWithPending();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byCat = (List<Map<String, Object>>) hp.get("pendingByCategory");
        // Add second SECURITY row → duplicate.
        Map<String, Object> dupe = new LinkedHashMap<>();
        dupe.put("category", "SECURITY");
        dupe.put("count", 0);
        byCat.add(dupe);
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate pendingByCategory.category");
    }

    @Test
    void tooManyInstalledHotfixesIsFailClosedRejected() {
        Map<String, Object> hp = golden();
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 513; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kbId", "KB" + (1000000 + i));
            row.put("installedOn", null);
            row.put("description", null);
            tooMany.add(row);
        }
        hp.put("installedHotfixes", tooMany);
        hp.put("installedCount", 513);
        hp.put("installedTruncated", false);
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Too many installedHotfixes");
    }

    @Test
    void tooManyPendingUpdatesIsFailClosedRejected() {
        Map<String, Object> hp = goldenWithPending();
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kbIds", List.of("KB" + (5000000 + i)));
            row.put("primaryCategory", "SECURITY");
            row.put("severity", "CRITICAL");
            tooMany.add(row);
        }
        hp.put("pendingUpdates", tooMany);
        hp.put("pendingTotalCount", 21);
        hp.put("pendingTruncated", false);
        // Update rollup to match.
        List<Map<String, Object>> byCat = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("category", "SECURITY");
        row.put("count", 21);
        byCat.add(row);
        hp.put("pendingByCategory", byCat);
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Too many pendingUpdates");
    }

    // ------------------------------------------------------------------
    // 6. notification_level — Codex iter-3 P1.4: verbatim bounded.
    // ------------------------------------------------------------------

    @Test
    void notificationLevelEmptyNormalizesToNull() {
        Map<String, Object> hp = golden();
        @SuppressWarnings("unchecked")
        Map<String, Object> ah = (Map<String, Object>) hp.get("agentHealth");
        ah.put("notificationLevel", "");
        Map<String, Object> sanitized = policy.sanitize(wrap(hp));
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitizedAh = (Map<String, Object>) hpOf(sanitized).get("agentHealth");
        assertThat(sanitizedAh.get("notificationLevel")).isNull();
    }

    @Test
    void notificationLevelMissingPersistsNull() {
        Map<String, Object> hp = golden();
        @SuppressWarnings("unchecked")
        Map<String, Object> ah = (Map<String, Object>) hp.get("agentHealth");
        ah.remove("notificationLevel");
        Map<String, Object> sanitized = policy.sanitize(wrap(hp));
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitizedAh = (Map<String, Object>) hpOf(sanitized).get("agentHealth");
        assertThat(sanitizedAh.get("notificationLevel")).isNull();
    }

    @Test
    void notificationLevelValidPersistsVerbatim() {
        Map<String, Object> hp = golden();
        @SuppressWarnings("unchecked")
        Map<String, Object> ah = (Map<String, Object>) hp.get("agentHealth");
        ah.put("notificationLevel", "4");
        Map<String, Object> sanitized = policy.sanitize(wrap(hp));
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitizedAh = (Map<String, Object>) hpOf(sanitized).get("agentHealth");
        assertThat(sanitizedAh.get("notificationLevel")).isEqualTo("4");
    }

    @Test
    void notificationLevelMalformedIsFailClosedRejected() {
        Map<String, Object> hp = golden();
        @SuppressWarnings("unchecked")
        Map<String, Object> ah = (Map<String, Object>) hp.get("agentHealth");
        ah.put("notificationLevel", "abc");
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid notificationLevel 'abc'");
    }

    @Test
    void notificationLevelOutOfBoundsIsFailClosedRejected() {
        Map<String, Object> hp = golden();
        @SuppressWarnings("unchecked")
        Map<String, Object> ah = (Map<String, Object>) hp.get("agentHealth");
        ah.put("notificationLevel", "12345");
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // 7. Raw-subtree denylist
    // ------------------------------------------------------------------

    @Test
    void productCodeAnywhereInTreeIsFailClosedRejected() {
        Map<String, Object> hp = golden();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> installed = (List<Map<String, Object>>) hp.get("installedHotfixes");
        installed.get(0).put("productCode", "{ABCDEFGH-1234-5678-9012-3456789012AB}");
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("substring 'productcode'");
    }

    @Test
    void bearerTokenInProbeErrorSummaryIsFailClosedRejected() {
        Map<String, Object> hp = golden();
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", "COM_FAILED");
        err.put("summary", "Authorization: Bearer eyJABC123longeryeahveryverylong");
        hp.put("probeErrors", new ArrayList<>(List.of(err)));
        hp.put("probeComplete", false);
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Secret value pattern detected");
    }

    // ------------------------------------------------------------------
    // 8. Bounded probeError summary
    // ------------------------------------------------------------------

    @Test
    void probeErrorSummaryCrlfIsStripped() {
        Map<String, Object> hp = golden();
        hp.put("probeComplete", false);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", "COM_FAILED");
        err.put("summary", "line1\r\nline2\tline3");
        hp.put("probeErrors", new ArrayList<>(List.of(err)));
        Map<String, Object> sanitized = policy.sanitize(wrap(hp));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projectedErrors =
                (List<Map<String, Object>>) hpOf(sanitized).get("probeErrors");
        String summary = (String) projectedErrors.get(0).get("summary");
        assertThat(summary).doesNotContain("\r").doesNotContain("\n").doesNotContain("\t");
    }

    @Test
    void probeErrorSummaryCappedAt200Chars() {
        Map<String, Object> hp = golden();
        hp.put("probeComplete", false);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", "COM_FAILED");
        err.put("summary", "x".repeat(500));
        hp.put("probeErrors", new ArrayList<>(List.of(err)));
        Map<String, Object> sanitized = policy.sanitize(wrap(hp));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projectedErrors =
                (List<Map<String, Object>>) hpOf(sanitized).get("probeErrors");
        assertThat(((String) projectedErrors.get(0).get("summary")).length()).isLessThanOrEqualTo(200);
    }

    // ------------------------------------------------------------------
    // 9. Agent-side `omitempty` shape (Codex 019e822b post-impl P1)
    // ------------------------------------------------------------------
    // The merged platform-agent (PR #45) marshals these fields with
    // `omitempty`: installedHotfixes, installedTruncated, pendingUpdates,
    // pendingByCategory, pendingTruncated, probeErrors. Real Windows
    // clean-posture + non-Windows stub payloads omit these when empty/
    // false. The backend MUST accept the missing keys and normalize
    // them on the projection.

    @Test
    void agentCleanNoPendingOmitemptyShapeSurvivesSanitize() {
        Map<String, Object> hp = new LinkedHashMap<>();
        hp.put("schemaVersion", 1);
        hp.put("supported", true);
        hp.put("probeComplete", true);
        hp.put("collectedAt", "2026-06-01T12:34:56Z");
        hp.put("probeDurationMs", 410);
        hp.put("installedSourceUsed", "wua");
        // installedHotfixes ABSENT (omitempty empty list).
        hp.put("installedCount", 0);
        // installedTruncated ABSENT (omitempty false).
        hp.put("pendingSourceUsed", "wua");
        // pendingUpdates ABSENT (omitempty empty list).
        // pendingByCategory ABSENT (omitempty empty list).
        hp.put("pendingTotalCount", 0);
        // pendingTruncated ABSENT (omitempty false).
        hp.put("healthSourceUsed", "composite");
        Map<String, Object> ah = new LinkedHashMap<>();
        ah.put("wuaServiceState", "RUNNING");
        ah.put("bitsServiceState", "RUNNING");
        ah.put("lastDetectAt", "2026-05-31T08:00:00Z");
        ah.put("lastInstallAt", "2026-05-30T22:00:00Z");
        ah.put("autoUpdatePolicyEnabled", true);
        ah.put("autoUpdateEffectiveEnabled", true);
        ah.put("notificationLevel", "4");
        hp.put("agentHealth", ah);
        // probeErrors ABSENT (omitempty empty list).

        assertThatCode(() -> policy.sanitize(wrap(hp))).doesNotThrowAnyException();
        Map<String, Object> sanitized = policy.sanitize(wrap(hp));
        Map<String, Object> projected = hpOf(sanitized);
        // Normalized values: empty list + false.
        assertThat((List<?>) projected.get("installedHotfixes")).isEmpty();
        assertThat(projected.get("installedTruncated")).isEqualTo(false);
        assertThat((List<?>) projected.get("pendingUpdates")).isEmpty();
        assertThat((List<?>) projected.get("pendingByCategory")).isEmpty();
        assertThat(projected.get("pendingTruncated")).isEqualTo(false);
    }

    @Test
    void unsupportedNonWindowsOmitemptyShapeSurvivesSanitize() {
        // Non-Windows stub: supported=false, probeComplete=false, source
        // attributions = "none", agentHealth UNKNOWN. Empty lists omitted
        // via `omitempty`. Persisted fail-closed as evidence (NOT
        // rejected as malformed).
        Map<String, Object> hp = new LinkedHashMap<>();
        hp.put("schemaVersion", 1);
        hp.put("supported", false);
        hp.put("probeComplete", false);
        hp.put("collectedAt", "2026-06-01T12:34:56Z");
        hp.put("probeDurationMs", 0);
        hp.put("installedSourceUsed", "none");
        hp.put("installedCount", 0);
        hp.put("pendingSourceUsed", "none");
        hp.put("pendingTotalCount", 0);
        hp.put("healthSourceUsed", "none");
        Map<String, Object> ah = new LinkedHashMap<>();
        ah.put("wuaServiceState", "UNKNOWN");
        ah.put("bitsServiceState", "UNKNOWN");
        hp.put("agentHealth", ah);
        // probeErrors PRESENT (the stub emits a single UNSUPPORTED_PLATFORM
        // error per the agent contract).
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("source", "none");
        err.put("code", "UNSUPPORTED_PLATFORM");
        hp.put("probeErrors", new ArrayList<>(List.of(err)));

        assertThatCode(() -> policy.sanitize(wrap(hp))).doesNotThrowAnyException();
        Map<String, Object> sanitized = policy.sanitize(wrap(hp));
        Map<String, Object> projected = hpOf(sanitized);
        assertThat(projected.get("supported")).isEqualTo(false);
        assertThat(projected.get("probeComplete")).isEqualTo(false);
        assertThat(projected.get("installedSourceUsed")).isEqualTo("none");
        assertThat(projected.get("pendingSourceUsed")).isEqualTo("none");
        assertThat(projected.get("healthSourceUsed")).isEqualTo("none");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) projected.get("probeErrors");
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).get("code")).isEqualTo("UNSUPPORTED_PLATFORM");
    }

    @Test
    void missingTruncationFlagsNormalizeToFalse() {
        // pending PRESENT but pendingTruncated omitted (clean
        // no-truncation default). pendingTotalCount matches list size.
        Map<String, Object> hp = goldenWithPending();
        hp.remove("pendingTruncated");
        hp.remove("installedTruncated");
        Map<String, Object> sanitized = policy.sanitize(wrap(hp));
        Map<String, Object> projected = hpOf(sanitized);
        assertThat(projected.get("pendingTruncated")).isEqualTo(false);
        assertThat(projected.get("installedTruncated")).isEqualTo(false);
    }

    @Test
    void nonZeroPendingTotalWithMissingByCategoryStillRejects() {
        // Invariant guard: pendingByCategory absent (normalize to empty)
        // + pendingTotalCount > 0 → sum(0) != totalCount → REJECT.
        Map<String, Object> hp = new LinkedHashMap<>();
        hp.put("schemaVersion", 1);
        hp.put("supported", true);
        hp.put("probeComplete", true);
        hp.put("installedSourceUsed", "wua");
        hp.put("installedCount", 0);
        hp.put("pendingSourceUsed", "wua");
        hp.put("pendingTotalCount", 5); // claims 5 pending but list/rollup absent
        hp.put("healthSourceUsed", "composite");
        Map<String, Object> ah = new LinkedHashMap<>();
        ah.put("wuaServiceState", "RUNNING");
        ah.put("bitsServiceState", "RUNNING");
        hp.put("agentHealth", ah);
        assertThatThrownBy(() -> policy.sanitize(wrap(hp)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void absentHotfixPostureBlockIsValidOptOut() {
        // No hotfixPosture at all — service ingest hook gates with
        // hasHotfixPostureBlock so this is the legitimate "lightweight"
        // path. Policy must NOT reject details lacking the block.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", new LinkedHashMap<>());
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    @Test
    void explicitNullHotfixPostureBlockIsValidOptOut() {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("hotfixPosture", null);
        details.put("inventory", inventory);
        assertThatCode(() -> policy.sanitize(details)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Map<String, Object> wrap(Map<String, Object> hp) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("hotfixPosture", hp);
        details.put("inventory", inventory);
        return details;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hpOf(Map<String, Object> details) {
        Map<String, Object> inv = (Map<String, Object>) details.get("inventory");
        return (Map<String, Object>) inv.get("hotfixPosture");
    }

    /** Minimal golden — one installed, zero pending (clean state). */
    private static Map<String, Object> golden() {
        Map<String, Object> hp = new LinkedHashMap<>();
        hp.put("schemaVersion", 1);
        hp.put("supported", true);
        hp.put("probeComplete", true);
        hp.put("collectedAt", "2026-06-01T12:34:56Z");
        hp.put("probeDurationMs", 410);
        hp.put("installedSourceUsed", "wua");
        Map<String, Object> installed = new LinkedHashMap<>();
        installed.put("kbId", "KB5034122");
        installed.put("installedOn", "2026-01-15T00:00:00Z");
        installed.put("description", "Security Update for Microsoft Windows");
        hp.put("installedHotfixes", new ArrayList<>(List.of(installed)));
        hp.put("installedCount", 1);
        hp.put("installedTruncated", false);
        hp.put("pendingSourceUsed", "wua");
        hp.put("pendingUpdates", new ArrayList<>());
        hp.put("pendingByCategory", new ArrayList<>());
        hp.put("pendingTotalCount", 0);
        hp.put("pendingTruncated", false);
        hp.put("healthSourceUsed", "composite");
        Map<String, Object> agentHealth = new LinkedHashMap<>();
        agentHealth.put("wuaServiceState", "RUNNING");
        agentHealth.put("bitsServiceState", "RUNNING");
        agentHealth.put("lastDetectAt", "2026-05-31T08:00:00Z");
        agentHealth.put("lastInstallAt", "2026-05-30T22:00:00Z");
        agentHealth.put("autoUpdatePolicyEnabled", true);
        agentHealth.put("autoUpdateEffectiveEnabled", true);
        agentHealth.put("notificationLevel", "4");
        hp.put("agentHealth", agentHealth);
        hp.put("probeErrors", new ArrayList<>());
        return hp;
    }

    /** Golden with one pending update + rollup. */
    private static Map<String, Object> goldenWithPending() {
        Map<String, Object> hp = golden();
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("kbIds", new ArrayList<>(List.of("KB5036899")));
        pending.put("primaryCategory", "SECURITY");
        pending.put("severity", "CRITICAL");
        hp.put("pendingUpdates", new ArrayList<>(List.of(pending)));
        Map<String, Object> rollup = new LinkedHashMap<>();
        rollup.put("category", "SECURITY");
        rollup.put("count", 1);
        hp.put("pendingByCategory", new ArrayList<>(List.of(rollup)));
        hp.put("pendingTotalCount", 1);
        return hp;
    }
}
