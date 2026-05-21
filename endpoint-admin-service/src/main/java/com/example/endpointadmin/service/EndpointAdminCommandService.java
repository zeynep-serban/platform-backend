package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.CreateEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandResultDto;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.security.AdminTenantContext;
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

    private static final Set<CommandType> ENABLED_ADMIN_COMMAND_TYPES = EnumSet.of(CommandType.COLLECT_INVENTORY);

    private final EndpointCommandRepository commandRepository;
    private final EndpointCommandResultRepository resultRepository;
    private final EndpointDeviceRepository deviceRepository;
    private final EndpointAuditService auditService;
    private final Clock clock;

    public EndpointAdminCommandService(EndpointCommandRepository commandRepository,
                                       EndpointCommandResultRepository resultRepository,
                                       EndpointDeviceRepository deviceRepository,
                                       EndpointAuditService auditService,
                                       Clock clock) {
        this.commandRepository = commandRepository;
        this.resultRepository = resultRepository;
        this.deviceRepository = deviceRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public EndpointCommandDto createCommand(AdminTenantContext context,
                                            UUID deviceId,
                                            CreateEndpointCommandRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Command request is required.");
        }
        validateCommandType(request.type());

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

        EndpointCommand command = new EndpointCommand();
        command.setTenantId(tenantId);
        command.setDevice(device);
        command.setCommandType(request.type());
        command.setIdempotencyKey(idempotencyKey);
        command.setStatus(CommandStatus.QUEUED);
        command.setPayload(payload);
        command.setPriority(request.priority() == null ? 100 : request.priority());
        command.setAttemptCount(0);
        command.setMaxAttempts(request.maxAttempts() == null ? 3 : request.maxAttempts());
        command.setVisibleAfterAt(visibleAfterAt);
        command.setExpiresAt(request.expiresAt());
        command.setIssuedBySubject(resolveSubject(context));
        command.setIssuedAt(now);

        EndpointCommand saved = commandRepository.saveAndFlush(command);
        auditService.record(
                tenantId,
                device,
                saved,
                "ENDPOINT_COMMAND_CREATED",
                "CREATE_COMMAND",
                resolveSubject(context),
                idempotencyKey,
                auditMetadata(saved),
                null,
                Map.of("status", saved.getStatus().name())
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
        if (!ENABLED_ADMIN_COMMAND_TYPES.contains(type)) {
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
            payload.putIfAbsent("reason", reason);
        }
        return payload;
    }

    private Map<String, Object> auditMetadata(EndpointCommand command) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("commandType", command.getCommandType().name());
        metadata.put("idempotencyKey", command.getIdempotencyKey());
        metadata.put("priority", command.getPriority());
        metadata.put("maxAttempts", command.getMaxAttempts());
        return metadata;
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
