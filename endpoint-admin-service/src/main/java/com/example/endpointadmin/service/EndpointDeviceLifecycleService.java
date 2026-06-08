package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.EndpointDeviceDto;
import com.example.endpointadmin.model.DeviceLifecycleAction;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDeviceLifecycleAudit;
import com.example.endpointadmin.repository.EndpointDeviceCredentialRepository;
import com.example.endpointadmin.repository.EndpointDeviceLifecycleAuditRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Device DECOMMISSION / REACTIVATE lifecycle (KVKK: deactivate, not delete —
 * reversible, data retained). Codex plan-time thread
 * {@code 019ea789-3060-7fd2-a6d6-feceefb31272} iter-2 AGREE.
 *
 * <p>Each transition runs in one transaction with a {@code PESSIMISTIC_WRITE}
 * row lock on the device (no reliance on optimistic-lock for operator races),
 * writes a structured append-only {@link EndpointDeviceLifecycleAudit} row
 * (who/when/why + cascade counts) AND the hash-chained
 * {@code endpoint_audit_events} forensic event, then returns the updated DTO.
 *
 * <p>The "decommissioned device cannot act OR revive itself" invariant is
 * enforced by the agent/auth/enrollment guards (separate commit in this PR):
 * this service only owns the admin-driven transition + cascade.
 */
@Service
public class EndpointDeviceLifecycleService {

    static final String EVENT_DECOMMISSIONED = "ENDPOINT_DEVICE_DECOMMISSIONED";
    static final String EVENT_REACTIVATED = "ENDPOINT_DEVICE_REACTIVATED";
    static final int MAX_REASON_LENGTH = 512;

    private final EndpointDeviceRepository deviceRepository;
    private final EndpointDeviceCredentialRepository credentialRepository;
    private final EndpointDeviceLifecycleAuditRepository lifecycleAuditRepository;
    private final EndpointDeviceLifecycleCascade cascade;
    private final EndpointAuditService auditService;
    private final EndpointDeviceService deviceService;

    public EndpointDeviceLifecycleService(
            EndpointDeviceRepository deviceRepository,
            EndpointDeviceCredentialRepository credentialRepository,
            EndpointDeviceLifecycleAuditRepository lifecycleAuditRepository,
            EndpointDeviceLifecycleCascade cascade,
            EndpointAuditService auditService,
            EndpointDeviceService deviceService) {
        this.deviceRepository = deviceRepository;
        this.credentialRepository = credentialRepository;
        this.lifecycleAuditRepository = lifecycleAuditRepository;
        this.cascade = cascade;
        this.auditService = auditService;
        this.deviceService = deviceService;
    }

    /**
     * Transition a device to {@link DeviceStatus#DECOMMISSIONED}. 409 if it is
     * already decommissioned. Cascades pending agent work (commands / secrets /
     * maintenance tokens / open uninstall requests) so a later reactivate does
     * not resurrect stale intent.
     */
    @Transactional
    public EndpointDeviceDto decommission(UUID tenantId, String actorSubject, UUID deviceId, String reason) {
        requireActor(actorSubject);
        String trimmedReason = requireReason(reason);
        EndpointDevice device = loadForUpdate(tenantId, deviceId);
        DeviceStatus from = device.getStatus();
        if (from == DeviceStatus.DECOMMISSIONED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Endpoint device is already decommissioned.");
        }

        device.setStatus(DeviceStatus.DECOMMISSIONED);
        EndpointDevice saved = deviceRepository.saveAndFlush(device);

        EndpointDeviceLifecycleCascade.CascadeCounts counts =
                cascade.onDecommission(tenantId, saved, actorSubject, trimmedReason);

        writeLifecycleAudit(tenantId, saved, DeviceLifecycleAction.DECOMMISSION,
                from, DeviceStatus.DECOMMISSIONED, actorSubject, trimmedReason, counts);
        recordHashChainEvent(tenantId, saved, EVENT_DECOMMISSIONED, "DECOMMISSION",
                actorSubject, from, DeviceStatus.DECOMMISSIONED, trimmedReason, counts);
        return deviceService.toDto(saved);
    }

