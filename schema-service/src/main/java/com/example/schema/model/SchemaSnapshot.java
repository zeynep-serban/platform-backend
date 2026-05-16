package com.example.schema.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full schema snapshot.
 *
 * <p>Phase B1-2 (capability R1+R2 — Codex 019e2d7d, ADR-0020 §2.3):
 * additive top-level {@code foreignKeys} / {@code uniqueConstraints}
 * authoritative inventory lists. Single-column FKs are also mirrored into
 * {@code relationships} ({@code source="fk_constraint"}) as a compatibility
 * layer; composite FKs live in {@code foreignKeys} only.
 */
public record SchemaSnapshot(
    String version,
    Metadata metadata,
    Map<String, TableInfo> tables,
    List<Relationship> relationships,
    List<ForeignKeyInfo> foreignKeys,
    List<UniqueConstraintInfo> uniqueConstraints,
    Map<String, List<String>> domains,
    Analysis analysis
) {
    /**
     * Legacy 6-arg constructor — pre-B1-2 shape (before the authoritative
     * FK / unique-constraint inventory). Retained so existing callers and
     * test fixtures compile unchanged; the new inventory lists default to
     * empty. Codex 019e2d7d B1-2 plan.
     */
    public SchemaSnapshot(String version, Metadata metadata, Map<String, TableInfo> tables,
                          List<Relationship> relationships, Map<String, List<String>> domains,
                          Analysis analysis) {
        this(version, metadata, tables, relationships, List.of(), List.of(), domains, analysis);
    }

    public record Metadata(
        String dbType,
        String host,
        String database,
        String schema,
        Instant extractedAt,
        int tableCount,
        int columnCount,
        int relationshipCount,
        int domainCount
    ) {}

    public record Analysis(
        List<DeadTable> deadTables,
        List<HubTable> hubTables
    ) {}

    public record DeadTable(String table, String reason, Long rowCount) {}
    public record HubTable(String table, int incomingRefs) {}
}
