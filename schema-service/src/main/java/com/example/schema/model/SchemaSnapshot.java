package com.example.schema.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full schema snapshot.
 *
 * <p>Phase B1-2 (Codex 019e2d7d, ADR-0020 §2.3): additive top-level
 * {@code foreignKeys} / {@code uniqueConstraints} authoritative inventory.
 * Single-column FKs are also mirrored into {@code relationships}
 * ({@code source="fk_constraint"}); composite FKs live in
 * {@code foreignKeys} only.
 *
 * <p>Phase B1-3 (capability M3): additive top-level
 * {@code checkConstraints} / {@code defaultConstraints} authoritative
 * inventory.
 *
 * <p>Phase B1-4 (capability M4 — Codex 019e325a): additive top-level
 * {@code indexes} — the authoritative physical (rowstore) index inventory.
 * Legacy 10-arg / 8-arg / 6-arg constructors keep older callers compiling;
 * each additive inventory list defaults to empty.
 */
public record SchemaSnapshot(
    String version,
    Metadata metadata,
    Map<String, TableInfo> tables,
    List<Relationship> relationships,
    List<ForeignKeyInfo> foreignKeys,
    List<UniqueConstraintInfo> uniqueConstraints,
    List<CheckConstraintInfo> checkConstraints,
    List<DefaultConstraintInfo> defaultConstraints,
    List<IndexInfo> indexes,
    Map<String, List<String>> domains,
    Analysis analysis
) {
    /**
     * Legacy 10-arg constructor — B1-3 shape (before the physical index
     * inventory). The {@code indexes} list defaults to empty.
     */
    public SchemaSnapshot(String version, Metadata metadata, Map<String, TableInfo> tables,
                          List<Relationship> relationships,
                          List<ForeignKeyInfo> foreignKeys,
                          List<UniqueConstraintInfo> uniqueConstraints,
                          List<CheckConstraintInfo> checkConstraints,
                          List<DefaultConstraintInfo> defaultConstraints,
                          Map<String, List<String>> domains, Analysis analysis) {
        this(version, metadata, tables, relationships, foreignKeys, uniqueConstraints,
             checkConstraints, defaultConstraints, List.of(), domains, analysis);
    }

    /**
     * Legacy 8-arg constructor — B1-2 shape (before the check / default
     * constraint inventory). The check / default / index lists default empty.
     */
    public SchemaSnapshot(String version, Metadata metadata, Map<String, TableInfo> tables,
                          List<Relationship> relationships,
                          List<ForeignKeyInfo> foreignKeys,
                          List<UniqueConstraintInfo> uniqueConstraints,
                          Map<String, List<String>> domains, Analysis analysis) {
        this(version, metadata, tables, relationships, foreignKeys, uniqueConstraints,
             List.of(), List.of(), List.of(), domains, analysis);
    }

    /**
     * Legacy 6-arg constructor — pre-B1-2 shape (before any authoritative
     * constraint inventory). All five inventory lists default to empty.
     */
    public SchemaSnapshot(String version, Metadata metadata, Map<String, TableInfo> tables,
                          List<Relationship> relationships, Map<String, List<String>> domains,
                          Analysis analysis) {
        this(version, metadata, tables, relationships,
             List.of(), List.of(), List.of(), List.of(), List.of(), domains, analysis);
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
