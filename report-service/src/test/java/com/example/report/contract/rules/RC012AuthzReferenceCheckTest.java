package com.example.report.contract.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.registry.OpenFgaModelAuthzReferenceRegistry;
import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * R16 PR-C — RC-012 authz reference check tests.
 *
 * <p>Codex 019e27f5 PARTIAL Option 2 doğrultusunda WARN-first behavior:
 * canonical model'de {@code type report_group} yoksa, reportGroup-yazılı
 * raporlar WARN üretir. Model'de varsa WARN üretmez.
 *
 * <p>R15 regression guard kanıtı: PR-B merge edilene kadar her PR'da bu
 * test çalışır, silent drift imkansızlaşır.
 */
class RC012AuthzReferenceCheckTest {

    @Test
    void reportGroupSet_modelHasReportGroupType_passes(@TempDir Path tmp) throws IOException {
        Path model = writeFakeModel(tmp, """
                model
                  schema 1.1
                type user
                type report_group
                  relations
                    define can_view: [user]
                """);

        RC012AuthzReferenceCheck rule = new RC012AuthzReferenceCheck(
                new OpenFgaModelAuthzReferenceRegistry(model));

        ReportDefinition def = reportWithGroup("fin-bank", "FINANCE_REPORTS");
        List<ContractViolation> violations = rule.validate(def);

        assertThat(violations).isEmpty();
    }

    @Test
    void reportGroupSet_modelMissingReportGroupType_emitsWarn(@TempDir Path tmp) throws IOException {
        Path model = writeFakeModel(tmp, """
                model
                  schema 1.1
                type user
                type report
                  relations
                    define can_view: [user]
                """);

        RC012AuthzReferenceCheck rule = new RC012AuthzReferenceCheck(
                new OpenFgaModelAuthzReferenceRegistry(model));

        ReportDefinition def = reportWithGroup("fin-bank", "FINANCE_REPORTS");
        List<ContractViolation> violations = rule.validate(def);

        assertThat(violations).hasSize(1);
        ContractViolation v = violations.get(0);
        assertThat(v.severity()).isEqualTo(ContractViolation.Severity.WARN);
        assertThat(v.ruleId()).isEqualTo("RC-012");
        assertThat(v.reportKey()).isEqualTo("fin-bank");
        assertThat(v.field()).isEqualTo("access.reportGroup");
        assertThat(v.message()).contains("FINANCE_REPORTS");
        assertThat(v.message()).contains("type report_group");
    }

    @Test
    void noReportGroup_doesNotApply(@TempDir Path tmp) throws IOException {
        Path model = writeFakeModel(tmp, """
                model
                  schema 1.1
                type user
                """);

        RC012AuthzReferenceCheck rule = new RC012AuthzReferenceCheck(
                new OpenFgaModelAuthzReferenceRegistry(model));

        // reportGroup boş; rule uygulanmaz
        ReportDefinition def = reportWithGroup("static-report", null);
        List<ContractViolation> violations = rule.validate(def);

        assertThat(violations).isEmpty();
    }

    @Test
    void modelMissingFile_doesNotCrash(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.fga");
        RC012AuthzReferenceCheck rule = new RC012AuthzReferenceCheck(
                new OpenFgaModelAuthzReferenceRegistry(missing));

        ReportDefinition def = reportWithGroup("fin-bank", "FINANCE_REPORTS");
        List<ContractViolation> violations = rule.validate(def);

        // Model dosyası yoksa: WARN üretir (model okunamadı = type yok varsayım)
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).severity()).isEqualTo(ContractViolation.Severity.WARN);
    }

    private static Path writeFakeModel(Path dir, String content) throws IOException {
        Path file = dir.resolve("model.fga");
        Files.writeString(file, content);
        return file;
    }

    private static ReportDefinition reportWithGroup(String key, String reportGroup) {
        AccessConfig access = reportGroup != null
                ? new AccessConfig(null, reportGroup, null, null)
                : null;
        return new ReportDefinition(
                key,            // key
                "1.0",          // version
                "Test " + key,  // title
                "Test",         // description
                "test",         // category
                null,           // source (sourceQuery yeterli)
                "dbo",          // sourceSchema
                "static",       // schemaMode
                null,           // yearColumn
                "SELECT 1 AS id", // sourceQuery
                List.of(new ColumnDefinition(
                        "id", "ID", "number", null,
                        false, false, false, null, null, false, null)),
                null,           // defaultSort
                "ASC",          // defaultSortDirection
                access);        // access
    }
}
