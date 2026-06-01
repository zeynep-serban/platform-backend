package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointDiagnosticsSnapshot;

import java.util.UUID;

/**
 * BE — custom write path for the append-only agent self-diagnostics
 * snapshot (Faz 22.5, AG-038-be ingest). Mirrors the AG-037 V22 hotfix-
 * posture targetless {@code ON CONFLICT DO NOTHING} pattern exactly.
 *
 * <h3>Why targetless ON CONFLICT</h3>
 *
 * <p>AG-038-be has TWO legitimate idempotency conflict targets:
 * <ol>
 *   <li>The partial UNIQUE on {@code source_command_result_id}
 *       (the agent SUBMIT-result idempotency primary).</li>
 *   <li>The full UNIQUE on
 *       {@code (tenant_id, device_id, payload_hash_sha256)} — the
 *       canonical-form payload-hash idempotency invariant.</li>
 * </ol>
 *
 * <p>The targetless form catches BOTH transaction-cleanly; the service
 * then re-looks up the winner sequentially via source-then-hash. ALL OTHER
 * CHECK / FK / triad / regex violations still propagate (transaction
 * rollback fail-closed).
 */
public interface EndpointDiagnosticsSnapshotRepositoryCustom {

    /**
     * Insert one diagnostics snapshot scalar row, treating a duplicate on
     * EITHER {@code source_command_result_id} (partial UNIQUE) OR
     * {@code (tenant_id, device_id, payload_hash_sha256)} (full UNIQUE) as
     * a no-op via targetless {@code ON CONFLICT DO NOTHING}. Every other
     * constraint / FK / CHECK breach propagates as a
     * {@code DataIntegrityViolationException} and rolls back the surrounding
     * transaction.
     *
     * @return the assigned snapshot {@code id} when a row was inserted,
     *         {@code null} when targetless ON CONFLICT made it a no-op (the
     *         caller MUST re-lookup the winner via source-then-hash before
     *         writing children).
     */
    UUID insertDiagnosticsSnapshotOnConflictDoNothing(EndpointDiagnosticsSnapshot snapshot);
}
