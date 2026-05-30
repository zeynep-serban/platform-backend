package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.ApproveEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.CreateInstallRequest;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandResultDto;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse;
import com.example.endpointadmin.dto.v1.admin.InstallPreflightResponse.InstallPreflightDecision;
import com.example.endpointadmin.exception.InstallBlockedException;
import com.example.endpointadmin.model.ApprovalDecision;
import com.example.endpointadmin.model.ApprovalStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandApproval;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.CatalogSilentArgsPolicy;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.repository.EndpointCommandApprovalRepository;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
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

    private final EndpointCommandRepository commandRepository;
    private final EndpointCommandResultRepository resultRepository;
    private final EndpointCommandApprovalRepository approvalRepository;
    private final EndpointDeviceRepository deviceRepository;
    private final EndpointSoftwareCatalogItemRepository catalogRepository;
    private final EndpointInstallPreflightService preflightService;
    private final EndpointAuditService auditService;
    private final Clock clock;

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
                                       EndpointInstallPreflightService preflightService,
                                       EndpointAuditService auditService,
                                       Clock clock,
                                       @Value("${endpoint-admin.commands.admin-creatable-types:COLLECT_INVENTORY}")
                                       Set<CommandType> adminCreatableTypes) {
        this.commandRepository = commandRepository;
        this.resultRepository = resultRepository;
        this.approvalRepository = approvalRepository;
        this.deviceRepository = deviceRepository;
        this.catalogRepository = catalogRepository;
        this.preflightService = preflightService;
        this.auditService = auditService;
        this.clock = clock;
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
        EndpointDevice device = deviceRepository.findByTenantIdAndId(tenantId, deviceId)
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
        deviceRepository.findByTenantIdAndId(context.tenantId(), deviceId)
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
        // BE-021 (Codex 019e6dfb iter-3 P0-1): INSTALL_SOFTWARE cannot
        // be created via the generic command surface — the preflight
        // recompute would be bypassed. The dedicated
        // POST .../endpoint-devices/{deviceId}/installs path is the
        // only legal install creation route.
        if (type == CommandType.INSTALL_SOFTWARE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "INSTALL_SOFTWARE must be created via "
                            + "POST /api/v1/admin/endpoint-devices/{deviceId}/installs.");
        }
        if (!adminCreatableTypes.contains(type)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Command type is not enabled for admin creation.");
        }
    }

    // ────────────────────────────────────────────────────────────────
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
        EndpointDevice device = deviceRepository.findByTenantIdAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint device not found."));
        EndpointSoftwareCatalogItem catalogItem = catalogRepository
                .findByTenantIdAndCatalogItemId(tenantId, slug)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalog item not found."));

        String reason = trimToNull(request.reason());
        Instant now = Instant.now(clock);
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
                    || !Objects.equals(existingCatalogUuid, catalogItem.getId())) {
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

        Map<String, Object> payload = buildInstallPayload(catalogItem, preflight, reason);

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
        command.setVisibleAfterAt(now);
        command.setExpiresAt(null);
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
                                                    String reason) {
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
     * Derive the AG-027 wire {@code detectionRule} ({@code {type, packageId}})
     * from the catalog item's AUTHORED detection rule, translating the backend
     * authoring field {@code wingetPackageId} to the agent wire field
     * {@code packageId}. Fail-closed (Codex 019e77bd):
     * <ul>
     *   <li>type must be {@code WINGET_PACKAGE} — a not-yet-agent-supported
     *       type (REGISTRY_UNINSTALL, FILE_EXISTS, …) must NOT be silently
     *       converted; the install is rejected here rather than dispatched with
     *       a fabricated rule the agent would mis-verify;</li>
     *   <li>{@code wingetPackageId} must be present and case-insensitively
     *       equal to {@code packageId} — drift fails closed so the agent never
     *       installs package A while verifying package B.</li>
     * </ul>
     */
    private static Map<String, Object> buildAgentDetectionRule(EndpointSoftwareCatalogItem catalogItem,
                                                               String agentPackageId) {
        Map<String, Object> catalogRule = catalogItem.getDetectionRule();
        Object type = catalogRule == null ? null : catalogRule.get("type");
        if (!"WINGET_PACKAGE".equals(type)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Catalog detection rule type '" + type
                            + "' is not agent-installable (AG-027 v1 supports WINGET_PACKAGE only).");
        }
        Object wingetPackageId = catalogRule.get("wingetPackageId");
        String detectionPackageId = wingetPackageId == null ? null : wingetPackageId.toString().trim();
        if (detectionPackageId == null || detectionPackageId.isEmpty()
                || agentPackageId == null
                || !detectionPackageId.equalsIgnoreCase(agentPackageId.trim())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Catalog detection rule wingetPackageId must equal the catalog packageId "
                            + "(drift fails closed before dispatch).");
        }
        Map<String, Object> detectionRule = new LinkedHashMap<>();
        detectionRule.put("type", "WINGET_PACKAGE");
        detectionRule.put("packageId", detectionPackageId);
        return detectionRule;
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

    private Map<String, Object> normalizePayload(CreateEndpointCommandRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (request.payload() != null) {
            payload.putAll(request.payload());
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
