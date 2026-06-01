package com.example.report.authz;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Maps legacy permission codes ({@code REPORT_VIEW}, {@code reports.hr.salary-view},
 * {@code dashboards.fin-analytics.view}, ...) to OpenFGA relationship tuples
 * {@code (relation, objectType, objectId)}.
 *
 * <p>PR6c-1 (CNS-20260416-003) — centralised alias registry. Legacy call sites
 * ({@link AuthzMeResponse#hasPermission(String)}, {@code ReportAccessEvaluator},
 * {@code ColumnFilter}) are preserved verbatim; only the resolver rebuilds the
 * permissions list from OpenFGA tuples via this mapper.
 *
 * <p>Resolution order (first match wins):
 * <ol>
 *   <li>Static module/action aliases ({@link #STATIC_ALIASES}).</li>
 *   <li>Scope-marker aliases — {@code *.all-companies/all-stores/all-warehouses}
 *       ({@link #SCOPE_MARKER_ALIASES}).</li>
 *   <li>Dashboard aliases — {@code dashboards.{key}.view} outliers that need a
 *       specific OpenFGA object id ({@link #DASHBOARD_ALIASES}).</li>
 *   <li>Column / sub-permission aliases — {@code *.financials},
 *       {@code *.salary-view}, {@code *.costs} ({@link #COLUMN_ALIASES}).</li>
 *   <li>Generic prefix patterns:
 *       <ul>
 *         <li>{@code reports.<key>.view → (can_view, report, <KEY_UPPER>)}</li>
 *         <li>{@code dashboards.<key>.view → (can_view, report, <KEY_UPPER>)}
 *             — dashboards share the {@code report} type bucket (no separate
 *             {@code dashboard} type in the Zanzibar model).</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Unknown codes return {@link Optional#empty()} → callers must treat as
 * deny-default (fail-closed) matching the legacy
 * {@code AuthzMeResponse.hasPermission()} semantics when the permissions list
 * does not contain the code.
 *
 * <p>Naming convention must stay aligned with {@code
 * RolePermissionGranuleDefaults} and {@code TupleSyncService} in
 * permission-service — drift here causes silent deny.
 */
@Component
public class PermissionCodeToTupleMapper {

    public record Tuple(String relation, String objectType, String objectId) {}

    private static final String TYPE_MODULE = "module";
    private static final String TYPE_ACTION = "action";
    private static final String TYPE_REPORT = "report";

    private static final String REL_CAN_VIEW = "can_view";
    private static final String REL_CAN_MANAGE = "can_manage";
    private static final String REL_ALLOWED = "allowed";

    /** Module- and action-level static aliases. Shape mirrors permission-service. */
    private static final Map<String, Tuple> STATIC_ALIASES;

    /**
     * Scope-marker aliases — {@code reports.<scope>.all-<target>}. These do not
     * follow the generic {@code reports.<key>.view} pattern and must be declared
     * explicitly to stay in sync with permission-service naming.
     */
    private static final Map<String, Tuple> SCOPE_MARKER_ALIASES;

    /**
     * Dashboard outlier aliases — {@code dashboards.<key>.view} entries whose
     * OpenFGA object id differs from the generic upper-snake conversion.
     */
    private static final Map<String, Tuple> DASHBOARD_ALIASES;

    /**
     * Column- or sub-permission aliases — sub-grants like {@code *.financials},
     * {@code *.costs}, {@code *.salary-view}. Mapped to {@code action} tuples so
     * that row/column restriction logic can use the same deny-wins semantics.
     */
    private static final Map<String, Tuple> COLUMN_ALIASES;

    static {
        // ---- Module-level ---------------------------------------------------
        Map<String, Tuple> staticMap = new LinkedHashMap<>();
        staticMap.put("REPORT_VIEW",   new Tuple(REL_CAN_VIEW,   TYPE_MODULE, "REPORT"));
        staticMap.put("REPORT_MANAGE", new Tuple(REL_CAN_MANAGE, TYPE_MODULE, "REPORT"));
        staticMap.put("REPORT_EXPORT", new Tuple(REL_ALLOWED,    TYPE_ACTION, "REPORT_EXPORT"));
        // PR-D2.2 (ADR-0015, Codex 019e83f0 PARTIAL absorb): access-view alias
        // for the ACCESS module's can_view relation. Used by access-report
        // ReportDefinition.access.permission so catalog visibility mirrors
        // the downstream permission-service @RequireModule(value="ACCESS",
        // relation="can_view") gate at /api/v1/roles.
        staticMap.put("access-view",   new Tuple(REL_CAN_VIEW,   TYPE_MODULE, "ACCESS"));
        // PR-D2.3 (ADR-0015, Codex 019e83fd): audit-view alias for the
        // AUDIT module's can_view relation. Used by audit-report
        // ReportDefinition.access.permission so catalog visibility mirrors
        // the downstream permission-service @RequireModule(value="AUDIT",
        // relation="can_view") gate at /api/audit/events.
        staticMap.put("audit-view",    new Tuple(REL_CAN_VIEW,   TYPE_MODULE, "AUDIT"));
        STATIC_ALIASES = Collections.unmodifiableMap(staticMap);

        // ---- Scope markers --------------------------------------------------
        Map<String, Tuple> scopeMap = new LinkedHashMap<>();
        // HR family
        scopeMap.put("reports.hr.all-companies", new Tuple(REL_CAN_VIEW, TYPE_REPORT, "HR_ALL_COMPANIES"));
        // Finance family
        scopeMap.put("reports.fin.all-companies", new Tuple(REL_CAN_VIEW, TYPE_REPORT, "FIN_ALL_COMPANIES"));
        // Sales report + store marker
        scopeMap.put("reports.satis-ozet.all-stores", new Tuple(REL_CAN_VIEW, TYPE_REPORT, "SATIS_OZET_ALL_STORES"));
        // Inventory report + warehouse marker
        scopeMap.put("reports.stok-durum.all-warehouses", new Tuple(REL_CAN_VIEW, TYPE_REPORT, "STOK_DURUM_ALL_WAREHOUSES"));
        SCOPE_MARKER_ALIASES = Collections.unmodifiableMap(scopeMap);

        // ---- Dashboard outliers --------------------------------------------
        // Dashboards live in the `report` type bucket — no separate `dashboard`
        // type in the current model. Explicit aliases keep the object ids
        // aligned with permission-service without silent prefix-strip.
        Map<String, Tuple> dashboardMap = new LinkedHashMap<>();
        dashboardMap.put("dashboards.fin-analytics.view",   new Tuple(REL_CAN_VIEW, TYPE_REPORT, "FIN_ANALYTICS"));
        dashboardMap.put("dashboards.hr-analytics.view",    new Tuple(REL_CAN_VIEW, TYPE_REPORT, "HR_ANALYTICS"));
        dashboardMap.put("dashboards.hr-finansal.view",     new Tuple(REL_CAN_VIEW, TYPE_REPORT, "HR_FINANSAL"));
        dashboardMap.put("dashboards.hr-compensation.view", new Tuple(REL_CAN_VIEW, TYPE_REPORT, "HR_COMPENSATION"));
        DASHBOARD_ALIASES = Collections.unmodifiableMap(dashboardMap);

        // ---- Column- and sub-permission aliases ---------------------------
        Map<String, Tuple> columnMap = new LinkedHashMap<>();
        // HR salary column (gates maaş columns inside several HR reports)
        columnMap.put("reports.hr.salary-view", new Tuple(REL_ALLOWED, TYPE_ACTION, "HR_SALARY_VIEW"));
        // Sales financial columns
        columnMap.put("reports.satis-ozet.financials", new Tuple(REL_ALLOWED, TYPE_ACTION, "SATIS_OZET_FINANCIALS"));
        // Inventory cost columns
        columnMap.put("reports.stok-durum.costs", new Tuple(REL_ALLOWED, TYPE_ACTION, "STOK_DURUM_COSTS"));
        // Finance invoice financial columns
        columnMap.put("reports.fin-faturalar.financials", new Tuple(REL_ALLOWED, TYPE_ACTION, "FIN_FATURALAR_FINANCIALS"));
        // Finance invoice-line financial columns (fin-fatura-satirlari.json)
        columnMap.put("reports.fin-fatura-satirlari.financials",
                new Tuple(REL_ALLOWED, TYPE_ACTION, "FIN_FATURA_SATIRLARI_FINANCIALS"));
        COLUMN_ALIASES = Collections.unmodifiableMap(columnMap);
    }

    /**
     * Resolve a legacy permission code to an OpenFGA tuple, or
     * {@link Optional#empty()} for unknown codes (fail-closed semantics).
     */
    public Optional<Tuple> toTuple(String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return Optional.empty();
        }

        Tuple explicit = STATIC_ALIASES.get(permissionCode);
        if (explicit != null) return Optional.of(explicit);

        explicit = SCOPE_MARKER_ALIASES.get(permissionCode);
        if (explicit != null) return Optional.of(explicit);

        explicit = DASHBOARD_ALIASES.get(permissionCode);
        if (explicit != null) return Optional.of(explicit);

        explicit = COLUMN_ALIASES.get(permissionCode);
        if (explicit != null) return Optional.of(explicit);

        // Generic pattern: reports.<key>.view → (can_view, report, <KEY_UPPER>)
        if (permissionCode.startsWith("reports.") && permissionCode.endsWith(".view")) {
            String key = permissionCode.substring("reports.".length(),
                    permissionCode.length() - ".view".length());
            return Optional.of(new Tuple(REL_CAN_VIEW, TYPE_REPORT, upperSnake(key)));
        }

        // Generic pattern: dashboards.<key>.view → (can_view, report, <KEY_UPPER>)
        if (permissionCode.startsWith("dashboards.") && permissionCode.endsWith(".view")) {
            String key = permissionCode.substring("dashboards.".length(),
                    permissionCode.length() - ".view".length());
            return Optional.of(new Tuple(REL_CAN_VIEW, TYPE_REPORT, upperSnake(key)));
        }

        return Optional.empty();
    }

    /** Known module-level permission codes — used by the resolver to up-front
     *  probe which module permissions the caller currently has. Report-level
     *  codes are resolved lazily via {@code listObjects}. */
    public Set<String> knownModulePermissions() {
        return STATIC_ALIASES.keySet();
    }

    /** Known scope-marker codes — same up-front probe as
     *  {@link #knownModulePermissions()} but for the scope-marker family. */
    public Set<String> knownScopeMarkers() {
        return SCOPE_MARKER_ALIASES.keySet();
    }

    /** Known column/sub-permission codes. */
    public Set<String> knownColumnPermissions() {
        return COLUMN_ALIASES.keySet();
    }

    /** Known dashboard outlier codes. */
    public Set<String> knownDashboardPermissions() {
        return DASHBOARD_ALIASES.keySet();
    }

    private static String upperSnake(String input) {
        // Locale.ROOT — avoid Turkish locale i → İ (dotted) conversion which
        // would break "satis" → "SATİS_OZET" instead of "SATIS_OZET".
        return input.replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
