package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.contract.schema.BuildTimeSchemaExistenceLookup;
import com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup;
import com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.CoverageStatus;
import com.example.report.contract.schema.TableRef;
import com.example.report.contract.schema.TenantColumnAllowlist;
import com.example.report.contract.schema.WorkcubeSqlTableRefScanner;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RC-004 — rowFilter.scopeType=COMPANY column allowlist + schema truth
 * existence cross-check.
 *
 * <p>Phase 2 Program 1d (Codex iter-4 §1d-AGREE absorb): allowlist match
 * established. Phase 2 Program 2c (Codex iter-15 §2c-AGREE absorb):
 * schema truth existence cross-check wired (consumes 2b
 * {@link BuildTimeYearlySchemaCoverageLookup}).
 *
 * <p>Fail modes (4 distinct categories):
 * <ul>
 *   <li>{@code COMPANY rowFilter requires resolvable source table for
 *       allowlist validation; sourceQuery alone is not sufficient}</li>
 *   <li>{@code Column 'X' not in tenant column allowlist for source 'Y'}
 *       (allowlist miss)</li>
 *   <li>{@code SCHEMA_TRUTH_COVERAGE_MISSING: snapshot does not cover
 *       schema/table 'Y'} — 2b coverage artifact incomplete; cannot
 *       authoritatively assert column existence (governance/coverage
 *       problem, not report-definition problem)</li>
 *   <li>{@code Column 'X' not found in schema truth for table 'Y'}
 *       (column absent in covered table — report-definition problem)</li>
 * </ul>
 *
 * <p>Coverage lookup uses the report's first resolvable schema (yearly
 * partition resolution happens at runtime via {@code YearlySchemaResolver};
 * for build-time RC-004 we use the canonical pattern with the report's
 * sourceSchema + the table from def.source()). When the lookup is null,
 * existence check is skipped (1d backward-compat path).
 *
 * <p>Backward-compat constructor (no lookup) skips the existence check;
 * 1d test fixtures keep passing without a full coverage artifact.
 */
public final class RC004RowFilterColumnAllowlisted implements ContractRule {

    private final TenantColumnAllowlist allowlist;
    private final BuildTimeSchemaExistenceLookup existenceLookup;

    /**
     * RC-004 v2 (Codex 019e3f5c): the deliberately narrow set of base tables a
     * {@code sourceQuery} report may declare for a projected-join company
     * boundary. Widening this set is a deliberate future change.
     */
    private static final Set<String> RC004_V2_BASE_TABLES =
            Set.of("OFFTIME", "EMPLOYEES_SALARY", "EMPLOYEES_SALARY_HISTORY");

    /**
     * RC-004 v2 (Codex 019e3f5c): forbidden wildcard projection forms. The
     * sourceQuery is masked + upper-cased before these are applied.
     */
    private static final Pattern WILDCARD_SELECT =
            Pattern.compile("\\bSELECT\\s+(?:(?:DISTINCT|ALL)\\s+)?"
                    + "(?:TOP\\s*(?:\\(\\s*\\d+\\s*\\)|\\d+)(?:\\s+PERCENT)?\\s+)?\\*");
    private static final Pattern WILDCARD_QUALIFIED =
            Pattern.compile("[A-Z_][A-Z0-9_]*\\.\\*");

    /**
     * RC-004 v2 (Codex 019e3fdd): clause keywords that may legitimately follow
     * the canonical projected-join clause on the SAME single query. Anything
     * else — OR / AND (extends the join predicate), a further JOIN, or a set
     * operator UNION / EXCEPT / INTERSECT (appends a second, unvalidated query
     * whose projected company column could be a constant) — is rejected.
     */
    private static final Pattern SAFE_JOIN_TAIL =
            Pattern.compile("(WHERE|GROUP|ORDER|HAVING|OPTION)\\b");

