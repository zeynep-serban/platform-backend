package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.AdminCatalogItemRequest;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogItemResponse;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogItemSummary;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogRevokeRequest;
import com.example.endpointadmin.exception.CatalogMakerCheckerViolationException;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogSourceTrust;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CatalogVersionPolicyType;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BE-020 — Approved Software Catalog admin service (Faz 22.5.3).
 *
 * <p>Manages the {@code endpoint_software_catalog_items} table behind the
 * {@code /api/v1/admin/endpoint-software-catalog} REST surface (wired up in
 * the PR-B controller). All mutations emit a tamper-evident audit event via
 * the BE-016 hash-chain ({@link EndpointAuditService#record}).
 *
 * <p>Maker-checker invariant: {@code approvedBySubject != createdBySubject}.
 * A reject does not silently fail — it emits a
 * {@code ENDPOINT_SOFTWARE_CATALOG_ITEM_APPROVAL_REJECTED_MAKER_CHECKER}
 * audit row and throws {@link CatalogMakerCheckerViolationException}. The
 * approve method opts out of rollback for this specific exception (BE-014A
 * pattern reuse, Codex 019e6a3e iter-2 acceptance #1) so the reject row
 * survives the failed approve transaction.
 *
 * <p>Provider compatibility matrix (Codex 019e6a3e iter-2 acceptance #2):
 * {@code provider=WINGET} requires {@code sourceType=WINGET} and
 * {@code sourceTrust IN (WINGET_COMMUNITY_REVIEWED, MICROSOFT_STORE)}.
 * Future providers (MSI / EXE) widen the matrix without a schema change.
 *
 * <p>Out of scope for BE-020: install command (AG-027 future), agent code
 * changes, GitOps digest bumps, OpenFGA model/tuple changes, the
 * {@code endpoint_commands} table, inventory ingest (BE-020I future),
 * compliance evaluator (BE-023 future). The boundary stays declarative —
 * catalog metadata only.
 */
@Service
public class EndpointSoftwareCatalogService {

    public static final String EVENT_CREATED =
            "ENDPOINT_SOFTWARE_CATALOG_ITEM_CREATED";
    public static final String EVENT_UPDATED =
            "ENDPOINT_SOFTWARE_CATALOG_ITEM_UPDATED";
    public static final String EVENT_APPROVED =
            "ENDPOINT_SOFTWARE_CATALOG_ITEM_APPROVED";
    public static final String EVENT_REVOKED =
            "ENDPOINT_SOFTWARE_CATALOG_ITEM_REVOKED";
    public static final String EVENT_APPROVAL_REJECTED_MAKER_CHECKER =
            "ENDPOINT_SOFTWARE_CATALOG_ITEM_APPROVAL_REJECTED_MAKER_CHECKER";

    public static final String ACTION_CREATE = "CREATE_CATALOG_ITEM";
    public static final String ACTION_UPDATE = "UPDATE_CATALOG_ITEM";
    public static final String ACTION_APPROVE = "APPROVE_CATALOG_ITEM";
    public static final String ACTION_REVOKE = "REVOKE_CATALOG_ITEM";

    private static final Set<CatalogSourceTrust> WINGET_ALLOWED_TRUST =
            EnumSet.of(CatalogSourceTrust.WINGET_COMMUNITY_REVIEWED,
                    CatalogSourceTrust.MICROSOFT_STORE);

    private final EndpointSoftwareCatalogItemRepository repository;
    private final EndpointAuditService auditService;
    private final DetectionRuleValidator detectionRuleValidator;
    private final Clock clock;

    public EndpointSoftwareCatalogService(
            EndpointSoftwareCatalogItemRepository repository,
            EndpointAuditService auditService,
            DetectionRuleValidator detectionRuleValidator,
            Clock clock) {
        this.repository = repository;
        this.auditService = auditService;
        this.detectionRuleValidator = detectionRuleValidator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<AdminCatalogItemSummary> listCatalogItems(
            AdminTenantContext context,
            CatalogItemStatus statusFilter,
            Boolean enabledFilter,
            Pageable pageable) {
        UUID tenantId = context.tenantId();
        Page<EndpointSoftwareCatalogItem> page;
        if (statusFilter != null && enabledFilter != null) {
            page = repository.findByTenantIdAndStatusAndEnabled(
                    tenantId, statusFilter, enabledFilter, pageable);
        } else if (statusFilter != null) {
            page = repository.findByTenantIdAndStatus(
                    tenantId, statusFilter, pageable);
        } else if (enabledFilter != null) {
            // Codex 019e6a64 post-impl PARTIAL absorb: don't silently ignore
            // an `enabled` filter when callers omit the status; the AG-027
            // future preflight only ever wants `APPROVED + enabled=true`,
            // and the admin UI may want a "disabled-only" view independent
            // of status.
            page = repository.findByTenantIdAndEnabled(
                    tenantId, enabledFilter, pageable);
        } else {
            page = repository.findByTenantId(tenantId, pageable);
        }
        return page.map(AdminCatalogItemSummary::from);
    }

    @Transactional(readOnly = true)
    public AdminCatalogItemResponse getCatalogItem(AdminTenantContext context,
                                                   String catalogItemId) {
        EndpointSoftwareCatalogItem item = loadOrNotFound(
                context.tenantId(), catalogItemId);
        return AdminCatalogItemResponse.from(item);
    }

    @Transactional
    public AdminCatalogItemResponse createCatalogItem(
            AdminTenantContext context,
            AdminCatalogItemRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Catalog item request is required.");
        }
        UUID tenantId = context.tenantId();
        String catalogItemId = request.catalogItemId();
        if (repository.existsByTenantIdAndCatalogItemId(tenantId, catalogItemId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Catalog item with this id already exists for the tenant.");
        }
        Map<String, Object> normalizedDetection =
                validateDetectionRule(request.detectionRule());
        validateProviderCompatibility(request.provider(),
                request.sourceType(), request.sourceTrust());
        validateVersionPolicy(request.versionPolicyType(),
                request.versionPolicyValue());
        validateProvenancePinning(request.sourceTrust(), request.sha256(),
                request.provenance());

        String subject = resolveSubject(context);

        EndpointSoftwareCatalogItem item = new EndpointSoftwareCatalogItem();
        item.setTenantId(tenantId);
        item.setCatalogItemId(catalogItemId);
        item.setStatus(CatalogItemStatus.DRAFT);
        item.setProvider(request.provider());
        item.setSourceType(request.sourceType());
        item.setSourceName(request.sourceName());
        item.setSourceTrust(request.sourceTrust());
        item.setPackageId(request.packageId());
        item.setDisplayName(request.displayName());
        item.setPublisher(request.publisher());
        item.setVersionPolicyType(request.versionPolicyType());
        item.setVersionPolicyValue(request.versionPolicyValue());
        item.setInstallerType(request.installerType());
        item.setSilentArgsPolicy(request.silentArgsPolicy());
        item.setSha256(request.sha256() == null ? null
                : request.sha256().toLowerCase(java.util.Locale.ROOT));
        item.setProvenance(request.provenance());
        item.setDetectionRule(normalizedDetection);
        item.setRiskTier(request.riskTier());
        item.setEnabled(false);
        item.setCreatedBySubject(subject);
        item.setLastUpdatedBySubject(subject);

        EndpointSoftwareCatalogItem saved = repository.save(item);

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

        return AdminCatalogItemResponse.from(saved);
    }

    @Transactional
    public AdminCatalogItemResponse updateCatalogItem(
            AdminTenantContext context,
            String catalogItemId,
            AdminCatalogItemRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Catalog item request is required.");
        }
        UUID tenantId = context.tenantId();
        EndpointSoftwareCatalogItem item = loadOrNotFound(tenantId, catalogItemId);
        if (item.getStatus() != CatalogItemStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Catalog item is not in DRAFT and cannot be edited.");
        }
        if (!item.getCatalogItemId().equals(request.catalogItemId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "catalogItemId in body does not match the path slug.");
        }
        Map<String, Object> normalizedDetection =
                validateDetectionRule(request.detectionRule());
        validateProviderCompatibility(request.provider(),
                request.sourceType(), request.sourceTrust());
        validateVersionPolicy(request.versionPolicyType(),
                request.versionPolicyValue());
        validateProvenancePinning(request.sourceTrust(), request.sha256(),
                request.provenance());

        Map<String, Object> beforeState = snapshotAfter(item);
        String subject = resolveSubject(context);

        item.setProvider(request.provider());
        item.setSourceType(request.sourceType());
        item.setSourceName(request.sourceName());
        item.setSourceTrust(request.sourceTrust());
        item.setPackageId(request.packageId());
        item.setDisplayName(request.displayName());
        item.setPublisher(request.publisher());
        item.setVersionPolicyType(request.versionPolicyType());
        item.setVersionPolicyValue(request.versionPolicyValue());
        item.setInstallerType(request.installerType());
        item.setSilentArgsPolicy(request.silentArgsPolicy());
        item.setSha256(request.sha256() == null ? null
                : request.sha256().toLowerCase(java.util.Locale.ROOT));
        item.setProvenance(request.provenance());
        item.setDetectionRule(normalizedDetection);
        item.setRiskTier(request.riskTier());
        item.setLastUpdatedBySubject(subject);

        EndpointSoftwareCatalogItem saved = repository.save(item);

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

        return AdminCatalogItemResponse.from(saved);
    }

    /**
     * Approve a DRAFT catalog item. Maker-checker invariant: the approver
     * subject must differ from the creator subject.
     *
     * <p>{@code noRollbackFor = CatalogMakerCheckerViolationException.class}
     * keeps the
     * {@code ENDPOINT_SOFTWARE_CATALOG_ITEM_APPROVAL_REJECTED_MAKER_CHECKER}
     * audit row durable when the approve is rejected (BE-014A pattern reuse,
     * Codex 019e6a3e iter-2 acceptance #1).
     */
    @Transactional(noRollbackFor = CatalogMakerCheckerViolationException.class)
    public AdminCatalogItemResponse approveCatalogItem(
            AdminTenantContext context,
            String catalogItemId) {
        UUID tenantId = context.tenantId();
        EndpointSoftwareCatalogItem item = loadOrNotFound(tenantId, catalogItemId);
        if (item.getStatus() != CatalogItemStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only DRAFT catalog items can be approved.");
        }
        String subject = resolveSubject(context);
        if (subject.equals(item.getCreatedBySubject())) {
            // Emit the reject audit row FIRST so the hash-chain captures it,
            // then throw — `noRollbackFor` keeps the audit insert committed.
            auditService.record(
                    tenantId,
                    null,
                    null,
                    EVENT_APPROVAL_REJECTED_MAKER_CHECKER,
                    ACTION_APPROVE,
                    subject,
                    item.getId().toString(),
                    buildAuditMetadata(item),
                    snapshotAfter(item),
                    null);
            throw new CatalogMakerCheckerViolationException();
        }

        Map<String, Object> beforeState = snapshotAfter(item);
        Instant now = Instant.now(clock);
        item.setStatus(CatalogItemStatus.APPROVED);
        item.setEnabled(true);
        item.setApprovedBySubject(subject);
        item.setApprovedAt(now);
        item.setLastUpdatedBySubject(subject);

        EndpointSoftwareCatalogItem saved = repository.save(item);

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

        return AdminCatalogItemResponse.from(saved);
    }

    @Transactional
    public AdminCatalogItemResponse revokeCatalogItem(
            AdminTenantContext context,
            String catalogItemId,
            AdminCatalogRevokeRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Revoke request body is required.");
        }
        UUID tenantId = context.tenantId();
        EndpointSoftwareCatalogItem item = loadOrNotFound(tenantId, catalogItemId);
        if (item.getStatus() != CatalogItemStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only APPROVED catalog items can be revoked.");
        }
        String subject = resolveSubject(context);

        Map<String, Object> beforeState = snapshotAfter(item);
        Instant now = Instant.now(clock);
        item.setStatus(CatalogItemStatus.REVOKED);
        item.setEnabled(false);
        item.setRevokedBySubject(subject);
        item.setRevokedAt(now);
        item.setRevocationReason(request.revocationReason());
        item.setLastUpdatedBySubject(subject);

        EndpointSoftwareCatalogItem saved = repository.save(item);

        Map<String, Object> afterState = snapshotAfter(saved);
        afterState.put("revocationReason", request.revocationReason());

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

        return AdminCatalogItemResponse.from(saved);
    }

    // ---------------------------------------------------------------
    // Helpers

    private EndpointSoftwareCatalogItem loadOrNotFound(UUID tenantId,
                                                      String catalogItemId) {
        return repository.findByTenantIdAndCatalogItemId(tenantId, catalogItemId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Catalog item not found."));
    }

    /**
     * Adapts the {@link DetectionRuleValidator} contract
     * ({@link IllegalArgumentException} on shape/safety violations) onto the
     * service-layer convention of {@link ResponseStatusException} (HTTP 400 to
     * the API caller via {@code GlobalExceptionHandler}). Keeps the validator
     * a pure utility usable from other layers (controller bean validation,
     * future BE-020I ingest, BE-021A preflight) without leaking framework
     * concerns into it.
     */
    private Map<String, Object> validateDetectionRule(
            Map<String, Object> rawDetectionRule) {
        try {
            return detectionRuleValidator.validateAndNormalize(rawDetectionRule);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    ex.getMessage());
        }
    }

    private void validateProviderCompatibility(CatalogProvider provider,
                                               CatalogSourceType sourceType,
                                               CatalogSourceTrust sourceTrust) {
        if (provider != CatalogProvider.WINGET) {
            // Future MSI / EXE providers reach this branch and currently get
            // rejected at the column-level CHECK constraint; this guard is
            // a service-layer redundancy + clearer error.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provider '" + provider + "' is not in the MVP allowlist.");
        }
        if (sourceType != CatalogSourceType.WINGET) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provider=WINGET requires sourceType=WINGET.");
        }
        if (!WINGET_ALLOWED_TRUST.contains(sourceTrust)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provider=WINGET requires sourceTrust in "
                            + WINGET_ALLOWED_TRUST + ".");
        }
    }

    private void validateVersionPolicy(CatalogVersionPolicyType type,
                                       String value) {
        switch (type) {
            case LATEST -> {
                if (value != null && !value.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "versionPolicyValue must be null for LATEST.");
                }
            }
            case EXACT, MINIMUM, RANGE -> {
                if (value == null || value.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "versionPolicyValue is required for " + type + ".");
                }
            }
        }
    }

    private void validateProvenancePinning(CatalogSourceTrust sourceTrust,
                                           String sha256,
                                           String provenance) {
        boolean pinningRequired =
                sourceTrust == CatalogSourceTrust.VENDOR_SIGNED_HASH_PINNED
                || sourceTrust == CatalogSourceTrust.INTERNAL_SIGNED;
        if (pinningRequired) {
            if (sha256 == null || sha256.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "sha256 is required for "
                                + "VENDOR_SIGNED_HASH_PINNED / INTERNAL_SIGNED.");
            }
            if (provenance == null || provenance.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "provenance is required for "
                                + "VENDOR_SIGNED_HASH_PINNED / INTERNAL_SIGNED.");
            }
        }
    }

    private Map<String, Object> buildAuditMetadata(
            EndpointSoftwareCatalogItem item) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("catalogItemId", item.getCatalogItemId());
        metadata.put("provider", item.getProvider().name());
        metadata.put("packageId", item.getPackageId());
        metadata.put("status", item.getStatus().name());
        metadata.put("versionPolicyType", item.getVersionPolicyType().name());
        return metadata;
    }

    private Map<String, Object> snapshotAfter(
            EndpointSoftwareCatalogItem item) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", item.getStatus().name());
        snapshot.put("enabled", item.isEnabled());
        snapshot.put("provider", item.getProvider().name());
        snapshot.put("sourceType", item.getSourceType().name());
        snapshot.put("sourceTrust", item.getSourceTrust().name());
        snapshot.put("packageId", item.getPackageId());
        snapshot.put("versionPolicyType", item.getVersionPolicyType().name());
        snapshot.put("versionPolicyValue", item.getVersionPolicyValue());
        snapshot.put("riskTier", item.getRiskTier().name());
        snapshot.put("createdBySubject", item.getCreatedBySubject());
        snapshot.put("lastUpdatedBySubject", item.getLastUpdatedBySubject());
        snapshot.put("approvedBySubject", item.getApprovedBySubject());
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
}
