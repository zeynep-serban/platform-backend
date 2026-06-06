package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.AdminAgentUpdateReleaseRequest;
import com.example.endpointadmin.dto.v1.admin.AdminAgentUpdateReleaseResponse;
import com.example.endpointadmin.dto.v1.admin.AdminAgentUpdateReleaseRevokeRequest;
import com.example.endpointadmin.dto.v1.admin.AdminAgentUpdateReleaseSummary;
import com.example.endpointadmin.exception.AgentUpdateReleaseMakerCheckerViolationException;
import com.example.endpointadmin.model.AgentUpdateChannel;
import com.example.endpointadmin.model.AgentUpdateReleaseStatus;
import com.example.endpointadmin.model.EndpointAgentUpdateRelease;
import com.example.endpointadmin.repository.EndpointAgentUpdateReleaseRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * BE-031 — signed agent update release catalog control-plane.
 *
 * <p>This service stores trusted release metadata only. It deliberately does
 * not create UPDATE_AGENT commands, does not target devices and does not
 * execute rollout. Future dispatch must resolve an APPROVED+enabled row from
 * this catalog and the agent still enforces local Authenticode/hash checks.
 */
@Service
public class EndpointAgentUpdateReleaseService {

    public static final String EVENT_CREATED =
            "ENDPOINT_AGENT_UPDATE_RELEASE_CREATED";
    public static final String EVENT_UPDATED =
            "ENDPOINT_AGENT_UPDATE_RELEASE_UPDATED";
    public static final String EVENT_APPROVED =
            "ENDPOINT_AGENT_UPDATE_RELEASE_APPROVED";
    public static final String EVENT_REVOKED =
            "ENDPOINT_AGENT_UPDATE_RELEASE_REVOKED";
    public static final String EVENT_APPROVAL_REJECTED_MAKER_CHECKER =
            "ENDPOINT_AGENT_UPDATE_RELEASE_APPROVAL_REJECTED_MAKER_CHECKER";