    /**
     * RC-004 v2 (Codex 019e3fdd): SQL set operators. A depth-0 occurrence
     * means the sourceQuery is more than one SELECT — the appended query is
     * not validated by RC-004 and its projected company column could be a
     * constant.
     */
    private static final Pattern SET_OPERATOR =
            Pattern.compile("\\b(UNION|EXCEPT|INTERSECT)\\b");

    public RC004RowFilterColumnAllowlisted(TenantColumnAllowlist allowlist,
                                            BuildTimeSchemaExistenceLookup existenceLookup) {
        this.allowlist = allowlist;
        this.existenceLookup = existenceLookup;
    }

    /**
     * Phase 2 Program 2c iter-18 absorb: legacy 2-arg constructor accepting
     * only the yearly coverage lookup. Wraps it in the unified existence
     * lookup with no canonical fallback (caller-supplied yearly only).
     * Production gate uses the unified 3-arg constructor via
     * {@link com.example.report.contract.ContractValidator#withDefaultRules(TenantColumnAllowlist, BuildTimeSchemaExistenceLookup)}.
     */
    public RC004RowFilterColumnAllowlisted(TenantColumnAllowlist allowlist,
                                            BuildTimeYearlySchemaCoverageLookup yearlyOnly) {
        this(allowlist, yearlyOnly == null
                ? null
                : new BuildTimeSchemaExistenceLookup(null, yearlyOnly));
    }

    /** Backward-compat: allowlist only (1d behavior, no existence check). */
    public RC004RowFilterColumnAllowlisted(TenantColumnAllowlist allowlist) {
        this(allowlist, (BuildTimeSchemaExistenceLookup) null);
    }

    /**
     * No-arg constructor for backward compatibility (1a tests + factory).
     * Behaves like an empty allowlist + no existence check — every
     * legitimate COMPANY rowFilter column will FAIL, so callers must
     * inject the real allowlist for production gate use.
     */
    public RC004RowFilterColumnAllowlisted() {
        this(new TenantColumnAllowlist(java.util.Map.of()), (BuildTimeSchemaExistenceLookup) null);
    }

