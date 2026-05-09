package com.example.schema.dto;

/**
 * Codex 019dda1c iter-29 + iter-30: typed payload for the master-data
 * internal read endpoint ({@code GET /api/v1/schema/master-data/{kind}}).
 *
 * <p>iter-30 added the optional {@code code} field — a human-friendly
 * identifier (PROJECT_NUMBER, COMPANY_SHORT_CODE, SPECIAL_CODE) shown
 * alongside the name in the drawer so admins can disambiguate rows that
 * share similar names. Null-safe; the drawer renders the code as a
 * subtle prefix when present and falls back to plain name otherwise.
 *
 * <p>PR-BE-15 (2026-05-09): three optional parent-FK fields added so
 * the frontend can compose a hierarchical scope picker view
 * (UserDetailDrawer "Veri Erişimi" → Hiyerarşik mode). Source
 * relationships from workcube_mikrolink schema dump (1774 FKs):
 *
 * <ul>
 *   <li>{@code parentCompanyId} — value is the parent OUR_COMPANY's
 *       primary key. NB: that PK column is named {@code COMP_ID} in
 *       workcube (NOT {@code OUR_COMPANY_ID}); other tables carry an
 *       {@code OUR_COMPANY_ID} FK column whose value targets
 *       {@code OUR_COMPANY.COMP_ID}. The API field name
 *       {@code parentCompanyId} stays generic; semantics is "the
 *       OUR_COMPANY this row hangs under". Resolution:
 *     <ul>
 *       <li>OUR_COMPANY rows: NULL (top of hierarchy)</li>
 *       <li>PRO_PROJECTS: via {@code PRO_PROJECTS.COMPANY_ID →
 *           COMPANY.OUR_COMPANY_ID → OUR_COMPANY.COMP_ID} JOIN</li>
 *       <li>BRANCH: via {@code BRANCH.COMPANY_ID →
 *           COMPANY.OUR_COMPANY_ID → OUR_COMPANY.COMP_ID} JOIN</li>
 *       <li>DEPARTMENT (warehouse): direct {@code OUR_COMPANY_ID} FK
 *           column (target {@code OUR_COMPANY.COMP_ID}); also has
 *           {@code BRANCH_ID} for a grandparent join, but
 *           {@code OUR_COMPANY_ID} is the canonical parent for the
 *           picker</li>
 *     </ul>
 *   </li>
 *   <li>{@code parentBranchId} — direct {@code BRANCH_ID} FK. Used
 *     by DEPARTMENT (warehouse → branch) and PRO_PROJECTS (project →
 *     primary branch).</li>
 *   <li>{@code parentProjectId} — currently always NULL (no scope
 *     type is project-scoped today). Reserved for future granular
 *     scopes (e.g. project-tasks) without another DTO bump.</li>
 * </ul>
 *
 * <p>All three fields are nullable; consumers MUST handle missing
 * parents (orphan items belong to the "no parent" bucket in the
 * hierarchical view). Pre-PR-BE-15 callers receive NULLs and
 * continue to render the flat-list view unchanged.
 *
 * <p>Backed by direct SQL against the workcube_mikrolink MSSQL schema.
 */
public record MasterDataItemDto(
        Long id,
        String code,
        String name,
        Long parentCompanyId,
        Long parentBranchId,
        Long parentProjectId,
        boolean status) {

    /**
     * PR-BE-15 backward-compat constructor for callers (and tests)
     * that haven't been updated to supply parent FKs. Defaults the
     * three new fields to NULL — equivalent to the pre-PR-BE-15
     * flat-list contract.
     */
    public MasterDataItemDto(Long id, String code, String name, boolean status) {
        this(id, code, name, null, null, null, status);
    }
}
