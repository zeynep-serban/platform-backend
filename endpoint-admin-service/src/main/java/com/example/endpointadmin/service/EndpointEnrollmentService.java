package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.CreateEndpointEnrollmentRequest;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointEnrollmentResponse;
import com.example.endpointadmin.dto.v1.admin.EndpointEnrollmentDto;
import com.example.endpointadmin.dto.v1.agent.ConsumeEnrollmentRequest;
import com.example.endpointadmin.dto.v1.agent.ConsumeEnrollmentResponse;
import com.example.endpointadmin.exception.EnrollmentExpiredException;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointEnrollment;
import com.example.endpointadmin.model.EnrollmentStatus;
import com.example.endpointadmin.repository.EndpointEnrollmentRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EnrollmentAttemptLimiter;
import com.example.endpointadmin.security.EnrollmentTokenGenerator;
import com.example.endpointadmin.security.EnrollmentTokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EndpointEnrollmentService {

    private final EndpointEnrollmentRepository repository;
    private final EndpointDeviceService deviceService;
    private final DeviceCredentialService credentialService;
    private final EndpointAuditService auditService;
    private final EnrollmentTokenGenerator tokenGenerator;
    private final EnrollmentTokenHasher tokenHasher;
    private final EnrollmentAttemptLimiter attemptLimiter;
    private final Clock clock;
    private final int defaultTtlMinutes;
    private final int maxTtlMinutes;

    public EndpointEnrollmentService(EndpointEnrollmentRepository repository,
                                     EndpointDeviceService deviceService,
                                     DeviceCredentialService credentialService,
                                     EndpointAuditService auditService,
                                     EnrollmentTokenGenerator tokenGenerator,
                                     EnrollmentTokenHasher tokenHasher,
                                     EnrollmentAttemptLimiter attemptLimiter,
                                     Clock clock,
                                     @Value("${endpoint-admin.enrollment.default-token-ttl-minutes:1440}") int defaultTtlMinutes,
                                     @Value("${endpoint-admin.enrollment.max-token-ttl-minutes:10080}") int maxTtlMinutes) {
        this.repository = repository;
        this.deviceService = deviceService;
        this.credentialService = credentialService;
        this.auditService = auditService;
        this.tokenGenerator = tokenGenerator;
        this.tokenHasher = tokenHasher;
        this.attemptLimiter = attemptLimiter;
        this.clock = clock;
        this.defaultTtlMinutes = Math.max(1, defaultTtlMinutes);
        this.maxTtlMinutes = Math.max(1, maxTtlMinutes);
    }

    @Transactional
    public CreateEndpointEnrollmentResponse createEnrollment(AdminTenantContext context,
                                                             CreateEndpointEnrollmentRequest request) {
        Instant now = Instant.now(clock);
        int ttlMinutes = resolveTtl(request == null ? null : request.expiresInMinutes());
        String token = tokenGenerator.generate();

        EndpointEnrollment enrollment = new EndpointEnrollment();
        enrollment.setTenantId(context.tenantId());
        enrollment.setEnrollmentTokenHash(tokenHasher.hash(token));
        enrollment.setRequestedBySubject(context.subject());
        enrollment.setNote(request == null ? null : request.note());
        enrollment.setExpiresAt(now.plusSeconds(ttlMinutes * 60L));
        EndpointEnrollment saved = repository.saveAndFlush(enrollment);

        auditService.record(context.tenantId(), null, null,
                "ENDPOINT_ENROLLMENT_CREATED",
                "CREATE_ENROLLMENT",
                context.subject(),
                null,
                Map.of("enrollmentId", saved.getId().toString(), "ttlMinutes", ttlMinutes),
                null,
                null);

        return new CreateEndpointEnrollmentResponse(saved.getId(), token, saved.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public List<EndpointEnrollmentDto> listEnrollments(AdminTenantContext context) {
        return repository.findByTenantIdOrderByCreatedAtDesc(context.tenantId())
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(noRollbackFor = EnrollmentExpiredException.class)
    public ConsumeEnrollmentResponse consumeEnrollment(ConsumeEnrollmentRequest request, String remoteAddress) {
        Instant now = Instant.now(clock);
        String tokenHash = tokenHasher.hash(request.enrollmentToken());
        attemptLimiter.checkAllowed(remoteAddress, tokenHash);

        EndpointEnrollment enrollment = repository.findByEnrollmentTokenHash(tokenHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid enrollment token."));

        if (enrollment.getStatus() != EnrollmentStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Enrollment token is not pending.");
        }
        if (!enrollment.getExpiresAt().isAfter(now)) {
            repository.markExpired(enrollment.getId(), now);
            throw new EnrollmentExpiredException();
        }

        EndpointDevice device = deviceService.upsertFromEnrollment(enrollment.getTenantId(), request, now);
        IssuedDeviceCredential credential = credentialService.issueCredential(device, now);
        int updated = repository.markConsumed(enrollment.getId(), device, now);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Enrollment token was already consumed.");
        }

        auditService.record(enrollment.getTenantId(), device, null,
                "ENDPOINT_ENROLLMENT_CONSUMED",
                "CONSUME_ENROLLMENT",
                "agent:" + request.hostname(),
                null,
                Map.of(
                        "enrollmentId", enrollment.getId().toString(),
                        "hostname", request.hostname(),
                        "agentVersion", request.agentVersion()
                ),
                null,
                Map.of("deviceId", device.getId().toString())
        );

        return new ConsumeEnrollmentResponse(
                device.getId(),
                credential.credentialKeyId(),
                credential.plainSecret(),
                "HMAC-SHA256",
                now
        );
    }

    private int resolveTtl(Integer requestedTtlMinutes) {
        int ttl = requestedTtlMinutes == null ? defaultTtlMinutes : requestedTtlMinutes;
        if (ttl < 1 || ttl > maxTtlMinutes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enrollment token TTL must be between 1 and " + maxTtlMinutes + " minutes.");
        }
        return ttl;
    }

    private EndpointEnrollmentDto toDto(EndpointEnrollment enrollment) {
        UUID deviceId = enrollment.getDevice() == null ? null : enrollment.getDevice().getId();
        return new EndpointEnrollmentDto(
                enrollment.getId(),
                enrollment.getTenantId(),
                enrollment.getStatus(),
                enrollment.getRequestedBySubject(),
                enrollment.getNote(),
                deviceId,
                enrollment.getExpiresAt(),
                enrollment.getConsumedAt(),
                enrollment.getCreatedAt()
        );
    }
}
