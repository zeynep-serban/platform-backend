package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.ApproveEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.CreateAgentUpdateRequest;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.CreateInstallRequest;
import com.example.endpointadmin.dto.v1.admin.CreateLocalPasswordChangeRequest;
import com.example.endpointadmin.dto.v1.admin.CreateLocalPasswordChangeResponse;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandResultDto;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse.InstallPreflightDecision;
import com.example.endpointadmin.exception.InstallBlockedException;
import com.example.endpointadmin.model.ApprovalDecision;
import com.example.endpointadmin.model.ApprovalStatus;
import com.example.endpointadmin.model.AgentUpdateReleaseStatus;
import com.example.endpointadmin.model.AgentUpdateSigningTier;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeploymentRing;
import com.example.endpointadmin.model.EndpointAgentUpdateRelease;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandApproval;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHeartbeat;
import com.example.endpointadmin.model.CatalogSilentArgsPolicy;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.repository.EndpointAgentUpdateReleaseRepository;
import com.example.endpointadmin.repository.EndpointCommandApprovalRepository;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointHeartbeatRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class EndpointAdminCommandService {

    /**
     * BE-017 — destructive command types that require a second admin's
     * dual-control approval before the agent can claim them (Codex 019e50a5).
     * This set is the fixed gate definition; it is independent of which types
     * an admin may currently create.
     */
    private static final Set<CommandType> APPROVAL_REQUIRED_TYPES = EnumSet.of(
            CommandType.LOCK_USER_LOGIN,
            CommandType.UNLOCK_USER_LOGIN,
            CommandType.CHANGE_LOCAL_PASSWORD,
            CommandType.SMB_DOWNLOAD_FILE,
            CommandType.SMB_UPLOAD_FILE,
            CommandType.ROTATE_CREDENTIAL);

    /**
     * AG-028 Phase 1 (Codex plan-time iter-2 absorb) — command types that
     * are reserved for their dedicated REST surfaces and MUST NOT be
     * created via the generic admin command surface.
     *
     * <ul>
     *   <li>{@link CommandType#INSTALL_SOFTWARE} — dedicated path:
     *       {@code POST /api/v1/admin/endpoint-devices/{deviceId}/installs}
     *       (BE-021). The generic path would bypass the preflight recompute.</li>
     *   <li>{@link CommandType#UNINSTALL_SOFTWARE} — dedicated path:
     *       {@code POST /api/v1/admin/endpoint-devices/{deviceId}/uninstalls}
     *       (AG-028 Phase 1b). The generic path would bypass the propose/approve
     *       maker-checker + capability/provenance gates.</li>
     *   <li>{@link CommandType#UPDATE_AGENT} — dedicated path:
     *       signed self-update release surface (AG-029/BE-030). The generic
     *       path would accept arbitrary caller payload instead of resolving
     *       release catalog, trust metadata, maker-checker and audit state.</li>
     *   <li>{@link CommandType#CHANGE_LOCAL_PASSWORD} — dedicated path:
     *       local recovery / secret-delivery surface (AG-042 follow-up). The
     *       generic path persists and returns payload JSON, so it must never
     *       accept caller-supplied password material.</li>
     * </ul>
     *
     * <p>Migrates the prior INSTALL_SOFTWARE 409 to 422 — semantically the
     * client request is not a "conflict with current resource state" but a
     * request the server refuses to process on this URI (RFC 4918 §11.2 spirit
     * of 422: server understands the request but cannot process it; the right
     * URI is the dedicated path). Existing INSTALL_SOFTWARE 409 regression
     * tests need to be updated to 422 in the same PR.
     */
    private static final Set<CommandType> DEDICATED_PATH_ONLY = EnumSet.of(
            CommandType.INSTALL_SOFTWARE,
            CommandType.UNINSTALL_SOFTWARE,
            CommandType.UPDATE_AGENT,
            CommandType.CHANGE_LOCAL_PASSWORD);

    private static final Duration DEFAULT_LOCAL_PASSWORD_SECRET_TTL = Duration.ofMinutes(15);
    private static final String LOCAL_PASSWORD_SECRET_REF = "endpoint-command-secret:newPassword";

    private final EndpointCommandRepository commandRepository;
    private final EndpointCommandResultRepository resultRepository;
    private final EndpointCommandApprovalRepository approvalRepository;
    private final EndpointDeviceRepository deviceRepository;
    private final EndpointSoftwareCatalogItemRepository catalogRepository;
    private final EndpointAgentUpdateReleaseRepository agentUpdateReleaseRepository;
    private final EndpointHeartbeatRepository heartbeatRepository;
    private final EndpointInstallPreflightService preflightService;
    private final EndpointCommandSecretService commandSecretService;
    private final EndpointAuditService auditService;
    private final Clock clock;
    private final Duration updateAgentHeartbeatFreshnessTtl;

    /**
     * BE-017 — command types an admin may create. Defaults to
     * {@code COLLECT_INVENTORY} only; destructive types are NOT runtime-enabled
     * here (agent executor + command-taxonomy parity is a separate task —
     * Codex 019e50a5 Q4). Tests widen this property to exercise the
     * dual-control gate.
     */
    private final Set<CommandType> adminCreatableTypes;

    public EndpointAdminCommandService(EndpointCommandRepository commandRepository,
                                       EndpointCommandResultRepository resultRepository,
                                       EndpointCommandApprovalRepository approvalRepository,
                                       EndpointDeviceRepository deviceRepository,
                                       EndpointSoftwareCatalogItemRepository catalogRepository,
                                       EndpointAgentUpdateReleaseRepository agentUpdateReleaseRepository,
                                       EndpointHeartbeatRepository heartbeatRepository,
                                       EndpointInstallPreflightService preflightService,
                                       EndpointCommandSecretService commandSecretService,
                                       EndpointAuditService auditService,
                                       Clock clock,
                                       @Value("${endpoint-admin.agent-updates.heartbeat-freshness-ttl:PT5M}")
                                       Duration updateAgentHeartbeatFreshnessTtl,
                                       @Value("${endpoint-admin.commands.admin-creatable-types:COLLECT_INVENTORY}")
                                       Set<CommandType> adminCreatableTypes) {
        this.commandRepository = commandRepository;
        this.resultRepository = resultRepository;
        this.approvalRepository = approvalRepository;
        this.deviceRepository = deviceRepository;
        this.catalogRepository = catalogRepository;
        this.agentUpdateReleaseRepository = agentUpdateReleaseRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.preflightService = preflightService;
        this.commandSecretService = commandSecretService;
        this.auditService = auditService;
        this.clock = clock;
        this.updateAgentHeartbeatFreshnessTtl = updateAgentHeartbeatFreshnessTtl == null
                ? Duration.ofMinutes(5) : updateAgentHeartbeatFreshnessTtl;
        this.adminCreatableTypes = (adminCreatableTypes == null || adminCreatableTypes.isEmpty())
                ? EnumSet.of(CommandType.COLLECT_INVENTORY)
                : EnumSet.copyOf(adminCreatableTypes);
    }

    @Transactional
    public EndpointCommandDto createCommand(AdminTenantContext context,
                                            UUID deviceId,
                                            CreateEndpointCommandRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Command request is required.");
        }
        validateCommandType(request.type());

        boolean requiresApproval = APPROVAL_REQUIRED_TYPES.contains(request.type());
        String reason = trimToNull(request.reason());
        if (requiresApproval && reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A reason is required for a destructive command.");
        }

        UUID tenantId = context.tenantId();
        EndpointDevice device = deviceRepository.findVisibleToOrgAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint device not found."));
        Instant now = Instant.now(clock);
        Instant visibleAfterAt = request.visibleAfterAt() == null ? now : request.visibleAfterAt();
        validateExpiry(visibleAfterAt, request.expiresAt());

        Map<String, Object> payload = normalizePayload(request);
        String idempotencyKey = resolveIdempotencyKey(deviceId, request);
        var existing = commandRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            validateIdempotentReplay(existing.get(), deviceId, request.type(), payload);
            return toDto(existing.get());
        }

        String subject = resolveSubject(context);
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(tenantId);
        command.setDevice(device);
        command.setCommandType(request.type());
        command.setIdempotencyKey(idempotencyKey);
        command.setStatus(CommandStatus.QUEUED);
        // BE-017: a destructive command is parked PENDING — invisible to the
        // agent claim path — until a second admin approves it.
        command.setApprovalStatus(requiresApproval ? ApprovalStatus.PENDING : ApprovalStatus.NOT_REQUIRED);
        command.setPayload(payload);
        command.setPriority(request.priority() == null ? 100 : request.priority());
        command.setAttemptCount(0);
        command.setMaxAttempts(request.maxAttempts() == null ? 3 : request.maxAttempts());
        command.setVisibleAfterAt(visibleAfterAt);
        command.setExpiresAt(request.expiresAt());
        command.setIssuedBySubject(subject);
        command.setIssuedAt(now);

        EndpointCommand saved = commandRepository.saveAndFlush(command);
        auditService.record(
                tenantId,
                device,
                saved,
                "ENDPOINT_COMMAND_CREATED",
                "CREATE_COMMAND",
                subject,
                idempotencyKey,
                createAuditMetadata(saved, requiresApproval, reason),
                null,
                Map.of("status", saved.getStatus().name(),
                        "approvalStatus", saved.getApprovalStatus().name())
        );
        return toDto(saved);
    }

    /**
     * BE-017 — record a second admin's dual-control decision on a destructive
     * command that is PENDING approval. The deciding admin MUST be a different
     * identity than the issuer; self-approval is rejected. An APPROVE makes the
     * command agent-claimable; a REJECT cancels it.
     */
    @Transactional
    public EndpointCommandDto approveCommand(AdminTenantContext context,
                                             UUID commandId,
                                             ApproveEndpointCommandRequest request) {
        if (request == null || request.decision() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An approval decision is required.");
        }
        UUID tenantId = context.tenantId();
        EndpointCommand command = commandRepository.findByTenantIdAndIdForUpdate(tenantId, commandId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint command not found."));

        if (command.getApprovalStatus() != ApprovalStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Command is not pending dual-control approval.");
        }

        String decidedBy = resolveSubject(context);
        if (Objects.equals(decidedBy, command.getIssuedBySubject())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A destructive command must be approved by a different admin than the issuer.");
        }

        Instant now = Instant.now(clock);
        if (command.getExpiresAt() != null && !command.getExpiresAt().isAfter(now)) {
            // The command stays QUEUED/PENDING: the agent claim queries already
            // filter out an expired command, and this throw would roll back any
            // eager EXPIRED status write anyway (status sweeping is separate).
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Command has expired and can no longer be approved.");
        }

        String reason = trimToNull(request.reason());
        if (request.decision() == ApprovalDecision.REJECT && reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A reason is required to reject a destructive command.");
        }

        EndpointCommandApproval approval = new EndpointCommandApproval();
        approval.setTenantId(tenantId);
        approval.setCommandId(command.getId());
        approval.setIssuerSubject(command.getIssuedBySubject());
        approval.setDecidedBySubject(decidedBy);
        approval.setDecision(request.decision());
        approval.setReason(reason);
        approval.setDecidedAt(now);

        String eventType;
        String action;
        if (request.decision() == ApprovalDecision.APPROVE) {
            command.setApprovalStatus(ApprovalStatus.APPROVED);
            // Clear the gate now without ever back-dating visibility.
            command.setVisibleAfterAt(maxInstant(command.getVisibleAfterAt(), now));
            eventType = "ENDPOINT_COMMAND_APPROVED";
            action = "APPROVE_COMMAND";
        } else {
            command.setApprovalStatus(ApprovalStatus.REJECTED);
            command.setStatus(CommandStatus.CANCELLED);
            command.setCancelledAt(now);
            eventType = "ENDPOINT_COMMAND_REJECTED";
            action = "REJECT_COMMAND";
        }

        approvalRepository.saveAndFlush(approval);
        EndpointCommand saved = commandRepository.saveAndFlush(command);
        auditService.record(
                tenantId,
                saved.getDevice(),
                saved,
                eventType,
                action,
                decidedBy,
                saved.getIdempotencyKey(),
                approvalAuditMetadata(saved, approval),
                null,
                Map.of("status", saved.getStatus().name(),
                        "approvalStatus", saved.getApprovalStatus().name())
        );
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public EndpointCommandDto getCommand(AdminTenantContext context, UUID commandId) {
        EndpointCommand command = commandRepository.findByTenantIdAndId(context.tenantId(), commandId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint command not found."));
        return toDto(command);
    }

    @Transactional(readOnly = true)
    public List<EndpointCommandDto> listCommands(AdminTenantContext context, UUID deviceId) {
        if (deviceId != null) {
            return listDeviceCommands(context, deviceId);
        }
        return commandRepository.findByTenantIdOrderByIssuedAtDesc(context.tenantId())
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<EndpointCommandDto> listDeviceCommands(AdminTenantContext context, UUID deviceId) {
        deviceRepository.findVisibleToOrgAndId(context.tenantId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint device not found."));
        return commandRepository.findByTenantIdAndDevice_IdOrderByIssuedAtDesc(context.tenantId(), deviceId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    public EndpointCommandDto toDto(EndpointCommand command) {
        EndpointCommandResultDto result = resultRepository.findByCommand_Id(command.getId())
                .map(this::toResultDto)
                .orElse(null);
        return new EndpointCommandDto(
                command.getId(),
                command.getTenantId(),
                command.getDevice().getId(),
                command.getCommandType(),
                command.getIdempotencyKey(),
                command.getStatus(),
                command.getApprovalStatus(),
                command.getPayload(),
                command.getPriority(),
                command.getAttemptCount(),
                command.getMaxAttempts(),
                command.getLockedBy(),
                command.getLockedUntil(),
                command.getVisibleAfterAt(),
                command.getExpiresAt(),
                command.getIssuedBySubject(),
                command.getIssuedAt(),
                command.getDeliveredAt(),
                command.getAckedAt(),
                command.getStartedAt(),
                command.getCompletedAt(),
                command.getCancelledAt(),
                command.getLastError(),
                command.getCreatedAt(),
                command.getUpdatedAt(),
                result
        );
    }

    private EndpointCommandResultDto toResultDto(EndpointCommandResult result) {
        return new EndpointCommandResultDto(
                result.getId(),
                result.getResultStatus(),
                result.getResultPayload(),
                result.getErrorCode(),
                result.getErrorMessage(),
                result.getExitCode(),
                result.getReportedAt(),
                result.getCreatedAt()
        );
    }

    private void validateCommandType(CommandType type) {
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Command type is required.");
        }
        // AG-028 Phase 1 (Codex plan-time iter-2 absorb): types in the
        // DEDICATED_PATH_ONLY set MUST go through their dedicated REST
        // surfaces. The generic path would bypass the preflight recompute
        // (INSTALL) / propose-approve maker-checker + capability+provenance
        // gates (UNINSTALL). 422 is the right status (RFC 4918 §11.2 spirit —
        // server understands the request but refuses to process it on this URI;
        // the right URI is the dedicated path). This migrates BE-021's prior
        // 409 to 422.
        if (DEDICATED_PATH_ONLY.contains(type)) {
            String surface = switch (type) {
                case INSTALL_SOFTWARE ->
                        "POST /api/v1/admin/endpoint-devices/{deviceId}/installs";
                case UNINSTALL_SOFTWARE ->
                        "POST /api/v1/admin/endpoint-devices/{deviceId}/uninstalls";
                case UPDATE_AGENT ->
                        "(dedicated signed self-update release surface; not the generic command endpoint)";
                case CHANGE_LOCAL_PASSWORD ->
                        "(dedicated local recovery / secret-delivery surface; not the generic command endpoint)";
                default -> "(its dedicated REST surface)";
            };
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    type.name() + " must be created via " + surface + ".");
        }
        if (!adminCreatableTypes.contains(type)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Command type is not enabled for admin creation.");
        }
    }

    // ────────────────────────────────────────────────────────────────
    // BE-032 — dedicated UPDATE_AGENT creation path

    /**
     * BE-032 — create an UPDATE_AGENT command from the approved release catalog.
     *
     * <p>The caller supplies only releaseId, schedule, reason and idempotency.
     * Binary URL, hash, signer thumbprint, signing tier, target version and
     * max-bytes are resolved from an APPROVED+enabled catalog row. The agent
     * still enforces local host/signer/lab-tier policy; this method prevents the
     * backend from accepting arbitrary update payloads through either the
     * generic command endpoint or this dedicated surface.
     */
    @Transactional
    public EndpointCommandDto createAgentUpdate(AdminTenantContext context,
                                                UUID deviceId,
                                                CreateAgentUpdateRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent update request is required.");
        }
        String releaseId = trimToNull(request.releaseId());
        if (releaseId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "releaseId is required.");
        }
        String reason = trimToNull(request.reason());
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required for agent update.");
        }

        UUID tenantId = context.tenantId();
        EndpointDevice device = deviceRepository.findVisibleToOrgAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint device not found."));
        validateRequiredDeploymentRing(device, request.requiredDeploymentRing());
        EndpointAgentUpdateRelease release = agentUpdateReleaseRepository
                .findByTenantIdAndReleaseId(tenantId, releaseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Agent update release not found."));
        if (release.getStatus() != AgentUpdateReleaseStatus.APPROVED || !release.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Agent update release must be APPROVED and enabled before dispatch.");
        }

        Instant now = Instant.now(clock);
        assertUpdateAgentHeartbeatFreshAndCapable(device, now);
        Instant visibleAfterAt = resolveInstallVisibleAfterAt(now, request.notBefore());
        validateExpiry(visibleAfterAt, request.expiresAt());

        Map<String, Object> payload = buildAgentUpdatePayload(release, device, reason,
                request.requiredDeploymentRing(), request.notBefore(), request.expiresAt());
        String idempotencyKey = resolveAgentUpdateIdempotencyKey(
                deviceId, release.getId(), request.idempotencyKey());
        var existing = commandRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            validateIdempotentReplay(existing.get(), deviceId, CommandType.UPDATE_AGENT, payload);
            return toDto(existing.get());
        }

        String subject = resolveSubject(context);
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(tenantId);
        command.setDevice(device);
        command.setCommandType(CommandType.UPDATE_AGENT);
        command.setIdempotencyKey(idempotencyKey);
        command.setStatus(CommandStatus.QUEUED);
        command.setApprovalStatus(ApprovalStatus.NOT_REQUIRED);
        command.setPayload(payload);
        command.setPriority(100);
        command.setAttemptCount(0);
        command.setMaxAttempts(1);
        command.setVisibleAfterAt(visibleAfterAt);
        command.setExpiresAt(request.expiresAt());
        command.setIssuedBySubject(subject);
        command.setIssuedAt(now);

        EndpointCommand saved = commandRepository.saveAndFlush(command);

        Map<String, Object> auditMetadata = new LinkedHashMap<>();
        auditMetadata.put("commandType", saved.getCommandType().name());
        auditMetadata.put("idempotencyKey", saved.getIdempotencyKey());
        auditMetadata.put("releaseId", release.getReleaseId());
        auditMetadata.put("releaseUuid", release.getId().toString());
        auditMetadata.put("targetVersion", release.getTargetVersion());
        auditMetadata.put("channel", release.getChannel().name());
        auditMetadata.put("signingTier", release.getSigningTier().name());
        auditMetadata.put("releaseRowVersion", release.getVersion());
        auditMetadata.put("deviceDeploymentRing", device.getDeploymentRing().name());
        if (request.requiredDeploymentRing() != null) {
            auditMetadata.put("requiredDeploymentRing", request.requiredDeploymentRing().name());
        }
        if (request.notBefore() != null) {
            auditMetadata.put("notBefore", request.notBefore().toString());
        }
        if (request.expiresAt() != null) {
            auditMetadata.put("expiresAt", request.expiresAt().toString());
        }
        auditMetadata.put("reason", reason);

        auditService.record(
                tenantId,
                device,
                saved,
                "ENDPOINT_AGENT_UPDATE_COMMAND_CREATED",
                "CREATE_AGENT_UPDATE_COMMAND",
                subject,
                idempotencyKey,
                auditMetadata,
                null,
                Map.of("status", saved.getStatus().name(),
                        "approvalStatus", saved.getApprovalStatus().name()));

        return toDto(saved);
    }

    // AG-042 — dedicated local recovery password creation path

    /**
     * AG-042 — create a local password change command without ever persisting
     * the raw password in {@code endpoint_commands.payload}. The generated
     * password is returned once to the admin response, stored encrypted in
     * {@code endpoint_command_secrets}, and injected only into the agent claim
     * response by {@link EndpointCommandSecretService}.
     */
    @Transactional
    public CreateLocalPasswordChangeResponse createLocalPasswordChange(AdminTenantContext context,
                                                                       UUID deviceId,
                                                                       CreateLocalPasswordChangeRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Local password change request is required.");
        }
        String username = validateLocalUsername(request.username());
        String reason = trimToNull(request.reason());
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A reason is required for local password change.");
        }

        UUID tenantId = context.tenantId();
        EndpointDevice device = deviceRepository.findVisibleToOrgAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint device not found."));
        Instant now = Instant.now(clock);
        Instant visibleAfterAt = resolveInstallVisibleAfterAt(now, request.notBefore());
        Instant expiresAt = request.expiresAt() == null
                ? visibleAfterAt.plus(DEFAULT_LOCAL_PASSWORD_SECRET_TTL)
                : request.expiresAt();
        validateExpiry(visibleAfterAt, expiresAt);

        String idempotencyKey = resolveLocalPasswordIdempotencyKey(deviceId, request.idempotencyKey());
        var existing = commandRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            validateLocalPasswordReplay(existing.get(), deviceId, username, reason,
                    request.notBefore(), request.expiresAt());
            // One-time disclosure: a replay confirms the existing command but
            // never reveals or regenerates password material.
            return new CreateLocalPasswordChangeResponse(toDto(existing.get()), null);
        }

        Map<String, Object> payload = buildLocalPasswordPayload(username, reason,
                request.notBefore(), expiresAt);
        String subject = resolveSubject(context);
        String oneTimePassword = commandSecretService.generateLocalPassword();
        EndpointCommand command = new EndpointCommand();
        command.setTenantId(tenantId);
        command.setDevice(device);
        command.setCommandType(CommandType.CHANGE_LOCAL_PASSWORD);
        command.setIdempotencyKey(idempotencyKey);
        command.setStatus(CommandStatus.QUEUED);
        command.setApprovalStatus(ApprovalStatus.PENDING);
        command.setPayload(payload);
        command.setPriority(100);
        command.setAttemptCount(0);
        command.setMaxAttempts(1);
        command.setVisibleAfterAt(visibleAfterAt);
        command.setExpiresAt(expiresAt);
        command.setIssuedBySubject(subject);
        command.setIssuedAt(now);

        EndpointCommand saved = commandRepository.saveAndFlush(command);
        commandSecretService.createLocalPasswordSecret(
                tenantId, device, saved, oneTimePassword, expiresAt, subject, username, reason);

        Map<String, Object> auditMetadata = createAuditMetadata(saved, true, reason);
        auditMetadata.put("username", username);
        auditMetadata.put("secretRef", LOCAL_PASSWORD_SECRET_REF);
        auditMetadata.put("secretExpiresAt", expiresAt.toString());
        auditService.record(
                tenantId,
                device,
                saved,
                "ENDPOINT_LOCAL_PASSWORD_COMMAND_CREATED",
                "CREATE_LOCAL_PASSWORD_COMMAND",
                subject,
                idempotencyKey,
                auditMetadata,
                null,
                Map.of("status", saved.getStatus().name(),
                        "approvalStatus", saved.getApprovalStatus().name()));

        return new CreateLocalPasswordChangeResponse(toDto(saved), oneTimePassword);
    }

    // BE-021 — dedicated INSTALL_SOFTWARE creation path

    /**
     * BE-021 — create an INSTALL_SOFTWARE command after recomputing the
     * preflight decision. BLOCK → {@link InstallBlockedException} (mapped
     * to 409 + {@link InstallPreflightResponse} body). PASS / WARN →
     * backend-controlled payload + standard idempotency. Codex 019e6dfb
     * iter-3 AGREE.
     */
    @Transactional
    public EndpointCommandDto createInstall(AdminTenantContext context,
                                            UUID deviceId,
                                            CreateInstallRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Install request is required.");
        }
        String slug = trimToNull(request.catalogItemId());
        if (slug == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "catalogItemId is required.");
        }

        UUID tenantId = context.tenantId();
        EndpointDevice device = deviceRepository.findVisibleToOrgAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint device not found."));
        validateRequiredDeploymentRing(device, request.requiredDeploymentRing());
        EndpointSoftwareCatalogItem catalogItem = catalogRepository
                .findByTenantIdAndCatalogItemId(tenantId, slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalog item not found."));

        String reason = trimToNull(request.reason());
        Instant now = Instant.now(clock);
        Instant visibleAfterAt = resolveInstallVisibleAfterAt(now, request.notBefore());
        validateExpiry(visibleAfterAt, request.expiresAt());
        String idempotencyKey = resolveInstallIdempotencyKey(
                deviceId, catalogItem.getId(), request.idempotencyKey());

        // BE-021 (Codex 019e6dfb iter-4 P1-1): idempotency replay MUST
        // run BEFORE the preflight recompute, otherwise a legitimate
        // retry of a PASS-issued command can fail with a 409 BLOCK if
        // the device / catalog drifted between the first issue and the
        // retry. Stable-field check guards against accidental key reuse
        // with mismatched device or catalog.
        var existing = commandRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
        if (existing.isPresent()) {
            EndpointCommand cmd = existing.get();
            UUID existingCatalogUuid = parseUuid(cmd.getPayload() == null
                    ? null : cmd.getPayload().get("catalogItemUuid"));
            if (cmd.getCommandType() != CommandType.INSTALL_SOFTWARE
                    || !Objects.equals(cmd.getDevice().getId(), deviceId)
                    || !Objects.equals(existingCatalogUuid, catalogItem.getId())
                    || !Objects.equals(cmd.getPayload() == null ? null : cmd.getPayload().get("requiredDeploymentRing"),
                            request.requiredDeploymentRing() == null ? null : request.requiredDeploymentRing().name())
                    || !Objects.equals(cmd.getPayload() == null ? null : cmd.getPayload().get("notBefore"),
                            instantStringOrNull(request.notBefore()))
                    || !Objects.equals(cmd.getPayload() == null ? null : cmd.getPayload().get("expiresAt"),
                            instantStringOrNull(request.expiresAt()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Install idempotency key already used by another install.");
            }
            return toDto(cmd);
        }

        // Recompute the preflight ONLY when creating a new command.
        // Cached PASS reuse is forbidden
        // (EndpointInstallPreflightService Javadoc).
        InstallPreflightResponse preflight =
                preflightService.evaluate(context, deviceId, slug);
        if (preflight.decision() == InstallPreflightDecision.BLOCK) {
            throw new InstallBlockedException(preflight);
        }

        Map<String, Object> payload = buildInstallPayload(catalogItem, preflight, reason,
                device.getDeploymentRing(), request.requiredDeploymentRing(), request.notBefore(), request.expiresAt());

        EndpointCommand command = new EndpointCommand();
        command.setTenantId(tenantId);
        command.setDevice(device);
        command.setCommandType(CommandType.INSTALL_SOFTWARE);
        command.setIdempotencyKey(idempotencyKey);
        command.setStatus(CommandStatus.QUEUED);
        // BE-021 MVP (Codex D): catalog maker-checker + preflight PASS
        // is sufficient; no dual-control approval gate on install
        // creation. Future HIGH-risk WARN escalation can flip this.
        command.setApprovalStatus(ApprovalStatus.NOT_REQUIRED);
        command.setPayload(payload);
        command.setPriority(100);
        command.setAttemptCount(0);
        command.setMaxAttempts(3);
        command.setVisibleAfterAt(visibleAfterAt);
        command.setExpiresAt(request.expiresAt());
        String subject = resolveSubject(context);
        command.setIssuedBySubject(subject);
        command.setIssuedAt(now);

        EndpointCommand saved = commandRepository.saveAndFlush(command);

        Map<String, Object> auditMetadata = new LinkedHashMap<>();
        auditMetadata.put("commandType", saved.getCommandType().name());
        auditMetadata.put("idempotencyKey", saved.getIdempotencyKey());
        auditMetadata.put("catalogItemId", slug);
        auditMetadata.put("catalogItemUuid", catalogItem.getId().toString());
        auditMetadata.put("catalogPackageId", catalogItem.getPackageId());
        auditMetadata.put("catalogRowVersion", catalogItem.getVersion());
        auditMetadata.put("deviceDeploymentRing", device.getDeploymentRing().name());
        if (request.requiredDeploymentRing() != null) {
            auditMetadata.put("requiredDeploymentRing", request.requiredDeploymentRing().name());
        }
        if (request.notBefore() != null) {
            auditMetadata.put("notBefore", request.notBefore().toString());
        }
        if (request.expiresAt() != null) {
            auditMetadata.put("expiresAt", request.expiresAt().toString());
        }
        auditMetadata.put("preflightDecision", preflight.decision().name());
        auditMetadata.put("preflightDecisionAt", preflight.evaluatedAt().toString());
        if (preflight.warnings() != null && !preflight.warnings().isEmpty()) {
            auditMetadata.put("preflightWarnCodes", preflight.warnings());
        }
        if (reason != null) {
            auditMetadata.put("reason", reason);
        }
        auditService.record(
                tenantId,
                device,
                saved,
                "ENDPOINT_INSTALL_COMMAND_CREATED",
                "CREATE_INSTALL_COMMAND",
                subject,
                idempotencyKey,
                auditMetadata,
                null,
                Map.of("status", saved.getStatus().name(),
                        "approvalStatus", saved.getApprovalStatus().name()));

        return toDto(saved);
    }

    private Map<String, Object> buildInstallPayload(EndpointSoftwareCatalogItem catalogItem,
                                                    InstallPreflightResponse preflight,
                                                    String reason,
                                                    DeploymentRing deviceDeploymentRing,
                                                    DeploymentRing requiredDeploymentRing,
                                                    Instant notBefore,
                                                    Instant expiresAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("catalogItemId", catalogItem.getCatalogItemId());
        payload.put("catalogItemUuid", catalogItem.getId().toString());
        payload.put("catalogPackageId", catalogItem.getPackageId());
        payload.put("catalogRowVersion", catalogItem.getVersion());
        payload.put("packageProvider", catalogItem.getProvider() == null
                ? null : catalogItem.getProvider().name());
        payload.put("sourceType", catalogItem.getSourceType() == null
                ? null : catalogItem.getSourceType().name());
        payload.put("installerType", catalogItem.getInstallerType() == null
                ? null : catalogItem.getInstallerType().name());

        // AG-027 COMMAND-CONTRACT §7 — agent-consumed install fields. The agent
        // (executor.go unmarshalInstallRequest + RunInstall) FAIL-CLOSES the
        // command unless provider / packageId / argsPolicyPreset and
        // detectionRule.{type,packageId} are present and mutually consistent
        // (provider=WINGET, packageId==detectionRule.packageId). The
        // catalog-shaped keys above (catalogPackageId / packageProvider) are
        // NOT what AG-027 reads, so without this block the agent rejected the
        // payload at "missing detectionRule.type" BEFORE winget ever ran — the
        // true reason the 7-Zip install pilot never completed end-to-end.
        String agentPackageId = catalogItem.getPackageId();
        payload.put("provider", catalogItem.getProvider() == null
                ? null : catalogItem.getProvider().name());
        payload.put("packageId", agentPackageId);
        payload.put("argsPolicyPreset", mapArgsPolicyPreset(catalogItem.getSilentArgsPolicy()));
        Map<String, Object> versionPredicate = new LinkedHashMap<>();
        versionPredicate.put("type", catalogItem.getVersionPolicyType() == null
                ? "LATEST" : catalogItem.getVersionPolicyType().name());
        // LATEST → null spec; EXACT/MINIMUM/RANGE carry the catalog authoring
        // string. The agent does NOT resolve LATEST → a concrete version, so
        // resolvedVersion stays null for LATEST (COMMAND-CONTRACT §7).
        versionPredicate.put("spec", catalogItem.getVersionPolicyValue());
        payload.put("versionPredicate", versionPredicate);
        payload.put("resolvedVersion", null);
        // Derive detectionRule from the catalog's AUTHORED rule (validated at
        // catalog-create by DetectionRuleValidator) and FAIL-CLOSE on drift /
        // a not-yet-agent-supported type — never synthesize a WINGET_PACKAGE
        // rule from packageId, which would let a drifted/REGISTRY_UNINSTALL
        // catalog install while the agent verifies the wrong target
        // (Codex 019e77bd).
        payload.put("detectionRule", buildAgentDetectionRule(catalogItem, agentPackageId));
        payload.put("catalogItemKey", catalogItem.getCatalogItemId());
        if (deviceDeploymentRing != null) {
            payload.put("deviceDeploymentRing", deviceDeploymentRing.name());
        }
        if (requiredDeploymentRing != null) {
            payload.put("requiredDeploymentRing", requiredDeploymentRing.name());
        }
        if (notBefore != null) {
            payload.put("notBefore", notBefore.toString());
        }
        if (expiresAt != null) {
            payload.put("expiresAt", expiresAt.toString());
        }

        payload.put("preflightDecision", preflight.decision().name());
        payload.put("preflightDecisionAt", preflight.evaluatedAt() == null
                ? null : preflight.evaluatedAt().toString());
        if (preflight.evidence() != null) {
            Map<String, Object> evidenceRefs = new LinkedHashMap<>();
            evidenceRefs.put("inventorySnapshotId", preflight.evidence().inventorySnapshotId() == null
                    ? null : preflight.evidence().inventorySnapshotId().toString());
            evidenceRefs.put("inventorySnapshotRowVersion", preflight.evidence().inventorySnapshotRowVersion());
            evidenceRefs.put("catalogRowVersion", preflight.evidence().catalogRowVersion());
            payload.put("preflightEvidenceRefs", evidenceRefs);
        }
        if (preflight.warnings() != null && !preflight.warnings().isEmpty()) {
            payload.put("preflightWarnCodes", preflight.warnings());
        }
        if (reason != null) {
            payload.put("reason", reason);
        }
        return payload;
    }

    private static Instant resolveInstallVisibleAfterAt(Instant now, Instant notBefore) {
        if (notBefore == null) {
            return now;
        }
        if (notBefore.isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Install notBefore must be now or in the future.");
        }
        return notBefore;
    }

    private static String instantStringOrNull(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static void validateRequiredDeploymentRing(EndpointDevice device, DeploymentRing requiredRing) {
        if (requiredRing == null) {
            return;
        }
        DeploymentRing actual = device.getDeploymentRing() == null ? DeploymentRing.PILOT : device.getDeploymentRing();
        if (actual != requiredRing) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Endpoint device is not assigned to the requested deployment ring.");
        }
    }

    /**
     * Map the catalog's {@link CatalogSilentArgsPolicy} onto the AG-027
     * {@code argsPolicyPreset} enum slot the agent recognises
     * (install_winget.go::argsPresets). Both presets ship the same arg slice
     * in v1; the distinct name preserves operator intent in the audit trail.
     */
    private static String mapArgsPolicyPreset(CatalogSilentArgsPolicy policy) {
        if (policy == null) {
            return "DEFAULT";
        }
        return switch (policy) {
            case VENDOR_RECOMMENDED -> "VENDOR_RECOMMENDED_WINGET_NO_UPGRADE";
            case DEFAULT -> "DEFAULT";
        };
    }

    /**
     * Derive the AG-027 wire {@code detectionRule} from the catalog item's
     * AUTHORED (validated + normalized) detection rule. The agent now supports
     * two installable detector families (agent PR #43 / Codex 019e7d82):
     * <ul>
     *   <li>{@code WINGET_PACKAGE} — forwarded as {@code {type, packageId}}.
     *       The authoring field is {@code packageId} (legacy {@code
     *       wingetPackageId} tolerated for un-migrated rows). The WINGET
     *       identity invariant ({@code packageId} case-insensitively equal to
     *       the catalog {@code packageId}) fails closed so the agent never
     *       installs package A while verifying package B (Codex 019e77bd).</li>
     *   <li>{@code REGISTRY_UNINSTALL} — the normalized registry selector is
     *       forwarded verbatim (no fabrication). The registry selector IS the
     *       rule's identity, so NO {@code packageId} and NO WINGET identity
     *       invariant apply.</li>
     *   <li>{@code FILE_EXISTS} / {@code FILE_SHA256} / {@code FILE_VERSION} —
     *       Path C2 (Codex 019e893a Opsiyon C): forwarded via
     *       {@link #buildFileWireRule}; catalog field {@code absolutePath} is
     *       renamed to the agent's canonical {@code path} contract, per-type
     *       extras (expectedSha256 + maxHashBytes for FILE_SHA256;
     *       versionPredicate + fileVersionField for FILE_VERSION) are
     *       forwarded verbatim from the validator-normalized payload.</li>
     * </ul>
     * Fail-closed otherwise: a missing / unknown / legacy-shape rule is
     * rejected, never silently coerced.
     *
     * <p>AG-028 Phase 1b (Codex post-impl iter-1 absorb, thread `019e8dcd`
     * must-fix #5) — visibility widened to {@code public static} so the
     * dedicated uninstall dispatch ({@code EndpointUninstallService}) can
     * reuse the SAME catalog-shape → agent-wire-shape translation. The
     * uninstall agent uses identical detection-rule semantics for the
     * authoritative absence check; forwarding raw catalog rules would skip
     * the {@code absolutePath → path} translation for FILE_* and the WINGET
     * identity invariant.
     */
    public static Map<String, Object> buildAgentDetectionRule(EndpointSoftwareCatalogItem catalogItem,
                                                               String agentPackageId) {
        Map<String, Object> catalogRule = catalogItem.getDetectionRule();
        Object typeObj = catalogRule == null ? null : catalogRule.get("type");
        String type = typeObj == null ? null : typeObj.toString();
        if (type == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Catalog detection rule is missing 'type' (cannot dispatch install).");
        }
        return switch (type) {
            case "WINGET_PACKAGE" -> buildWingetWireRule(catalogRule, agentPackageId);
            case "REGISTRY_UNINSTALL" -> buildRegistryWireRule(catalogRule);
            case "FILE_EXISTS", "FILE_SHA256", "FILE_VERSION" -> buildFileWireRule(catalogRule, type);
            default -> throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "detection_rule_type_not_supported_by_agent: unknown catalog detection rule type '"
                            + type + "'.");
        };
    }

    /**
     * Path C2 — Codex 019e893a Opsiyon C: forward FILE_EXISTS / FILE_SHA256 /
     * FILE_VERSION to the agent (Path C1 lands the agent-side detectors).
     * Catalog authoring stores the path as {@code absolutePath}, the agent
     * contract field is {@code path} (mirrors the C1 {@code DetectionRule}
     * struct); we translate at this dispatch point. The validator has already
     * normalized + enforced predicate shape, so we copy the agent-recognised
     * keys without re-validation.
     */
    private static Map<String, Object> buildFileWireRule(Map<String, Object> catalogRule, String type) {
        Object absolutePath = catalogRule.get("absolutePath");
        if (absolutePath == null || absolutePath.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "detection_rule_type_not_supported_by_agent: " + type
                            + " catalog rule is missing 'absolutePath'.");
        }
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", type);
        // Agent contract field name is `path` (C1 DetectionRule.Path).
        wire.put("path", absolutePath.toString());

        switch (type) {
            case "FILE_SHA256" -> {
                copyIfPresent(catalogRule, wire, "expectedSha256");
                // Optional per-rule cap forwarded as-is; agent re-caps.
                copyIfPresent(catalogRule, wire, "maxHashBytes");
            }
            case "FILE_VERSION" -> {
                copyIfPresent(catalogRule, wire, "versionPredicate");
                copyIfPresent(catalogRule, wire, "fileVersionField");
            }
            default -> {
                // FILE_EXISTS: path-only.
            }
        }
        return wire;
    }

    private static Map<String, Object> buildWingetWireRule(Map<String, Object> catalogRule,
                                                           String agentPackageId) {
        Object pkg = catalogRule.get("packageId");
        if (pkg == null) {
            // Tolerate the legacy authoring field on rows that predate the V21
            // wingetPackageId→packageId sweep; the normalized field is packageId.
            pkg = catalogRule.get("wingetPackageId");
        }
        String detectionPackageId = pkg == null ? null : pkg.toString().trim();
        if (detectionPackageId == null || detectionPackageId.isEmpty()
                || agentPackageId == null
                || !detectionPackageId.equalsIgnoreCase(agentPackageId.trim())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Catalog detection rule packageId must equal the catalog packageId "
                            + "(drift fails closed before dispatch).");
        }
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", "WINGET_PACKAGE");
        wire.put("packageId", detectionPackageId);
        return wire;
    }

    /**
     * Forward the normalized REGISTRY_UNINSTALL selector verbatim. The rule was
     * validated + normalized at catalog authoring (DetectionRuleValidator) so it
     * carries only agent-recognised keys; we copy them rather than pass the map
     * through so no stray / legacy key (e.g. a surviving {@code hive}) reaches
     * the wire. A legacy-shape row with neither {@code productCode} nor
     * {@code displayName} fails closed here with a clear reauthor signal.
     */
    private static Map<String, Object> buildRegistryWireRule(Map<String, Object> catalogRule) {
        boolean hasProductCode = catalogRule.get("productCode") != null;
        boolean hasDisplayName = catalogRule.get("displayName") != null;
        if (!hasProductCode && !hasDisplayName) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "detection_rule_type_not_supported_by_agent: REGISTRY_UNINSTALL catalog rule "
                            + "is legacy-shaped (no productCode or displayName); reauthor to the "
                            + "agent schema before dispatch.");
        }
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", "REGISTRY_UNINSTALL");
        copyIfPresent(catalogRule, wire, "productCode");
        copyIfPresent(catalogRule, wire, "displayName");
        copyIfPresent(catalogRule, wire, "displayNameMatch");
        copyIfPresent(catalogRule, wire, "publisher");
        copyIfPresent(catalogRule, wire, "publisherMatch");
        copyIfPresent(catalogRule, wire, "allowPublisherMissing");
        return wire;
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        Object value = from.get(key);
        if (value != null) {
            to.put(key, value);
        }
    }

    /**
     * Compose {@code admin-install:{deviceId}:{catalogUuid}:{key}} so
     * the canonical string always fits the
     * {@code endpoint_commands.idempotency_key VARCHAR(128)} column.
     * Caller-supplied keys are bounded to 40 chars by the request DTO
     * size annotation (CreateInstallRequest, Codex iter-4 P1-2). If the
     * caller-supplied key is unusually long (programmatic callers that
     * bypass the DTO validator), we hash it down to the first 16 hex
     * chars of SHA-256.
     */
    private String resolveInstallIdempotencyKey(UUID deviceId, UUID catalogUuid, String requestedKey) {
        // Canonical key shape: `admin-install:{deviceId(36)}:{catalogUuid(36)}:{body}`.
        // Fixed prefix = 88 chars; body MUST fit in 40 chars so the canonical
        // string stays ≤ 128 (endpoint_commands.idempotency_key VARCHAR(128)).
        // CreateInstallRequest @Size(max=40) enforces this on caller input;
        // the > 40 fall-through here covers programmatic callers
        // (Codex iter-4 P1-2).
        String key = trimToNull(requestedKey);
        if (key == null) {
            key = UUID.randomUUID().toString();
        } else if (key.length() > 40) {
            key = sha256Prefix(key);
        }
        return "admin-install:" + deviceId + ":" + catalogUuid + ":" + key;
    }

    private String resolveAgentUpdateIdempotencyKey(UUID deviceId, UUID releaseUuid, String requestedKey) {
        String key = trimToNull(requestedKey);
        if (key == null) {
            key = UUID.randomUUID().toString();
        } else if (key.length() > 31) {
            key = sha256Prefix(key);
        }
        return "admin-update-agent:" + deviceId + ":" + releaseUuid + ":" + key;
    }

    private String resolveLocalPasswordIdempotencyKey(UUID deviceId, String requestedKey) {
        String key = trimToNull(requestedKey);
        if (key == null) {
            key = UUID.randomUUID().toString();
        } else if (key.length() > 40) {
            key = sha256Prefix(key);
        }
        return "admin-local-password:" + deviceId + ":" + key;
    }

    private static String sha256Prefix(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static UUID parseUuid(Object node) {
        if (node == null) {
            return null;
        }
        if (node instanceof UUID u) {
            return u;
        }
        try {
            return UUID.fromString(String.valueOf(node).trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Map<String, Object> buildLocalPasswordPayload(String username,
                                                          String reason,
                                                          Instant notBefore,
                                                          Instant expiresAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("reason", reason);
        payload.put("secretRef", LOCAL_PASSWORD_SECRET_REF);
        payload.put("secretName", "newPassword");
        payload.put("secretDelivery", "agent_claim_once");
        if (notBefore != null) {
            payload.put("notBefore", notBefore.toString());
        }
        payload.put("expiresAt", expiresAt.toString());
        return payload;
    }

    private String validateLocalUsername(String username) {
        String value = trimToNull(username);
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Local username is required.");
        }
        if (!value.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Local username must be a local SAM name without domain, UPN, path, or whitespace.");
        }
        return value;
    }

    private void validateExpiry(Instant visibleAfterAt, Instant expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(visibleAfterAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Command expiry must be after visible time.");
        }
    }

    private void validateIdempotentReplay(EndpointCommand existing,
                                          UUID deviceId,
                                          CommandType type,
                                          Map<String, Object> payload) {
        if (!Objects.equals(existing.getDevice().getId(), deviceId)
                || existing.getCommandType() != type
                || !Objects.equals(existing.getPayload(), payload)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key is already used by another command.");
        }
    }

    private void validateLocalPasswordReplay(EndpointCommand existing,
                                             UUID deviceId,
                                             String username,
                                             String reason,
                                             Instant notBefore,
                                             Instant expiresAt) {
        Map<String, Object> payload = existing.getPayload() == null
                ? Map.of()
                : existing.getPayload();
        if (!Objects.equals(existing.getDevice().getId(), deviceId)
                || existing.getCommandType() != CommandType.CHANGE_LOCAL_PASSWORD
                || !Objects.equals(payload.get("username"), username)
                || !Objects.equals(payload.get("reason"), reason)
                || !Objects.equals(payload.get("notBefore"), instantStringOrNull(notBefore))
                || (expiresAt != null && !Objects.equals(payload.get("expiresAt"), expiresAt.toString()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Local password idempotency key is already used by another command.");
        }
    }

    private Map<String, Object> normalizePayload(CreateEndpointCommandRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (request.payload() != null) {
            payload.putAll(request.payload());
        }
        if (request.type() == CommandType.COLLECT_INVENTORY) {
            applyCollectInventoryOptIns(payload);
        }
        String reason = trimToNull(request.reason());
        if (reason != null) {
            // BE-017: the dedicated `reason` field is authoritative — overwrite
            // any caller-supplied payload.reason so the validated (and, for a
            // destructive command, mandatory) justification is the value the
            // agent actually receives.
            payload.put("reason", reason);
        }
        return payload;
    }

    /**
     * Faz 22.5 keystone — the operator-initiated "Envanteri Şimdi Topla"
     * full collect (the only backend-constructed {@code COLLECT_INVENTORY}
     * path; this generic admin command surface is what the İşlemler tab
     * action targets) MUST carry the same read-only visibility opt-ins as the
     * web "Envanteri Şimdi Topla" button. Otherwise a direct API/operator
     * caller can queue a syntactically valid collect command that only runs
     * the AG-025H lightweight contract and leaves the drawer's posture,
     * diagnostic and visibility tabs empty.
     *
     * <p>The wire key names below are the frozen agent contract and must match
     * the agent's {@code boolPayload} lookups exactly:
     * {@code includeSoftware}, {@code includeWinGetEgress},
     * {@code includeHardware}, {@code includeDeviceHealth},
     * {@code includeOutdatedSoftware}, {@code includeHotfixPosture},
     * {@code includeDiagnostics}, {@code includeServices},
     * {@code includeStartupExposure}, and {@code includeAppControl}.
     *
     * <p>Scope (deliberate — Codex review to validate the default): this is
     * the operator-initiated full collect, so running the heavier health +
     * outdated probes is appropriate and is what the views' empty-state
     * promises. The lightweight heartbeat / auto-enroll path stays opt-out for
     * cost (AG-025H) — and it does NOT flow through here: no backend code
     * constructs a {@code COLLECT_INVENTORY} command other than this admin
     * command-creation path, so opting in here cannot leak into the
     * heartbeat default.
     *
     * <p>An explicit caller-supplied value for either key is respected (not
     * overwritten): a future lightweight caller that sends
     * {@code includeDeviceHealth=false} or {@code includeServices=false}
     * keeps the AG-025H opt-out boundary. Only an absent key is defaulted to
     * {@code true}, so the backend guarantees the documented data path for
     * manual collect-now without depending on every client to remember the
     * bits.
     */
    private static void applyCollectInventoryOptIns(Map<String, Object> payload) {
        payload.putIfAbsent("includeSoftware", true);
        payload.putIfAbsent("includeWinGetEgress", true);
        payload.putIfAbsent("includeHardware", true);
        payload.putIfAbsent("includeDeviceHealth", true);
        payload.putIfAbsent("includeOutdatedSoftware", true);
        payload.putIfAbsent("includeHotfixPosture", true);
        payload.putIfAbsent("includeDiagnostics", true);
        payload.putIfAbsent("includeServices", true);
        payload.putIfAbsent("includeStartupExposure", true);
        payload.putIfAbsent("includeAppControl", true);
    }

    private void assertUpdateAgentHeartbeatFreshAndCapable(EndpointDevice device,
                                                           Instant now) {
        var latest = heartbeatRepository.findFirstByDevice_IdOrderByReceivedAtDesc(device.getId());
        Instant receivedAt = latest.map(EndpointHeartbeat::getReceivedAt).orElse(null);
        boolean fresh = receivedAt != null
                && Duration.between(receivedAt, now).compareTo(updateAgentHeartbeatFreshnessTtl) <= 0;
        if (!fresh) {
            throw new ResponseStatusException(HttpStatus.FAILED_DEPENDENCY,
                    "Agent heartbeat is stale (receivedAt="
                            + receivedAt + ", ttl=" + updateAgentHeartbeatFreshnessTtl
                            + "). Retry after the agent reconnects.");
        }
        boolean advertised = latest
                .map(EndpointHeartbeat::getPayload)
                .map(payload -> payload.get("capabilities"))
                .map(node -> containsCapability(node, CommandType.UPDATE_AGENT.name()))
                .orElse(false);
        if (!advertised) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Agent does not advertise the '" + CommandType.UPDATE_AGENT.name()
                            + "' capability on the most recent heartbeat. "
                            + "Upgrade/configure the agent and retry.");
        }
    }

    private boolean containsCapability(Object capabilitiesNode, String requiredCapability) {
        if (capabilitiesNode == null) {
            return false;
        }
        if (capabilitiesNode instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null && requiredCapability.equalsIgnoreCase(
                        String.valueOf(item).trim())) {
                    return true;
                }
            }
            return false;
        }
        if (capabilitiesNode instanceof Map<?, ?> map) {
            Object val = map.get(requiredCapability);
            return val instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(val));
        }
        return false;
    }

    private Map<String, Object> buildAgentUpdatePayload(EndpointAgentUpdateRelease release,
                                                        EndpointDevice device,
                                                        String reason,
                                                        DeploymentRing requiredDeploymentRing,
                                                        Instant notBefore,
                                                        Instant expiresAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("releaseId", release.getReleaseId());
        payload.put("channel", release.getChannel().name());
        payload.put("ring", device.getDeploymentRing().name());
        payload.put("targetVersion", release.getTargetVersion());
        payload.put("binaryUrl", release.getBinaryUrl());
        payload.put("claimedSha256", release.getSha256());
        payload.put("claimedSignerThumbprint", release.getSignerThumbprint());
        payload.put("signingTier", agentWireSigningTier(release.getSigningTier()));
        payload.put("maxBytes", release.getMaxBytes());
        payload.put("reason", reason);
        if (requiredDeploymentRing != null) {
            payload.put("requiredDeploymentRing", requiredDeploymentRing.name());
        }
        if (notBefore != null) {
            payload.put("notBefore", notBefore.toString());
        }
        if (expiresAt != null) {
            payload.put("expiresAt", expiresAt.toString());
        }
        return payload;
    }

    private String agentWireSigningTier(AgentUpdateSigningTier tier) {
        if (tier == AgentUpdateSigningTier.TRUSTED_SIGNED) {
            return "TRUSTED";
        }
        return "LAB_ONLY_EVIDENCE";
    }

    private Map<String, Object> createAuditMetadata(EndpointCommand command,
                                                    boolean requiresApproval,
                                                    String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("commandType", command.getCommandType().name());
        metadata.put("idempotencyKey", command.getIdempotencyKey());
        metadata.put("priority", command.getPriority());
        metadata.put("maxAttempts", command.getMaxAttempts());
        metadata.put("requiresApproval", requiresApproval);
        metadata.put("approvalStatus", command.getApprovalStatus().name());
        metadata.put("issuerSubject", command.getIssuedBySubject());
        // BE-017 (Codex 019e50e0): keep the justification durable in the audit
        // trail independently of the payload map.
        if (reason != null) {
            metadata.put("reason", reason);
        }
        return metadata;
    }

    private Map<String, Object> approvalAuditMetadata(EndpointCommand command, EndpointCommandApproval approval) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("commandType", command.getCommandType().name());
        metadata.put("decision", approval.getDecision().name());
        metadata.put("issuerSubject", approval.getIssuerSubject());
        metadata.put("decidedBySubject", approval.getDecidedBySubject());
        if (approval.getReason() != null) {
            metadata.put("reason", approval.getReason());
        }
        return metadata;
    }

    private static Instant maxInstant(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return a.isAfter(b) ? a : b;
    }

    private String resolveIdempotencyKey(UUID deviceId, CreateEndpointCommandRequest request) {
        String requested = trimToNull(request.idempotencyKey());
        if (requested != null) {
            return requested;
        }
        return "admin:" + deviceId + ":" + request.type().name() + ":" + UUID.randomUUID();
    }

    private String resolveSubject(AdminTenantContext context) {
        String subject = trimToNull(context.subject());
        return subject == null ? "unknown-admin" : subject;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
