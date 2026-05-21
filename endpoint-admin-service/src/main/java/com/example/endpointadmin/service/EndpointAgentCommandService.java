package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.agent.AgentCommandResponse;
import com.example.endpointadmin.dto.v1.agent.AgentCommandResultRequest;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.security.DeviceCredentialException;
import com.example.endpointadmin.security.DeviceCredentialResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class EndpointAgentCommandService {

    private final EndpointCommandRepository commandRepository;
    private final EndpointCommandResultRepository resultRepository;
    private final Clock clock;
    private final Duration claimTtl;

    public EndpointAgentCommandService(EndpointCommandRepository commandRepository,
                                       EndpointCommandResultRepository resultRepository,
                                       Clock clock,
                                       @Value("${endpoint-admin.commands.claim-ttl-seconds:300}") long claimTtlSeconds) {
        this.commandRepository = commandRepository;
        this.resultRepository = resultRepository;
        this.clock = clock;
        this.claimTtl = Duration.ofSeconds(Math.max(30L, claimTtlSeconds));
    }

    @Transactional
    public Optional<AgentCommandResponse> claimNext(DeviceCredentialResult principal) {
        UUID deviceId = resolveDeviceId(principal);
        Instant now = Instant.now(clock);
        var candidates = commandRepository.findClaimCandidatesForDevice(
                deviceId,
                DeviceStatus.ONLINE,
                CommandStatus.QUEUED,
                CommandStatus.DELIVERED,
                now,
                PageRequest.of(0, 1)
        );
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        EndpointCommand command = candidates.get(0);
        String claimId = UUID.randomUUID().toString();
        Instant claimExpiresAt = now.plus(claimTtl);
        command.setStatus(CommandStatus.DELIVERED);
        command.setLockedBy(claimId);
        command.setLockedUntil(claimExpiresAt);
        command.setAttemptCount(safeInt(command.getAttemptCount()) + 1);
        if (command.getDeliveredAt() == null) {
            command.setDeliveredAt(now);
        }
        commandRepository.saveAndFlush(command);

        return Optional.of(toResponse(command, claimId, claimExpiresAt));
    }

    @Transactional
    public void submitResult(DeviceCredentialResult principal,
                             UUID commandId,
                             AgentCommandResultRequest request) {
        UUID deviceId = resolveDeviceId(principal);
        EndpointCommand command = commandRepository.findByIdAndDeviceIdForUpdate(commandId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint command not found."));

        if (resultRepository.findByCommand_Id(command.getId()).isPresent()) {
            return;
        }
        validateResultSubmission(command, request);

        Instant now = Instant.now(clock);
        EndpointCommandResult result = new EndpointCommandResult();
        result.setTenantId(command.getTenantId());
        result.setCommand(command);
        result.setDevice(command.getDevice());
        result.setResultStatus(request.status());
        result.setResultPayload(resultPayload(request));
        result.setErrorCode(trimToNull(request.errorCode()));
        result.setErrorMessage(trimToNull(request.errorMessage()));
        result.setExitCode(request.exitCode());
        result.setReportedAt(now);

        if (request.startedAt() != null) {
            command.setStartedAt(request.startedAt());
        } else if (command.getStartedAt() == null) {
            command.setStartedAt(now);
        }
        command.setCompletedAt(request.finishedAt() == null ? now : request.finishedAt());
        command.setStatus(toCommandStatus(request.status()));
        command.setLastError(resolveLastError(request));
        command.setLockedBy(null);
        command.setLockedUntil(null);

        commandRepository.saveAndFlush(command);
        resultRepository.saveAndFlush(result);
    }

    private void validateResultSubmission(EndpointCommand command, AgentCommandResultRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Command result request is required.");
        }
        if (isFinal(command.getStatus()) || command.getStatus() == CommandStatus.CANCELLED
                || command.getStatus() == CommandStatus.EXPIRED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Endpoint command is not accepting results.");
        }
        if (trimToNull(command.getLockedBy()) == null || !command.getLockedBy().equals(request.claimId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Command claim is not valid.");
        }
        if (request.attemptNumber() != null && request.attemptNumber() != safeInt(command.getAttemptCount())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Command attempt number is not current.");
        }
    }

    private AgentCommandResponse toResponse(EndpointCommand command, String claimId, Instant claimExpiresAt) {
        return new AgentCommandResponse(
                command.getId(),
                claimId,
                safeInt(command.getAttemptCount()),
                command.getCommandType(),
                command.getIssuedBySubject(),
                reason(command.getPayload()),
                command.getPayload(),
                claimExpiresAt
        );
    }

    private String reason(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object value = payload.get("reason");
        return value instanceof String stringValue ? trimToNull(stringValue) : null;
    }

    private Map<String, Object> resultPayload(AgentCommandResultRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "summary", request.summary());
        if (request.details() != null) {
            payload.put("details", request.details());
        }
        putIfPresent(payload, "claimId", request.claimId());
        if (request.attemptNumber() != null) {
            payload.put("attemptNumber", request.attemptNumber());
        }
        if (request.startedAt() != null) {
            payload.put("startedAt", request.startedAt());
        }
        if (request.finishedAt() != null) {
            payload.put("finishedAt", request.finishedAt());
        }
        return payload;
    }

    private CommandStatus toCommandStatus(CommandResultStatus resultStatus) {
        return resultStatus == CommandResultStatus.SUCCEEDED
                ? CommandStatus.SUCCEEDED
                : CommandStatus.FAILED;
    }

    private String resolveLastError(AgentCommandResultRequest request) {
        if (request.status() == CommandResultStatus.SUCCEEDED) {
            return null;
        }
        String errorMessage = trimToNull(request.errorMessage());
        if (errorMessage != null) {
            return errorMessage;
        }
        String summary = trimToNull(request.summary());
        return summary == null ? request.status().name() : summary;
    }

    private boolean isFinal(CommandStatus status) {
        return status == CommandStatus.SUCCEEDED || status == CommandStatus.FAILED;
    }

    private UUID resolveDeviceId(DeviceCredentialResult principal) {
        if (principal == null || trimToNull(principal.deviceId()) == null) {
            throw new DeviceCredentialException("DEVICE_AUTH_REQUIRED", "Device authentication is required.");
        }
        try {
            return UUID.fromString(principal.deviceId());
        } catch (IllegalArgumentException ex) {
            throw new DeviceCredentialException("DEVICE_AUTH_INVALID", "Device authentication is invalid.");
        }
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private void putIfPresent(Map<String, Object> payload, String key, String value) {
        String trimmed = trimToNull(value);
        if (trimmed != null) {
            payload.put(key, trimmed);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
