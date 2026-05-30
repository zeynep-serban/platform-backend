package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;

/**
 * BE — custom write path for the append-only outdated-software snapshot
 * (Faz 22.5, AG-036 ingest). Reuses the BE-024 atomicity pattern
 * (Halildeu/platform-backend#334, commit {@code d154ac7a}).
 *
 * <p>Exists so the ingest-time insert can use a native
 * {@code INSERT ... ON CONFLICT (source_command_result_id)
 * WHERE source_command_result_id IS NOT NULL DO NOTHING} instead of a broad
 * {@code catch (DataIntegrityViolationException)}. Swallowing every
 * {@code DataIntegrityViolationException} (a) hides a non-duplicate V20
 * constraint / FK / CHECK breach (mis-classified as a "duplicate") and (b)
 * on PostgreSQL leaves the surrounding transaction marked rollback-only, so
 * the later audit/commit stage fails uncontrolled — breaking the
 * snapshot+result atomicity claim. The partial-unique inference makes ONLY
 * the duplicate {@code source_command_result_id} a no-op; every other
 * violation propagates and rolls the whole transaction back together with
 * the snapshot.
 *
 * <p>Unlike the BE-024 single-table history insert, the outdated-software
 * snapshot owns a child {@code endpoint_outdated_software_packages} list.
 * The native insert therefore returns the (assigned) snapshot id so the
 * caller can persist the child rows when a row was actually inserted; a
 * duplicate no-op returns {@code null} (the caller must NOT write children
 * for a no-op).
 */
public interface EndpointOutdatedSoftwareSnapshotRepositoryCustom {

    /**
     * Insert one outdated-software snapshot scalar row, treating a duplicate
     * non-null {@code source_command_result_id} as a no-op (the partial-UNIQUE
     * index {@code uq_endpoint_outdated_software_snapshots_source_cmd_result}
     * is the conflict target). Every other constraint / FK / CHECK breach
     * propagates as a {@code DataIntegrityViolationException} and rolls back
     * the surrounding transaction.
     *
     * <p>The entity's {@code id} (UUID) and {@code createdAt}/{@code updatedAt}
     * are normally assigned by Hibernate ({@code @GeneratedValue} /
     * {@code @PrePersist}). On the native PG branch this path bypasses both,
     * so the implementation assigns them (and sets them back onto the passed
     * entity so the caller can bind the child package rows to the snapshot id).
     * {@code redactedPayload} / {@code probeErrors} are serialized to JSON
     * strings by the implementation and bound with a {@code ::jsonb} cast.
     *
     * @return the assigned snapshot {@code id} when a row was inserted,
     *         {@code null} when the duplicate {@code source_command_result_id}
     *         made it a no-op (no child rows should be written).
     */
    java.util.UUID insertIfNewSourceCommandResult(
            EndpointOutdatedSoftwareSnapshot snapshot);
}
