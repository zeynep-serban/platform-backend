package com.example.schema.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full schema snapshot.
 *
 * <p>The canonical record constructor is the single source of truth — Jackson
 * deserializes the {@code /api/v1/schema/snapshot} wire contract through it.
 * {@link Builder} is the ergonomic construction path for callers that set only
 * a subset of fields: every additive inventory list (and {@code domains})
 * defaults to empty, so a new B1 capability inventory can be added as a record
 * component + one builder field without churning existing call sites.
 *
 * <p>B1 authoritative inventories (Codex 019e2d7d / 019e325a, ADR-0020):
 * {@code foreignKeys} / {@code uniqueConstraints} (B1-2), {@code checkConstraints}
 * / {@code defaultConstraints} (B1-3), {@code indexes} (B1-4). Earlier callers
 * used a chain of legacy positional constructors; that chain was replaced by
 * {@link Builder} once it reached four overloads (refactor — Codex 019e3270).
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
     * Ergonomic builder — {@code tables} / {@code domains} and every additive
     * inventory list default to empty; the caller sets only what it needs.
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for {@link SchemaSnapshot} — see {@link #builder()}. */
    public static final class Builder {
        private String version;
        private Metadata metadata;
        private Map<String, TableInfo> tables = Map.of();
        private List<Relationship> relationships = List.of();
        private List<ForeignKeyInfo> foreignKeys = List.of();
        private List<UniqueConstraintInfo> uniqueConstraints = List.of();
        private List<CheckConstraintInfo> checkConstraints = List.of();
        private List<DefaultConstraintInfo> defaultConstraints = List.of();
        private List<IndexInfo> indexes = List.of();
        private Map<String, List<String>> domains = Map.of();
        private Analysis analysis;

        private Builder() {}

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder metadata(Metadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder tables(Map<String, TableInfo> tables) {
            this.tables = tables;
            return this;
        }

        public Builder relationships(List<Relationship> relationships) {
            this.relationships = relationships;
            return this;
        }

        public Builder foreignKeys(List<ForeignKeyInfo> foreignKeys) {
            this.foreignKeys = foreignKeys;
            return this;
        }

        public Builder uniqueConstraints(List<UniqueConstraintInfo> uniqueConstraints) {
            this.uniqueConstraints = uniqueConstraints;
            return this;
        }

        public Builder checkConstraints(List<CheckConstraintInfo> checkConstraints) {
            this.checkConstraints = checkConstraints;
            return this;
        }

        public Builder defaultConstraints(List<DefaultConstraintInfo> defaultConstraints) {
            this.defaultConstraints = defaultConstraints;
            return this;
        }

        public Builder indexes(List<IndexInfo> indexes) {
            this.indexes = indexes;
            return this;
        }

        public Builder domains(Map<String, List<String>> domains) {
            this.domains = domains;
            return this;
        }

        public Builder analysis(Analysis analysis) {
            this.analysis = analysis;
            return this;
        }

        public SchemaSnapshot build() {
            return new SchemaSnapshot(version, metadata, tables, relationships,
                foreignKeys, uniqueConstraints, checkConstraints, defaultConstraints,
                indexes, domains, analysis);
        }
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
