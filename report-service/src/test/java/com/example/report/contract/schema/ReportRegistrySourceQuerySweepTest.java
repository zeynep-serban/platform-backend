package com.example.report.contract.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.schema.TableRef.SchemaKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Phase 2 Program 11.2a — registry sourceQuery sweep (Adım 11.2a;
 * Codex iter-21 REVISE-1 third clarity ask).
 *
 * <p>Scans every {@code report-service/src/main/resources/reports/*.json}
 * with a {@code sourceQuery} entry and asserts that
 * {@link WorkcubeSqlTableRefScanner} extracts only refs in
 * {@link ReportingAllowlist#V1}. UNKNOWN / UNQUALIFIED targets count as
 * failures.
 *
 * <p>This test is the live proof of the PR's value claim: "every existing
 * sourceQuery already conforms to the V1 allowlist as scanned by the new
 * parser." A V2 update or a new report that introduces an unknown table
 * here will surface as a test failure with the report key + table name
 * + position — giving the author a precise pointer.
 */
class ReportRegistrySourceQuerySweepTest {

    private static final Path REPORTS_DIR = Path.of("src/main/resources/reports");

    @TestFactory
    Stream<DynamicTest> allCurrentReportSourceQueries_haveOnlyAllowlistedRefs() throws IOException {
        ObjectMapper om = new ObjectMapper();
        List<DynamicTest> tests = new ArrayList<>();

        try (var stream = Files.list(REPORTS_DIR)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                JsonNode root;
                try {
                    root = om.readTree(p.toFile());
                } catch (IOException ex) {
                    return; // ignore malformed files (other tests catch them)
                }
                String key = root.path("key").asText("(unknown)");
                JsonNode sqNode = root.path("sourceQuery");
                if (sqNode.isMissingNode() || sqNode.isNull()) return;
                String sourceQuery = sqNode.asText();
                if (sourceQuery == null || sourceQuery.isBlank()) return;

                tests.add(DynamicTest.dynamicTest(
                        "sourceQuery sweep — " + key,
                        () -> assertSweep(key, sourceQuery)));
            });
        }

        return tests.stream();
    }

    private void assertSweep(String key, String sourceQuery) {
        List<TableRef> refs = WorkcubeSqlTableRefScanner.scan(sourceQuery);
        for (TableRef ref : refs) {
            // Unsupported / unqualified targets indicate a sourceQuery
            // pattern the scanner cannot safely allowlist.
            assertThat(ref.schemaKind())
                    .as("report '%s' sourceQuery ref '%s' schemaKind", key, ref.raw())
                    .isNotIn(SchemaKind.UNKNOWN, SchemaKind.UNQUALIFIED);

            // V1 allowlist membership
            assertThat(ReportingAllowlist.V1)
                    .as("report '%s' references table '%s' (schema '%s') at position %d "
                            + "which is not in ReportingAllowlist.V1",
                            key, ref.table(), ref.schema(), ref.position())
                    .contains(ref.table());
        }
    }
}
