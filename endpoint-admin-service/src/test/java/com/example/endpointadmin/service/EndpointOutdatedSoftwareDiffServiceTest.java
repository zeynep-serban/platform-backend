package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareDiffEntryResponse;
import com.example.endpointadmin.dto.v1.admin.AdminOutdatedSoftwareDiffResponse;
import com.example.endpointadmin.model.EndpointOutdatedSoftwarePackage;
import com.example.endpointadmin.model.EndpointOutdatedSoftwareSnapshot;
import com.example.endpointadmin.repository.EndpointOutdatedSoftwareSnapshotRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BE-024b — outdated-software diff service unit tests (Faz 22.5 P2-A
 * slice-3). Codex 019e8542 iter-2 absorb invariants pinned:
 * - NO_HISTORY when 0 snapshots (no-existence-leak)
 * - INSUFFICIENT_HISTORY when 1 snapshot (carries toSnapshot scalars)
 * - NO_CHANGE when 2 snapshots have identical package map
 * - 4 change types with VERSION_CHANGED precedence over
 *   AVAILABLE_VERSION_BUMPED
 * - canonical packageId.toLowerCase identity match
 * - duplicate canonical key fail-loud
 */
class EndpointOutdatedSoftwareDiffServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID DEVICE = UUID.fromString("22222222-0000-0000-0000-000000000001");

    private final EndpointOutdatedSoftwareSnapshotRepository repository =
            mock(EndpointOutdatedSoftwareSnapshotRepository.class);
    private final EndpointOutdatedSoftwareDiffService service =
            new EndpointOutdatedSoftwareDiffService(repository);

    private final AdminTenantContext context = mock(AdminTenantContext.class);

    EndpointOutdatedSoftwareDiffServiceTest() {
        when(context.tenantId()).thenReturn(TENANT);
    }

    @Test
    void noHistory_whenZeroSnapshots() {
        mockRepoReturnsEmpty();
        AdminOutdatedSoftwareDiffResponse response = service.diffLatest(context, DEVICE);
        assertThat(response.status()).isEqualTo(AdminOutdatedSoftwareDiffResponse.DiffStatus.NO_HISTORY);
        assertThat(response.deviceId()).isEqualTo(DEVICE);
        assertThat(response.added()).isEmpty();
        assertThat(response.removed()).isEmpty();
        assertThat(response.versionChanged()).isEmpty();
        assertThat(response.availableVersionBumped()).isEmpty();
    }

    @Test
    void insufficientHistory_whenSingleSnapshot() {
        EndpointOutdatedSoftwareSnapshot to = snapshot(0, 5, false, pkg("Foo.Bar", "1.0", "2.0"));
        mockRepoReturnsList(List.of(to));
        AdminOutdatedSoftwareDiffResponse response = service.diffLatest(context, DEVICE);
        assertThat(response.status()).isEqualTo(AdminOutdatedSoftwareDiffResponse.DiffStatus.INSUFFICIENT_HISTORY);
        assertThat(response.toSnapshotId()).isEqualTo(to.getId());
        assertThat(response.toUpgradeCount()).isEqualTo(5);
        assertThat(response.toPossiblyTruncated()).isFalse();
        assertThat(response.fromSnapshotId()).isNull();
    }

    @Test
    void possiblyTruncatedDerivedFromMaxUpgradeNotJustFlag() {
        // Codex iter-3 P1: upgradeCount >= maxUpgrade implies possiblyTruncated
        // even when upgradeTruncated=false. OutdatedSnapshotTruncation is the
        // single source of truth.
        EndpointOutdatedSoftwareSnapshot to = snapshot(2, 512, false, pkg("Foo.Bar", "1.0", "2.0"));
        to.setMaxUpgrade(512);
        mockRepoReturnsList(List.of(to));
        AdminOutdatedSoftwareDiffResponse response = service.diffLatest(context, DEVICE);
        assertThat(response.status())
                .isEqualTo(AdminOutdatedSoftwareDiffResponse.DiffStatus.INSUFFICIENT_HISTORY);
        assertThat(response.toPossiblyTruncated()).isTrue();
    }

    @Test
    void noChange_whenIdenticalPackageMap() {
        EndpointOutdatedSoftwareSnapshot from = snapshot(1, 1, false, pkg("Foo.Bar", "1.0", "2.0"));
        EndpointOutdatedSoftwareSnapshot to = snapshot(2, 1, false, pkg("Foo.Bar", "1.0", "2.0"));
        mockRepoReturnsList(List.of(to, from));
        AdminOutdatedSoftwareDiffResponse response = service.diffLatest(context, DEVICE);
        assertThat(response.status()).isEqualTo(AdminOutdatedSoftwareDiffResponse.DiffStatus.NO_CHANGE);
        assertThat(response.fromSnapshotId()).isEqualTo(from.getId());
        assertThat(response.toSnapshotId()).isEqualTo(to.getId());
        assertThat(response.added()).isEmpty();
        assertThat(response.versionChanged()).isEmpty();
        assertThat(response.availableVersionBumped()).isEmpty();
    }

    @Test
    void addedWhenPackageOnlyInLatest() {
        EndpointOutdatedSoftwareSnapshot from = snapshot(1, 0, false);
        EndpointOutdatedSoftwareSnapshot to = snapshot(2, 1, false, pkg("New.Pkg", "1.0", "1.1"));
        mockRepoReturnsList(List.of(to, from));
        AdminOutdatedSoftwareDiffResponse response = service.diffLatest(context, DEVICE);
        assertThat(response.status()).isEqualTo(AdminOutdatedSoftwareDiffResponse.DiffStatus.OK);
        assertThat(response.added()).hasSize(1);
        assertThat(response.added().get(0).packageId()).isEqualTo("New.Pkg");
        assertThat(response.added().get(0).changeType())
                .isEqualTo(AdminOutdatedSoftwareDiffEntryResponse.ChangeType.ADDED);
        assertThat(response.added().get(0).fromInstalledVersion()).isNull();
        assertThat(response.added().get(0).toInstalledVersion()).isEqualTo("1.0");
    }

    @Test
    void removedWhenPackageOnlyInPrevious() {
        EndpointOutdatedSoftwareSnapshot from = snapshot(1, 1, false, pkg("Gone.Pkg", "1.0", "2.0"));
        EndpointOutdatedSoftwareSnapshot to = snapshot(2, 0, false);
        mockRepoReturnsList(List.of(to, from));
        AdminOutdatedSoftwareDiffResponse response = service.diffLatest(context, DEVICE);
        assertThat(response.status()).isEqualTo(AdminOutdatedSoftwareDiffResponse.DiffStatus.OK);
        assertThat(response.removed()).hasSize(1);
        assertThat(response.removed().get(0).packageId()).isEqualTo("Gone.Pkg");
        assertThat(response.removed().get(0).changeType())
                .isEqualTo(AdminOutdatedSoftwareDiffEntryResponse.ChangeType.REMOVED);
        assertThat(response.removed().get(0).fromInstalledVersion()).isEqualTo("1.0");
        assertThat(response.removed().get(0).toInstalledVersion()).isNull();
    }

    @Test
    void versionChangedPrecedenceOverAvailable() {
        // installed AND available both changed → VERSION_CHANGED single entry,
        // both deltas on the wire, NOT duplicated to availableVersionBumped.
        EndpointOutdatedSoftwareSnapshot from = snapshot(1, 1, false, pkg("Foo.Bar", "1.0", "2.0"));
        EndpointOutdatedSoftwareSnapshot to = snapshot(2, 1, false, pkg("Foo.Bar", "1.5", "2.5"));
        mockRepoReturnsList(List.of(to, from));
        AdminOutdatedSoftwareDiffResponse response = service.diffLatest(context, DEVICE);
        assertThat(response.versionChanged()).hasSize(1);
        AdminOutdatedSoftwareDiffEntryResponse vc = response.versionChanged().get(0);
        assertThat(vc.fromInstalledVersion()).isEqualTo("1.0");
        assertThat(vc.toInstalledVersion()).isEqualTo("1.5");
        assertThat(vc.fromAvailableVersion()).isEqualTo("2.0");
        assertThat(vc.toAvailableVersion()).isEqualTo("2.5");
        assertThat(response.availableVersionBumped()).isEmpty();
    }

    @Test
    void availableVersionBumpedWhenOnlyAvailableChanged() {
        EndpointOutdatedSoftwareSnapshot from = snapshot(1, 1, false, pkg("Foo.Bar", "1.0", "2.0"));
        EndpointOutdatedSoftwareSnapshot to = snapshot(2, 1, false, pkg("Foo.Bar", "1.0", "2.5"));
        mockRepoReturnsList(List.of(to, from));
        AdminOutdatedSoftwareDiffResponse response = service.diffLatest(context, DEVICE);
        assertThat(response.versionChanged()).isEmpty();
        assertThat(response.availableVersionBumped()).hasSize(1);
        AdminOutdatedSoftwareDiffEntryResponse avb = response.availableVersionBumped().get(0);
        assertThat(avb.changeType())
                .isEqualTo(AdminOutdatedSoftwareDiffEntryResponse.ChangeType.AVAILABLE_VERSION_BUMPED);
        assertThat(avb.fromAvailableVersion()).isEqualTo("2.0");
        assertThat(avb.toAvailableVersion()).isEqualTo("2.5");
    }

    @Test
    void canonicalKeyMatchesCaseInsensitive() {
        // packageId case differs across captures — should match canonically.
        EndpointOutdatedSoftwareSnapshot from = snapshot(1, 1, false, pkg("Foo.BAR", "1.0", "2.0"));
        EndpointOutdatedSoftwareSnapshot to = snapshot(2, 1, false, pkg("foo.bar", "1.0", "2.5"));
        mockRepoReturnsList(List.of(to, from));
        AdminOutdatedSoftwareDiffResponse response = service.diffLatest(context, DEVICE);
        assertThat(response.status()).isEqualTo(AdminOutdatedSoftwareDiffResponse.DiffStatus.OK);
        assertThat(response.added()).isEmpty();
        assertThat(response.removed()).isEmpty();
        assertThat(response.availableVersionBumped()).hasSize(1);
        assertThat(response.availableVersionBumped().get(0).packageId()).isEqualTo("foo.bar");
    }

    @Test
    void duplicateCanonicalKeyFailsLoud() {
        // Two snapshots so the diff path is entered (single snapshot
        // shortcuts to INSUFFICIENT_HISTORY without touching the
        // canonical index).
        EndpointOutdatedSoftwareSnapshot from = snapshot(1, 0, false);
        EndpointOutdatedSoftwareSnapshot to = snapshot(2, 2, false,
                pkg("Foo.Bar", "1.0", "2.0"),
                pkg("foo.bar", "1.5", "2.5"));
        mockRepoReturnsList(List.of(to, from));
        assertThatThrownBy(() -> service.diffLatest(context, DEVICE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate canonical packageId");
    }

    // ─── helpers ────────────────────────────────────────────────

    private void mockRepoReturnsEmpty() {
        Page<EndpointOutdatedSoftwareSnapshot> page = new PageImpl<>(List.of());
        when(repository.findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                eq(TENANT), eq(DEVICE), any(Pageable.class))).thenReturn(page);
    }

    private void mockRepoReturnsList(List<EndpointOutdatedSoftwareSnapshot> list) {
        Page<EndpointOutdatedSoftwareSnapshot> page = new PageImpl<>(list);
        when(repository.findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                eq(TENANT), eq(DEVICE), any(Pageable.class))).thenReturn(page);
    }

    private EndpointOutdatedSoftwareSnapshot snapshot(
            int seq, int count, boolean truncated, EndpointOutdatedSoftwarePackage... pkgs) {
        EndpointOutdatedSoftwareSnapshot s = new EndpointOutdatedSoftwareSnapshot();
        s.setId(UUID.fromString("33333333-0000-0000-0000-00000000000" + seq));
        s.setTenantId(TENANT);
        s.setDeviceId(DEVICE);
        s.setCollectedAt(Instant.parse("2026-06-01T10:0" + seq + ":00Z"));
        s.setCreatedAt(Instant.parse("2026-06-01T10:0" + seq + ":01Z"));
        s.setUpgradeCount(count);
        s.setUpgradeTruncated(truncated);
        List<EndpointOutdatedSoftwarePackage> list = new ArrayList<>();
        for (EndpointOutdatedSoftwarePackage p : pkgs) {
            list.add(p);
        }
        s.setPackages(list);
        return s;
    }

    private EndpointOutdatedSoftwarePackage pkg(String packageId, String installed, String available) {
        EndpointOutdatedSoftwarePackage p = new EndpointOutdatedSoftwarePackage();
        p.setPackageId(packageId);
        p.setInstalledVersion(installed);
        p.setAvailableVersion(available);
        return p;
    }
}