    /**
     * Reverse a decommission. 409 if the device is not currently decommissioned.
     * Target state (Codex 019ea789): the device must NOT be restored to ONLINE
     * (that is earned by a real heartbeat) or STALE (synthetic here).
     * <ol>
     *   <li>last DECOMMISSION came from PENDING_ENROLLMENT → PENDING_ENROLLMENT</li>
     *   <li>else credential exists OR enrolledAt != null → OFFLINE</li>
     *   <li>else → PENDING_ENROLLMENT</li>
     * </ol>
     */
    @Transactional
    public EndpointDeviceDto reactivate(UUID tenantId, String actorSubject, UUID deviceId, String reason) {
        requireActor(actorSubject);
        String trimmedReason = requireReason(reason);
        EndpointDevice device = loadForUpdate(tenantId, deviceId);
        DeviceStatus from = device.getStatus();
        if (from != DeviceStatus.DECOMMISSIONED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Endpoint device is not decommissioned.");
        }

        DeviceStatus target = resolveReactivateTarget(tenantId, device);
        device.setStatus(target);
        EndpointDevice saved = deviceRepository.saveAndFlush(device);

        EndpointDeviceLifecycleCascade.CascadeCounts none =
                EndpointDeviceLifecycleCascade.CascadeCounts.none();
        writeLifecycleAudit(tenantId, saved, DeviceLifecycleAction.REACTIVATE,
                from, target, actorSubject, trimmedReason, none);
        recordHashChainEvent(tenantId, saved, EVENT_REACTIVATED, "REACTIVATE",
                actorSubject, from, target, trimmedReason, none);
        return deviceService.toDto(saved);
    }

    private DeviceStatus resolveReactivateTarget(UUID tenantId, EndpointDevice device) {
        Optional<EndpointDeviceLifecycleAudit> lastDecommission = lifecycleAuditRepository
                .findTopByDeviceIdAndOrgIdAndActionOrderByCreatedAtDesc(
                        device.getId(), tenantId, DeviceLifecycleAction.DECOMMISSION);
        if (lastDecommission.isPresent()
                && lastDecommission.get().getFromStatus() == DeviceStatus.PENDING_ENROLLMENT) {
            return DeviceStatus.PENDING_ENROLLMENT;
        }
        boolean hasCredential = credentialRepository.findByDevice_Id(device.getId()).isPresent();
        if (hasCredential || device.getEnrolledAt() != null) {
            return DeviceStatus.OFFLINE;
        }
        return DeviceStatus.PENDING_ENROLLMENT;
    }

    private EndpointDevice loadForUpdate(UUID tenantId, UUID deviceId) {
        return deviceRepository.findVisibleToOrgAndIdForUpdate(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Endpoint device not found."));
    }

    private void requireActor(String actorSubject) {
        // Codex 019ea789 post-impl polish: the lifecycle audit actor_subject is
        // NOT NULL — reject a blank/absent admin subject before any mutation.
        if (actorSubject == null || actorSubject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "An authenticated admin subject is required.");
        }
    }

    private String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.strip();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required.");
        }
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Reason must be at most " + MAX_REASON_LENGTH + " characters.");
        }
        return trimmed;
    }

    private void writeLifecycleAudit(UUID tenantId, EndpointDevice device,
                                     DeviceLifecycleAction action, DeviceStatus from, DeviceStatus to,
                                     String actorSubject, String reason,
                                     EndpointDeviceLifecycleCascade.CascadeCounts counts) {
        EndpointDeviceLifecycleAudit audit = new EndpointDeviceLifecycleAudit();
        audit.setTenantId(tenantId);
        audit.setDeviceId(device.getId());
        audit.setAction(action);
        audit.setFromStatus(from);
        audit.setToStatus(to);
        audit.setActorSubject(actorSubject);
        audit.setReason(reason);
        audit.setCancelledCommands(counts.cancelledCommands());
        audit.setRevokedTokens(counts.revokedTokens());
        audit.setFinalizedUninstalls(counts.finalizedUninstalls());
        lifecycleAuditRepository.save(audit);
    }

    private void recordHashChainEvent(UUID tenantId, EndpointDevice device,
                                      String eventType, String action, String actorSubject,
                                      DeviceStatus from, DeviceStatus to, String reason,
                                      EndpointDeviceLifecycleCascade.CascadeCounts counts) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reason", reason);
        metadata.put("cancelledCommands", counts.cancelledCommands());
        metadata.put("revokedTokens", counts.revokedTokens());
        metadata.put("finalizedUninstalls", counts.finalizedUninstalls());
        Map<String, Object> beforeState = new HashMap<>();
        beforeState.put("status", from.name());
        Map<String, Object> afterState = new HashMap<>();
        afterState.put("status", to.name());
        auditService.record(tenantId, device, null, eventType, action, actorSubject,
                null, metadata, beforeState, afterState);
    }
}
