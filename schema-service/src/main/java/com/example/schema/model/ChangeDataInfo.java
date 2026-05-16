package com.example.schema.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Authoritative per-table change-data feature footprint extracted from MSSQL
 * {@code sys.tables} + {@code sys.change_tracking_tables} —
 * {@code authoritative_mssql} truth tier (ADR-0020 §2.3, capability M13 —
 * Codex 019e32aa).
 *
 * <p>Phase B1-7. Covers the four SQL Server change-data surfaces a migration
 * must plan delta-sync, cutover-window and rollback strategy around: Change
 * Data Capture, Change Tracking, system-versioned temporal tables and
 * (transactional / merge) replication.
 *
 * <p>This is a <strong>filtered</strong> inventory — only tables that bear at
 * least one change-data feature appear. An empty list from a <em>successful</em>
 * extraction is itself authoritative truth: the schema uses no table-level
 * CDC / Change Tracking / temporal / replication. A non-fatal extraction
 * failure also yields an empty list, but that is a degraded snapshot — the
 * two cases are distinguished only by the service-level warning log.
 *
 * <p>{@code temporalType} is {@code sys.tables.temporal_type_desc}
 * ({@code SYSTEM_VERSIONED_TEMPORAL_TABLE} / {@code HISTORY_TABLE} /
 * {@code NON_TEMPORAL_TABLE}); it requires SQL Server 2016+, so on an older
 * engine it stays {@code NON_TEMPORAL_TABLE} while CDC / Change Tracking /
 * replication still extract.
 *
 * <p>The Change Tracking version fields ({@code ctMinValidVersion} etc.) are
 * non-null only when {@code changeTrackingEnabled}. {@code ctMinValidVersion}
 * drives re-init risk: a last-sync version below it means a full re-seed.
 */
public record ChangeDataInfo(
    String table,
    String schema,
    boolean cdcEnabled,
    boolean changeTrackingEnabled,
    boolean trackColumnsUpdated,
    Long ctMinValidVersion,
    Long ctBeginVersion,
    Long ctCleanupVersion,
    String temporalType,
    String historySchema,
    String historyTable,
    boolean transactionalReplicationEnabled,
    boolean mergePublished,
    boolean replicationFilterEnabled,
    boolean syncTranSubscribed
) {

    /**
     * True when the table participates in any replication topology. Derived
     * convenience over the four specific {@code sys.tables} replication flags
     * — {@code @JsonIgnore} keeps it out of the wire contract; a consumer that
     * needs the topology kind reads the specific flags.
     */
    @JsonIgnore
    public boolean replicated() {
        return transactionalReplicationEnabled || mergePublished
            || replicationFilterEnabled || syncTranSubscribed;
    }
}
