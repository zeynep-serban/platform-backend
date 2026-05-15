package com.example.permission.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * R16 PR-B-2 (Codex 019e27f5 önerisi) — TupleSyncService report_group
 * key-aware mapping unit test.
 *
 * <p>R15 regression guard: yeni reportGroup keys eklendiğinde TupleSyncService
 * `REPORT_GROUP_KEYS` set'i güncel kalmalı; aksi halde permission yazılır ama
 * OpenFGA tuple `report` type'a düşer ve FE filter görmez.
 */
class TupleSyncServiceReportGroupMappingTest {

    @Test
    void normalizeReportGroupKey_stripsReportsPrefix() {
        assertThat(TupleSyncService.normalizeReportGroupKey("reports.FINANCE_REPORTS"))
                .isEqualTo("FINANCE_REPORTS");
        assertThat(TupleSyncService.normalizeReportGroupKey("reports.HR_REPORTS"))
                .isEqualTo("HR_REPORTS");
    }

    @Test
    void normalizeReportGroupKey_passesThroughNonPrefixedKey() {
        // Dashboard keys (FIN_ANALYTICS, HR_DEMOGRAFIK) prefix yok; olduğu gibi
        assertThat(TupleSyncService.normalizeReportGroupKey("FIN_ANALYTICS"))
                .isEqualTo("FIN_ANALYTICS");
        assertThat(TupleSyncService.normalizeReportGroupKey("HR_EXECUTIVE_SUMMARY"))
                .isEqualTo("HR_EXECUTIVE_SUMMARY");
    }

    @Test
    void normalizeReportGroupKey_handlesNullAndEmpty() {
        assertThat(TupleSyncService.normalizeReportGroupKey(null)).isNull();
        assertThat(TupleSyncService.normalizeReportGroupKey("")).isEmpty();
    }

    @Test
    void isReportGroupKey_reportsPrefixWithGroupSuffix_returnsTrue() {
        assertThat(TupleSyncService.isReportGroupKey("reports.FINANCE_REPORTS")).isTrue();
        assertThat(TupleSyncService.isReportGroupKey("reports.HR_REPORTS")).isTrue();
        assertThat(TupleSyncService.isReportGroupKey("reports.SALES_REPORTS")).isTrue();
        assertThat(TupleSyncService.isReportGroupKey("reports.ANALYTICS_REPORTS")).isTrue();
    }

    @Test
    void isReportGroupKey_rawGroupKey_returnsTrue() {
        // Direkt FINANCE_REPORTS de geçerli (TupleSyncService write tarafı
        // raw kullanabilir; normalize sonrası set membership check).
        assertThat(TupleSyncService.isReportGroupKey("FINANCE_REPORTS")).isTrue();
        assertThat(TupleSyncService.isReportGroupKey("HR_REPORTS")).isTrue();
    }

    @Test
    void isReportGroupKey_dashboardKeys_returnsFalse() {
        // Dashboard keys (PermissionType.REPORT ama report_group değil)
        assertThat(TupleSyncService.isReportGroupKey("FIN_ANALYTICS")).isFalse();
        assertThat(TupleSyncService.isReportGroupKey("HR_DEMOGRAFIK")).isFalse();
        assertThat(TupleSyncService.isReportGroupKey("HR_EXECUTIVE_SUMMARY")).isFalse();
    }

    @Test
    void isReportGroupKey_unknownKey_returnsFalse() {
        assertThat(TupleSyncService.isReportGroupKey("reports.UNKNOWN_GROUP")).isFalse();
        assertThat(TupleSyncService.isReportGroupKey("RANDOM_KEY")).isFalse();
        assertThat(TupleSyncService.isReportGroupKey(null)).isFalse();
        assertThat(TupleSyncService.isReportGroupKey("")).isFalse();
    }

    @Test
    void REPORT_GROUP_KEYS_setIsComplete() {
        // Bu test set'in 4 expected key içerdiğini doğrular; yeni reportGroup
        // eklenirse R15 close-out checklist'i tetikler.
        assertThat(TupleSyncService.REPORT_GROUP_KEYS).containsExactlyInAnyOrder(
                "FINANCE_REPORTS", "HR_REPORTS", "SALES_REPORTS", "ANALYTICS_REPORTS");
    }

    // --- R16 PR-B-2 Codex 019e2a13 REVISE P1 absorb tests ---

    @Test
    void isReportGroupKey_rawAndPrefixed_bothTrue_logicalEquivalence() {
        // Raw `FINANCE_REPORTS` (V18 migration legacy) ve prefixed
        // `reports.FINANCE_REPORTS` (PR-B-2 yeni) aynı logical group.
        // İkisi de aynı OpenFGA `report_group:FINANCE_REPORTS` tuple'a
        // yazılmalı (deny-wins merge AuthorizationControllerV1'da).
        assertThat(TupleSyncService.isReportGroupKey("FINANCE_REPORTS")).isTrue();
        assertThat(TupleSyncService.isReportGroupKey("reports.FINANCE_REPORTS")).isTrue();
        // Normalize identical
        assertThat(TupleSyncService.normalizeReportGroupKey("FINANCE_REPORTS"))
                .isEqualTo(TupleSyncService.normalizeReportGroupKey("reports.FINANCE_REPORTS"));
    }
}
