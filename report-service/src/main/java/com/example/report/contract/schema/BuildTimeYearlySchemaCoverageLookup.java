package com.example.report.contract.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Phase 2 Program 2b — Build-time consumer for the yearly schema coverage
 * artifact (Codex iter-15 §2b-AGREE absorb, thread 019e0119).
 *
 * <p>Distinguishes 3 lookup outcomes via {@link CoverageStatus}:
 * <ul>
 *   <li>{@link CoverageStatus#NOT_COVERED} — artifact does not include the
 *       requested {@code (schema, table)} pair → 2c surfaces
 *       {@code SCHEMA_TRUTH_COVERAGE_MISSING}, NOT a column-existence FAIL</li>
 *   <li>{@link CoverageStatus#COLUMN_MISSING} — schema/table covered but
 *       column absent → 2c surfaces {@code RC-004 Column not found}</li>
 *   <li>{@link CoverageStatus#PRESENT} — column present in covered table</li>
 * </ul>
 *
 * <p>Build-time only: no {@code @Component}; constructor injection.
 */
public final class BuildTimeYearlySchemaCoverageLookup {

    private static final Logger log = LoggerFactory.getLogger(BuildTimeYearlySchemaCoverageLookup.class);

    public static final String DEFAULT_PATH =
            "classpath:schema/workcube-schema-yearly-coverage.json";

    /** Three-way coverage outcome (Codex iter-15 §2b-AGREE absorb). */
    public enum CoverageStatus {
        NOT_COVERED,
        COLUMN_MISSING,
        PRESENT
    }

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String artifactPath;
    private YearlySchemaCoverage coverage;
    /** Per (schema → tableName.upper → set of column names upper). */
    private Map<String, Map<String, java.util.Set<String>>> columnIndex;

    public BuildTimeYearlySchemaCoverageLookup(ResourceLoader resourceLoader,
                                                ObjectMapper objectMapper,
                                                String artifactPath) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.artifactPath = artifactPath;
    }

    public BuildTimeYearlySchemaCoverageLookup(ResourceLoader resourceLoader,
                                                ObjectMapper objectMapper) {
        this(resourceLoader, objectMapper, DEFAULT_PATH);
    }

    /** Load + index the artifact. Idempotent. */
    public void loadCoverage() {
        try {
            Resource resource = resourceLoader.getResource(artifactPath);
            if (!resource.exists()) {
                log.warn("Yearly schema coverage artifact not found at {}", artifactPath);
                this.coverage = empty();
                this.columnIndex = Map.of();
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                this.coverage = objectMapper.readValue(in, YearlySchemaCoverage.class);
                this.columnIndex = buildIndex(this.coverage);
                log.info("Loaded yearly schema coverage: schemas={} from {}",
                        this.coverage.schemas().size(), artifactPath);
            }
        } catch (IOException e) {
            log.error("Failed to load yearly schema coverage from {}", artifactPath, e);
            this.coverage = empty();
            this.columnIndex = Map.of();
        }
    }

    /** Look up coverage for a {@code (schema, table, column)} triple. */
    public CoverageStatus lookup(String schema, String table, String column) {
        if (coverage == null || columnIndex == null) {
            return CoverageStatus.NOT_COVERED;
        }
        Map<String, java.util.Set<String>> tables = columnIndex.get(schema);
        if (tables == null) {
            return CoverageStatus.NOT_COVERED;
        }
        java.util.Set<String> columns = tables.get(upper(table));
        if (columns == null) {
            // Schema covered but table missing — still distinct from "column missing".
            // This collapses to NOT_COVERED at lookup level since 2c needs to know
            // the artifact didn't authoritatively confirm the (schema, table) pair.
            return CoverageStatus.NOT_COVERED;
        }
        return columns.contains(upper(column))
                ? CoverageStatus.PRESENT
                : CoverageStatus.COLUMN_MISSING;
    }

    public int schemaCount() {
        return coverage == null ? 0 : coverage.schemas().size();
    }

    public String artifactPath() {
        return artifactPath;
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static Map<String, Map<String, java.util.Set<String>>> buildIndex(YearlySchemaCoverage cov) {
        Map<String, Map<String, java.util.Set<String>>> idx = new HashMap<>();
        for (YearlySchemaCoverage.SchemaCoverage sc : cov.schemas()) {
            Map<String, java.util.Set<String>> tableMap = new HashMap<>();
            for (Map.Entry<String, YearlySchemaCoverage.TableCoverage> entry : sc.tables().entrySet()) {
                java.util.Set<String> cols = new java.util.HashSet<>();
                for (YearlySchemaCoverage.ColumnCoverage cc : entry.getValue().columns()) {
                    if (cc.name() != null) {
                        cols.add(cc.name().toUpperCase(Locale.ROOT));
                    }
                }
                tableMap.put(upper(entry.getKey()), cols);
            }
            idx.put(sc.schema(), tableMap);
        }
        return idx;
    }

    private static YearlySchemaCoverage empty() {
        return new YearlySchemaCoverage(
                1,
                java.time.Instant.EPOCH,
                "yearly",
                "workcube_mikrolink_{year}_{companyId}",
                new YearlySchemaCoverage.Filters(java.util.List.of(), java.util.List.of()),
                java.util.List.of());
    }
}
