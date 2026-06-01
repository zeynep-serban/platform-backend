package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointAppControlSnapshot;

import java.util.UUID;

/**
 * BE — race-safe append surface for AG-041-be app-control snapshots.
 * Mirrors AG-040-be {@code EndpointStartupExposureSnapshotRepositoryCustom}.
 */
public interface EndpointAppControlSnapshotRepositoryCustom {

    /**
     * Targetless {@code INSERT ... ON CONFLICT DO NOTHING} returning the
     * inserted id, or {@code null} on conflict (caller MUST re-lookup
     * the winning row via source_command_result_id or payload_hash).
     */
    UUID insertAppControlSnapshotOnConflictDoNothing(EndpointAppControlSnapshot snapshot);
}
