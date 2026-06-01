package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointHotfixPostureSnapshot;

/**
 * BE — custom write path for the append-only hotfix posture snapshot
 * (Faz 22.5, AG-037 ingest). Reuses the BE-024/AG-036 atomicity pattern
 * with the AG-037-specific extension to a TARGETLESS
 * {@code ON CONFLICT DO NOTHING} (Codex 019e81fe iter-3 P1.1).
 *
 * <p>Exists so the ingest-time insert can use a native
 * {@code INSERT ... ON CONFLICT DO NOTHING} instead of a broad
 * {@code catch (DataIntegrityViolationException)}. Swallowing every
 * {@code DataIntegrityViolationException} (a) hides a non-duplicate V22
 * CHECK / FK violation (mis-classified as a "duplicate") and (b) on
 * PostgreSQL leaves the surrounding transaction marked rollback-only,
 * so the later audit/commit stage fails uncontrolled — breaking the
 * snapshot+result atomicity claim.
 *
 * <h3>Why targetless ON CONFLICT</h3>
 *
 * <p>AG-037 has TWO legitimate idempotency conflict targets:
 * <ol>
 *   <li>The partial UNIQUE on {@code source_command_result_id}
 *       (the agent SUBMIT-result idempotency primary).</li>
 *   <li>The full UNIQUE on
 *       {@code (tenant_id, device_id, payload_hash_sha256)} — the
 *       canonical-form payload-hash idempotency invariant (Codex
 *       019e81fe iter-2 P1.7).</li>
 * </ol>
 *
 * <p>The targeted form ({@code ON CONFLICT (source_command_result_id)
 * WHERE ... DO NOTHING}) catches only conflict #1 — a same-hash
 * different-command race would then raise the
 * {@code (tenant, device, hash)} UNIQUE violation, abort the transaction
 * with {@code current transaction is aborted}, and the secondary winner
 * re-lookup would be impossible. The targetless
 * {@code ON CONFLICT DO NOTHING} catches BOTH unique conflicts (and
 * the rare PK / {@code (id, tenant_id)} UNIQUE collision) transaction-
 * cleanly; the service then re-looks up the winner sequentially via
 * source-then-hash. ALL OTHER CHECK / FK / child UNIQUE breaches still
 * propagate (transaction rollback fail-closed).
 *
 * <p>Returns the assigned snapshot {@code id} when a row was inserted;
 * {@code null} when the targetless conflict made it a no-op (the caller
 * MUST re-lookup the winner before writing children).
 */
public interface EndpointHotfixPostureSnapshotRepositoryCustom {

    /**
     * Insert one hotfix posture snapshot scalar row, treating a duplicate
     * on EITHER {@code source_command_result_id} (partial UNIQUE) OR
     * {@code (tenant_id, device_id, payload_hash_sha256)} (full UNIQUE)
     * as a no-op via targetless {@code ON CONFLICT DO NOTHING}. Every
     * other constraint / FK / CHECK breach propagates as a
     * {@code DataIntegrityViolationException} and rolls back the
     * surrounding transaction.
     *
     * <p>The entity's {@code id} (UUID) and
     * {@code createdAt}/{@code updatedAt} are normally assigned by
     * Hibernate ({@code @GeneratedValue} / {@code @PrePersist}). On the
     * native PG branch this path bypasses both, so the implementation
     * assigns them (and sets them back onto the passed entity so the
     * caller can bind the child rows to the snapshot id).
     * {@code redactedPayload} / {@code probeErrors} are serialized to
     * JSON strings by the implementation and bound with a
     * {@code ::jsonb} cast.
     *
     * @return the assigned snapshot {@code id} when a row was inserted,
     *         {@code null} when targetless {@code ON CONFLICT} made it a
     *         no-op (the caller MUST re-lookup the winner via
     *         {@code findBySourceCommandResultId} then
     *         {@code findFirstByTenantIdAndDeviceIdAndPayloadHashSha256...}
     *         before writing children).
     */
    java.util.UUID insertHotfixPostureSnapshotOnConflictDoNothing(
            EndpointHotfixPostureSnapshot snapshot);
}