    @Override
    public String ruleId() {
        return "RC-004";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        AccessConfig access = def.access();
        if (access == null || access.rowFilter() == null) {
            return List.of();
        }
        AccessConfig.RowFilter rowFilter = access.rowFilter();
        if (!"COMPANY".equalsIgnoreCase(rowFilter.scopeType())) {
            return List.of();
        }
        if (rowFilter.column() == null || rowFilter.column().isBlank()) {
            return List.of(ContractViolation.fail(
                    ruleId(), def.key(), "rowFilter.column",
                    "rowFilter.scopeType=COMPANY requires non-blank column"));
        }

        // RC-004 v2 (Codex 019e3f5c): a sourceQuery report whose physical base
        // table carries no company column scopes via a declared projected-join
        // boundary. Validate that declaration against the SQL text instead of
        // the single-source allowlist path.
        if (def.sourceQuery() != null && !def.sourceQuery().isBlank()) {
            return validateSourceQueryCompanyBoundary(def, rowFilter);
        }

        // Codex iter-4 §1d-AGREE: sourceQuery+no source → tek FAIL, allowlist skip.
        // source table çözülemiyorsa allowlist + existence check anlamlı değil.
        if (def.source() == null || def.source().isBlank()) {
            return List.of(ContractViolation.fail(
                    ruleId(), def.key(), "rowFilter.column",
                    "COMPANY rowFilter requires resolvable source table for "
                            + "allowlist validation; sourceQuery alone is not sufficient"));
        }

        List<ContractViolation> violations = new ArrayList<>();

        // Allowlist match check.
        if (!allowlist.allows(def.source(), rowFilter.column())) {
            violations.add(ContractViolation.fail(
                    ruleId(), def.key(), "rowFilter.column",
                    "Column '" + rowFilter.column() + "' not in tenant column "
                            + "allowlist for source '" + def.source() + "' "
                            + "(scopeType=COMPANY rowFilter must use a tenant boundary column; "
                            + "review allowlist or correct scopeType)"));
        }

        // Phase 2 Program 2c (Codex iter-18 §2c-AGREE absorb, thread 019e0119):
        // schema truth existence cross-check via unified existence lookup
        // (canonical + yearly).
        //
        // Scope: existence check runs only for reports whose tables are
        // expected to be in the governance artifacts:
        //   - yearly schemaMode reports (CARI_ROWS/INVOICE_ROW from 2b yearly artifact)
        //   - canonical-snapshot HR tables (EMPLOYEES_PUANTAJ_ROWS, OFFTIME, etc.)
        // Static-schema tenant reports (workcube_mikrolink_<id>) referencing
        // tables outside both artifact scopes are governance-debt-tracked via
        // RC-004 allowlist + 2e scope migration; we don't double-flag them
        // with SCHEMA_TRUTH_COVERAGE_MISSING.
        //
        // Routing inside BuildTimeSchemaExistenceLookup:
        //   1. Canonical snapshot first (HR/static/global tables)
        //   2. Yearly artifact fallback (CARI_ROWS, INVOICE_ROW, ...)
        //   3. Empty artifact → graceful skip (pre-deployment rollout)
        //
        // NOT_COVERED → SCHEMA_TRUTH_COVERAGE_MISSING (governance/coverage gap)
        // COLUMN_MISSING → RC-004 Column not found (report-definition issue)
        // PRESENT → pass
        boolean existenceCheckApplicable = isYearlyOrCanonicalScope(def);
        if (existenceCheckApplicable && existenceLookup != null && existenceLookup.enforcementActive()) {
            CoverageStatus status = existenceLookup.lookup(
                    def.sourceSchema(), def.source(), rowFilter.column());
            switch (status) {
                case NOT_COVERED -> violations.add(ContractViolation.fail(
                        "SCHEMA_TRUTH_COVERAGE_MISSING", def.key(), "rowFilter.column",
                        "Snapshot does not cover schema/table '" + def.sourceSchema()
                                + "/" + def.source() + "', so RC-004 cannot prove column "
                                + "existence for '" + rowFilter.column() + "' "
                                + "(governance artifact coverage gap; not a report-definition issue)"));
                case COLUMN_MISSING -> violations.add(ContractViolation.fail(
                        ruleId(), def.key(), "rowFilter.column",
                        "Column '" + rowFilter.column() + "' not found in schema truth "
                                + "for table '" + def.source() + "' in schema '"
                                + def.sourceSchema() + "' (snapshot covers the table but "
                                + "lacks this column; report definition references a "
                                + "nonexistent column)"));
                case PRESENT -> { /* OK */ }
            }
        }

        return violations;
    }

    /**
     * Phase 2 Program 2c iter-18 §2c absorb: existence check applicability
     * gate. Returns true only when the report's source table is expected to
     * be in either the canonical snapshot (HR/global tables) or the yearly
     * artifact (yearly partitions).
     *
     * <p>Static-schema tenant reports (workcube_mikrolink_&lt;id&gt;) referencing
     * tables outside both artifact scopes (e.g. ORDERS, ORDER_ROW in
     * legacy reports) are NOT subject to existence enforcement here; they
     * are governance-debt-tracked via RC-004 allowlist + 2e scope migration.
     */
    private static boolean isYearlyOrCanonicalScope(ReportDefinition def) {
        if (def == null) {
            return false;
        }
        // Yearly partitioning: definitely covered by 2b artifact contract
        if ("yearly".equals(def.schemaMode())) {
            return true;
        }
        // Static reports pointing to canonical workcube_mikrolink schema:
        // HR/global tables expected in canonical snapshot.
        if (def.sourceSchema() != null
                && def.sourceSchema().equalsIgnoreCase("workcube_mikrolink")) {
            return true;
        }
        return false;
    }

