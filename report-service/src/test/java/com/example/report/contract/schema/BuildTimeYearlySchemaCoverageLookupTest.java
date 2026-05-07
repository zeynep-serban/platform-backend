package com.example.report.contract.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.CoverageStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Phase 2 Program 2b — BuildTimeYearlySchemaCoverageLookup tests.
 *
 * <p>Codex iter-15 §2b-AGREE absorb (thread 019e0119): three-way coverage
 * outcome (NOT_COVERED / COLUMN_MISSING / PRESENT). 2c consumer
 * distinguishes coverage-missing from column-existence-missing.
 */
class BuildTimeYearlySchemaCoverageLookupTest {

    private BuildTimeYearlySchemaCoverageLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new BuildTimeYearlySchemaCoverageLookup(
                new DefaultResourceLoader(),
                new ObjectMapper().findAndRegisterModules(),
                "classpath:schema/workcube-schema-yearly-coverage-fixture.json");
        lookup.loadCoverage();
    }

    @Test
    void lookup_presentColumnInCoveredSchema_returnsPRESENT() {
        assertThat(lookup.lookup("workcube_mikrolink_2026_1", "INVOICE", "COMPANY_ID"))
                .isEqualTo(CoverageStatus.PRESENT);
        assertThat(lookup.lookup("workcube_mikrolink_2026_1", "CARI_ROWS", "FROM_CMP_ID"))
                .isEqualTo(CoverageStatus.PRESENT);
    }

    @Test
    void lookup_missingColumnInCoveredTable_returnsCOLUMN_MISSING() {
        assertThat(lookup.lookup("workcube_mikrolink_2026_1", "INVOICE", "DOES_NOT_EXIST"))
                .isEqualTo(CoverageStatus.COLUMN_MISSING);
        assertThat(lookup.lookup("workcube_mikrolink_2025_1", "INVOICE", "INVOICE_DATE"))
                .as("2025_1 fixture INVOICE doesn't include INVOICE_DATE")
                .isEqualTo(CoverageStatus.COLUMN_MISSING);
    }

    @Test
    void lookup_missingTableInCoveredSchema_returnsNOT_COVERED() {
        // 2c semantic: "table not in artifact" collapses to NOT_COVERED so
        // the rule reports SCHEMA_TRUTH_COVERAGE_MISSING (not column-missing).
        assertThat(lookup.lookup("workcube_mikrolink_2026_35", "CARI_ROWS", "FROM_CMP_ID"))
                .as("2026_35 fixture only has INVOICE; CARI_ROWS not covered")
                .isEqualTo(CoverageStatus.NOT_COVERED);
    }

    @Test
    void lookup_uncoveredSchema_returnsNOT_COVERED() {
        assertThat(lookup.lookup("workcube_mikrolink_2099_99", "INVOICE", "COMPANY_ID"))
                .isEqualTo(CoverageStatus.NOT_COVERED);
    }

    @Test
    void lookup_caseInsensitiveTableAndColumn() {
        // Tables/columns are upper-cased internally; lookups should be
        // case-insensitive (MSSQL collation tolerance).
        assertThat(lookup.lookup("workcube_mikrolink_2026_1", "invoice", "company_id"))
                .isEqualTo(CoverageStatus.PRESENT);
        assertThat(lookup.lookup("workcube_mikrolink_2026_1", "Invoice", "Company_ID"))
                .isEqualTo(CoverageStatus.PRESENT);
    }

    @Test
    void schemaCount_reflectsFixture() {
        // Fixture has 3 schemas: 2026_1, 2026_35, 2025_1
        assertThat(lookup.schemaCount()).isEqualTo(3);
    }

    @Test
    void lookup_missingArtifact_returnsNOT_COVERED() {
        BuildTimeYearlySchemaCoverageLookup missing = new BuildTimeYearlySchemaCoverageLookup(
                new DefaultResourceLoader(),
                new ObjectMapper().findAndRegisterModules(),
                "classpath:schema/does-not-exist.json");
        missing.loadCoverage();

        assertThat(missing.schemaCount()).isZero();
        assertThat(missing.lookup("any", "any", "any")).isEqualTo(CoverageStatus.NOT_COVERED);
    }
}
