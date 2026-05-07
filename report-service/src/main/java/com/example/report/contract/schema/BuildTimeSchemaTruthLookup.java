package com.example.report.contract.schema;

import com.example.report.schema.SchemaSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Phase 2 Program 1d — Build-time schema truth adapter.
 *
 * <p>Codex iter-4 §1d-AGREE absorb (thread 019e0119): minimal factory wrapping
 * the committed snapshot for build-time contract gate use. Explicit
 * {@link #loadSnapshot()} init keeps this no-{@code @Component}; production
 * runtime fallback path ({@code CommittedSnapshotLoader} with fixture default)
 * stays untouched.
 *
 * <p>Schema name normalization: {@code BUILD_DETERMINISTIC} policy in this
 * adapter always queries against the canonical snapshot regardless of the
 * report's {@code sourceSchema} value (yearly partition crawl is out of 1d
 * scope; canonical snapshot is the only ground truth available).
 *
 * <p>Coverage caveat: existing snapshot (1509 tables) covers HR/canonical
 * reference tables but does NOT cover yearly-partitioned finance source
 * tables (CARI_ROWS, INVOICE_ROW, etc. — currently absent). RC-004 schema
 * truth existence cross-check is therefore deferred to Phase 2 Program 2
 * when yearly snapshot crawler lands; 1d uses this adapter only as
 * infrastructure scaffold.
 *
 * <p>Build-time only: no {@code @Component}, explicit constructor, no Spring
 * lifecycle.
 */
public final class BuildTimeSchemaTruthLookup {

    private static final Logger log = LoggerFactory.getLogger(BuildTimeSchemaTruthLookup.class);

    /** Canonical snapshot path (governance artifact, copied from platform-k8s-gitops). */
    public static final String DEFAULT_SNAPSHOT_PATH = "classpath:schema/workcube-schema.json";

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final String snapshotPath;
    private SchemaSnapshot snapshot;

    public BuildTimeSchemaTruthLookup(ResourceLoader resourceLoader,
                                       ObjectMapper objectMapper,
                                       String snapshotPath) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
        this.snapshotPath = snapshotPath;
    }

    public BuildTimeSchemaTruthLookup(ResourceLoader resourceLoader,
                                       ObjectMapper objectMapper) {
        this(resourceLoader, objectMapper, DEFAULT_SNAPSHOT_PATH);
    }

    /**
     * Load the committed snapshot. Caller must invoke before {@link #tableExists}/
     * {@link #columnExists}; idempotent.
     */
    public void loadSnapshot() {
        try {
            Resource resource = resourceLoader.getResource(snapshotPath);
            if (!resource.exists()) {
                log.warn("BuildTimeSchemaTruthLookup snapshot not found at {}", snapshotPath);
                this.snapshot = new SchemaSnapshot(Map.of());
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                this.snapshot = objectMapper.readValue(in, SchemaSnapshot.class);
                log.info("BuildTimeSchemaTruthLookup loaded snapshot: tables={} path={}",
                        this.snapshot.tables().size(), snapshotPath);
            }
        } catch (IOException e) {
            log.error("BuildTimeSchemaTruthLookup failed to load snapshot from {}", snapshotPath, e);
            this.snapshot = new SchemaSnapshot(Map.of());
        }
    }

    /**
     * Check whether the snapshot includes a table with the given name
     * (case-sensitive match against snapshot key).
     */
    public boolean tableExists(String tableName) {
        if (snapshot == null || tableName == null) {
            return false;
        }
        return snapshot.tables().containsKey(tableName);
    }

    /**
     * Check whether the snapshot's table includes the given column.
     * Returns false when table is absent (RC-004 caller should distinguish
     * via {@link #tableExists} when needed).
     */
    public boolean columnExists(String tableName, String columnName) {
        if (snapshot == null || tableName == null || columnName == null) {
            return false;
        }
        SchemaSnapshot.TableInfo table = snapshot.tables().get(tableName);
        if (table == null || table.columns() == null) {
            return false;
        }
        return table.columns().stream()
                .anyMatch(c -> c.name() != null && c.name().equalsIgnoreCase(columnName));
    }

    /** Total table count (for fixture vs real snapshot ayrımı testi). */
    public int tableCount() {
        return snapshot == null ? 0 : snapshot.tables().size();
    }

    /** Path used for the loaded snapshot (debug/audit). */
    public String snapshotPath() {
        return snapshotPath;
    }
}
