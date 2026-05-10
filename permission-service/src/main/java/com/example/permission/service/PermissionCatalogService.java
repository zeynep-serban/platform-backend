package com.example.permission.service;

import com.example.permission.dto.v1.PermissionCatalogDto;
import com.example.permission.dto.v1.PermissionCatalogDto.ModuleCatalogItem;
import com.example.permission.dto.v1.PermissionCatalogDto.ActionCatalogItem;
import com.example.permission.dto.v1.PermissionCatalogDto.ReportCatalogItem;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Provides the permission catalog — all available permission granules.
 * Replaces hardcoded MODULE_KEYS across the codebase.
 * Initially code-defined; can be migrated to DB-driven later.
 */
@Service
public class PermissionCatalogService {

    private static final List<ModuleCatalogItem> MODULES = List.of(
            new ModuleCatalogItem("USER_MANAGEMENT", "Kullanıcı Yönetimi", List.of("VIEW", "MANAGE")),
            new ModuleCatalogItem("ACCESS", "Erişim Yönetimi", List.of("VIEW", "MANAGE")),
            new ModuleCatalogItem("AUDIT", "Denetim", List.of("VIEW", "MANAGE")),
            // PR-D2 (User Impersonation v1): IMPERSONATION_AUDIT is intentionally
            // separated from AUDIT so an AUDIT viewer/manager CANNOT see
            // impersonation events. ADMIN role gets MANAGE seeded by default
            // (PermissionDataInitializer.DEFAULT_ROLE_GRANULES).
            new ModuleCatalogItem("IMPERSONATION_AUDIT", "Impersonation Denetim", List.of("VIEW", "MANAGE")),
            new ModuleCatalogItem("REPORT", "Raporlama", List.of("VIEW", "MANAGE")),
            new ModuleCatalogItem("WAREHOUSE", "Depo", List.of("VIEW", "MANAGE")),
            new ModuleCatalogItem("PURCHASE", "Satın Alma", List.of("VIEW", "MANAGE")),
            new ModuleCatalogItem("THEME", "Tema", List.of("VIEW", "MANAGE"))
    );

    private static final List<ActionCatalogItem> ACTIONS = List.of(
            new ActionCatalogItem("APPROVE_PURCHASE", "Satın Alma Onay", "PURCHASE", true),
            new ActionCatalogItem("CREATE_PO", "Sipariş Oluştur", "PURCHASE", true),
            new ActionCatalogItem("DELETE_PO", "Sipariş Sil", "PURCHASE", true),
            new ActionCatalogItem("RESET_PASSWORD", "Parola Sıfırla", "USER_MANAGEMENT", true),
            new ActionCatalogItem("DELETE_USER", "Kullanıcı Sil", "USER_MANAGEMENT", true),
            new ActionCatalogItem("TOGGLE_STATUS", "Durum Değiştir", "USER_MANAGEMENT", true)
    );

    // Codex 019dda1c iter-26: dashboard catalog sync (Plan C+ — statik manifest
    // + drift guard). Mirrors report-service/src/main/resources/dashboards/*.json
    // entries. Each row pairs the upper-snake permission key persisted in DB
    // (role_permissions.permission_key) with the Turkish display title and
    // category from the source JSON. Coarse module gate is "REPORT" for every
    // dashboard — fine-grained access flows through the per-key granule
    // (e.g. REPORT:HR_ANALYTICS:VIEW).
    //
    // Drift guard: PermissionCatalogServiceDashboardSyncTest validates this
    // list against the dashboard JSON files at build time. Adding a new
    // dashboard JSON without updating this list will fail CI.
    //
    // Legacy report group keys (HR_REPORTS, FINANCE_REPORTS, ANALYTICS_REPORTS,
    // SALES_REPORTS, PURCHASE_SUMMARY, WAREHOUSE_STOCK) retired here because:
    //   - the drawer cannot render group keys alongside per-dashboard keys
    //     without confusing the user (which one wins?);
    //   - per-dashboard keys are now the canonical contract going forward;
    //   - existing role grants on group keys remain in role_permissions but
    //     no longer surface in the catalog. Migration to expand them into
    //     per-dashboard grants is a separate task.
    private static final List<ReportCatalogItem> REPORTS = List.of(
            // Category: İnsan Kaynakları (9 dashboards, source: hr-*.json)
            new ReportCatalogItem("HR_ANALYTICS", "İK Analitik Dashboard", "REPORT", "İnsan Kaynakları"),
            new ReportCatalogItem("HR_FINANSAL", "İK Finansal Dashboard", "REPORT", "İnsan Kaynakları"),
            new ReportCatalogItem("HR_EQUITY_RISK", "İç Denge ve Risk", "REPORT", "İnsan Kaynakları"),
            new ReportCatalogItem("HR_BENEFITS_LITE", "Yan Haklar Analitiği", "REPORT", "İnsan Kaynakları"),
            new ReportCatalogItem("HR_COMPENSATION", "Ücret ve Yan Haklar Analitiği", "REPORT", "İnsan Kaynakları"),
            new ReportCatalogItem("HR_SALARY_ANALYTICS", "Ücret Analitiği", "REPORT", "İnsan Kaynakları"),
            new ReportCatalogItem("HR_PAYROLL_TRENDS", "Bordro Trendleri", "REPORT", "İnsan Kaynakları"),
            new ReportCatalogItem("HR_DEMOGRAFIK", "İK Demografik Yapı", "REPORT", "İnsan Kaynakları"),
            new ReportCatalogItem("HR_EXECUTIVE_SUMMARY", "Yönetici Özeti", "REPORT", "İnsan Kaynakları"),
            // Category: Finans (3 dashboards, source: fin-*.json)
            new ReportCatalogItem("FIN_ANALYTICS", "Finans Analitik Dashboard", "REPORT", "Finans"),
            new ReportCatalogItem("FIN_RATIOS", "Finansal Oran Analizi", "REPORT", "Finans"),
            new ReportCatalogItem("FIN_RECONCILIATION", "Tutar Mutabakat Kontrolü", "REPORT", "Finans")
    );

    public PermissionCatalogDto getCatalog() {
        return new PermissionCatalogDto(MODULES, ACTIONS, REPORTS);
    }

    public List<String> getModuleKeys() {
        return MODULES.stream().map(ModuleCatalogItem::key).toList();
    }

    /**
     * Returns the Turkish user-facing label for a canonical module key
     * (matches /v1/authz/catalog seed). Used by AccessRoleService to
     * produce /v1/roles policies[].moduleLabel from a canonical key.
     */
    public Optional<String> getModuleLabel(String moduleKey) {
        if (moduleKey == null) return Optional.empty();
        return MODULES.stream()
                .filter(m -> m.key().equals(moduleKey))
                .map(ModuleCatalogItem::label)
                .findFirst();
    }
}
