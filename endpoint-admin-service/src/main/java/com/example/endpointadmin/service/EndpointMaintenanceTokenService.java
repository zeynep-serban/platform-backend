package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.CreateMaintenanceTokenRequest;
import com.example.endpointadmin.dto.v1.admin.CreateMaintenanceTokenResponse;
import com.example.endpointadmin.dto.v1.admin.EndpointMaintenanceTokenDto;
import com.example.endpointadmin.dto.v1.agent.ConsumeMaintenanceTokenRequest;
import com.example.endpointadmin.dto.v1.agent.ConsumeMaintenanceTokenResponse;
import com.example.endpointadmin.exception.MaintenanceTokenExpiredException;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointMaintenanceToken;
import com.example.endpointadmin.model.MaintenanceAction;
import com.example.endpointadmin.model.MaintenanceTokenStatus;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointMaintenanceTokenRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.DeviceCredentialException;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.security.EnrollmentTokenGenerator;
import com.example.endpointadmin.security.EnrollmentTokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EndpointMaintenanceTokenService {

    private final EndpointMaintenanceTokenRepository tokenRepository;
    private final EndpointDeviceRepository deviceRepository;
    private final EndpointAuditService auditService;
    private final EnrollmentTokenGenerator tokenGenerator;
    private final EnrollmentTokenHasher tokenHasher;
    private final Clock clock;
    private final int maxTtlMinutes;

    public EndpointMaintenanceTokenService(EndpointMaintenanceTokenRepository tokenRepository,
                                           EndpointDeviceRepository deviceRepository,
                                           EndpointAuditService auditService,
                                           EnrollmentTokenGenerator tokenGenerator,
                                           EnrollmentTokenHasher tokenHasher,
                                           Clock clock,
                                           @Value("${endpoint-admin.maintenance.max-token-ttl-minutes:10080}") int maxTtlMinutes) {
        this.tokenRepository = tokenRepository;
        this.deviceRepository = deviceRepository;
        this.auditService = auditService;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.clock = clock;
        this.maxTtlMinutes = Math.max(1, maxTtlMinutes);
    }

    @Transactional
    public CreateMaintenanceTokenResponse createToken(AdminTenantContext context,
                                                      UUID deviceId,
                                                      CreateMaintenanceTokenRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maintenance token request is required.");
        }
        if (request.action() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maintenance action is required.");
        }
        String reason = trimToNull(request.reason());
        if (reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maintenance reason is required.");
        }

        Instant now = Instant.now(clock);
        int ttlMinutes = resolveTtl(request.expiresInMinutes());
        EndpointDevice device = findDevice(context.tenantId(), deviceId);
        if (device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Endpoint device is decommissioned.");
        }
        if (tokenRepository.existsByTenantIdAndDevice_IdAndActionAndStatusAndExpiresAtAfter(
                context.tenantId(),
                deviceId,
                request.action(),
                MaintenanceTokenStatus.PENDING,
                now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Pending maintenance token already exists for this device and action.");
        }

        String plainToken = tokenGenerator.generate();
        EndpointMaintenanceToken token = new EndpointMaintenanceToken();
        token.setTenantId(context.tenantId());
        token.setDevice(device);
        token.setTokenHash(tokenHasher.hash(plainToken));
        token.setAction(request.action());
        token.setStatus(MaintenanceTokenStatus.PENDING);
        token.setReason(reason);
        token.setIssuedBySubject(resolveSubject(context));
        token.setExpiresAt(now.plusSeconds(ttlMinutes * 60L));

        EndpointMaintenanceToken saved = tokenRepository.saveAndFlush(token);
        auditService.record(
                context.tenantId(),
                device,
                null,
                "MAINTENANCE_TOKEN_CREATED",
                "CREATE_MAINTENANCE_TOKEN",
                resolveSubject(context),
                saved.getId().toString(),
                metadata(saved, ttlMinutes),
                null,
                Map.of("status", saved.getStatus().name())
        );

        return new CreateMaintenanceTokenResponse(
                saved.getId(),
                plainToken,
                saved.getAction(),
                saved.getExpiresAt()
        );
    }

    @Transactional(readOnly = true)
    public List<EndpointMaintenanceTokenDto> listTokens(AdminTenantContext context, UUID deviceId) {
        findDevice(context.tenantId(), deviceId);
        return tokenRepository.findByTenantIdAndDevice_IdOrderByCreatedAtDesc(context.tenantId(), deviceId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public EndpointMaintenanceTokenDto getToken(AdminTenantContext context, UUID tokenId) {
        return toDto(findToken(context.tenantId(), tokenId));
    }

    @Transactional(noRollbackFor = MaintenanceTokenExpiredException.class)
    public EndpointMaintenanceTokenDto revokeToken(AdminTenantContext context, UUID tokenId) {
        EndpointMaintenanceToken token = findToken(context.tenantId(), tokenId);
        Instant now = Instant.now(clock);
        expireIfNeeded(token, now);
        if (token.getStatus() != MaintenanceTokenStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Maintenance token is not pending.");
        }

        MaintenanceTokenStatus beforeStatus = token.getStatus();
        token.setStatus(MaintenanceTokenStatus.REVOKED);
        EndpointMaintenanceToken saved = tokenRepository.saveAndFlush(token);
        auditService.record(
                token.getTenantId(),
                token.getDevice(),
                null,
                "MAINTENANCE_TOKEN_REVOKED",
                "REVOKE_MAINTENANCE_TOKEN",
                resolveSubject(context),
                token.getId().toString(),
                metadata(token, null),
                Map.of("status", beforeStatus.name()),
                Map.of("status", saved.getStatus().name())
        );
        return toDto(saved);
    }

    @Transactional(noRollbackFor = {
            MaintenanceTokenExpiredException.class,
            // BE-014A (Codex 019e4ee1 REVISE absorb): deny paths
            // (device mismatch / REVOKED / ALREADY_CONSUMED / EXPIRED
            // re-attempt) throw ResponseStatusException to produce 403/409,
            // but the deny audit event published in the SAME transaction
            // MUST persist. With noRollbackFor on ResponseStatusException,
            // the audit row survives the throw and the client still sees
            // the correct HTTP response. The deny paths never mutate
            // business state before throwing (only the just-expired path
            // does, via expireIfNeeded(), and its state flip + audit pair
            // are coherent — both persist).
            ResponseStatusException.class
    })
    public ConsumeMaintenanceTokenResponse consumeToken(DeviceCredentialResult principal,
                                                        ConsumeMaintenanceTokenRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maintenance token consume request is required.");
        }
        UUID authenticatedDeviceId = resolveDeviceId(principal);
        Instant now = Instant.now(clock);
        String tokenHash = tokenHasher.hash(request.maintenanceToken());
        EndpointMaintenanceToken token = tokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid maintenance token."));

        if (!token.getDevice().getId().equals(authenticatedDeviceId)) {
            // BE-014A (Codex 019e4ed6 + 019e4ee1 REVISE absorb):
            // emit deny audit event in the SAME transaction; the caller
            // @Transactional has noRollbackFor=ResponseStatusException
            // so the 403 throws below do NOT roll back the audit row.
            // Device mismatch — token was issued for a different device.
            auditService.record(
                    token.getTenantId(),
                    token.getDevice(),
                    null,
                    "MAINTENANCE_TOKEN_DENIED_DEVICE_MISMATCH",
                    "CONSUME_MAINTENANCE_TOKEN",
                    "agent:" + authenticatedDeviceId,
                    token.getId().toString(),
                    denyMetadata(token, "DEVICE_MISMATCH", authenticatedDeviceId, request),
                    null,
                    null
            );
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Maintenance token does not belong to the authenticated device.");
        }
        expireIfNeeded(token, now);
        if (token.getStatus() != MaintenanceTokenStatus.PENDING) {
            // BE-014A (Codex 019e4ed6 + 019e4ee1 REVISE absorb):
            // emit deny audit event for misuse paths in the SAME transaction.
            // Caller @Transactional noRollbackFor=ResponseStatusException
            // keeps the deny audit row even when the 409 throws below.
            // expireIfNeeded() above already emits MAINTENANCE_TOKEN_EXPIRED
            // for the just-expired case via the caller transaction (its
            // status flip + audit pair are coherent). Here we cover deny
            // on re-attempt against an already-non-PENDING token
            // (revoked-then-consume, consumed-then-consume,
            // expired-then-consume); those throw without mutating DB —
            // the audit row alone documents the misuse attempt.
            String denyEventType;
            String denyReason;
            switch (token.getStatus()) {
                case REVOKED -> {
                    denyEventType = "MAINTENANCE_TOKEN_DENIED_REVOKED";
                    denyReason = "REVOKED";
                }
                case CONSUMED -> {
                    denyEventType = "MAINTENANCE_TOKEN_DENIED_ALREADY_CONSUMED";
                    denyReason = "ALREADY_CONSUMED";
                }
                case EXPIRED -> {
                    denyEventType = "MAINTENANCE_TOKEN_DENIED_EXPIRED";
                    denyReason = "EXPIRED";
                }
                default -> {
                    denyEventType = "MAINTENANCE_TOKEN_DENIED_NOT_PENDING";
                    denyReason = "NOT_PENDING";
                }
            }
            auditService.record(
                    token.getTenantId(),
                    token.getDevice(),
                    null,
                    denyEventType,
                    "CONSUME_MAINTENANCE_TOKEN",
                    "agent:" + authenticatedDeviceId,
                    token.getId().toString(),
                    denyMetadata(token, denyReason, authenticatedDeviceId, request),
                    null,
                    null
            );
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Maintenance token is not pending.");
        }

        MaintenanceTokenStatus beforeStatus = token.getStatus();
        token.setStatus(MaintenanceTokenStatus.CONSUMED);
        token.setConsumedAt(now);
        token.setConsumedByAgentVersion(trimToNull(request.agentVersion()));
        EndpointMaintenanceToken saved = tokenRepository.saveAndFlush(token);
        auditService.record(
                token.getTenantId(),
                token.getDevice(),
                null,
                "MAINTENANCE_TOKEN_CONSUMED",
                "CONSUME_MAINTENANCE_TOKEN",
                "agent:" + authenticatedDeviceId,
                token.getId().toString(),
                metadata(token, null),
                Map.of("status", beforeStatus.name()),
                Map.of("status", saved.getStatus().name(), "consumedAt", now.toString())
        );

        return new ConsumeMaintenanceTokenResponse(
                saved.getAction(),
                saved.getDevice().getId(),
                saved.getConsumedAt()
        );
    }

    private EndpointDevice findDevice(UUID tenantId, UUID deviceId) {
        if (deviceId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Endpoint device id is required.");
        }
        return deviceRepository.findByTenantIdAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint device not found."));
    }

    private EndpointMaintenanceToken findToken(UUID tenantId, UUID tokenId) {
        if (tokenId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maintenance token id is required.");
        }
        return tokenRepository.findByTenantIdAndId(tenantId, tokenId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Maintenance token not found."));
    }

    private void expireIfNeeded(EndpointMaintenanceToken token, Instant now) {
        if (token.getStatus() == MaintenanceTokenStatus.PENDING && !token.getExpiresAt().isAfter(now)) {
            MaintenanceTokenStatus beforeStatus = token.getStatus();
            token.setStatus(MaintenanceTokenStatus.EXPIRED);
            EndpointMaintenanceToken saved = tokenRepository.saveAndFlush(token);
            auditService.record(
                    token.getTenantId(),
                    token.getDevice(),
                    null,
                    "MAINTENANCE_TOKEN_EXPIRED",
                    "EXPIRE_MAINTENANCE_TOKEN",
                    "system",
                    token.getId().toString(),
                    metadata(token, null),
                    Map.of("status", beforeStatus.name()),
                    Map.of("status", saved.getStatus().name())
            );
            throw new MaintenanceTokenExpiredException();
        }
    }

    private int resolveTtl(Integer requestedTtlMinutes) {
        if (requestedTtlMinutes == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maintenance token TTL is required.");
        }
        if (requestedTtlMinutes < 1 || requestedTtlMinutes > maxTtlMinutes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Maintenance token TTL must be between 1 and " + maxTtlMinutes + " minutes.");
        }
        return requestedTtlMinutes;
    }

    private Map<String, Object> metadata(EndpointMaintenanceToken token, Integer ttlMinutes) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tokenId", token.getId().toString());
        metadata.put("action", token.getAction().name());
        if (ttlMinutes != null) {
            metadata.put("ttlMinutes", ttlMinutes);
        }
        String reason = trimToNull(token.getReason());
        if (reason != null) {
            metadata.put("reason", reason);
        }
        String agentVersion = trimToNull(token.getConsumedByAgentVersion());
        if (agentVersion != null) {
            metadata.put("agentVersion", agentVersion);
        }
        return metadata;
    }

    /**
     * BE-014A (Codex 019e4ed6 + 019e4ee1 REVISE absorb): Build metadata for
     * deny-path audit events.
     *
     * <p>Includes:
     * <ul>
     *   <li>tokenId (UUID; opaque DB reference)</li>
     *   <li>action (STOP_AGENT / UNINSTALL_AGENT)</li>
     *   <li>denyReason (DEVICE_MISMATCH / REVOKED / ALREADY_CONSUMED / EXPIRED — uppercase normalized)</li>
     *   <li>tokenStatus (current DB status snapshot)</li>
     *   <li>tokenOwnerDeviceId (device the token was issued for)</li>
     *   <li>authenticatedDeviceId (device that attempted the misuse)</li>
     *   <li>agentVersion (request body field, optional)</li>
     *   <li>issueReason (admin-supplied reason at issuance, optional)</li>
     * </ul>
     *
     * <p>Token plaintext is NEVER included (hash-only DB discipline preserved).
     */
    private Map<String, Object> denyMetadata(EndpointMaintenanceToken token,
                                             String denyReason,
                                             UUID authenticatedDeviceId,
                                             ConsumeMaintenanceTokenRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tokenId", token.getId().toString());
        metadata.put("action", token.getAction().name());
        metadata.put("denyReason", denyReason);
        metadata.put("tokenStatus", token.getStatus().name());
        metadata.put("tokenOwnerDeviceId", token.getDevice().getId().toString());
        metadata.put("authenticatedDeviceId", authenticatedDeviceId.toString());
        if (request != null) {
            String agentVersion = trimToNull(request.agentVersion());
            if (agentVersion != null) {
                metadata.put("agentVersion", agentVersion);
            }
        }
        String reason = trimToNull(token.getReason());
        if (reason != null) {
            metadata.put("issueReason", reason);
        }
        return metadata;
    }

    private EndpointMaintenanceTokenDto toDto(EndpointMaintenanceToken token) {
        return new EndpointMaintenanceTokenDto(
                token.getId(),
                token.getTenantId(),
                token.getDevice().getId(),
                token.getAction(),
                token.getStatus(),
                token.getReason(),
                token.getIssuedBySubject(),
                token.getExpiresAt(),
                token.getConsumedAt(),
                token.getConsumedByAgentVersion(),
                token.getCreatedAt(),
                token.getUpdatedAt()
        );
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