    public static final String ACTION_CREATE = "CREATE_AGENT_UPDATE_RELEASE";
    public static final String ACTION_UPDATE = "UPDATE_AGENT_UPDATE_RELEASE";
    public static final String ACTION_APPROVE = "APPROVE_AGENT_UPDATE_RELEASE";
    public static final String ACTION_REVOKE = "REVOKE_AGENT_UPDATE_RELEASE";

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9][A-Za-z0-9._+-]*)?");
    private static final Pattern HEX_64 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern HEX_128 = Pattern.compile("[a-f0-9]{128}");
    private static final Pattern THUMBPRINT = Pattern.compile(
            "(?:[A-F0-9]{40}|[A-F0-9]{64})");
    private static final int MAX_RELEASE_ID_LENGTH = 128;
    private static final int MAX_TARGET_VERSION_LENGTH = 64;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_NOTES_LENGTH = 2048;
    private static final int MAX_REVOCATION_REASON_LENGTH = 512;

    private final EndpointAgentUpdateReleaseRepository repository;
    private final EndpointAuditService auditService;
    private final Clock clock;

    public EndpointAgentUpdateReleaseService(
            EndpointAgentUpdateReleaseRepository repository,
            EndpointAuditService auditService,
            Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<AdminAgentUpdateReleaseSummary> listReleases(
            AdminTenantContext context,
            AgentUpdateChannel channel,
            AgentUpdateReleaseStatus status,
            Boolean enabled,
            Pageable pageable) {
        UUID tenantId = context.tenantId();
        Page<EndpointAgentUpdateRelease> page;
        if (channel != null && status != null && enabled != null) {
            page = repository.findByTenantIdAndChannelAndStatusAndEnabled(
                    tenantId, channel, status, enabled, pageable);
        } else if (channel != null && status != null) {
            page = repository.findByTenantIdAndChannelAndStatus(
                    tenantId, channel, status, pageable);
        } else if (channel != null && enabled != null) {
            page = repository.findByTenantIdAndChannelAndEnabled(
                    tenantId, channel, enabled, pageable);
        } else if (channel != null) {
            page = repository.findByTenantIdAndChannel(tenantId, channel, pageable);
        } else if (status != null && enabled != null) {
            page = repository.findByTenantIdAndStatusAndEnabled(
                    tenantId, status, enabled, pageable);
        } else if (status != null) {
            page = repository.findByTenantIdAndStatus(tenantId, status, pageable);
        } else if (enabled != null) {
            page = repository.findByTenantIdAndEnabled(tenantId, enabled, pageable);
        } else {
            page = repository.findByTenantId(tenantId, pageable);
        }
        return page.map(AdminAgentUpdateReleaseSummary::from);
    }

    @Transactional(readOnly = true)
    public AdminAgentUpdateReleaseResponse getRelease(AdminTenantContext context,
                                                      String releaseId) {
        return AdminAgentUpdateReleaseResponse.from(
                loadOrNotFound(context.tenantId(), releaseId));
    }

    @Transactional
    public AdminAgentUpdateReleaseResponse createRelease(
            AdminTenantContext context,
            AdminAgentUpdateReleaseRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Agent update release request is required.");
        }
        UUID tenantId = context.tenantId();
        String releaseId = requireText(
                request.releaseId(), "releaseId", MAX_RELEASE_ID_LENGTH);
        if (repository.existsByTenantIdAndReleaseId(tenantId, releaseId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Agent update release with this id already exists.");
        }
        String subject = resolveSubject(context);

        EndpointAgentUpdateRelease release = new EndpointAgentUpdateRelease();
        release.setTenantId(tenantId);
        release.setReleaseId(releaseId);
        applyMutableFields(release, request, subject);
        release.setStatus(AgentUpdateReleaseStatus.DRAFT);
        release.setEnabled(false);
        release.setCreatedBySubject(subject);
        release.setLastUpdatedBySubject(subject);

        EndpointAgentUpdateRelease saved = repository.save(release);
        auditService.record(
                tenantId,
                null,
                null,
                EVENT_CREATED,
                ACTION_CREATE,
                subject,
                saved.getId().toString(),
                buildAuditMetadata(saved),
                null,
                snapshotAfter(saved));

        return AdminAgentUpdateReleaseResponse.from(saved);
    }

    @Transactional
    public AdminAgentUpdateReleaseResponse updateRelease(
            AdminTenantContext context,
            String releaseId,
            AdminAgentUpdateReleaseRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Agent update release request is required.");
        }
        UUID tenantId = context.tenantId();
        EndpointAgentUpdateRelease release = loadOrNotFound(tenantId, releaseId);
        if (release.getStatus() != AgentUpdateReleaseStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only DRAFT agent update releases can be edited.");
        }
        String requestReleaseId = requireText(
                request.releaseId(), "releaseId", MAX_RELEASE_ID_LENGTH);
        if (!release.getReleaseId().equals(requestReleaseId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "releaseId in body does not match the path slug.");
        }
        String subject = resolveSubject(context);
        Map<String, Object> beforeState = snapshotAfter(release);
        applyMutableFields(release, request, subject);
        EndpointAgentUpdateRelease saved = repository.save(release);

        auditService.record(
                tenantId,
                null,
                null,
                EVENT_UPDATED,
                ACTION_UPDATE,
                subject,
                saved.getId().toString(),
                buildAuditMetadata(saved),
                beforeState,
                snapshotAfter(saved));

        return AdminAgentUpdateReleaseResponse.from(saved);
    }

    @Transactional(noRollbackFor =
            AgentUpdateReleaseMakerCheckerViolationException.class)
    public AdminAgentUpdateReleaseResponse approveRelease(
            AdminTenantContext context,
            String releaseId) {
        UUID tenantId = context.tenantId();
        EndpointAgentUpdateRelease release = loadOrNotFound(tenantId, releaseId);
        if (release.getStatus() != AgentUpdateReleaseStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only DRAFT agent update releases can be approved.");
        }
        String subject = resolveSubject(context);
        if (subject.equals(release.getCreatedBySubject())) {
            auditService.record(
                    tenantId,
                    null,
                    null,
                    EVENT_APPROVAL_REJECTED_MAKER_CHECKER,
                    ACTION_APPROVE,
                    subject,
                    release.getId().toString(),
                    buildAuditMetadata(release),
                    snapshotAfter(release),
                    null);
            throw new AgentUpdateReleaseMakerCheckerViolationException();
        }

        Map<String, Object> beforeState = snapshotAfter(release);
        Instant now = Instant.now(clock);
        release.setStatus(AgentUpdateReleaseStatus.APPROVED);
        release.setEnabled(true);
        release.setApprovedBySubject(subject);
        release.setApprovedAt(now);
        release.setLastUpdatedBySubject(subject);

        EndpointAgentUpdateRelease saved = repository.save(release);
        auditService.record(
                tenantId,
                null,
                null,
                EVENT_APPROVED,
                ACTION_APPROVE,
                subject,
                saved.getId().toString(),
                buildAuditMetadata(saved),
                beforeState,
                snapshotAfter(saved));

        return AdminAgentUpdateReleaseResponse.from(saved);
    }

    @Transactional
    public AdminAgentUpdateReleaseResponse revokeRelease(
            AdminTenantContext context,
            String releaseId,
            AdminAgentUpdateReleaseRevokeRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Revoke request body is required.");
        }
        UUID tenantId = context.tenantId();
        EndpointAgentUpdateRelease release = loadOrNotFound(tenantId, releaseId);
        if (release.getStatus() != AgentUpdateReleaseStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only APPROVED agent update releases can be revoked.");
        }
        String subject = resolveSubject(context);
        Map<String, Object> beforeState = snapshotAfter(release);
        Instant now = Instant.now(clock);
        release.setStatus(AgentUpdateReleaseStatus.REVOKED);
        release.setEnabled(false);
        release.setRevokedBySubject(subject);
        release.setRevokedAt(now);
        release.setRevocationReason(requireText(
                request.revocationReason(),
                "revocationReason",
                MAX_REVOCATION_REASON_LENGTH));
        release.setLastUpdatedBySubject(subject);

        EndpointAgentUpdateRelease saved = repository.save(release);
        Map<String, Object> afterState = snapshotAfter(saved);
        afterState.put("revocationReason", saved.getRevocationReason());

        auditService.record(
                tenantId,
                null,
                null,
                EVENT_REVOKED,
                ACTION_REVOKE,
                subject,
                saved.getId().toString(),
                buildAuditMetadata(saved),
                beforeState,
                afterState);

        return AdminAgentUpdateReleaseResponse.from(saved);
    }

    private EndpointAgentUpdateRelease loadOrNotFound(UUID tenantId,
                                                      String releaseId) {
        return repository.findByTenantIdAndReleaseId(tenantId, releaseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Agent update release not found."));
    }

    private void applyMutableFields(EndpointAgentUpdateRelease release,
                                    AdminAgentUpdateReleaseRequest request,
                                    String subject) {
        release.setChannel(request.channel());
        release.setTargetVersion(validateTargetVersion(request.targetVersion()));
        release.setBinaryUrl(validateCatalogUrl(request.binaryUrl(), "binaryUrl"));
        release.setManifestUrl(validateOptionalCatalogUrl(
                request.manifestUrl(), "manifestUrl"));
        release.setSha256(normalizeHex(request.sha256(), "sha256", HEX_64)
                .toLowerCase(Locale.ROOT));
        release.setSha512(request.sha512() == null ? null
                : normalizeHex(request.sha512(), "sha512", HEX_128)
                .toLowerCase(Locale.ROOT));
        release.setSignerThumbprint(
                normalizeThumbprint(request.signerThumbprint()));
        release.setSigningTier(request.signingTier());
        release.setMaxBytes(validateMaxBytes(request.maxBytes()));
        release.setReleaseNotes(trimToNull(
                request.releaseNotes(), "releaseNotes", MAX_NOTES_LENGTH));
        release.setLastUpdatedBySubject(subject);
    }

    private String validateTargetVersion(String rawVersion) {
        String version = requireText(
                rawVersion, "targetVersion", MAX_TARGET_VERSION_LENGTH);
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "targetVersion must be parseable semver-like text "
                            + "(example: 0.1.0 or 0.1.0-dev).");
        }
        return version;
    }

    private String validateOptionalCatalogUrl(String rawUrl, String field) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        return validateCatalogUrl(rawUrl, field);
    }

    private String validateCatalogUrl(String rawUrl, String field) {
        String value = requireText(rawUrl, field, MAX_URL_LENGTH);
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        field + " must use https.");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        field + " must include a host.");
            }
            if (uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        field + " must not contain userinfo, query or fragment.");
            }
            return uri.toString();
        } catch (URISyntaxException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be a valid https URI.");
        }
    }

    private String normalizeHex(String value, String field, Pattern pattern) {
        String normalized = requireText(value, field).toLowerCase(Locale.ROOT);
        if (!pattern.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " has an invalid hex length or character.");
        }
        return normalized;
    }

    private String normalizeThumbprint(String rawThumbprint) {
        String normalized = requireText(rawThumbprint, "signerThumbprint")
                .replace(":", "")
                .replace("-", "")
                .replace(" ", "")
                .toUpperCase(Locale.ROOT);
        if (!THUMBPRINT.matcher(normalized).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "signerThumbprint must be a 40 or 64 hex character "
                            + "certificate thumbprint.");
        }
        return normalized;
    }

    private long validateMaxBytes(long maxBytes) {
        if (maxBytes <= 0 || maxBytes > 524_288_000L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "maxBytes must be between 1 and 524288000.");
        }
        return maxBytes;
    }

    private Map<String, Object> buildAuditMetadata(
            EndpointAgentUpdateRelease release) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("releaseId", release.getReleaseId());
        metadata.put("channel", release.getChannel().name());
        metadata.put("targetVersion", release.getTargetVersion());
        metadata.put("status", release.getStatus().name());
        metadata.put("signingTier", release.getSigningTier().name());
        metadata.put("sha256", release.getSha256());
        return metadata;
    }

    private Map<String, Object> snapshotAfter(
            EndpointAgentUpdateRelease release) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", release.getStatus().name());
        snapshot.put("enabled", release.isEnabled());
        snapshot.put("channel", release.getChannel().name());
        snapshot.put("targetVersion", release.getTargetVersion());
        snapshot.put("sha256", release.getSha256());
        snapshot.put("sha512", release.getSha512());
        snapshot.put("signerThumbprint", release.getSignerThumbprint());
        snapshot.put("signingTier", release.getSigningTier().name());
        snapshot.put("maxBytes", release.getMaxBytes());
        snapshot.put("createdBySubject", release.getCreatedBySubject());
        snapshot.put("lastUpdatedBySubject",
                release.getLastUpdatedBySubject());
        snapshot.put("approvedBySubject", release.getApprovedBySubject());
        snapshot.put("revokedBySubject", release.getRevokedBySubject());
        return snapshot;
    }

    private String resolveSubject(AdminTenantContext context) {
        String subject = context.subject();
        if (subject == null || subject.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Authenticated admin subject is required.");
        }
        return subject;
    }

    private String requireText(String value, String field) {
        return requireText(value, field, Integer.MAX_VALUE);
    }

    private String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " is required.");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be at most " + maxLength + " characters.");
        }
        return trimmed;
    }

    private String trimToNull(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " must be at most " + maxLength + " characters.");
        }
        return trimmed;
    }
}
