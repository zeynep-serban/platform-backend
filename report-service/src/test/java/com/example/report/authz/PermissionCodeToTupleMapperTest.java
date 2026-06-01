package com.example.report.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PermissionCodeToTupleMapper}. Verifies:
 * <ul>
 *   <li>Static module/action aliases (REPORT_VIEW, REPORT_MANAGE, REPORT_EXPORT)</li>
 *   <li>Scope-marker aliases (all-companies / all-stores / all-warehouses)</li>
 *   <li>Dashboard outlier aliases (fin-analytics, hr-analytics, ...)</li>
 *   <li>Column / sub-permission aliases (financials, costs, salary-view)</li>
 *   <li>Generic prefix patterns (reports.&lt;key&gt;.view, dashboards.&lt;key&gt;.view)</li>
 *   <li>Unknown / blank codes → Optional.empty() (deny-default)</li>
 * </ul>
 */
class PermissionCodeToTupleMapperTest {

    private final PermissionCodeToTupleMapper mapper = new PermissionCodeToTupleMapper();

    // ---- Static module/action aliases -----------------------------------

    @Test
    void reportView_mapsToModuleCanView() {
        var t = mapper.toTuple("REPORT_VIEW").orElseThrow();
        assertEquals("can_view", t.relation());
        assertEquals("module", t.objectType());
        assertEquals("REPORT", t.objectId());
    }

    @Test
    void reportManage_mapsToModuleCanManage() {
        var t = mapper.toTuple("REPORT_MANAGE").orElseThrow();
        assertEquals("can_manage", t.relation());
        assertEquals("module", t.objectType());
        assertEquals("REPORT", t.objectId());
    }

    @Test
    void reportExport_mapsToActionAllowed() {
        var t = mapper.toTuple("REPORT_EXPORT").orElseThrow();
        assertEquals("allowed", t.relation());
        assertEquals("action", t.objectType());
        assertEquals("REPORT_EXPORT", t.objectId());
    }

    // ---- Scope markers --------------------------------------------------

    @Test
    void hrAllCompanies_scopeMarker() {
        var t = mapper.toTuple("reports.hr.all-companies").orElseThrow();
        assertEquals("can_view", t.relation());
        assertEquals("report", t.objectType());
        assertEquals("HR_ALL_COMPANIES", t.objectId());
    }

    @Test
    void finAllCompanies_scopeMarker() {
        var t = mapper.toTuple("reports.fin.all-companies").orElseThrow();
        assertEquals("FIN_ALL_COMPANIES", t.objectId());
    }

    @Test
    void allStores_scopeMarker() {
        var t = mapper.toTuple("reports.satis-ozet.all-stores").orElseThrow();
        assertEquals("SATIS_OZET_ALL_STORES", t.objectId());
    }

    @Test
    void allWarehouses_scopeMarker() {
        var t = mapper.toTuple("reports.stok-durum.all-warehouses").orElseThrow();
        assertEquals("STOK_DURUM_ALL_WAREHOUSES", t.objectId());
    }

    // ---- Dashboard outliers --------------------------------------------

    @Test
    void finAnalyticsDashboard_outlierAlias() {
        var t = mapper.toTuple("dashboards.fin-analytics.view").orElseThrow();
        assertEquals("can_view", t.relation());
        assertEquals("report", t.objectType());
        assertEquals("FIN_ANALYTICS", t.objectId());
    }

    @Test
    void hrAnalyticsDashboard_outlierAlias() {
        var t = mapper.toTuple("dashboards.hr-analytics.view").orElseThrow();
        assertEquals("HR_ANALYTICS", t.objectId());
    }

    @Test
    void hrFinansalDashboard_outlierAlias() {
        var t = mapper.toTuple("dashboards.hr-finansal.view").orElseThrow();
        assertEquals("HR_FINANSAL", t.objectId());
    }

    // ---- Column / sub-permission aliases -------------------------------

    @Test
    void hrSalaryView_columnAlias() {
        var t = mapper.toTuple("reports.hr.salary-view").orElseThrow();
        assertEquals("allowed", t.relation());
        assertEquals("action", t.objectType());
        assertEquals("HR_SALARY_VIEW", t.objectId());
    }

    @Test
    void satisOzetFinancials_columnAlias() {
        var t = mapper.toTuple("reports.satis-ozet.financials").orElseThrow();
        assertEquals("SATIS_OZET_FINANCIALS", t.objectId());
    }

    @Test
    void stokDurumCosts_columnAlias() {
        var t = mapper.toTuple("reports.stok-durum.costs").orElseThrow();
        assertEquals("STOK_DURUM_COSTS", t.objectId());
    }

    @Test
    void finFaturalarFinancials_columnAlias() {
        var t = mapper.toTuple("reports.fin-faturalar.financials").orElseThrow();
        assertEquals("allowed", t.relation());
        assertEquals("action", t.objectType());
        assertEquals("FIN_FATURALAR_FINANCIALS", t.objectId());
    }

    @Test
    void finFaturaSatirlariFinancials_columnAlias() {
        // CNS-20260416-003 Codex round-2 blocker: this permission code is
        // referenced by fin-fatura-satirlari.json columnRestrictions and
        // must not fall into the generic Optional.empty() branch (would
        // produce silent deny in prod).
        var t = mapper.toTuple("reports.fin-fatura-satirlari.financials").orElseThrow();
        assertEquals("allowed", t.relation());
        assertEquals("action", t.objectType());
        assertEquals("FIN_FATURA_SATIRLARI_FINANCIALS", t.objectId());
    }

