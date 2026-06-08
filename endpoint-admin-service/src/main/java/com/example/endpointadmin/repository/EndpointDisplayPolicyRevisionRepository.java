package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointDisplayPolicyRevision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * #508 slice-2b — Spring Data JPA repository for
 * {@link EndpointDisplayPolicyRevision} (append-only).
 *
 * <p>Reads stay tenant-keyed (A5 deferred); {@code org_id = tenant_id} so the
 * tenant-scoped finders align with the V58 org-composite arbiters.
 */
public interface EndpointDisplayPolicyRevisionRepository
        extends JpaRepository<EndpointDisplayPolicyRevision, UUID> {

    /**
     * Provenance lookup used by {@code DisplayPolicyApprovalListener} to promote
     * a revision to the current row on APPROVE. The V58 partial unique
     * {@code ux_edpr_command (org_id, command_id)} guarantees ≤1 row per
     * command; the listener treats {@code size != 1} as a data-integrity error
     * and rolls back the approve (Codex 019ea911 must-fix #4).
     */
    List<EndpointDisplayPolicyRevision> findByTenantIdAndCommandId(UUID tenantId, UUID commandId);

    /**
     * Open-proposal lookup (Codex 019ea911 must-fix #1/#4): the latest revision
     * for the device whose backing command is NON-terminal — i.e. still able to
     * dispatch or already approved-and-queued. "Open" =
     * {@code status NOT IN (CANCELLED, EXPIRED, FAILED, SUCCEEDED)} AND
     * {@code approval_status IN (PENDING, APPROVED)}. The one-open-proposal
     * invariant (enforced at PUT under the device write-lock) means there is at
     * most one such row; we order DESC and take the first defensively.
     *
     * <p>This is the idempotency/dedup mechanism — it is NOT bound to the
     * current row, so a rejected/terminal prior proposal never blocks a fresh
     * re-proposal.
     */
    @Query("""
            SELECT r FROM EndpointDisplayPolicyRevision r, EndpointCommand c
            WHERE r.tenantId = :tenantId
              AND r.deviceId = :deviceId
              AND r.commandId = c.id
              AND c.tenantId = :tenantId
              AND c.commandType = com.example.endpointadmin.model.CommandType.SET_DISPLAY_POLICY
              AND c.status NOT IN (
                    com.example.endpointadmin.model.CommandStatus.CANCELLED,
                    com.example.endpointadmin.model.CommandStatus.EXPIRED,
                    com.example.endpointadmin.model.CommandStatus.FAILED,
                    com.example.endpointadmin.model.CommandStatus.SUCCEEDED)
              AND c.approvalStatus IN (
                    com.example.endpointadmin.model.ApprovalStatus.PENDING,
                    com.example.endpointadmin.model.ApprovalStatus.APPROVED)
            ORDER BY r.createdAt DESC
            """)
    List<EndpointDisplayPolicyRevision> findOpenProposals(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId);

    /** History page (newest first) for the GET/audit surface. */
    Page<EndpointDisplayPolicyRevision>
        findByTenantIdAndDeviceIdOrderByCreatedAtDesc(UUID tenantId, UUID deviceId, Pageable pageable);
}
