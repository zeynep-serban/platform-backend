package com.example.report.workcube;

import com.example.report.contract.schema.ReportingAllowlist;
import com.example.report.contract.schema.TableRef;
import com.example.report.contract.schema.WorkcubeSqlTableRefScanner;
import com.example.report.query.SqlBuilder;
import com.example.report.query.YearlySchemaResolver;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Phase 2 Program 11.2b — Workcube query adapter (Adım 11.2b).
 *
 * <p>Codex thread {@code 019e258f} iter-22 absorb: composes the existing
 * {@link SqlBuilder} (template render + placeholder substitution + RLS +
 * pagination) with a second-line allowlist defence on the <b>rendered</b>
 * SQL. Build-time {@code RC-011} already enforces source / sourceQuery
 * refs at contract validation, but {@code {schema}} placeholder expansion
 * and {@code {tenantSetupProcessCatRelation}}-style per-tenant lookup
 * inlining happen at render time — those final table refs must be
 * re-checked before execution.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Caller resolves authz (PermissionResolver → AuthzMeResponse) and
 *       narrows to {@code X-Company-Id} via
 *       {@link com.example.report.authz.CompanyHeaderScopeNarrower}
 *       upstream (Adım 5 TenantBoundaryGuard pre-flight).</li>
 *   <li>{@link SqlBuilder#buildDataQuery} renders SQL with visible-column
 *       projection, filter / sort allowlist already enforced.</li>
 *   <li>{@link WorkcubeSqlTableRefScanner} re-scans the rendered SQL.</li>
 *   <li>This adapter enforces {@link ReportingAllowlist#V1} membership +
 *       {@code UNKNOWN}/{@code UNQUALIFIED} fail-closed. Violations →
 *       {@link WorkcubeQuerySecurityException}.</li>
 *   <li>{@link NamedParameterJdbcTemplate} executes the validated SQL.</li>
 * </ol>
 *
 * <h2>What this adapter does NOT do</h2>
 * <ul>
 *   <li>Controller adoption — interim {@code @PreAuthorize} on
 *       {@link WorkcubeReportController} is preserved (Adım 11.3 will
 *       wire controllers to this adapter; Adım 11.4 removes the interim
 *       gate).</li>
 *   <li>Composite multi-table tenant boundary check — Adım 11.2c verifies
 *       that every tenant-partitioned ref in a JOIN belongs to the same
 *       branch.</li>
 *   <li>Projection / filter / sort identifier validation —
 *       {@link SqlBuilder}'s {@code FilterTranslator} + {@code SortTranslator}
 *       already validate identifiers against {@code visibleColumns}.</li>
 * </ul>
 */
@Service
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class WorkcubeQueryAdapter {

    private static final Logger log = LoggerFactory.getLogger(WorkcubeQueryAdapter.class);

    private final SqlBuilder sqlBuilder;
    private final NamedParameterJdbcTemplate jdbc;
    private final CompositeTenantBoundaryEnforcer compositeEnforcer;

    public WorkcubeQueryAdapter(SqlBuilder sqlBuilder,
                                @Qualifier("workcubeMssqlJdbc") NamedParameterJdbcTemplate jdbc,
                                CompositeTenantBoundaryEnforcer compositeEnforcer) {
        this.sqlBuilder = sqlBuilder;
        this.jdbc = jdbc;
        this.compositeEnforcer = compositeEnforcer;
    }

    /**
     * Execute a data query with allowlist + fail-closed enforcement on
     * the rendered SQL.
     */
    public List<Map<String, Object>> executeData(ReportDefinition def,
                                                 YearlySchemaResolver.ResolvedSchemas schemas,
                                                 List<String> visibleColumns,
                                                 Map<String, Object> agGridFilter,
                                                 List<Map<String, String>> sortModel,
                                                 String rlsWhereClause,
                                                 MapSqlParameterSource rlsParams,
                                                 int page,
                                                 int pageSize) {
        SqlBuilder.BuiltQuery built = sqlBuilder.buildDataQuery(
                def, schemas, visibleColumns, agGridFilter, sortModel,
                rlsWhereClause, rlsParams, page, pageSize);
        enforceRendered(def, built.sql());
        log.debug("WorkcubeQueryAdapter execute report={} sql_length={}",
                def.key(), built.sql().length());
        return jdbc.queryForList(built.sql(), built.params());
    }

    /**
     * Execute a count query (same enforcement pipeline).
     */
    public long executeCount(ReportDefinition def,
                             YearlySchemaResolver.ResolvedSchemas schemas,
                             Map<String, Object> agGridFilter,
                             List<String> visibleColumns,
                             String rlsWhereClause,
                             MapSqlParameterSource rlsParams) {
        SqlBuilder.BuiltQuery built = sqlBuilder.buildCountQuery(
                def, schemas, agGridFilter, visibleColumns, rlsWhereClause, rlsParams);
        enforceRendered(def, built.sql());
        Long c = jdbc.queryForObject(built.sql(), built.params(), Long.class);
        return c != null ? c : 0L;
    }

    /**
     * Run the second-line scanner on rendered SQL and fail-closed on any
     * UNKNOWN / UNQUALIFIED target or non-V1 table.
     *
     * <p>Visibility {@code package} so tests can inject SQL directly without
     * a full SqlBuilder render.
     */
    void enforceRendered(ReportDefinition def, String renderedSql) {
        // Codex iter-23 REVISE-1 blocker 1: blank/no-ref rendered SQL must
        // fail-closed. "Scanner found nothing" is not a safe success: it
        // could mean an unsupported syntax slipped past the scanner, a
        // SqlBuilder bug produced an empty payload, or a SELECT-without-FROM
        // surface that has no business reaching MSSQL.
        if (renderedSql == null || renderedSql.isBlank()) {
            throw new WorkcubeQuerySecurityException(def.key(),
                    "Rendered Workcube SQL is empty for report '" + def.key()
                            + "' — refusing to execute (template render bug suspected).");
        }
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(renderedSql);
        if (refs.isEmpty()) {
            throw new WorkcubeQuerySecurityException(def.key(),
                    "Rendered Workcube SQL contains no detectable allowlisted "
                            + "table refs for report '" + def.key()
                            + "' — refusing to execute (parser miss or non-Workcube payload).");
        }
        for (TableRef ref : refs) {
            if (ref.schemaKind() == TableRef.SchemaKind.UNKNOWN
                    || ref.schemaKind() == TableRef.SchemaKind.UNQUALIFIED) {
                throw new WorkcubeQuerySecurityException(def.key(),
                        "Rendered SQL contains unsupported or unqualified table target '"
                                + ref.raw() + "' at position " + ref.position()
                                + " for report '" + def.key() + "'. Possible placeholder "
                                + "expansion drift or template tampering — execution refused.");
            }
            if (!ReportingAllowlist.containsV1(ref.table())) {
                throw new WorkcubeQuerySecurityException(def.key(),
                        "Rendered SQL references non-allowlisted table '" + ref.table()
                                + "' (schema '" + ref.schema() + "') at position "
                                + ref.position() + " for report '" + def.key()
                                + "'. ReportingAllowlist.V1 (" + ReportingAllowlist.V1.size()
                                + " tables) does not include this table.");
            }
        }
        // Codex iter-27: composite tenant boundary check runs LAST so it
        // sees only classified + allowlisted refs (V1 + non-UNKNOWN +
        // non-UNQUALIFIED). Earlier failures surface root-cause errors;
        // composite is the cross-tenant invariant.
        compositeEnforcer.validateComposite(refs, def);
    }
}
