package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointServicesSnapshot;

import java.util.UUID;

/**
 * BE — custom write path for the append-only services snapshot
 * (Faz 22.5, AG-039-be ingest). Mirrors AG-038-be
 * {@code EndpointDiagnosticsSnapshotRepositoryCustom} targetless
 * ON CONFLICT pattern. Dual idempotency: partial UNIQUE on
 * source_command_result_id + full UNIQUE on (tenant, device, hash).
 */
public interface EndpointServicesSnapshotRepositoryCustom {
    UUID insertServicesSnapshotOnConflictDoNothing(EndpointServicesSnapshot snapshot);
}
