package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareDiffEntryResponse;
import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareDiffResponse;
import com.example.endpointadmin.model.EndpointOutdatedSoftwarePackage;
import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import com.example.endpointadmin.repository.EndpointOutdatedSoftwareSnapshotRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * BE-024b — latest-vs-previous outdated-software diff service (Faz 22.5
 * P2-A slice-3). Codex 019e8542 iter-2 AGREE.
 *
 * <p>Identity: canonical {@code packageId.toLowerCase(Locale.ROOT)};
 * cross-domain join NOT performed. NO_CHANGE = package-map delta empty
 * (NOT hash fast-path). Duplicate canonical key in a single snapshot →
 * fail-loud {@link IllegalStateException}. Tenant isolation: every
 * read scoped by tenantId+deviceId; unknown/cross-tenant returns
 * NO_HISTORY (no-existence-leak).
 */
@Service
public class EndpointOutdatedSoftwareDiffService {

    private final EndpointOutdatedSoftwareSnapshotRepository repository;

    public EndpointOutdatedSoftwareDiffService(
            EndpointOutdatedSoftwareSnapshotRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AdminOutdatedSoftwareDiffResponse diffLatest(
            AdminTenantContext context, UUID deviceId) {
        UUID tenantId = context.tenantId();
        List<EndpointOutdatedSoftwareSnapshot> latestTwo = repository
                .findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                        tenantId, deviceId, PageRequest.of(0, 2))
                .getContent();

        if (latestTwo.isEmpty()) {
            return AdminOutdatedSoftwareDiffResponse.noHistory(deviceId);
        }

        EndpointOutdatedSoftwareSnapshot to = latestTwo.get(0);

        if (latestTwo.size() == 1) {
            return AdminOutdatedSoftwareDiffResponse.insufficientHistory(
                    deviceId,
                    to.getId(),
                    to.getCollectedAt(),
                    to.getUpgradeCount(),
                    OutdatedSnapshotTruncation.isPossiblyTruncated(to));
        }

        EndpointOutdatedSoftwareSnapshot from = latestTwo.get(1);

        Map<String, EndpointOutdatedSoftwarePackage> fromMap = indexByCanonicalKey(
                from.getPackages(), from.getId());
        Map<String, EndpointOutdatedSoftwarePackage> toMap = indexByCanonicalKey(
                to.getPackages(), to.getId());

        List<AdminOutdatedSoftwareDiffEntryResponse> added = new ArrayList<>();
        List<AdminOutdatedSoftwareDiffEntryResponse> removed = new ArrayList<>();
        List<AdminOutdatedSoftwareDiffEntryResponse> versionChanged = new ArrayList<>();
        List<AdminOutdatedSoftwareDiffEntryResponse> availableVersionBumped = new ArrayList<>();

        for (Map.Entry<String, EndpointOutdatedSoftwarePackage> e : toMap.entrySet()) {
            String canonical = e.getKey();
            EndpointOutdatedSoftwarePackage toPkg = e.getValue();
            EndpointOutdatedSoftwarePackage fromPkg = fromMap.get(canonical);

            if (fromPkg == null) {
                added.add(AdminOutdatedSoftwareDiffEntryResponse.added(
                        toPkg.getPackageId(),
                        toPkg.getInstalledVersion(),
                        toPkg.getAvailableVersion()));
                continue;
            }
            boolean installedDelta = !Objects.equals(
                    fromPkg.getInstalledVersion(), toPkg.getInstalledVersion());
            boolean availableDelta = !Objects.equals(
                    fromPkg.getAvailableVersion(), toPkg.getAvailableVersion());

            if (installedDelta) {
                versionChanged.add(AdminOutdatedSoftwareDiffEntryResponse.versionChanged(
                        toPkg.getPackageId(),
                        fromPkg.getInstalledVersion(), toPkg.getInstalledVersion(),
                        fromPkg.getAvailableVersion(), toPkg.getAvailableVersion()));
            } else if (availableDelta) {
                availableVersionBumped.add(AdminOutdatedSoftwareDiffEntryResponse.availableVersionBumped(
                        toPkg.getPackageId(),
                        toPkg.getInstalledVersion(),
                        fromPkg.getAvailableVersion(), toPkg.getAvailableVersion()));
            }
        }

        for (Map.Entry<String, EndpointOutdatedSoftwarePackage> e : fromMap.entrySet()) {
            if (!toMap.containsKey(e.getKey())) {
                EndpointOutdatedSoftwarePackage fromPkg = e.getValue();
                removed.add(AdminOutdatedSoftwareDiffEntryResponse.removed(
                        fromPkg.getPackageId(),
                        fromPkg.getInstalledVersion(),
                        fromPkg.getAvailableVersion()));
            }
        }

        int totalDelta = added.size() + removed.size()
                + versionChanged.size() + availableVersionBumped.size();
        if (totalDelta == 0) {
            return AdminOutdatedSoftwareDiffResponse.noChange(
                    deviceId,
                    from.getId(), to.getId(),
                    from.getCollectedAt(), to.getCollectedAt(),
                    from.getUpgradeCount(), to.getUpgradeCount(),
                    OutdatedSnapshotTruncation.isPossiblyTruncated(from),
                    OutdatedSnapshotTruncation.isPossiblyTruncated(to));
        }

        return new AdminOutdatedSoftwareDiffResponse(
                deviceId,
                AdminOutdatedSoftwareDiffResponse.DiffStatus.OK,
                from.getId(), to.getId(),
                from.getCollectedAt(), to.getCollectedAt(),
                from.getUpgradeCount(), to.getUpgradeCount(),
                OutdatedSnapshotTruncation.isPossiblyTruncated(from),
                OutdatedSnapshotTruncation.isPossiblyTruncated(to),
                added, removed, versionChanged, availableVersionBumped);
    }

    private Map<String, EndpointOutdatedSoftwarePackage> indexByCanonicalKey(
            List<EndpointOutdatedSoftwarePackage> packages, UUID snapshotId) {
        if (packages == null || packages.isEmpty()) {
            return Map.of();
        }
        Map<String, EndpointOutdatedSoftwarePackage> map = new LinkedHashMap<>(packages.size());
        for (EndpointOutdatedSoftwarePackage p : packages) {
            String key = canonicalKey(p.getPackageId());
            EndpointOutdatedSoftwarePackage previous = map.put(key, p);
            if (previous != null) {
                throw new IllegalStateException(
                        "AG-036 outdated snapshot " + snapshotId
                                + " carries duplicate canonical packageId '"
                                + key + "' — evidence quality broken; agent/parser bug");
            }
        }
        return map;
    }

    private static String canonicalKey(String packageId) {
        return packageId == null ? "" : packageId.toLowerCase(Locale.ROOT);
    }
}
