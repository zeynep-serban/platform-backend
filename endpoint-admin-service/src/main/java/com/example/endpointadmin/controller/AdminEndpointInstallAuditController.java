package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.EndpointInstallAuditDto;
import com.example.endpointadmin.model.EndpointInstallAudit;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.repository.EndpointInstallAuditRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCatalogItemRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * BE-021 — install audit read surface (Faz 22.5).
 *
 * <pre>
 * GET /api/v1/admin/endpoint-devices/{deviceId}/installs   ?page=N&size=N
 * GET /api/v1/admin/endpoint-install-audits/{auditId}
 * </pre>
 *
 * <p>Both routes are tenant-scoped (404 on cross-tenant access) and
 * read-only. The list endpoint is paged; default page size 25, max 100
 * (matches the BE-020 catalog listing convention). The detail endpoint
 * surfaces both the public catalog slug and the internal UUID via a
 * cheap repository-side join, so the UI can render
 * {@code "package: 7zip.7zip (slug: 7zip-7zip-stable)"} without an
 * extra round-trip.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointInstallAuditController {

    private static final int MAX_PAGE_SIZE = 100;

    private final EndpointInstallAuditRepository installAuditRepository;
    private final EndpointSoftwareCatalogItemRepository catalogRepository;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointInstallAuditController(
            EndpointInstallAuditRepository installAuditRepository,
            EndpointSoftwareCatalogItemRepository catalogRepository,
            TenantContextResolver tenantContextResolver) {
        this.installAuditRepository = installAuditRepository;
        this.catalogRepository = catalogRepository;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-devices/{deviceId}/installs")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public Page<EndpointInstallAuditDto> listInstallsForDevice(
            @PathVariable UUID deviceId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0.");
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "size must be in [1, " + MAX_PAGE_SIZE + "].");
        }
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "reportedAt"));
        // Faz 21.1 PR2b-iv.e-A — effective-org page read. orgId ==
        // tenantId for canonical rows (PR2b-ii write path); legacy NULL
        // rows still visible via OR-fallback inside the @Query.
        Page<EndpointInstallAudit> rows = installAuditRepository
                .findVisibleToOrgAndDeviceIdOrderByReportedAtDesc(
                        context.tenantId(), deviceId, pageRequest);
        Map<UUID, String> slugByUuid = lookupCatalogSlugs(context, rows.getContent());
        return rows.map(audit -> toDto(audit, slugByUuid.get(audit.getCatalogItemId())));
    }

    @GetMapping("/endpoint-install-audits/{auditId}")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public EndpointInstallAuditDto getInstallAudit(@PathVariable UUID auditId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        // Faz 21.1 PR2b-iv.e-A — effective-org audit ownership gate.
        EndpointInstallAudit audit = installAuditRepository
                .findVisibleToOrgAndId(context.tenantId(), auditId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Install audit not found."));
        String slug = catalogRepository
                .findByTenantIdAndId(context.tenantId(), audit.getCatalogItemId())
                .map(EndpointSoftwareCatalogItem::getCatalogItemId)
                .orElse(null);
        return toDto(audit, slug);
    }

    private Map<UUID, String> lookupCatalogSlugs(AdminTenantContext context,
                                                 List<EndpointInstallAudit> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> map = new HashMap<>();
        for (UUID uuid : rows.stream().map(EndpointInstallAudit::getCatalogItemId)
                .collect(Collectors.toSet())) {
            catalogRepository.findByTenantIdAndId(context.tenantId(), uuid)
                    .ifPresent(item -> map.put(uuid, item.getCatalogItemId()));
        }
        return map;
    }

    private static EndpointInstallAuditDto toDto(EndpointInstallAudit audit, String slug) {
        return new EndpointInstallAuditDto(
                audit.getId(),
                audit.getTenantId(),
                audit.getDeviceId(),
                audit.getCommandId(),
                slug,
                audit.getCatalogItemId(),
                audit.getCatalogPackageId(),
                audit.getCatalogRowVersion(),
                audit.getPreflightDecision(),
                audit.getPreflightDecisionAt(),
                audit.getPreflightWarnCodes(),
                audit.getActorSubject(),
                audit.getApprovalSubject(),
                audit.getResultStatus(),
                audit.getExitCode(),
                audit.getReportedAt(),
                audit.getStartedAt(),
                audit.getFinishedAt(),
                audit.getPostVerification(),
                audit.getDetectedPackageId(),
                audit.getDetectedVersion(),
                audit.getPostVerificationEvidence(),
                audit.getRedactedPayload(),
                audit.getRowVersion(),
                audit.getCreatedAt());
    }
}