    /**
     * RC-004 v2 (Codex 019e3f5c) — validate a COMPANY rowFilter on a
     * {@code sourceQuery} report. The report must declare a
     * {@code sourceQueryBoundary} projected-join, and the {@code sourceQuery}
     * text must demonstrably match that declaration so the projected company
     * column is provably tenant-isolating.
     *
     * <p>The accepted surface is deliberately narrow: the boundary table is
     * {@code EMPLOYEE_POSITIONS} and the base table is one of the supported HR
     * payroll/leave tables. Widening it is a deliberate future change.
     */
    private List<ContractViolation> validateSourceQueryCompanyBoundary(
            ReportDefinition def, AccessConfig.RowFilter rowFilter) {
        AccessConfig.SourceQueryBoundary b = rowFilter.sourceQueryBoundary();
        String f = "rowFilter.sourceQueryBoundary";

        // (1) declaration must be present.
        if (b == null) {
            return List.of(ContractViolation.fail(ruleId(), def.key(), f,
                    "COMPANY rowFilter on a sourceQuery report requires an "
                            + "access.rowFilter.sourceQueryBoundary declaration "
                            + "(RC-004 v2 projected-join company boundary)"));
        }

        List<ContractViolation> violations = new ArrayList<>();

        // (2) mode.
        if (!"projectedJoin".equals(b.mode())) {
            violations.add(v2Fail(def, f,
                    "sourceQueryBoundary.mode must be 'projectedJoin' (got '" + b.mode() + "')"));
        }
        // (3) boundary table — narrow to EMPLOYEE_POSITIONS.
        if (!"EMPLOYEE_POSITIONS".equalsIgnoreCase(b.boundaryTable())) {
            violations.add(v2Fail(def, f,
                    "sourceQueryBoundary.boundaryTable must be EMPLOYEE_POSITIONS (got '"
                            + b.boundaryTable() + "')"));
        }
        // (4) base table — narrow to the supported HR set.
        if (b.sourceTable() == null
                || !RC004_V2_BASE_TABLES.contains(b.sourceTable().toUpperCase(Locale.ROOT))) {
            violations.add(v2Fail(def, f,
                    "sourceQueryBoundary.sourceTable must be one of " + RC004_V2_BASE_TABLES
                            + " (got '" + b.sourceTable() + "')"));
        }
        // (5) rowFilter.column must equal projectedColumn.
        if (!equalsIgnoreCaseSafe(rowFilter.column(), b.projectedColumn())) {
            violations.add(v2Fail(def, f,
                    "rowFilter.column ('" + rowFilter.column() + "') must equal "
                            + "sourceQueryBoundary.projectedColumn ('" + b.projectedColumn() + "')"));
        }
        // (6) projectedColumn must equal boundaryColumn.
        if (!equalsIgnoreCaseSafe(b.projectedColumn(), b.boundaryColumn())) {
            violations.add(v2Fail(def, f,
                    "sourceQueryBoundary.projectedColumn ('" + b.projectedColumn()
                            + "') must equal boundaryColumn ('" + b.boundaryColumn() + "')"));
        }
        // (7) boundaryColumn must be allowlisted for boundaryTable.
        if (!allowlist.allows(b.boundaryTable(), b.boundaryColumn())) {
            violations.add(v2Fail(def, f,
                    "sourceQueryBoundary.boundaryColumn '" + b.boundaryColumn()
                            + "' is not in the tenant column allowlist for boundary table '"
                            + b.boundaryTable() + "'"));
        }

        // (8-12) sourceQuery text cross-checks. Codex 019e3f5c hardening:
        // string literals + comments are masked first (a projection/join that
        // only appears inside a comment or literal must NOT satisfy a check);
        // the scanned table set must be exactly the declared base + boundary
        // tables (no extra, non-isolating table smuggled in).
        String sql = WorkcubeSqlTableRefScanner.maskLiteralsAndComments(def.sourceQuery())
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
        // (8) explicit projection — reject every wildcard form.
        if (WILDCARD_SELECT.matcher(sql).find() || WILDCARD_QUALIFIED.matcher(sql).find()) {
            violations.add(v2Fail(def, "sourceQuery",
                    "RC-004 v2 sourceQuery must use an explicit column projection — "
                            + "no wildcard (SELECT * / SELECT TOP n * / alias.*)"));
        }
        // (8b) single SELECT statement — a depth-0 UNION/EXCEPT/INTERSECT
        // appends a second query RC-004 never validates (its projected company
        // column could be a constant). Checked across the whole query, not
        // just the join tail, so '<canonical> WHERE 1=1 UNION SELECT ...' is
        // caught too (Codex 019e3fdd).
        if (hasTopLevelSetOperator(sql)) {
            violations.add(v2Fail(def, "sourceQuery",
                    "RC-004 v2 sourceQuery must be a single SELECT statement — a top-level "
                            + "UNION / EXCEPT / INTERSECT appends an unvalidated query"));
        }
        if (b.sourceTable() != null && b.sourceAlias() != null
                && b.boundaryTable() != null && b.boundaryAlias() != null
                && b.boundaryColumn() != null && b.projectedColumn() != null
                && b.sourceJoinColumn() != null && b.boundaryJoinColumn() != null) {
            String src = b.sourceTable().toUpperCase(Locale.ROOT);
            String srcA = b.sourceAlias().toUpperCase(Locale.ROOT);
            String bnd = b.boundaryTable().toUpperCase(Locale.ROOT);
            String bndA = b.boundaryAlias().toUpperCase(Locale.ROOT);
            String bndCol = b.boundaryColumn().toUpperCase(Locale.ROOT);
            String projCol = b.projectedColumn().toUpperCase(Locale.ROOT);
            String srcJoin = b.sourceJoinColumn().toUpperCase(Locale.ROOT);
            String bndJoin = b.boundaryJoinColumn().toUpperCase(Locale.ROOT);

            // (9) the sourceQuery may reference ONLY the declared base +
            // boundary tables — an extra join could surface rows that are not
            // bound to the RLS-scoped employee key. The reference COUNTS are
            // also bounded (Codex 019e3f5c): the distinct-set check alone
            // misses a duplicate alias of the same base table, which is an
            // isolation hole — so the base table must appear exactly once and
            // the boundary table once (join) or twice (join + TOP-1 subquery).
            List<TableRef> tableRefs = WorkcubeSqlTableRefScanner.scan(def.sourceQuery());
            Set<String> tablesInQuery = tableRefs.stream()
                    .map(TableRef::table).collect(Collectors.toSet());
            Set<String> allowedTables = Set.of(src, bnd);
            if (!tablesInQuery.equals(allowedTables)) {
                violations.add(v2Fail(def, "sourceQuery",
                        "RC-004 v2 sourceQuery may reference only the declared base + boundary "
                                + "tables " + allowedTables + " — found " + tablesInQuery));
            } else {
                long sourceRefs = tableRefs.stream().filter(r -> src.equals(r.table())).count();
                long boundaryRefs = tableRefs.stream().filter(r -> bnd.equals(r.table())).count();
                if (sourceRefs != 1 || boundaryRefs < 1 || boundaryRefs > 2) {
                    violations.add(v2Fail(def, "sourceQuery",
                            "RC-004 v2 sourceQuery must reference the base table exactly once "
                                    + "and the boundary table once or twice — found base×"
                                    + sourceRefs + ", boundary×" + boundaryRefs
                                    + " (duplicate aliases of the same table are not allowed)"));
                }
            }

            // (10) base + boundary table physical-alias references.
            if (!sql.contains("[WORKCUBE_MIKROLINK].[" + src + "] " + srcA)) {
                violations.add(v2Fail(def, "sourceQuery",
                        "RC-004 v2 sourceQuery must reference the base table as "
                                + "'[workcube_mikrolink].[" + b.sourceTable() + "] "
                                + b.sourceAlias() + "'"));
            }
            if (!sql.contains("[WORKCUBE_MIKROLINK].[" + bnd + "] " + bndA)) {
                violations.add(v2Fail(def, "sourceQuery",
                        "RC-004 v2 sourceQuery must reference the boundary table as "
                                + "'[workcube_mikrolink].[" + b.boundaryTable() + "] "
                                + b.boundaryAlias() + "'"));
            }
            // (11) the boundary column must be a genuine TOP-LEVEL output of
            // the outer SELECT — not merely present somewhere (Codex 019e3fdd:
            // the substring could sit in a WHERE EXISTS subselect while the
            // real output column [<proj>] is a constant; RLS would then filter
            // the constant). Parse the depth-0 SELECT list and require exactly
            // one item to be the canonical projection.
            String expectedProjection = bndA + "." + bndCol + " AS [" + projCol + "]";
            long projectionItems = topLevelSelectItems(sql).stream()
                    .filter(it -> it.equals(expectedProjection)).count();
            if (projectionItems != 1) {
                violations.add(v2Fail(def, "sourceQuery",
                        "RC-004 v2 sourceQuery must project the boundary column as a top-level "
                                + "SELECT output exactly once: '" + b.boundaryAlias() + "."
                                + b.boundaryColumn() + " AS [" + b.projectedColumn() + "]'"));
            }
            // (12) the boundary table must be joined via the EXACT canonical
            // INNER-JOIN + deterministic TOP-1 position clause. A substring
            // check on the join predicate alone is bypassable (Codex 019e3fdd:
            // 'ON ... OR 1=1', a predicate hidden in a SELECT CASE, fan-out
            // joins). The canonical clause pins: INNER JOIN (fail-closed), the
            // exact employee-key equality, the single-position TOP-1 dedup
            // (no row fan-out), and the boundary-column IS NOT NULL guard.
            String canonicalJoin =
                    "INNER JOIN [WORKCUBE_MIKROLINK].[" + bnd + "] " + bndA + " WITH (NOLOCK) "
                    + "ON " + bndA + "." + bndJoin + " = " + srcA + "." + srcJoin + " "
                    + "AND " + bndA + ".POSITION_ID = (SELECT TOP 1 " + bndA + "2.POSITION_ID "
                    + "FROM [WORKCUBE_MIKROLINK].[" + bnd + "] " + bndA + "2 WITH (NOLOCK) "
                    + "WHERE " + bndA + "2." + bndJoin + " = " + srcA + "." + srcJoin + " "
                    + "AND " + bndA + "2." + bndCol + " IS NOT NULL "
                    + "ORDER BY CASE WHEN " + bndA + "2.POSITION_STATUS = 1 THEN 0 ELSE 1 END, "
                    + bndA + "2.IS_MASTER DESC, " + bndA + "2.POSITION_ID DESC)";
            int joinIdx = sql.indexOf(canonicalJoin);
            if (joinIdx < 0) {
                violations.add(v2Fail(def, "sourceQuery",
                        "RC-004 v2 sourceQuery must join the boundary table via the canonical "
                                + "deterministic projected-join clause: 'INNER JOIN "
                                + "[workcube_mikrolink].[" + b.boundaryTable() + "] " + b.boundaryAlias()
                                + " WITH (NOLOCK) ON " + b.boundaryAlias() + "." + b.boundaryJoinColumn()
                                + " = " + b.sourceAlias() + "." + b.sourceJoinColumn() + " AND "
                                + b.boundaryAlias() + ".POSITION_ID = (SELECT TOP 1 ... "
                                + "single deterministic position ...)'"));
            } else {
                // Nothing isolation-affecting may extend the join clause — the
                // tail must be empty or a benign continuation (WHERE / GROUP /
                // ORDER / ...), never OR / AND / a further join.
                String tail = sql.substring(joinIdx + canonicalJoin.length()).trim();
                if (!tail.isEmpty() && !SAFE_JOIN_TAIL.matcher(tail).lookingAt()) {
                    violations.add(v2Fail(def, "sourceQuery",
                            "RC-004 v2 sourceQuery must not extend the canonical projected-join "
                                    + "clause — unexpected trailing SQL '"
                                    + tail.substring(0, Math.min(40, tail.length())) + "'"));
                }
            }
        }

        // (13) schema-truth: declared join + boundary columns must exist.
        if (existenceLookup != null && existenceLookup.enforcementActive()) {
            checkV2ColumnExists(def, violations, b.sourceTable(), b.sourceJoinColumn());
            checkV2ColumnExists(def, violations, b.boundaryTable(), b.boundaryJoinColumn());
            checkV2ColumnExists(def, violations, b.boundaryTable(), b.boundaryColumn());
        }

        return violations;
    }

