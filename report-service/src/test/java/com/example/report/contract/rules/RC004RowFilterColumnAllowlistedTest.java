package com.example.report.contract.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.report.ContractViolation;
import com.example.report.contract.schema.TenantColumnAllowlist;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Program 1d — RC-004 row filter column allowlist tests.
 *
 * <p>Codex iter-4 §1d-AGREE absorb: allowlist-only check (existence cross-check
 * deferred to Phase 2 Program 2). Two distinct fail modes covered:
 * <ul>
 *   <li>Allowlist miss → "Column not in tenant column allowlist for source"</li>
 *   <li>Source unresolved (sourceQuery + no source) → single fail, skip rest</li>
 * </ul>
 */
class RC004RowFilterColumnAllowlistedTest {

    private static final TenantColumnAllowlist ALLOWLIST = new TenantColumnAllowlist(Map.of(
            "INVOICE", List.of("COMPANY_ID"),
            "CARI_ROWS", List.of("FROM_CMP_ID", "OUR_COMPANY_ID"),
            "EMPLOYEE_POSITIONS", List.of("OUR_COMPANY_ID")));

    @Test
    void validate_companyRowFilter_columnInAllowlist_returnsEmpty() {
        ReportDefinition def = newDef("INVOICE", "COMPANY_ID");

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_companyRowFilter_columnNotInAllowlist_failsWithAllowlistMessage() {
        ReportDefinition def = newDef("INVOICE", "DEPARTMENT_ID");  // not allowlisted

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).ruleId()).isEqualTo("RC-004");
        assertThat(violations.get(0).severity()).isEqualTo(ContractViolation.Severity.FAIL);
        assertThat(violations.get(0).message())
                .contains("not in tenant column allowlist for source 'INVOICE'");
    }

    @Test
    void validate_companyRowFilter_unknownSourceTable_failsWithAllowlistMessage() {
        ReportDefinition def = newDef("UNKNOWN_TABLE", "ANY_COL");

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("not in tenant column allowlist for source 'UNKNOWN_TABLE'"));
    }

    @Test
    void validate_companyRowFilter_sourceQueryWithoutBoundary_failsRequiringBoundary() {
        // RC-004 v2 (Codex 019e3f5c): a COMPANY rowFilter on a sourceQuery
        // report with no sourceQueryBoundary declaration fails fast.
        ReportDefinition def = new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                null,  // source absent — sourceQuery report
                "workcube_mikrolink", "static", null,
                "SELECT OT.EMPLOYEE_ID FROM [workcube_mikrolink].[OFFTIME] OT WITH (NOLOCK)",
                List.of(new ColumnDefinition("EMPLOYEE_ID", "E", "number", 100, false)),
                "EMPLOYEE_ID", "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter("OUR_COMPANY_ID", "COMPANY", null)));

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).ruleId()).isEqualTo("RC-004");
        assertThat(violations.get(0).message())
                .contains("requires an access.rowFilter.sourceQueryBoundary declaration");
    }

    @Test
    void validate_nonCompanyScope_returnsEmpty_skipsAllowlistEntirely() {
        // BRANCH scopeType not subject to RC-004 allowlist.
        ReportDefinition def = new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                "EXPENSE_ITEM_PLANS", "workcube_mikrolink_1", "static", null, null,
                List.of(new ColumnDefinition("BRANCH_ID", "B", "number", 100, false)),
                "BRANCH_ID", "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter("BRANCH_ID", "BRANCH", null)));

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_noRowFilter_returnsEmpty() {
        ReportDefinition def = new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                "INVOICE", "workcube_mikrolink_1", "static", null, null,
                List.of(new ColumnDefinition("COMPANY_ID", "C", "number", 100, false)),
                "COMPANY_ID", "ASC",
                null);  // no access

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_blankColumn_failsWithRequiredColumnMessage() {
        ReportDefinition def = new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                "INVOICE", "workcube_mikrolink_1", "static", null, null,
                List.of(new ColumnDefinition("COMPANY_ID", "C", "number", 100, false)),
                "COMPANY_ID", "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter("", "COMPANY", null)));

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("requires non-blank column"));
    }

    @Test
    void validate_emptyAllowlistDefault_failsAllCompanyRowFilters() {
        // Backward-compat: no-arg constructor uses empty allowlist; everything
        // fails. Production callers must inject real allowlist via overload.
        ReportDefinition def = newDef("INVOICE", "COMPANY_ID");

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted().validate(def);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message())
                .contains("not in tenant column allowlist");
    }

    // Phase 2 Program 2c (Codex iter-15 §2c-AGREE absorb): existence cross-check tests

    @Test
    void validate_coverageLookup_columnPresent_passesExistenceCheck() {
        // Allowlist matches, coverage PRESENT → no violation.
        // Use yearly schemaMode so existence check is applicable.
        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup mockLookup =
                org.mockito.Mockito.mock(
                        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.class);
        org.mockito.Mockito.when(mockLookup.schemaCount()).thenReturn(3);
        org.mockito.Mockito.when(mockLookup.lookup("workcube_mikrolink_2026_1", "INVOICE", "COMPANY_ID"))
                .thenReturn(com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup
                        .CoverageStatus.PRESENT);

        ReportDefinition def = newYearlyDef("INVOICE", "COMPANY_ID", "workcube_mikrolink_2026_1");
        List<ContractViolation> violations =
                new RC004RowFilterColumnAllowlisted(ALLOWLIST, mockLookup).validate(def);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_coverageLookup_columnMissing_failsWithRC004() {
        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup mockLookup =
                org.mockito.Mockito.mock(
                        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.class);
        org.mockito.Mockito.when(mockLookup.schemaCount()).thenReturn(3);
        org.mockito.Mockito.when(mockLookup.lookup("workcube_mikrolink_2026_1", "INVOICE", "COMPANY_ID"))
                .thenReturn(com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup
                        .CoverageStatus.COLUMN_MISSING);

        ReportDefinition def = newYearlyDef("INVOICE", "COMPANY_ID", "workcube_mikrolink_2026_1");
        List<ContractViolation> violations =
                new RC004RowFilterColumnAllowlisted(ALLOWLIST, mockLookup).validate(def);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).ruleId()).isEqualTo("RC-004");
        assertThat(violations.get(0).message())
                .contains("not found in schema truth")
                .contains("INVOICE");
    }

    @Test
    void validate_coverageLookup_notCovered_failsWithSchemaTruthCoverageMissing() {
        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup mockLookup =
                org.mockito.Mockito.mock(
                        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.class);
        org.mockito.Mockito.when(mockLookup.schemaCount()).thenReturn(3);
        org.mockito.Mockito.when(mockLookup.lookup("workcube_mikrolink_2026_1", "INVOICE", "COMPANY_ID"))
                .thenReturn(com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup
                        .CoverageStatus.NOT_COVERED);

        ReportDefinition def = newYearlyDef("INVOICE", "COMPANY_ID", "workcube_mikrolink_2026_1");
        List<ContractViolation> violations =
                new RC004RowFilterColumnAllowlisted(ALLOWLIST, mockLookup).validate(def);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).ruleId()).isEqualTo("SCHEMA_TRUTH_COVERAGE_MISSING");
        assertThat(violations.get(0).message())
                .contains("Snapshot does not cover")
                .contains("governance artifact coverage gap");
    }

    @Test
    void validate_emptyCoverageLookup_skipsExistenceCheck_gracefulDegradation() {
        // Pre-2b deployment: coverage artifact not yet present. Lookup returns
        // schemaCount=0 → existence check skipped. Allowlist match still
        // applies (no false-positive SCHEMA_TRUTH_COVERAGE_MISSING).
        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup emptyLookup =
                org.mockito.Mockito.mock(
                        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.class);
        org.mockito.Mockito.when(emptyLookup.schemaCount()).thenReturn(0);

        ReportDefinition def = newDef("INVOICE", "COMPANY_ID");
        List<ContractViolation> violations =
                new RC004RowFilterColumnAllowlisted(ALLOWLIST, emptyLookup).validate(def);

        assertThat(violations).isEmpty();  // allowlist OK, existence check skipped
    }

    @Test
    void validate_combinedAllowlistMissAndColumnMissing_emitsBothFailures() {
        // Allowlist miss + COLUMN_MISSING — both surface (orthogonal failure modes).
        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup mockLookup =
                org.mockito.Mockito.mock(
                        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.class);
        org.mockito.Mockito.when(mockLookup.schemaCount()).thenReturn(3);
        org.mockito.Mockito.when(mockLookup.lookup(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup
                        .CoverageStatus.COLUMN_MISSING);

        // INVOICE allowlists COMPANY_ID only; UNKNOWN_COL is not in allowlist
        // → produces both allowlist miss + column missing.
        // Use yearly mode so existence check is applicable.
        ReportDefinition def = newYearlyDef("INVOICE", "UNKNOWN_COL", "workcube_mikrolink_2026_1");
        List<ContractViolation> violations =
                new RC004RowFilterColumnAllowlisted(ALLOWLIST, mockLookup).validate(def);

        assertThat(violations).hasSize(2);
        assertThat(violations).extracting("ruleId")
                .containsExactly("RC-004", "RC-004");
        assertThat(violations).extracting("message")
                .anyMatch(m -> m.toString().contains("not in tenant column allowlist"))
                .anyMatch(m -> m.toString().contains("not found in schema truth"));
    }

    // ---------- RC-004 v2: sourceQuery projected-join company boundary ----------

    private static final String VALID_V2_SQL =
            "SELECT OT.EMPLOYEE_ID, EP.OUR_COMPANY_ID AS [OUR_COMPANY_ID] "
                    + "FROM [workcube_mikrolink].[OFFTIME] OT WITH (NOLOCK) "
                    + "INNER JOIN [workcube_mikrolink].[EMPLOYEE_POSITIONS] EP WITH (NOLOCK) "
                    + "ON EP.EMPLOYEE_ID = OT.EMPLOYEE_ID "
                    + "AND EP.POSITION_ID = (SELECT TOP 1 EP2.POSITION_ID "
                    + "FROM [workcube_mikrolink].[EMPLOYEE_POSITIONS] EP2 WITH (NOLOCK) "
                    + "WHERE EP2.EMPLOYEE_ID = OT.EMPLOYEE_ID AND EP2.OUR_COMPANY_ID IS NOT NULL "
                    + "ORDER BY CASE WHEN EP2.POSITION_STATUS = 1 THEN 0 ELSE 1 END, "
                    + "EP2.IS_MASTER DESC, EP2.POSITION_ID DESC)";

    private static AccessConfig.SourceQueryBoundary validBoundary() {
        return new AccessConfig.SourceQueryBoundary(
                "projectedJoin", "OFFTIME", "OT", "EMPLOYEE_ID",
                "EMPLOYEE_POSITIONS", "EP", "EMPLOYEE_ID", "OUR_COMPANY_ID", "OUR_COMPANY_ID");
    }

    private static ReportDefinition v2Def(String sourceQuery, AccessConfig.SourceQueryBoundary boundary) {
        return new ReportDefinition(
                "test-v2", "1.0", "Test", null, "İnsan Kaynakları",
                null, "workcube_mikrolink", "static", null, sourceQuery,
                List.of(new ColumnDefinition("EMPLOYEE_ID", "E", "number", 100, false)),
                "EMPLOYEE_ID", "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter("OUR_COMPANY_ID", "COMPANY", null, boundary)));
    }

    @Test
    void validate_v2_validProjectedJoinBoundary_returnsEmpty() {
        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(VALID_V2_SQL, validBoundary()));

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_v2_missingBoundaryDeclaration_fails() {
        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(VALID_V2_SQL, null));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message())
                .contains("requires an access.rowFilter.sourceQueryBoundary declaration");
    }

    @Test
    void validate_v2_sqlMissingProjectionAlias_fails() {
        String sql = "SELECT OT.EMPLOYEE_ID, EP.OUR_COMPANY_ID "
                + "FROM [workcube_mikrolink].[OFFTIME] OT WITH (NOLOCK) "
                + "INNER JOIN [workcube_mikrolink].[EMPLOYEE_POSITIONS] EP WITH (NOLOCK) "
                + "ON EP.EMPLOYEE_ID = OT.EMPLOYEE_ID";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId()) && v.message().contains("must project the boundary column"));
    }

    @Test
    void validate_v2_nonCanonicalJoin_fails() {
        // A plausible-looking but non-canonical join (wrong predicate, no
        // TOP-1 dedup) must be rejected — only the exact canonical clause
        // passes.
        String sql = "SELECT OT.EMPLOYEE_ID, EP.OUR_COMPANY_ID AS [OUR_COMPANY_ID] "
                + "FROM [workcube_mikrolink].[OFFTIME] OT WITH (NOLOCK) "
                + "INNER JOIN [workcube_mikrolink].[EMPLOYEE_POSITIONS] EP WITH (NOLOCK) "
                + "ON EP.POSITION_ID = OT.IN_OUT_ID";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("canonical deterministic projected-join clause"));
    }

    @Test
    void validate_v2_selectStar_fails() {
        String sql = "SELECT * FROM [workcube_mikrolink].[OFFTIME] OT WITH (NOLOCK) "
                + "INNER JOIN [workcube_mikrolink].[EMPLOYEE_POSITIONS] EP WITH (NOLOCK) "
                + "ON EP.EMPLOYEE_ID = OT.EMPLOYEE_ID";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId()) && v.message().contains("explicit column projection"));
    }

    @Test
    void validate_v2_wrongBoundaryTable_fails() {
        AccessConfig.SourceQueryBoundary boundary = new AccessConfig.SourceQueryBoundary(
                "projectedJoin", "OFFTIME", "OT", "EMPLOYEE_ID",
                "DEPARTMENT", "EP", "EMPLOYEE_ID", "OUR_COMPANY_ID", "OUR_COMPANY_ID");

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(VALID_V2_SQL, boundary));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("boundaryTable must be EMPLOYEE_POSITIONS"));
    }

    @Test
    void validate_v2_rowFilterColumnMismatch_fails() {
        // rowFilter.column ('EMPLOYEE_ID') != projectedColumn ('OUR_COMPANY_ID').
        ReportDefinition def = new ReportDefinition(
                "test-v2", "1.0", "Test", null, "İnsan Kaynakları",
                null, "workcube_mikrolink", "static", null, VALID_V2_SQL,
                List.of(new ColumnDefinition("EMPLOYEE_ID", "E", "number", 100, false)),
                "EMPLOYEE_ID", "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter("EMPLOYEE_ID", "COMPANY", null, validBoundary())));

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("must equal sourceQueryBoundary.projectedColumn"));
    }

    @Test
    void validate_v2_projectionOnlyInComment_fails() {
        // Codex 019e3f5c hardening: the required projection appears only inside
        // a /* */ comment; literal/comment masking means it must NOT satisfy
        // check 11 — the real projection is a constant proxy column.
        String sql = "SELECT OT.EMPLOYEE_ID, 1 AS [OUR_COMPANY_ID] "
                + "/* EP.OUR_COMPANY_ID AS [OUR_COMPANY_ID] */ "
                + "FROM [workcube_mikrolink].[OFFTIME] OT WITH (NOLOCK) "
                + "INNER JOIN [workcube_mikrolink].[EMPLOYEE_POSITIONS] EP WITH (NOLOCK) "
                + "ON EP.EMPLOYEE_ID = OT.EMPLOYEE_ID";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId()) && v.message().contains("must project the boundary column"));
    }

    @Test
    void validate_v2_extraJoinedTable_fails() {
        // Codex 019e3f5c hardening: an extra (non-isolating) table joined in —
        // RLS on EP.OUR_COMPANY_ID would not bind the smuggled rows.
        String sql = "SELECT OT.EMPLOYEE_ID, S.M1, EP.OUR_COMPANY_ID AS [OUR_COMPANY_ID] "
                + "FROM [workcube_mikrolink].[OFFTIME] OT WITH (NOLOCK) "
                + "INNER JOIN [workcube_mikrolink].[EMPLOYEE_POSITIONS] EP WITH (NOLOCK) "
                + "ON EP.EMPLOYEE_ID = OT.EMPLOYEE_ID "
                + "CROSS JOIN [workcube_mikrolink].[EMPLOYEES_SALARY] S";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("may reference only the declared base + boundary"));
    }

    @Test
    void validate_v2_qualifiedWildcard_fails() {
        // Codex 019e3f5c hardening: alias.* wildcard projection is forbidden.
        String sql = "SELECT OT.*, EP.OUR_COMPANY_ID AS [OUR_COMPANY_ID] "
                + "FROM [workcube_mikrolink].[OFFTIME] OT WITH (NOLOCK) "
                + "INNER JOIN [workcube_mikrolink].[EMPLOYEE_POSITIONS] EP WITH (NOLOCK) "
                + "ON EP.EMPLOYEE_ID = OT.EMPLOYEE_ID";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId()) && v.message().contains("explicit column projection"));
    }

    @Test
    void validate_lowercaseCompanyScopeType_isStillValidated() {
        // Codex 019e3f5c: RC-004 scopeType match is case-insensitive — a
        // lowercase 'company' must not skip the rule (runtime RLS resolves
        // scope case-insensitively, so skipping would be a silent drift).
        ReportDefinition def = new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                "INVOICE", "workcube_mikrolink_1", "static", null, null,
                List.of(new ColumnDefinition("DEPARTMENT_ID", "D", "number", 100, false)),
                "DEPARTMENT_ID", "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter("DEPARTMENT_ID", "company", null)));

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("not in tenant column allowlist"));
    }

    @Test
    void validate_v2_duplicateBaseTableAlias_fails() {
        // Codex 019e3f5c hardening: a second alias of the SAME base table
        // keeps the distinct table set valid, but its rows are not bound to
        // the RLS-scoped employee key — the reference-count check rejects it.
        String sql = "SELECT OT2.EMPLOYEE_ID, EP.OUR_COMPANY_ID AS [OUR_COMPANY_ID] "
                + "FROM [workcube_mikrolink].[OFFTIME] OT WITH (NOLOCK) "
                + "INNER JOIN [workcube_mikrolink].[EMPLOYEE_POSITIONS] EP WITH (NOLOCK) "
                + "ON EP.EMPLOYEE_ID = OT.EMPLOYEE_ID "
                + "CROSS JOIN [workcube_mikrolink].[OFFTIME] OT2 WITH (NOLOCK)";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("base table exactly once"));
    }

    @Test
    void validate_v2_parenthesizedTopWildcard_fails() {
        // Codex 019e3f5c hardening: T-SQL 'SELECT TOP (n) *' is also a
        // forbidden wildcard projection.
        String sql = "SELECT TOP (10) * "
                + "FROM [workcube_mikrolink].[OFFTIME] OT WITH (NOLOCK) "
                + "INNER JOIN [workcube_mikrolink].[EMPLOYEE_POSITIONS] EP WITH (NOLOCK) "
                + "ON EP.EMPLOYEE_ID = OT.EMPLOYEE_ID";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId()) && v.message().contains("explicit column projection"));
    }

    @Test
    void validate_v2_joinWeakenedWithOr_fails() {
        // Codex 019e3fdd: 'ON ... OR 1=1' turns the join cartesian; the
        // canonical-clause requirement rejects any join that is not the exact
        // pinned shape, so a substring of the real predicate cannot rescue it.
        String sql = "SELECT OT.EMPLOYEE_ID, EP.OUR_COMPANY_ID AS [OUR_COMPANY_ID] "
                + "FROM [workcube_mikrolink].[OFFTIME] OT WITH (NOLOCK) "
                + "INNER JOIN [workcube_mikrolink].[EMPLOYEE_POSITIONS] EP WITH (NOLOCK) "
                + "ON EP.EMPLOYEE_ID = OT.EMPLOYEE_ID OR 1=1";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("canonical deterministic projected-join clause"));
    }

    @Test
    void validate_v2_canonicalJoinExtendedWithTrailingOr_fails() {
        // Codex 019e3fdd: the canonical join clause is present verbatim but a
        // trailing 'OR 1=1' extends the outer join predicate — rejected by the
        // join-tail guard.
        String sql = VALID_V2_SQL + " OR 1=1";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("must not extend the canonical projected-join clause"));
    }

    @Test
    void validate_v2_projectionInSubselectNotTopLevel_fails() {
        // Codex 019e3fdd: the required projection appears only inside a
        // WHERE EXISTS subselect while the actual top-level output column
        // [OUR_COMPANY_ID] is a constant. RLS would filter the constant —
        // the top-level SELECT-list check rejects this.
        String sql = VALID_V2_SQL.replace(
                        "EP.OUR_COMPANY_ID AS [OUR_COMPANY_ID]", "1 AS [OUR_COMPANY_ID]")
                + " WHERE EXISTS (SELECT EP.OUR_COMPANY_ID AS [OUR_COMPANY_ID])";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("top-level SELECT output exactly once"));
    }

    @Test
    void validate_v2_unionAppendedQuery_fails() {
        // A UNION appends a second, unvalidated query whose [OUR_COMPANY_ID]
        // could be a constant — rejected as a non-single-statement query.
        String sql = VALID_V2_SQL + " UNION SELECT 1, 1 AS [OUR_COMPANY_ID]";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId()) && v.message().contains("single SELECT statement"));
    }

    @Test
    void validate_v2_unionAfterWhereClause_fails() {
        // Codex 019e3fdd: a UNION reached only after a benign WHERE tail must
        // still be caught — the set-operator scan covers the whole query, not
        // just the first token after the canonical join.
        String sql = VALID_V2_SQL + " WHERE 1=1 UNION SELECT 1, 1 AS [OUR_COMPANY_ID]";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId()) && v.message().contains("single SELECT statement"));
    }

    @Test
    void validate_v2_exceptAppendedQuery_fails() {
        // EXCEPT / INTERSECT are rejected on the same single-statement basis.
        String sql = VALID_V2_SQL + " EXCEPT SELECT 1, 1 AS [OUR_COMPANY_ID]";

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST)
                .validate(v2Def(sql, validBoundary()));

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId()) && v.message().contains("single SELECT statement"));
    }

    private ReportDefinition newDef(String source, String rowFilterColumn) {
        // Static schema (workcube_mikrolink_1) — existence check NOT applicable.
        // Used for allowlist-only test scenarios.
        return new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                source, "workcube_mikrolink_1", "static", null, null,
                List.of(new ColumnDefinition(rowFilterColumn, "C", "number", 100, false)),
                rowFilterColumn, "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter(rowFilterColumn, "COMPANY", null)));
    }

    private ReportDefinition newYearlyDef(String source, String rowFilterColumn, String sourceSchema) {
        // Yearly schema — existence check applicable (Phase 2 Program 2c).
        return new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                source, sourceSchema, "yearly", "ACTION_DATE", null,
                List.of(new ColumnDefinition(rowFilterColumn, "C", "number", 100, false)),
                rowFilterColumn, "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter(rowFilterColumn, "COMPANY", null)));
    }
}
