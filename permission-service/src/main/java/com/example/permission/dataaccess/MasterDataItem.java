package com.example.permission.dataaccess;

/**
 * Lightweight DTO for master data scope picker dropdowns.
 *
 * <p>iter-30 added an optional {@code code} field (PROJECT_NUMBER,
 * COMPANY_SHORT_CODE, SPECIAL_CODE) so the drawer can show a code prefix
 * alongside the name and the admin can disambiguate similarly-named rows.
 *
 * <p>PR-BE-15 (2026-05-09): three optional parent-FK fields added so the
 * frontend (UserDetailDrawer "Veri Erişimi" → Hiyerarşik mode) can
 * compose a tree view of {@code OUR_COMPANY → projects/branches/
 * warehouses}. Field semantics mirror the schema-service
 * {@code MasterDataItemDto}:
 *
 * <ul>
 *   <li>{@code parentCompanyId} — parent OUR_COMPANY_ID. NULL for
 *     OUR_COMPANY rows themselves; populated for projects/branches
 *     (via {@code COMPANY.OUR_COMPANY_ID} JOIN) and departments
 *     (direct {@code OUR_COMPANY_ID} column).</li>
 *   <li>{@code parentBranchId} — direct {@code BRANCH_ID} for
 *     departments and projects when present.</li>
 *   <li>{@code parentProjectId} — reserved for future project-scoped
 *     permission types; always NULL today.</li>
 * </ul>
 *
 * <p>All three are nullable; pre-PR-BE-15 callers that constructed the
 * 4-arg form via the legacy reports_db Postgres mirror path receive
 * NULLs for the new fields and continue to render the flat-list view
 * unchanged.
 *
 * @param id              workcube source PK (numeric, e.g. COMP_ID, PROJECT_ID)
 * @param code            optional natural code (nullable; not every entity has one)
 * @param name            display name (Turkish locale, fallback empty string)
 * @param parentCompanyId optional parent OUR_COMPANY id (nullable)
 * @param parentBranchId  optional parent BRANCH id (nullable)
 * @param parentProjectId reserved, always null today
 * @param status          active/inactive flag (true = aktif)
 */
public record MasterDataItem(
        Long id,
        String code,
        String name,
        Long parentCompanyId,
        Long parentBranchId,
        Long parentProjectId,
        boolean status) {

    /**
     * PR-BE-15 backward-compat 4-arg constructor for callers that haven't
     * been updated to supply parent FKs (e.g. the iter-30 path that
     * predates parentCompanyId).
     */
    public MasterDataItem(Long id, String code, String name, boolean status) {
        this(id, code, name, null, null, null, status);
    }

    /**
     * Original 3-arg constructor for the legacy reports_db Postgres mirror
     * service. Defaults code AND parent FKs to null — equivalent to the
     * earliest pre-iter-30 contract.
     */
    public MasterDataItem(Long id, String name, boolean status) {
        this(id, null, name, null, null, null, status);
    }
}