    private void checkV2ColumnExists(ReportDefinition def, List<ContractViolation> violations,
                                     String table, String column) {
        if (table == null || column == null) {
            return;
        }
        CoverageStatus status = existenceLookup.lookup(def.sourceSchema(), table, column);
        if (status == CoverageStatus.COLUMN_MISSING) {
            violations.add(v2Fail(def, "rowFilter.sourceQueryBoundary",
                    "RC-004 v2: column '" + column + "' declared for table '" + table
                            + "' is not found in schema truth"));
        }
        // NOT_COVERED → graceful skip (snapshot coverage gap, not a report
        // defect; the v2 surface is narrow to canonical-snapshot tables).
    }

    private ContractViolation v2Fail(ReportDefinition def, String field, String message) {
        return ContractViolation.fail(ruleId(), def.key(), field, message);
    }

    private static boolean equalsIgnoreCaseSafe(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    /**
     * RC-004 v2 (Codex 019e3fdd): the comma-separated items of the outer
     * (depth-0) SELECT list — the text between the leading {@code SELECT} and
     * the first depth-0 {@code FROM}. Subquery {@code SELECT}/{@code FROM}
     * tokens (inside parentheses) are skipped via paren-depth tracking, so a
     * projection that only appears in a subselect is NOT counted as a
     * top-level output. Returns an empty list if {@code sql} is not a plain
     * top-level {@code SELECT ... FROM} (fail-closed for the caller).
     *
     * <p>{@code sql} must already be literal/comment-masked, whitespace-
     * collapsed and upper-cased.
     */
    private static List<String> topLevelSelectItems(String sql) {
        String s = sql.trim();
        if (!s.startsWith("SELECT ")) {
            return List.of();
        }
        int depth = 0;
        int fromIdx = -1;
        for (int i = 7; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && c == ' ' && s.startsWith("FROM ", i + 1)) {
                fromIdx = i + 1;
                break;
            }
        }
        if (fromIdx < 0) {
            return List.of();
        }
        String selectList = s.substring(7, fromIdx).trim();
        List<String> items = new ArrayList<>();
        int d = 0;
        int start = 0;
        for (int i = 0; i < selectList.length(); i++) {
            char c = selectList.charAt(i);
            if (c == '(') {
                d++;
            } else if (c == ')') {
                d--;
            } else if (c == ',' && d == 0) {
                items.add(selectList.substring(start, i).trim());
                start = i + 1;
            }
        }
        items.add(selectList.substring(start).trim());
        return items;
    }

    /**
     * RC-004 v2 (Codex 019e3fdd): true if {@code sql} contains a
     * {@code UNION}/{@code EXCEPT}/{@code INTERSECT} at parenthesis-depth 0 —
     * a genuine set operator joining top-level queries, not one nested inside
     * a subquery. {@code sql} must already be masked + whitespace-normalised.
     */
    private static boolean hasTopLevelSetOperator(String sql) {
        Matcher m = SET_OPERATOR.matcher(sql);
        while (m.find()) {
            int depth = 0;
            for (int i = 0; i < m.start(); i++) {
                char c = sql.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
            if (depth == 0) {
                return true;
            }
        }
        return false;
    }
}
