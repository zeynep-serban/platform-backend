package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.AdminSoftwareBundleRequest;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareBundleResponse;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareBundleRevokeRequest;
import com.example.endpointadmin.dto.v1.admin.AdminSoftwareBundleSummary;
import com.example.endpointadmin.exception.SoftwareBundleMakerCheckerViolationException;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.EndpointSoftwareBundle;
import com.example.endpointadmin.model.EndpointSoftwareBundleItem;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.model.SoftwareBundleStatus;
import com.example.endpointadmin.repository.EndpointSoftwareBundleItemRepository;
import com.example.endpointadmin.repository.EndpointSoftwareBundleRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BE-029 — approved package bundle control-plane.
 *
 * <p>Bundles are definitions assembled from existing APPROVED+enabled catalog
 * rows. This service does not dispatch install commands, does not accept raw
 * package ids and does not mutate devices.
 */
@Service
public class EndpointSoftwareBundleService {

    public static final String EVENT_CREATED =
            "ENDPOINT_SOFTWARE_BUNDLE_CREATED";
    public static final String EVENT_APPROVED =
            "ENDPOINT_SOFTWARE_BUNDLE_APPROVED";
    public static final String EVENT_REVOKED =
            "ENDPOINT_SOFTWARE_BUNDLE_REVOKED";
    public static final String EVENT_APPROVAL_REJECTED_MAKER_CHECKER =
            "ENDPOINT_SOFTWARE_BUNDLE_APPROVAL_REJECTED_MAKER_CHECKER";

    public static final String ACTION_CREATE = "CREATE_SOFTWARE_BUNDLE";
    public static final String ACTION_APPROVE = "APPROVE_SOFTWARE_BUNDLE";
    public static final String ACTION_REVOKE = "REVOKE_SOFTWARE_BUNDLE";

    private static final int MAX_ITEMS = 32;

    private final EndpointSoftwareBundleRepository bundleRepository;
    private final EndpointSoftwareBundleItemRepository itemRepository;
    private final EndpointSoftwareCatalogItemRepository catalogRepository;
    private final EndpointAuditService auditService;
    private final Clock clock;

