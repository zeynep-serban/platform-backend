package com.example.schema.service;

import com.example.schema.dto.MasterDataItemDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Codex 019dda1c iter-29: master-data live MSSQL reader. Picks the same
 * MSSQL connection that {@link SchemaExtractService} uses for metadata
 * extraction and runs an allowlist of SELECT queries — one per scope kind
 * the platform supports (companies/projects/branches/departments).
 *
 * <p>Identifier injection guard: schema and table names are NEVER taken
 * from user input. The {@code kind} string maps via a fixed enum-like
 * switch to a hard-coded {@link TableMapping}; dynamic SQL is not built
 * from request payload. Limit is configurable but capped server-side.
 *
 * <p>Failure mode: if the MSSQL connection fails or the table is missing,
 * the service logs a warning and returns an empty list. Callers (the
 * permission-service proxy) translate this into the same empty UX the
 * Postgres path produced.
 */
@Service
public class MasterDataReadService {

    private static final Logger log = LoggerFactory.getLogger(MasterDataReadService.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final String schemaName;
    private final int rowLimit;

    public MasterDataReadService(
            NamedParameterJdbcTemplate jdbc,
            @Value("${schema.master-data.schema:${schema.default-schema:workcube_mikrolink}}") String schemaName,
            @Value("${schema.master-data.limit:50000}") int rowLimit) {
        this.jdbc = jdbc;
        this.schemaName = schemaName;
        // Codex 019dda1c iter-30f: cap raised from 5000 → 100000 because the
        // user reported projects and warehouses are still missing rows. Live
        // Workcube cardinalities exceed the previous 5k cap (projects sit
        // around 5k+ in production tenants). The hard cap is a safety net,
        // not a paging boundary; the public default in @Value above is 50k
        // which covers every observed table on testai.
        this.rowLimit = Math.min(Math.max(rowLimit, 1), 100000);
    }

    /**
     * Codex 019dda1c iter-30: full SQL templates per kind. iter-29 pre-image
     * tried to mechanically compose tableName + idCol + nameCol; that doesn't
     * fit the real schema once you need a JOIN (DEPARTMENT name lives in
     * SETUP_DEPARTMENT_NAME) or a code column on top of name. The template
     * uses {@code %d} for the configurable row limit; placeholders here come
     * from a static allowlist, never from request input.
     *
     * <p>Each query:
     * <ul>
     *   <li>aliases columns to {@code id, code, name, status} (matches DTO)</li>
     *   <li>filters out rows whose name is NULL or empty (no blank checkboxes)</li>
     *   <li>orders by name then id for stable UI</li>
     * </ul>
     */
    private record TableMapping(String sqlTemplate) {}

    private static final Map<String, TableMapping> KIND_MAP = Map.of(
            // PR-BE-15 (2026-05-09): all four templates now project the
            // canonical parent FKs (parentCompanyId, parentBranchId,
            // parentProjectId) so the frontend can compose a
            // hierarchical scope-picker view. Parent resolution follows
            // the workcube_mikrolink real FK graph (1774 relations
            // dump). Naming gotcha: OUR_COMPANY's primary key column is
            // [COMP_ID], NOT [OUR_COMPANY_ID]. Other tables carry an
            // [OUR_COMPANY_ID] FK column whose value targets
            // OUR_COMPANY.[COMP_ID] — a workcube convention oddity.
            // Refs: V25 schema contract; codex thread 019e0de4 iter-1
            // P1 absorb (this exact alias was wrong in iter-1).
            //
            //   OUR_COMPANY (PK COMP_ID) ← COMPANY (FK OUR_COMPANY_ID)
            //     ← BRANCH (FK COMPANY_ID)
            //     ← PRO_PROJECTS (FK COMPANY_ID)
            //     ← DEPARTMENT (FK OUR_COMPANY_ID, FK BRANCH_ID)
            //
            // OUR_COMPANY rows are top-level — parent fields NULL.
            // PRO_PROJECTS / BRANCH go through COMPANY.OUR_COMPANY_ID
            // (their direct FK to COMPANY); OUR_COMPANY is the picker's
            // "company" type so we lift the parent up by one hop with
            // the JOIN. DEPARTMENT carries OUR_COMPANY_ID directly as
            // an FK (target OUR_COMPANY.COMP_ID). parentProjectId is
            // always NULL today — reserved for future project-scoped
            // permission types without another DTO bump.
            "companies",   new TableMapping("""
                    SELECT TOP (%1$d)
                        c.[COMP_ID]            AS id,
                        c.[COMPANY_SHORT_CODE] AS code,
                        c.[COMPANY_NAME]       AS name,
                        CAST(NULL AS BIGINT)   AS parentCompanyId,
                        CAST(NULL AS BIGINT)   AS parentBranchId,
                        CAST(NULL AS BIGINT)   AS parentProjectId,
                        COALESCE(c.[COMP_STATUS], 1) AS status
                    FROM [%2$s].[OUR_COMPANY] c
                    WHERE c.[COMPANY_NAME] IS NOT NULL AND LEN(LTRIM(RTRIM(c.[COMPANY_NAME]))) > 0
                    ORDER BY c.[COMPANY_NAME], c.[COMP_ID]
                    """),
            "projects",    new TableMapping("""
                    SELECT TOP (%1$d)
                        p.[PROJECT_ID]        AS id,
                        p.[PROJECT_NUMBER]    AS code,
                        p.[PROJECT_HEAD]      AS name,
                        oc.[COMP_ID]          AS parentCompanyId,
                        p.[BRANCH_ID]         AS parentBranchId,
                        CAST(NULL AS BIGINT)  AS parentProjectId,
                        COALESCE(p.[PROJECT_STATUS], 1) AS status
                    FROM [%2$s].[PRO_PROJECTS] p
                    LEFT JOIN [%2$s].[COMPANY] cmp ON cmp.[COMPANY_ID] = p.[COMPANY_ID]
                    LEFT JOIN [%2$s].[OUR_COMPANY] oc ON oc.[COMP_ID] = cmp.[OUR_COMPANY_ID]
                    WHERE p.[PROJECT_HEAD] IS NOT NULL AND LEN(LTRIM(RTRIM(p.[PROJECT_HEAD]))) > 0
                    ORDER BY p.[PROJECT_HEAD], p.[PROJECT_ID]
                    """),
            "branches",    new TableMapping("""
                    SELECT TOP (%1$d)
                        b.[BRANCH_ID]         AS id,
                        CAST(NULL AS NVARCHAR(64)) AS code,
                        b.[BRANCH_NAME]       AS name,
                        oc.[COMP_ID]          AS parentCompanyId,
                        CAST(NULL AS BIGINT)  AS parentBranchId,
                        CAST(NULL AS BIGINT)  AS parentProjectId,
                        COALESCE(b.[BRANCH_STATUS], 1) AS status
                    FROM [%2$s].[BRANCH] b
                    LEFT JOIN [%2$s].[COMPANY] cmp ON cmp.[COMPANY_ID] = b.[COMPANY_ID]
                    LEFT JOIN [%2$s].[OUR_COMPANY] oc ON oc.[COMP_ID] = cmp.[OUR_COMPANY_ID]
                    WHERE b.[BRANCH_NAME] IS NOT NULL AND LEN(LTRIM(RTRIM(b.[BRANCH_NAME]))) > 0
                    ORDER BY b.[BRANCH_NAME], b.[BRANCH_ID]
                    """),
            // DEPARTMENT name resolution is multi-source. iter-30 first cut
            // assumed SETUP_DEPARTMENT_NAME would always carry the label,
            // but live smoke returned [] because either SETUP_DEPARTMENT_NAME
            // is sparsely populated or DEPARTMENT._DEPARTMENT_NAME_ID is
            // mostly NULL.
            //
            // iter-30f: name source priority swapped — DEPARTMENT_HEAD now
            // comes BEFORE DEPARTMENT_DETAIL. The Workcube screenshot the
            // user shared shows the "Depolama Alanları" page rendering
            // values like "Çanakkale Hilton Deposu", "2022_P05_Maslak Veri
            // Merkezi" — those are DEPARTMENT_HEAD entries (Workcube uses
            // "head" as "title", not "manager"). DEPARTMENT_DETAIL is the
            // free-form description column where users sometimes paste
            // addresses (the "10035 Sokak No:5 ..." rows in iter-30b/30c
            // smoke). Picking HEAD before DETAIL recovers the human label.
            //
            // SETUP_DEPARTMENT_NAME lookup stays first (most authoritative
            // when populated). DEPARTMENT_DETAIL is the last-resort fallback.
            //
            // IS_STORE=1 filter preserved (iter-30c) so HR org-units and
            // production zones don't leak into the warehouse picker.
            "departments", new TableMapping("""
                    SELECT TOP (%1$d)
                        d.[DEPARTMENT_ID]      AS id,
                        d.[SPECIAL_CODE]       AS code,
                        COALESCE(
                            NULLIF(LTRIM(RTRIM(dn.[DEPARTMENT_NAME])), ''),
                            NULLIF(LTRIM(RTRIM(d.[DEPARTMENT_HEAD])), ''),
                            NULLIF(LTRIM(RTRIM(d.[DEPARTMENT_DETAIL])), '')
                        ) AS name,
                        d.[OUR_COMPANY_ID]     AS parentCompanyId,
                        d.[BRANCH_ID]          AS parentBranchId,
                        CAST(NULL AS BIGINT)   AS parentProjectId,
                        COALESCE(d.[DEPARTMENT_STATUS], 1) AS status
                    FROM [%2$s].[DEPARTMENT] d
                    LEFT JOIN [%2$s].[SETUP_DEPARTMENT_NAME] dn
                        ON dn.[DEPARTMENT_NAME_ID] = d.[_DEPARTMENT_NAME_ID]
                    WHERE d.[IS_STORE] = 1
                      AND COALESCE(
                            NULLIF(LTRIM(RTRIM(dn.[DEPARTMENT_NAME])), ''),
                            NULLIF(LTRIM(RTRIM(d.[DEPARTMENT_HEAD])), ''),
                            NULLIF(LTRIM(RTRIM(d.[DEPARTMENT_DETAIL])), '')
                          ) IS NOT NULL
                    ORDER BY name, d.[DEPARTMENT_ID]
                    """)
    );

    public List<MasterDataItemDto> list(String kind) {
        String normalized = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        TableMapping mapping = KIND_MAP.get(normalized);
        if (mapping == null) {
            throw new IllegalArgumentException("Unknown master-data kind: " + kind);
        }

        // Schema name + limit interpolated from server-side allowlist/config —
        // never from request input. SQL injection guard relies on KIND_MAP
        // being the closed allowlist (no kind → 400 above).
        String sql = String.format(Locale.ROOT, mapping.sqlTemplate(), rowLimit, schemaName);

        try {
            return jdbc.query(sql, Map.of(), (rs, rowNum) -> {
                // PR-BE-15 (2026-05-09): rs.getLong returns 0 for SQL NULL,
                // so we have to ask wasNull() to distinguish "real id 0"
                // from "no parent". Top-level OUR_COMPANY rows always
                // resolve to NULL parents; project/branch/department may
                // resolve to NULL when the source row has a missing FK
                // (legacy data, soft-deletes, etc.).
                long parentCompanyRaw = rs.getLong("parentCompanyId");
                Long parentCompanyId = rs.wasNull() ? null : parentCompanyRaw;
                long parentBranchRaw = rs.getLong("parentBranchId");
                Long parentBranchId = rs.wasNull() ? null : parentBranchRaw;
                long parentProjectRaw = rs.getLong("parentProjectId");
                Long parentProjectId = rs.wasNull() ? null : parentProjectRaw;
                return new MasterDataItemDto(
                        rs.getLong("id"),
                        rs.getString("code"),
                        rs.getString("name"),
                        parentCompanyId,
                        parentBranchId,
                        parentProjectId,
                        rs.getBoolean("status"));
            });
        } catch (DataAccessException ex) {
            log.warn("MasterData live MSSQL read failed for kind={} schema={}; reason={}",
                    normalized, schemaName,
                    ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage());
            return Collections.emptyList();
        }
    }
}
