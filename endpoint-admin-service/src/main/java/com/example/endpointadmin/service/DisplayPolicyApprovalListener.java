package com.example.endpointadmin.service;

import com.example.endpointadmin.event.CommandApprovalDecidedEvent;
import com.example.endpointadmin.model.ApprovalDecision;
import com.example.endpointadmin.model.ApprovalStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DisplayPolicyOperation;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointDisplayPolicy;
import com.example.endpointadmin.model.EndpointDisplayPolicyRevision;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRepository;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRevisionRepository;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * #508 slice-2b — promotes a SET_DISPLAY_POLICY revision to the current
 * desired-state row when (and only when) a second admin APPROVES the backing
 * maker-checker command (Codex 019ea911 RED-fix), and enforces per-device
 * single-flight at that approval (Codex 019ea92f).
 *
 * <p><b>Why a listener, not a PUT-time write:</b> the dual-control approve
 * surface ({@code EndpointAdminCommandService.approveCommand}) is generic and
 * does not know about display-policy domain state. If the PUT path wrote the
 * current row eagerly, a REJECTED proposal would still leave the new
 * desired-state as "current truth", bypassing maker-checker at the state level.
 * Instead PUT/DELETE write only a revision + a PENDING command; this listener
 * reacts to the approval decision:
 * <ul>
 *   <li><b>APPROVE</b> → (1) supersede any prior approved-but-undispatched
 *       display-policy command for the device so the agent never has two
 *       non-terminal commands to order, then (2) upsert the current row from the
 *       backing REVISION, flipping ENFORCE ⇄ CLEAR in place.</li>
 *   <li><b>REJECT</b> → no-op; the proposal dies, current stays unchanged, and
 *       a previously-approved command keeps its delivery (a rejected proposal
 *       must NOT suppress the currently-approved desired state).</li>
 * </ul>
 *
 * <p><b>Single-flight (Codex 019ea92f):</b> supersede happens at APPROVAL time,
 * not at propose time — a still-PENDING/rejectable proposal must never cancel
 * the delivery of the currently-approved policy. At approval we cancel a prior
 * APPROVED+QUEUED command under a row lock; if a prior approved command is
 * already in-flight (DELIVERED/ACKED/RUNNING) we throw, which rolls back THIS
 * approval (the checker retries once the in-flight command terminalizes — a
 * bounded wait, not an offline dead-lock).
 *
 * <p><b>Transaction contract (Codex must-fix #3):</b> {@code @EventListener} is
 * synchronous and {@code @Transactional(MANDATORY)} forces it into the approve
 * transaction, so any throw here rolls the approval back atomically. It never
 * uses {@code @TransactionalEventListener(AFTER_COMMIT)} (which cannot roll
 * back).
 */
@Component
public class DisplayPolicyApprovalListener {

    static final String EVENT_SUPERSEDED = "ENDPOINT_DISPLAY_POLICY_COMMAND_SUPERSEDED";
    private static final String ACTION_SUPERSEDE = "SUPERSEDE_ENDPOINT_DISPLAY_POLICY_COMMAND";

    private final EndpointDisplayPolicyRevisionRepository revisionRepository;
    private final EndpointDisplayPolicyRepository policyRepository;
    private final EndpointCommandRepository commandRepository;
    private final EndpointAuditService auditService;
    private final Clock clock;

    public DisplayPolicyApprovalListener(
            EndpointDisplayPolicyRevisionRepository revisionRepository,
            EndpointDisplayPolicyRepository policyRepository,
            EndpointCommandRepository commandRepository,
            EndpointAuditService auditService,
            Clock clock) {
        this.revisionRepository = revisionRepository;
        this.policyRepository = policyRepository;
        this.commandRepository = commandRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onCommandApprovalDecided(CommandApprovalDecidedEvent event) {
        if (event.commandType() != CommandType.SET_DISPLAY_POLICY) {
            return;
        }
        if (event.decision() != ApprovalDecision.APPROVE) {
            // REJECT (or any non-approve): the proposal is dead; current truth
            // is left untouched and a previously-approved command keeps delivery.
            return;
        }

        // Source of truth is the REVISION, not the command payload (Codex
        // must-fix #4). ux_edpr_command guarantees ≤1 row per command; treat any
        // other cardinality as a data-integrity error and roll the approve back.
        List<EndpointDisplayPolicyRevision> revisions =
                revisionRepository.findByTenantIdAndCommandId(event.tenantId(), event.commandId());
        if (revisions.size() != 1) {
            throw new IllegalStateException(
                    "Display-policy approval integrity error: expected exactly 1 revision for command "
                            + event.commandId() + " but found " + revisions.size() + ".");
        }
        EndpointDisplayPolicyRevision revision = revisions.get(0);
        Instant now = Instant.now(clock);

        // Per-device single-flight: supersede any OTHER approved-queued
        // display-policy command (or 409 if one is mid-apply) BEFORE promoting,
        // so a throw rolls back this whole approval.
        supersedePriorApprovedCommands(event.tenantId(), revision.getDeviceId(),
                event.commandId(), now, event.decidedBySubject());

        promoteCurrent(event, revision, now);
    }

    private void supersedePriorApprovedCommands(UUID tenantId, UUID deviceId,
                                                UUID approvedCommandId, Instant now, String subject) {
        for (UUID otherId :
                commandRepository.findActiveDisplayPolicyCommandIdsForDevice(deviceId, approvedCommandId)) {
            EndpointCommand other =
                    commandRepository.findByIdAndDeviceIdForUpdate(otherId, deviceId).orElse(null);
            if (other == null || other.getApprovalStatus() != ApprovalStatus.APPROVED) {
                // A still-PENDING separate proposal (not deliverable) or an
                // already-resolved row — leave it; only enacted (APPROVED)
                // commands threaten ordering.
                continue;
            }
            if (other.getStatus() == CommandStatus.QUEUED) {
                other.setStatus(CommandStatus.CANCELLED);
                other.setCancelledAt(now);
                commandRepository.save(other);
                auditService.record(tenantId, null, other, EVENT_SUPERSEDED, ACTION_SUPERSEDE, subject,
                        other.getIdempotencyKey(),
                        Map.of("supersededCommandId", other.getId().toString(),
                                "supersededByCommandId", approvedCommandId.toString()),
                        null, null);
            } else {
                // APPROVED + in-flight (DELIVERED/ACKED/RUNNING): cannot supersede
                // a command the agent is applying. Roll back this approval; the
                // checker retries once it terminalizes (bounded by the claim lease).
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "An older display-policy command is currently being applied on this device; "
                                + "retry the approval shortly.");
            }
        }
    }

    private void promoteCurrent(CommandApprovalDecidedEvent event,
                                EndpointDisplayPolicyRevision revision, Instant now) {
        EndpointDisplayPolicy current = policyRepository
                .findByTenantIdAndDeviceId(event.tenantId(), revision.getDeviceId())
                .orElseGet(EndpointDisplayPolicy::new);

        boolean isNew = current.getId() == null;

        current.setTenantId(revision.getTenantId());
        current.setDeviceId(revision.getDeviceId());
        current.setScopeType(EndpointDisplayPolicyRevision.SCOPE_DEVICE);
        current.setOperation(revision.getOperation());
        current.setCurrentRevisionId(revision.getId());
        current.setPolicyHashSha256(revision.getPolicyHashSha256());
        current.setLastUpdatedBySubject(event.decidedBySubject());
        current.setLastUpdatedAt(now);
        if (isNew) {
            current.setCreatedBySubject(revision.getCreatedBySubject());
            current.setCreatedAt(now);
        }

        if (revision.getOperation() == DisplayPolicyOperation.CLEAR) {
            current.clearSnapshot();
            current.setClearedAt(now);
            current.setClearedBySubject(event.decidedBySubject());
        } else {
            // ENFORCE: active managed policy (cleared_at must be NULL per
            // ck_edp_operation_cleared_state); copy the desired-state snapshot.
            current.setClearedAt(null);
            current.setClearedBySubject(null);
            current.setScreensaverEnabled(revision.getScreensaverEnabled());
            current.setScreensaverTimeoutSeconds(revision.getScreensaverTimeoutSeconds());
            current.setScreensaverSecure(revision.getScreensaverSecure());
            current.setScreensaverScrPath(revision.getScreensaverScrPath());
            current.setWallpaperEnabled(revision.getWallpaperEnabled());
            current.setWallpaperStyle(revision.getWallpaperStyle());
            current.setWallpaperUserCannotChange(revision.getWallpaperUserCannotChange());
            current.setWallpaperAssetRef(revision.getWallpaperAssetRef());
            current.setWallpaperAssetSha256(revision.getWallpaperAssetSha256());
            current.setWallpaperContentType(revision.getWallpaperContentType());
        }

        policyRepository.save(current);
    }
}