    public EndpointSoftwareBundleService(
            EndpointSoftwareBundleRepository bundleRepository,
            EndpointSoftwareBundleItemRepository itemRepository,
            EndpointSoftwareCatalogItemRepository catalogRepository,
            EndpointAuditService auditService,
            Clock clock) {
        this.bundleRepository = bundleRepository;
        this.itemRepository = itemRepository;
        this.catalogRepository = catalogRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<AdminSoftwareBundleSummary> listBundles(
            AdminTenantContext context,
            SoftwareBundleStatus statusFilter,
            Boolean enabledFilter,
            Pageable pageable) {
        UUID tenantId = context.tenantId();
        Page<EndpointSoftwareBundle> bundles;
        if (statusFilter != null && enabledFilter != null) {
            bundles = bundleRepository.findByTenantIdAndStatusAndEnabled(
                    tenantId, statusFilter, enabledFilter, pageable);
        } else if (statusFilter != null) {
            bundles = bundleRepository.findByTenantIdAndStatus(
                    tenantId, statusFilter, pageable);
        } else if (enabledFilter != null) {
            bundles = bundleRepository.findByTenantIdAndEnabled(
                    tenantId, enabledFilter, pageable);
        } else {
            bundles = bundleRepository.findByTenantId(tenantId, pageable);
        }
        List<AdminSoftwareBundleSummary> content = bundles.getContent()
                .stream()
                .map(bundle -> AdminSoftwareBundleSummary.from(bundle,
                        itemRepository.countByTenantIdAndBundleId(
                                tenantId, bundle.getId())))
                .toList();
        return new PageImpl<>(content, pageable, bundles.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AdminSoftwareBundleResponse getBundle(AdminTenantContext context,
                                                 String bundleId) {
        EndpointSoftwareBundle bundle =
                loadBundleOrNotFound(context.tenantId(), bundleId);
        return responseFor(context.tenantId(), bundle);
    }

    @Transactional
    public AdminSoftwareBundleResponse createBundle(
            AdminTenantContext context,
            AdminSoftwareBundleRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Software bundle request is required.");
        }
        UUID tenantId = context.tenantId();
        String bundleId = requireText(request.bundleId(), "bundleId");
        if (bundleRepository.existsByTenantIdAndBundleId(tenantId, bundleId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Software bundle with this id already exists for the tenant.");
        }
        List<EndpointSoftwareCatalogItem> catalogItems =
                resolveApprovedCatalogItems(tenantId, request.catalogItemIds());

        String subject = resolveSubject(context);

        EndpointSoftwareBundle bundle = new EndpointSoftwareBundle();
        bundle.setTenantId(tenantId);
        bundle.setBundleId(bundleId);
        bundle.setDisplayName(requireText(request.displayName(), "displayName"));
        bundle.setDescription(trimToNull(request.description()));
        bundle.setStatus(SoftwareBundleStatus.DRAFT);
        bundle.setEnabled(false);
        bundle.setCreatedBySubject(subject);
        bundle.setLastUpdatedBySubject(subject);

        EndpointSoftwareBundle savedBundle = bundleRepository.save(bundle);
        itemRepository.saveAll(buildItems(savedBundle, catalogItems));

        auditService.record(
                tenantId,
                null,
                null,
                EVENT_CREATED,
                ACTION_CREATE,
                subject,
                savedBundle.getId().toString(),
                buildAuditMetadata(savedBundle, catalogItems),
                null,
                snapshotAfter(savedBundle, catalogItems));

        return responseFor(tenantId, savedBundle);
    }

    // Keep the maker-checker rejection audit row even though the API returns
    // conflict through SoftwareBundleMakerCheckerViolationException.
    @Transactional(noRollbackFor = SoftwareBundleMakerCheckerViolationException.class)
    public AdminSoftwareBundleResponse approveBundle(
            AdminTenantContext context,
            String bundleId) {
        UUID tenantId = context.tenantId();
        EndpointSoftwareBundle bundle = loadBundleOrNotFound(tenantId, bundleId);
        if (bundle.getStatus() != SoftwareBundleStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only DRAFT software bundles can be approved.");
        }
        List<EndpointSoftwareBundleItem> items =
                itemRepository.findByTenantIdAndBundleIdOrderByItemOrderAsc(
                        tenantId, bundle.getId());
        if (items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Software bundle has no catalog items.");
        }
        String subject = resolveSubject(context);
        if (subject.equals(bundle.getCreatedBySubject())) {
            auditService.record(
                    tenantId,
                    null,
                    null,
                    EVENT_APPROVAL_REJECTED_MAKER_CHECKER,
                    ACTION_APPROVE,
                    subject,
                    bundle.getId().toString(),
                    buildAuditMetadataFromItems(bundle, items),
                    snapshotAfterFromItems(bundle, items),
                    null);
            throw new SoftwareBundleMakerCheckerViolationException();
        }

        Map<String, Object> beforeState = snapshotAfterFromItems(bundle, items);
        Instant now = Instant.now(clock);
        bundle.setStatus(SoftwareBundleStatus.APPROVED);
        bundle.setEnabled(true);
        bundle.setApprovedBySubject(subject);
        bundle.setApprovedAt(now);
        bundle.setLastUpdatedBySubject(subject);

        EndpointSoftwareBundle saved = bundleRepository.save(bundle);

        auditService.record(
                tenantId,
                null,
                null,
                EVENT_APPROVED,
                ACTION_APPROVE,
                subject,
                saved.getId().toString(),
                buildAuditMetadataFromItems(saved, items),
                beforeState,
                snapshotAfterFromItems(saved, items));

        return responseFor(tenantId, saved);
    }

    @Transactional
    public AdminSoftwareBundleResponse revokeBundle(
            AdminTenantContext context,
            String bundleId,
            AdminSoftwareBundleRevokeRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Revoke request body is required.");
        }
        UUID tenantId = context.tenantId();
        EndpointSoftwareBundle bundle = loadBundleOrNotFound(tenantId, bundleId);
        if (bundle.getStatus() != SoftwareBundleStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only APPROVED software bundles can be revoked.");
        }
        List<EndpointSoftwareBundleItem> items =
                itemRepository.findByTenantIdAndBundleIdOrderByItemOrderAsc(
                        tenantId, bundle.getId());
        String subject = resolveSubject(context);
        Map<String, Object> beforeState = snapshotAfterFromItems(bundle, items);
        Instant now = Instant.now(clock);
        bundle.setStatus(SoftwareBundleStatus.REVOKED);
        bundle.setEnabled(false);
        bundle.setRevokedBySubject(subject);
        bundle.setRevokedAt(now);
        bundle.setRevocationReason(request.revocationReason());
        bundle.setLastUpdatedBySubject(subject);

        EndpointSoftwareBundle saved = bundleRepository.save(bundle);
        Map<String, Object> afterState = snapshotAfterFromItems(saved, items);
        afterState.put("revocationReason", request.revocationReason());

        auditService.record(
                tenantId,
                null,
                null,
                EVENT_REVOKED,
                ACTION_REVOKE,
                subject,
                saved.getId().toString(),
                buildAuditMetadataFromItems(saved, items),
                beforeState,
                afterState);

        return responseFor(tenantId, saved);
    }

    private EndpointSoftwareBundle loadBundleOrNotFound(UUID tenantId,
                                                        String bundleId) {
        return bundleRepository.findByTenantIdAndBundleId(tenantId, bundleId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Software bundle not found."));
    }

    private List<EndpointSoftwareCatalogItem> resolveApprovedCatalogItems(
            UUID tenantId,
            List<String> catalogItemIds) {
        if (catalogItemIds == null || catalogItemIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "At least one catalog item is required.");
        }
        if (catalogItemIds.size() > MAX_ITEMS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A software bundle can contain at most " + MAX_ITEMS
                            + " catalog items.");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : catalogItemIds) {
            String slug = requireText(raw, "catalogItemIds");
            if (!normalized.add(slug)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Duplicate catalog item in bundle: " + slug);
            }
        }
        List<EndpointSoftwareCatalogItem> resolved = new ArrayList<>();
        for (String slug : normalized) {
            EndpointSoftwareCatalogItem item = catalogRepository
                    .findByTenantIdAndCatalogItemId(tenantId, slug)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Catalog item is not approved for bundle use: "
                                    + slug));
            if (item.getStatus() != CatalogItemStatus.APPROVED
                    || !item.isEnabled()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Catalog item is not approved for bundle use: "
                                + slug);
            }
            resolved.add(item);
        }
        return resolved;
    }

    private List<EndpointSoftwareBundleItem> buildItems(
            EndpointSoftwareBundle bundle,
            List<EndpointSoftwareCatalogItem> catalogItems) {
        List<EndpointSoftwareBundleItem> items = new ArrayList<>();
        int order = 0;
        for (EndpointSoftwareCatalogItem catalogItem : catalogItems) {
            EndpointSoftwareBundleItem item = new EndpointSoftwareBundleItem();
            item.setTenantId(bundle.getTenantId());
            item.setOrgId(bundle.getEffectiveOrgId());
            item.setBundleId(bundle.getId());
            item.setBundle(bundle);
            item.setCatalogItemId(catalogItem.getId());
            item.setCatalogItem(catalogItem);
            item.setItemOrder(order++);
            item.setRequired(true);
            items.add(item);
        }
        return items;
    }

    private AdminSoftwareBundleResponse responseFor(
            UUID tenantId,
            EndpointSoftwareBundle bundle) {
        List<EndpointSoftwareBundleItem> items =
                itemRepository.findByTenantIdAndBundleIdOrderByItemOrderAsc(
                        tenantId, bundle.getId());
        return AdminSoftwareBundleResponse.from(bundle, items);
    }

    private Map<String, Object> buildAuditMetadata(
            EndpointSoftwareBundle bundle,
            List<EndpointSoftwareCatalogItem> catalogItems) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bundleId", bundle.getBundleId());
        metadata.put("status", bundle.getStatus().name());
        metadata.put("catalogItemIds", catalogItems.stream()
                .map(EndpointSoftwareCatalogItem::getCatalogItemId)
                .toList());
        return metadata;
    }

    private Map<String, Object> buildAuditMetadataFromItems(
            EndpointSoftwareBundle bundle,
            List<EndpointSoftwareBundleItem> items) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("bundleId", bundle.getBundleId());
        metadata.put("status", bundle.getStatus().name());
        metadata.put("catalogItemIds", items.stream()
                .map(item -> item.getCatalogItem().getCatalogItemId())
                .toList());
        return metadata;
    }

    private Map<String, Object> snapshotAfter(
            EndpointSoftwareBundle bundle,
            List<EndpointSoftwareCatalogItem> catalogItems) {
        Map<String, Object> snapshot = baseSnapshot(bundle);
        snapshot.put("catalogItemIds", catalogItems.stream()
                .map(EndpointSoftwareCatalogItem::getCatalogItemId)
                .toList());
        return snapshot;
    }

    private Map<String, Object> snapshotAfterFromItems(
            EndpointSoftwareBundle bundle,
            List<EndpointSoftwareBundleItem> items) {
        Map<String, Object> snapshot = baseSnapshot(bundle);
        snapshot.put("catalogItemIds", items.stream()
                .map(item -> item.getCatalogItem().getCatalogItemId())
                .toList());
        return snapshot;
    }

    private Map<String, Object> baseSnapshot(EndpointSoftwareBundle bundle) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", bundle.getStatus().name());
        snapshot.put("enabled", bundle.isEnabled());
        snapshot.put("displayName", bundle.getDisplayName());
        snapshot.put("createdBySubject", bundle.getCreatedBySubject());
        snapshot.put("lastUpdatedBySubject", bundle.getLastUpdatedBySubject());
        snapshot.put("approvedBySubject", bundle.getApprovedBySubject());
        snapshot.put("revokedBySubject", bundle.getRevokedBySubject());
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
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " is required.");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
