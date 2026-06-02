package com.example.endpointadmin.grid;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.endpointadmin.grid.DeviceGridColumns.ColumnType;
import com.example.endpointadmin.grid.DeviceGridColumns.GridColumn;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * WEB-015 v2-a (Codex 019e8785 iter-2) — DeviceGridColumns registry tests.
 *
 * <p>Pinned invariants:
 * <ul>
 *   <li>{@link DeviceGridColumns#SCHEMA_VERSION} = 3 (bump from 2).</li>
 *   <li>Registry carries the 5 new BE-025 + AG-041 column ids in the
 *       expected canonical (raw-export) order, after the existing
 *       AG-036 columns.</li>
 *   <li>SQL expressions reference the {@code pe} and {@code ac}
 *       LATERAL aliases (not interpolated client input — the registry
 *       is the security spine).</li>
 *   <li>JSONB-safe array length expression is the canonical
 *       {@code jsonb_typeof = 'array'} guard form.</li>
 *   <li>{@link DeviceGridColumns#columnIdsHash()} is stable, lowercase
 *       hex SHA-256 over the comma-joined column ids.</li>
 * </ul>
 */
class DeviceGridColumnsTest {

    /** WEB-015 v2-a (schema v3) — BE-025 prohibited + AG-041 app-control. */
    private static final List<String> EXPECTED_V2A_COL_IDS = List.of(
            "prohibited_status",
            "prohibited_decision",
            "prohibited_findings_count",
            "app_control_wdac_mode",
            "app_control_app_id_svc_state");

    /** WEB-015 v2-b (schema v4) — AG-038 diagnostics + AG-040 startup + AG-039 services. */
    private static final List<String> EXPECTED_V2B_COL_IDS = List.of(
            "diagnostics_last_poll_latency_ms",
            "diagnostics_last_error_code",
            "diagnostics_last_error_at",
            "startup_rdp_enabled",
            "startup_windows_firewall_event_log_enabled",
            "services_critical_stopped_count");

    /**
     * WEB-015 v2-d (schema v5, Codex 019e8a39 iter-1 plan AGREE) — BE-024c
     * DiffCache 9 cache-fed colIds appended after services_critical_stopped_count.
     */
    private static final List<String> EXPECTED_V2D_COL_IDS = List.of(
            "software_diff_status",
            "software_diff_added_count",
            "software_diff_removed_count",
            "software_diff_version_changed_count",
            "outdated_diff_status",
            "outdated_diff_added_count",
            "outdated_diff_removed_count",
            "outdated_diff_version_changed_count",
            "outdated_diff_available_version_bumped_count");

    @Test
    void schemaVersionIsFive() {
        // Codex 019e8a39 iter-2 must-fix #P1 absorb: bump 4→5 was missed in
        // the runtime constant but this stale test was still pinning 4. The
        // service runtime emits DeviceGridColumns.SCHEMA_VERSION into the
        // audit metadata so a stale test here would have shipped a v4-labelled
        // payload (web mfe would mis-detect drift).
        assertThat(DeviceGridColumns.SCHEMA_VERSION).isEqualTo(5);
    }

    @Test
    void registryAppendsV2aColumnsAfterOutdatedColumns() {
        List<String> ids = DeviceGridColumns.allColumnIds();
        int outdatedCollectedAtIdx = ids.indexOf("outdated_collected_at");
        assertThat(outdatedCollectedAtIdx).isPositive();
        assertThat(ids.subList(outdatedCollectedAtIdx + 1, outdatedCollectedAtIdx + 1 + 5))
                .containsExactlyElementsOf(EXPECTED_V2A_COL_IDS);
    }

    @Test
    void registryAppendsV2bColumnsAfterAppControlColumns() {
        List<String> ids = DeviceGridColumns.allColumnIds();
        int appCtlSvcIdx = ids.indexOf("app_control_app_id_svc_state");
        assertThat(appCtlSvcIdx).isPositive();
        assertThat(ids.subList(appCtlSvcIdx + 1, appCtlSvcIdx + 1 + 6))
                .containsExactlyElementsOf(EXPECTED_V2B_COL_IDS);
    }

    @Test
    void registryAppendsV2dCacheColumnsAfterServicesColumn() {
        // Codex 019e8a39 iter-2 ask: pin v5 colId order. The 9 cache-fed
        // columns must immediately follow services_critical_stopped_count
        // so the canonical (raw-export) order stays stable.
        List<String> ids = DeviceGridColumns.allColumnIds();
        int servicesIdx = ids.indexOf("services_critical_stopped_count");
        assertThat(servicesIdx).isPositive();
        assertThat(ids.subList(servicesIdx + 1, servicesIdx + 1 + 9))
                .containsExactlyElementsOf(EXPECTED_V2D_COL_IDS);
    }

    @Test
    void v5_softwareDiffStatus_isEnumOnSdcStatus() {
        GridColumn c = DeviceGridColumns.byId("software_diff_status");
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo(ColumnType.ENUM);
        assertThat(c.sqlExpr()).isEqualTo("sdc.status");
    }

    @Test
    void v5_outdatedDiffAvailableVersionBumpedCount_isNumberOnOdcAlias() {
        GridColumn c = DeviceGridColumns.byId("outdated_diff_available_version_bumped_count");
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo(ColumnType.NUMBER);
        assertThat(c.sqlExpr()).isEqualTo("odc.available_version_bumped_count");
    }

    @Test
    void prohibitedStatusUsesCaseExpressionOverPeId() {
        GridColumn c = DeviceGridColumns.byId("prohibited_status");
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo(ColumnType.ENUM);
        assertThat(c.sqlExpr())
                .contains("CASE WHEN pe.id IS NULL THEN 'NO_EVALUATION' ELSE 'OK' END");
    }

    @Test
    void prohibitedDecisionReadsPersistedDecision() {
        GridColumn c = DeviceGridColumns.byId("prohibited_decision");
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo(ColumnType.ENUM);
        assertThat(c.sqlExpr()).isEqualTo("pe.decision");
    }

    @Test
    void prohibitedFindingsCountUsesJsonbDefensiveArrayLength() {
        GridColumn c = DeviceGridColumns.byId("prohibited_findings_count");
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo(ColumnType.NUMBER);
        assertThat(c.sqlExpr())
                .contains("jsonb_typeof(pe.evidence #> '{matchedItems,prohibitedInstalled}') = 'array'")
                .contains("jsonb_array_length(pe.evidence #> '{matchedItems,prohibitedInstalled}')")
                .contains("WHEN pe.id IS NULL THEN NULL");
    }

    @Test
    void appControlColumnsReadFromAcAliasAndLeaveNullPathOnNoSnapshot() {
        GridColumn wdac = DeviceGridColumns.byId("app_control_wdac_mode");
        assertThat(wdac).isNotNull();
        assertThat(wdac.type()).isEqualTo(ColumnType.ENUM);
        assertThat(wdac.sqlExpr()).isEqualTo("ac.wdac_mode");

        GridColumn svc = DeviceGridColumns.byId("app_control_app_id_svc_state");
        assertThat(svc).isNotNull();
        assertThat(svc.sqlExpr()).isEqualTo("ac.app_locker_app_id_svc_state");
    }

    @Test
    void diagnosticsLatencyReadsDxAlias() {
        GridColumn c = DeviceGridColumns.byId("diagnostics_last_poll_latency_ms");
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo(ColumnType.NUMBER);
        assertThat(c.sqlExpr()).isEqualTo("dx.last_poll_latency_ms");
    }

    @Test
    void diagnosticsLastErrorCodeIsTextNotEnum() {
        // Codex 019e87bc iter-1 must_fix #2: NOT a closed enum tuple. The
        // V23 DB CHECK enforces only the lexical regex `^[A-Z][A-Z0-9_]{2,64}$`,
        // so the surface MUST be TEXT (free Set Filter labels would silently
        // drop unmodelled codes like NEXT_COMMAND_TIMEOUT / DNS_TIMEOUT).
        GridColumn c = DeviceGridColumns.byId("diagnostics_last_error_code");
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo(ColumnType.TEXT);
        assertThat(c.sqlExpr()).isEqualTo("dx.last_error_code");
    }

    @Test
    void diagnosticsLastErrorAtReadsCanonicalDbColumn() {
        // Codex 019e87bc iter-1 must_fix #3: payload field is `lastError.occurredAt`,
        // DB scalar is `last_error_occurred_at`. UI surface "last_error_at"
        // is OK but the SQL source must address the V23 column verbatim.
        GridColumn c = DeviceGridColumns.byId("diagnostics_last_error_at");
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo(ColumnType.TIMESTAMP);
        assertThat(c.sqlExpr()).isEqualTo("dx.last_error_occurred_at");
    }

    @Test
    void startupSentinelsCaseGuardNullForNotMeasurableYet() {
        // Codex 019e87bc iter-1 must_fix #4: V25 boolean columns are NOT NULL
        // (fail-closed evidence: supported=false persisted). CASE-guard so
        // the grid projects NULL for no-snapshot / unsupported / probe-incomplete
        // — false would silently hide the difference between "evidence: RDP
        // off" and "not measurable yet".
        GridColumn rdp = DeviceGridColumns.byId("startup_rdp_enabled");
        assertThat(rdp).isNotNull();
        assertThat(rdp.type()).isEqualTo(ColumnType.BOOLEAN);
        assertThat(rdp.sqlExpr())
                .contains("WHEN sx.id IS NULL")
                .contains("OR sx.supported = false")
                .contains("OR sx.probe_complete = false")
                .contains("THEN NULL")
                .contains("ELSE sx.rdp_enabled");

        GridColumn fw = DeviceGridColumns.byId("startup_windows_firewall_event_log_enabled");
        assertThat(fw).isNotNull();
        assertThat(fw.type()).isEqualTo(ColumnType.BOOLEAN);
        assertThat(fw.sqlExpr())
                .contains("WHEN sx.id IS NULL")
                .contains("ELSE sx.windows_firewall_event_log_enabled");
    }

    @Test
    void servicesCriticalStoppedCountReadsPrecomputedSeColumn() {
        // Codex 019e87bc iter-1 must_fix #5: only "stopped" count surfaces
        // (allowlist enforced server-side at ingest; ServicesPayloadPolicy).
        // CASE guard mirrors startup sentinels (NULL for not-measurable-yet,
        // never 0 — Codex iter-1 must_fix #4).
        GridColumn c = DeviceGridColumns.byId("services_critical_stopped_count");
        assertThat(c).isNotNull();
        assertThat(c.type()).isEqualTo(ColumnType.NUMBER);
        assertThat(c.sqlExpr())
                .contains("WHEN se.id IS NULL")
                .contains("OR se.supported = false")
                .contains("OR se.probe_complete = false")
                .contains("THEN NULL")
                .contains("ELSE se.critical_stopped_count");
    }

    @Test
    void columnIdsHashIsLowercaseHexSha256AndStable() {
        String h1 = DeviceGridColumns.columnIdsHash();
        String h2 = DeviceGridColumns.columnIdsHash();
        assertThat(h1).matches("^[0-9a-f]{64}$");
        assertThat(h2).isEqualTo(h1);
    }

    @Test
    void columnIdsHashMatchesCanonicalCommaJoinedIds() {
        String expected = sha256Hex(String.join(",", DeviceGridColumns.allColumnIds()));
        assertThat(DeviceGridColumns.columnIdsHash()).isEqualTo(expected);
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
