package com.example.schema.service;

import com.example.schema.contract.SchemaReportingAllowlist;
import com.example.schema.model.ColumnInfo;
import com.example.schema.model.ReportingContractColumn;
import com.example.schema.model.ReportingContractSnapshot;
import com.example.schema.model.ReportingContractTable;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.model.TableInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Adım 12 — builds the {@link ReportingContractSnapshot} target contract
 * for {@code GET /api/v1/schema/reporting-contract}.
 *
 * <p>Codex {@code 019e2d64} plan-time AGREE (Opt-B′):
 * <ul>
 *   <li>Reuses {@link SchemaSnapshotService#buildSnapshot(String)} — no
 *       duplicate extraction logic, inherits the {@code @Cacheable}
 *       snapshot cache.</li>
 *   <li>Server-side allowlist filter: the response carries only the
 *       intersection of {@link SchemaReportingAllowlist#V1} and the
 *       extracted snapshot tables. The 1509-table full snapshot is
 *       never shipped to the worker (Codex S2: "allowlist provenance"
 *       must be enforced server-side, not delegated to the consumer).</li>
 *   <li>Deterministic ordering: tables sorted by {@code (schema, name)},
 *       columns by ordinal. Codex S2 trap: never rely on
 *       {@code Set} iteration order.</li>
 *   <li>{@code dataType → type} mapping happens here, in the projection.
 *       The {@link ColumnInfo} source record may carry additional
 *       metadata (B1-1 onwards: precision, scale, defaults, ...), but the
 *       reporting-contract projection stays a narrow {@code name/type/
 *       nullable} subset (Codex S4).</li>
 * </ul>
 *
 * <p>Empty-intersection handling: this service returns a snapshot whose
 * {@code tables} list is empty when no allowlisted table is present in
 * the target schema. The controller layer turns that into a fail-closed
 * {@code 404} so the etl-worker consumer surfaces {@code EX_SOFTWARE=70}
 * (terminal) rather than a deceptive {@code EX_OK} with zero tables
 * (Codex S2 trap).
 */
@Service
public class ReportingContractService {

    private static final Logger log = LoggerFactory.getLogger(ReportingContractService.class);

    private final SchemaSnapshotService snapshotService;
    private final String contractVersion;

    public ReportingContractService(
            SchemaSnapshotService snapshotService,
            @Value("${schema.reporting-contract.version:1}") String contractVersion) {
        this.snapshotService = snapshotService;
        this.contractVersion = contractVersion;
    }

    /**
     * Build the reporting-contract projection for {@code schema}.
     *
     * @param schema target MSSQL schema name
     * @return the allowlist-filtered, deterministically ordered contract
     */
    public ReportingContractSnapshot buildContract(String schema) {
        SchemaSnapshot snapshot = snapshotService.buildSnapshot(schema);

        List<ReportingContractTable> tables = new ArrayList<>();
        for (TableInfo table : snapshot.tables().values()) {
            if (!SchemaReportingAllowlist.containsV1(table.name())) {
                continue;
            }
            tables.add(toContractTable(table, schema));
        }

        // Deterministic ordering — Codex 019e2d64 S2 trap: Set / Map
        // iteration order is not stable across runs; the consumer's
        // checkpoint signature is content-only but a stable order keeps
        // logs + diffs reviewable.
        tables.sort(
            Comparator.comparing(ReportingContractTable::schema, Comparator.nullsFirst(String::compareTo))
                .thenComparing(ReportingContractTable::name, Comparator.nullsFirst(String::compareTo))
        );

        log.info(
            "Reporting contract for schema '{}': {} of {} allowlist tables matched",
            schema, tables.size(), SchemaReportingAllowlist.V1.size()
        );

        return new ReportingContractSnapshot(
            contractVersion,
            SchemaReportingAllowlist.NAME,
            SchemaReportingAllowlist.VERSION,
            tables
        );
    }

    private static ReportingContractTable toContractTable(
            TableInfo table, String targetSchema) {
        List<ColumnInfo> sourceColumns = table.columns();
        List<ReportingContractColumn> columns = new ArrayList<>(sourceColumns.size());
        // Preserve the extracted column order (ordinal). Sort defensively
        // by ordinal so a future extraction change cannot silently
        // reorder columns in the wire contract.
        sourceColumns.stream()
            .sorted(Comparator.comparingInt(ColumnInfo::ordinal))
            .forEach(c -> columns.add(
                new ReportingContractColumn(c.name(), c.dataType(), c.nullable())
            ));
        return new ReportingContractTable(
            normaliseSchema(table.schema(), targetSchema),
            table.name(),
            columns
        );
    }

    /**
     * Resolve the table's schema name for the wire contract.
     *
     * <p>Codex {@code 019e2d64} post-impl P2: the extraction path
     * normally fills {@link TableInfo#schema()} with the requested
     * target schema, so {@code null} / blank is only a legacy edge
     * case. When it does occur, fall back to the {@code targetSchema}
     * the caller asked for — that is the correct answer (the table
     * was extracted from that schema) and keeps the JSON field a
     * non-empty string, which the etl-worker parser requires.
     */
    private static String normaliseSchema(String schema, String targetSchema) {
        if (schema != null && !schema.isBlank()) {
            return schema;
        }
        return targetSchema == null ? "" : targetSchema;
    }
}
