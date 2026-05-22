package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.ApproveEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandResultDto;
import com.example.endpointadmin.model.ApprovalDecision;
import com.example.endpointadmin.model.ApprovalStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandApproval;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointCommandApprovalRepository;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

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
                                       EndpointAuditService auditService,
                                       Clock clock,
                                       @Value("${endpoint-admin.commands.admin-creatable-types:COLLECT_INVENTORY}")
                                       Set<CommandType> adminCreatableTypes) {
        this.commandRepository = commandRepository;
        this.resultRepository = resultRepository;
        this.approvalRepository = approvalRepository;
        this.deviceRepository = deviceRepository;
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
        if (!adminCreatableTypes.contains(type)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Command type is not enabled for admin creation.");
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
