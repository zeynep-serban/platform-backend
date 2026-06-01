package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.agent.AgentCommandResponse;
import com.example.endpointadmin.dto.v1.agent.AgentCommandResultRequest;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.security.DeviceCredentialException;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.security.DeviceHealthPayloadPolicy;
import com.example.endpointadmin.security.HardwareInventoryPayloadPolicy;
import com.example.endpointadmin.security.HotfixPosturePayloadPolicy;
import com.example.endpointadmin.security.InstallEvidencePayloadPolicy;
import com.example.endpointadmin.security.OutdatedSoftwarePayloadPolicy;
import com.example.endpointadmin.security.SoftwareInventoryPayloadPolicy;
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
    private final SoftwareInventoryPayloadPolicy inventoryPayloadPolicy;
    private final InstallEvidencePayloadPolicy installEvidencePayloadPolicy;
    private final HardwareInventoryPayloadPolicy hardwareInventoryPayloadPolicy;
    private final DeviceHealthPayloadPolicy deviceHealthPayloadPolicy;
    private final OutdatedSoftwarePayloadPolicy outdatedSoftwarePayloadPolicy;
    private final HotfixPosturePayloadPolicy hotfixPosturePayloadPolicy;
    private final EndpointSoftwareInventoryService softwareInventoryService;
    private final EndpointHardwareInventoryService hardwareInventoryService;
    private final EndpointDeviceHealthService deviceHealthService;
    private final EndpointOutdatedSoftwareService outdatedSoftwareService;
    private final EndpointHotfixPostureService hotfixPostureService;
    private final EndpointInstallAuditService installAuditService;
    private final Clock clock;
    private final Duration claimTtl;

    public EndpointAgentCommandService(EndpointCommandRepository commandRepository,
                                       EndpointCommandResultRepository resultRepository,
                                       SoftwareInventoryPayloadPolicy inventoryPayloadPolicy,
                                       InstallEvidencePayloadPolicy installEvidencePayloadPolicy,
                                       HardwareInventoryPayloadPolicy hardwareInventoryPayloadPolicy,
                                       DeviceHealthPayloadPolicy deviceHealthPayloadPolicy,
                                       OutdatedSoftwarePayloadPolicy outdatedSoftwarePayloadPolicy,
                                       HotfixPosturePayloadPolicy hotfixPosturePayloadPolicy,
                                       EndpointSoftwareInventoryService softwareInventoryService,
                                       EndpointHardwareInventoryService hardwareInventoryService,
                                       EndpointDeviceHealthService deviceHealthService,
                                       EndpointOutdatedSoftwareService outdatedSoftwareService,
                                       EndpointHotfixPostureService hotfixPostureService,
                                       EndpointInstallAuditService installAuditService,
                                       Clock clock,
                                       @Value("${endpoint-admin.commands.claim-ttl-seconds:300}") long claimTtlSeconds) {
        this.commandRepository = commandRepository;
        this.resultRepository = resultRepository;
        this.inventoryPayloadPolicy = inventoryPayloadPolicy;
        this.installEvidencePayloadPolicy = installEvidencePayloadPolicy;
        this.hardwareInventoryPayloadPolicy = hardwareInventoryPayloadPolicy;
        this.deviceHealthPayloadPolicy = deviceHealthPayloadPolicy;
        this.outdatedSoftwarePayloadPolicy = outdatedSoftwarePayloadPolicy;
        this.hotfixPosturePayloadPolicy = hotfixPosturePayloadPolicy;
        this.softwareInventoryService = softwareInventoryService;
        this.hardwareInventoryService = hardwareInventoryService;
        this.deviceHealthService = deviceHealthService;
        this.outdatedSoftwareService = outdatedSoftwareService;
        this.hotfixPostureService = hotfixPostureService;
        this.installAuditService = installAuditService;
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

        // BE-020I + BE-022 (Codex 019e6ab2 iter-2 acceptance #5,
        // Codex 019e7007 iter-3/4 must-fix): fail-closed PII /
        // sensitive-field check on the agent payload BEFORE the
        // result row is persisted. Order matters:
        //   1. BE-022 hardware sanitizer (mutating) — strips BIOS /
        //      disk / serial / user-path / SID / machine-GUID from
        //      the hardware sub-tree; fail-closed rejects token /
        //      password / JWT / bearer. Returns a sanitized
        //      effectiveDetails.
        //   2. BE-020I software validator (validate-only, no
        //      mutation) — runs on the sanitized effectiveDetails
        //      so its user-path / SID / GUID rejection does not
        //      fire on hardware facts that the hardware sanitizer
        //      legitimately captures (in redacted form).
        // Any forbidden key (licenseKey/productKey/uninstallString/
        // userProfile/sid/bearer/jwt/token/password) or value (raw
        // MSI GUID, C:\Users\... path, Windows SID literal) aborts
        // the result submit with 400 and rolls back the transaction
        // so neither endpoint_command_results nor the inventory
        // tables persist anything.
        Map<String, Object> effectiveDetails = request.details();
        if (command.getCommandType() == CommandType.COLLECT_INVENTORY
                && request.details() != null) {
            try {
                // 1. Hardware sanitizer (mutating) on the raw details.
                effectiveDetails = hardwareInventoryPayloadPolicy.sanitize(request.details());
                // 2. Device-health validator/sanitizer (AG-033). Runs on
                //    the hardware-sanitized form and validates the
                //    details.inventory.deviceHealth block against the
                //    contract redaction boundary (driveLetter-only disks,
                //    sourceUsed enum, schemaVersion=1, no secret values).
                //    A fail-closed reject (out-of-shape disk key, forbidden
                //    secret) aborts the result submit with 400 and rolls
                //    back the transaction so neither endpoint_command_results
                //    nor the device-health tables persist anything.
                effectiveDetails = deviceHealthPayloadPolicy.sanitize(effectiveDetails);
                // 3. Outdated-software validator/sanitizer (AG-036). Runs on
                //    the device-health-sanitized form and validates the
                //    details.inventory.outdatedSoftware block against the
                //    contract redaction boundary (per-package keys EXACTLY
                //    {packageId, installedVersion, availableVersion},
                //    sourceUsed enum {winget,none}, maxUpgrade=512,
                //    schemaVersion=1, no secret values). A fail-closed reject
                //    (off-contract package key, forbidden secret) aborts the
                //    result submit with 400 and rolls back the transaction so
                //    neither endpoint_command_results nor the outdated-software
                //    tables persist anything.
                effectiveDetails = outdatedSoftwarePayloadPolicy.sanitize(effectiveDetails);
                // 4. Hotfix-posture validator/sanitizer (AG-037). Runs on the
                //    outdated-software-sanitized form and validates the
                //    details.inventory.hotfixPosture block against the
                //    contract redaction boundary (strict allowlist
                //    projection, schemaVersion=1, KbId regex
                //    {@code ^KB[0-9]{4,10}$}, count invariants
                //    [installedTruncated/pendingTruncated semantics +
                //    sum(pendingByCategory.count) == pendingTotalCount],
                //    agent-health typed enums [ServiceState 4-state +
                //    notificationLevel ~ '^[0-9]{1,4}$'], no secret
                //    values, no forbidden MS-update fields
                //    [productCode/msiGuid/title/installClient/etc.]). A
                //    fail-closed reject (off-contract key, forbidden
                //    secret, malformed KbId, broken count invariant)
                //    aborts the result submit with 400 and rolls back
                //    the transaction so neither endpoint_command_results
                //    nor the hotfix-posture tables persist anything.
                effectiveDetails = hotfixPosturePayloadPolicy.sanitize(effectiveDetails);
                // 5. Software validator (validate-only) on the sanitized form.
                inventoryPayloadPolicy.validate(effectiveDetails);
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ex.getMessage());
            }
        }

        // BE-021 (Codex 019e6dfb iter-3 P0-2): for INSTALL_SOFTWARE
        // terminal results, the double-redact pass runs BEFORE the
        // result row is built so the raw agent payload never reaches
        // either endpoint_command_results.result_payload.details or
        // endpoint_install_audit.redacted_payload. A forbidden key or
        // value throws 400 + rolls back the entire submitResult tx.
        if (command.getCommandType() == CommandType.INSTALL_SOFTWARE
                && request.details() != null) {
            try {
                installEvidencePayloadPolicy.validate(request.details());
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ex.getMessage());
            }
            effectiveDetails = installEvidencePayloadPolicy.redact(request.details());
        }

        Instant now = Instant.now(clock);
        EndpointCommandResult result = new EndpointCommandResult();
        result.setTenantId(command.getTenantId());
        result.setCommand(command);
        result.setDevice(command.getDevice());
        result.setResultStatus(request.status());
        result.setResultPayload(buildResultPayload(request, effectiveDetails));
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

        // BE-020I + BE-022: only ingest inventory on a SUCCEEDED
        // COLLECT_INVENTORY result. Both ingest paths receive the
        // sanitized `effectiveDetails` (NOT raw `request.details()`)
        // so the result row, the software inventory snapshot, and
        // the hardware inventory snapshot all reference the same
        // redacted payload (Codex 019e7007 iter-3 must-fix). Same
        // transaction — an ingest failure rolls back the command
        // result + status flips above.
        if (command.getCommandType() == CommandType.COLLECT_INVENTORY
                && request.status() == CommandResultStatus.SUCCEEDED
                && effectiveDetails != null
                && command.getDevice() != null) {
            try {
                softwareInventoryService.ingest(
                        command.getDevice(), command, result, effectiveDetails);
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        ex.getMessage());
            }
            if (EndpointHardwareInventoryService.hasHardwareBlock(effectiveDetails)) {
                try {
                    hardwareInventoryService.ingest(
                            command.getDevice(), command, result, effectiveDetails);
                } catch (IllegalArgumentException ex) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            ex.getMessage());
                }
            }
            // AG-033 device-health ingest — same transaction, same
            // sanitized effectiveDetails. Only runs when the sanitized
            // payload actually carries a details.inventory.deviceHealth
            // block (nullable; absent for lightweight heartbeat collects).
            if (EndpointDeviceHealthService.hasDeviceHealthBlock(effectiveDetails)) {
                try {
                    deviceHealthService.ingest(
                            command.getDevice(), command, result, effectiveDetails);
                } catch (IllegalArgumentException ex) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            ex.getMessage());
                }
            }
            // AG-036 outdated-software ingest — same transaction, same
            // sanitized effectiveDetails. Only runs when the sanitized payload
            // actually carries a details.inventory.outdatedSoftware block
            // (nullable; absent for heartbeat / lightweight collects).
            if (EndpointOutdatedSoftwareService.hasOutdatedSoftwareBlock(effectiveDetails)) {
                try {
                    outdatedSoftwareService.ingest(
                            command.getDevice(), command, result, effectiveDetails);
                } catch (IllegalArgumentException ex) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            ex.getMessage());
                }
            }
            // AG-037 hotfix-posture ingest — same transaction, same
            // sanitized effectiveDetails. Only runs when the sanitized
            // payload actually carries a details.inventory.hotfixPosture
            // block (opt-in; absent unless backend requested
            // includeHotfixPosture=true). Dual idempotency (Codex
            // 019e81fe iter-3 P1.1 + iter-4): targetless ON CONFLICT
            // DO NOTHING + sequential winner lookup races both
            // source_command_result_id and (tenant, device, hash)
            // UNIQUEs transaction-cleanly. NO exception swallow — any
            // policy-bypass IllegalStateException rolls full tx back.
            if (EndpointHotfixPostureService.hasHotfixPostureBlock(effectiveDetails)) {
                try {
                    hotfixPostureService.ingest(
                            command.getDevice(), command, result, effectiveDetails);
                } catch (IllegalArgumentException ex) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            ex.getMessage());
                }
            }
        }

        // BE-021 (Codex 019e6dfb iter-3 P0-3): write the install audit
        // row in the SAME transaction as the result row so a failed
        // audit insert rolls back the result too. The compliance
        // re-evaluation trigger is published as an ApplicationEvent and
        // fires on AFTER_COMMIT (ComplianceInstallAuditEventListener).
        if (command.getCommandType() == CommandType.INSTALL_SOFTWARE
                && isTerminalResult(request.status())) {
            installAuditService.recordInstallResult(
                    command, result, request, effectiveDetails, now);
        }
    }

    private static boolean isTerminalResult(CommandResultStatus status) {
        return status == CommandResultStatus.SUCCEEDED
                || status == CommandResultStatus.FAILED
                || status == CommandResultStatus.PARTIAL
                || status == CommandResultStatus.UNSUPPORTED;
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

    private Map<String, Object> buildResultPayload(AgentCommandResultRequest request,
                                                   Map<String, Object> effectiveDetails) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "summary", request.summary());
        if (effectiveDetails != null) {
            payload.put("details", effectiveDetails);
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