    // ---- Generic prefix patterns ---------------------------------------

    @Test
    void reportsViewGeneric_upperSnake() {
        var t = mapper.toTuple("reports.satis-ozet.view").orElseThrow();
        assertEquals("can_view", t.relation());
        assertEquals("report", t.objectType());
        assertEquals("SATIS_OZET", t.objectId());
    }

    @Test
    void dashboardsViewGeneric_upperSnake() {
        var t = mapper.toTuple("dashboards.unknown-dash.view").orElseThrow();
        assertEquals("can_view", t.relation());
        assertEquals("report", t.objectType());
        assertEquals("UNKNOWN_DASH", t.objectId());
    }

    @Test
    void reportsViewMultiDash_allSegmentsUppered() {
        var t = mapper.toTuple("reports.hr-maas-raporu.view").orElseThrow();
        assertEquals("HR_MAAS_RAPORU", t.objectId());
    }

    // ---- Unknown / edge cases ------------------------------------------

    @Test
    void unknownCode_returnsEmpty() {
        assertTrue(mapper.toTuple("SOMETHING_RANDOM").isEmpty());
    }

    @Test
    void nullCode_returnsEmpty() {
        assertTrue(mapper.toTuple(null).isEmpty());
    }

    @Test
    void blankCode_returnsEmpty() {
        assertTrue(mapper.toTuple("   ").isEmpty());
    }

    @Test
    void reportsPrefixWithoutView_returnsEmpty() {
        // reports.foo but without .view suffix and not in any explicit alias
        assertTrue(mapper.toTuple("reports.foo.something-else").isEmpty());
    }

    @Test
    void explicitAliasBeatsGenericPattern() {
        // dashboards.fin-analytics.view has an explicit alias; generic pattern
        // would also match but must not be used.
        Optional<PermissionCodeToTupleMapper.Tuple> t = mapper.toTuple("dashboards.fin-analytics.view");
        assertTrue(t.isPresent());
        assertEquals("FIN_ANALYTICS", t.get().objectId());
    }

    // ---- Known-set accessors --------------------------------------------

    @Test
    void knownModulePermissions_hasFiveCoreEntries() {
        // PR-D2.2 (ADR-0015, Codex 019e83f0): added "access-view".
        // PR-D2.3 (ADR-0015, Codex 019e83fd): added "audit-view".
        var known = mapper.knownModulePermissions();
        assertTrue(known.contains("REPORT_VIEW"));
        assertTrue(known.contains("REPORT_MANAGE"));
        assertTrue(known.contains("REPORT_EXPORT"));
        assertTrue(known.contains("access-view"));
        assertTrue(known.contains("audit-view"));
        assertEquals(5, known.size());
    }

    @Test
    void accessViewAlias_mapsToAccessModuleCanView() {
        // PR-D2.2 absorb: access-view → (can_view, module, ACCESS)
        var t = mapper.toTuple("access-view").orElseThrow();
        assertEquals("can_view", t.relation());
        assertEquals("module", t.objectType());
        assertEquals("ACCESS", t.objectId());
    }

    @Test
    void auditViewAlias_mapsToAuditModuleCanView() {
        // PR-D2.3 absorb: audit-view → (can_view, module, AUDIT)
        var t = mapper.toTuple("audit-view").orElseThrow();
        assertEquals("can_view", t.relation());
        assertEquals("module", t.objectType());
        assertEquals("AUDIT", t.objectId());
    }

    @Test
    void knownScopeMarkers_containsAllCompaniesAllStoresAllWarehouses() {
        var known = mapper.knownScopeMarkers();
        assertTrue(known.contains("reports.hr.all-companies"));
        assertTrue(known.contains("reports.fin.all-companies"));
        assertTrue(known.contains("reports.satis-ozet.all-stores"));
        assertTrue(known.contains("reports.stok-durum.all-warehouses"));
    }

    @Test
    void knownDashboardPermissions_containsFinAndHrOutliers() {
        var known = mapper.knownDashboardPermissions();
        assertTrue(known.contains("dashboards.fin-analytics.view"));
        assertTrue(known.contains("dashboards.hr-analytics.view"));
    }

    @Test
    void knownColumnPermissions_containsSalaryAndFinancials() {
        var known = mapper.knownColumnPermissions();
        assertTrue(known.contains("reports.hr.salary-view"));
        assertTrue(known.contains("reports.satis-ozet.financials"));
    }

    @Test
    void knownSets_areDisjoint() {
        // Sanity: a code must not appear in more than one known set.
        var allSets = new java.util.ArrayList<java.util.Set<String>>();
        allSets.add(mapper.knownModulePermissions());
        allSets.add(mapper.knownScopeMarkers());
        allSets.add(mapper.knownDashboardPermissions());
        allSets.add(mapper.knownColumnPermissions());
        for (int i = 0; i < allSets.size(); i++) {
            for (int j = i + 1; j < allSets.size(); j++) {
                for (String code : allSets.get(i)) {
                    assertFalse(allSets.get(j).contains(code),
                            "Code " + code + " appears in multiple known-sets");
                }
            }
        }
    }
}
