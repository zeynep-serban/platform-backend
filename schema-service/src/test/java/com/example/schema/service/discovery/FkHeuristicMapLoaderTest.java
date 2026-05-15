package com.example.schema.service.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Phase 1 portability (Codex 019e2d7d AGREE — quick win 3+4/5):
 * {@link FkHeuristicMapLoader} unit tests. Covers the packaged Workcube
 * classpath defaults, external file override, the fallback contract
 * (external broken → classpath default; classpath default broken →
 * fail-fast), entry normalization and malformed-entry skipping.
 */
class FkHeuristicMapLoaderTest {

    private final FkHeuristicMapLoader loader =
            new FkHeuristicMapLoader(new DefaultResourceLoader(), new ObjectMapper());

    private static String fileUri(Path p) {
        return p.toUri().toString();
    }

    // --- classpath default smoke (packaged Workcube resources) ---

    @Test
    void classpathDefault_loadsWorkcubeAliasMap() {
        Map<String, String> m = loader.load(
                "", RelationshipDiscoveryService.DEFAULT_ALIAS_RESOURCE, "alias");
        assertThat(m).hasSize(45);
        assertThat(m).containsEntry("COMP_ID", "COMPANY");
        assertThat(m).containsEntry("ACC_EMPLOYEE_ID", "EMPLOYEES");
    }

    @Test
    void classpathDefault_loadsWorkcubeCommonFkMap() {
        Map<String, String> m = loader.load(
                "", RelationshipDiscoveryService.DEFAULT_COMMON_FK_RESOURCE, "common-fk");
        assertThat(m).hasSize(25);
        assertThat(m).containsEntry("COMPANY_ID", "COMPANY");
        assertThat(m).containsEntry("PRODUCT_ID", "PRODUCTS");
    }

    @Test
    void blankConfig_resolvesToSameMapAsExplicitClasspathPath() {
        Map<String, String> viaBlank = loader.load(
                "  ", RelationshipDiscoveryService.DEFAULT_ALIAS_RESOURCE, "alias");
        Map<String, String> viaExplicit = loader.load(
                RelationshipDiscoveryService.DEFAULT_ALIAS_RESOURCE,
                RelationshipDiscoveryService.DEFAULT_ALIAS_RESOURCE, "alias");
        assertThat(viaBlank).isEqualTo(viaExplicit).hasSize(45);
    }

    // --- external override ---

    @Test
    void customExternalJson_isLoaded(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("acme-aliases.json");
        Files.writeString(f, "{\"ACME_OWNER_ID\":\"ACME_OWNER\",\"ACME_DEPT\":\"ACME_DEPARTMENT\"}");

        Map<String, String> m = loader.load(
                fileUri(f), RelationshipDiscoveryService.DEFAULT_ALIAS_RESOURCE, "alias");

        assertThat(m).hasSize(2)
                .containsEntry("ACME_OWNER_ID", "ACME_OWNER")
                .containsEntry("ACME_DEPT", "ACME_DEPARTMENT");
    }

    @Test
    void caseNormalization_keysAndValuesTrimmedAndUpperCased(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("lower.json");
        Files.writeString(f, "{\"  comp_id \":\" company \"}");

        Map<String, String> m = loader.load(
                fileUri(f), RelationshipDiscoveryService.DEFAULT_ALIAS_RESOURCE, "alias");

        assertThat(m).hasSize(1).containsEntry("COMP_ID", "COMPANY");
    }

    // --- fallback semantics ---

    @Test
    void externalMissing_fallsBackToClasspathDefault() {
        Map<String, String> m = loader.load(
                "file:/no/such/path/aliases.json",
                RelationshipDiscoveryService.DEFAULT_ALIAS_RESOURCE, "alias");
        assertThat(m).hasSize(45);   // fell back to the Workcube default
    }

    @Test
    void externalMalformedJson_fallsBackToClasspathDefault(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("broken.json");
        Files.writeString(f, "{ this is not valid json ");

        Map<String, String> m = loader.load(
                fileUri(f), RelationshipDiscoveryService.DEFAULT_ALIAS_RESOURCE, "alias");
        assertThat(m).hasSize(45);   // fell back
    }

    @Test
    void validEmptyJson_yieldsEmptyMapNotFallback(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("empty.json");
        Files.writeString(f, "{}");

        Map<String, String> m = loader.load(
                fileUri(f), RelationshipDiscoveryService.DEFAULT_ALIAS_RESOURCE, "alias");
        // {} is an intentional empty map — it switches the heuristic off
        // and must NOT trigger the 45-entry classpath fallback.
        assertThat(m).isEmpty();
    }

    @Test
    void malformedEntries_skippedAndRestKept(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("partial.json");
        Files.writeString(f, "{\"GOOD_ID\":\"GOOD_TABLE\",\"\":\"NO_KEY\",\"NO_VALUE\":\"\"}");

        Map<String, String> m = loader.load(
                fileUri(f), RelationshipDiscoveryService.DEFAULT_ALIAS_RESOURCE, "alias");
        assertThat(m).hasSize(1).containsEntry("GOOD_ID", "GOOD_TABLE");
    }

    // --- fail-fast: the classpath default itself is broken (packaging bug) ---

    @Test
    void brokenClasspathDefault_failsFast() {
        String missing = "classpath:fk-heuristics/does-not-exist.json";
        assertThatThrownBy(() -> loader.load(missing, missing, "alias"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default resource");
    }
}
