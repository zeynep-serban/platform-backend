package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.compliancegap.ComplianceGapResponse;
import com.example.endpointadmin.dto.compliancegap.DeviceComplianceGap;
import com.example.endpointadmin.repository.ComplianceGapRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Faz 22.7 D2 (Codex 019e881c AGREE D) unit tests for
 * {@link EndpointComplianceGapService} validation + happy path orchestration.
 *
 * <p>SQL aggregation correctness validated separately via Testcontainers PG
 * integration (deferred to D2-ii follow-up; cluster LIVE acceptance
 * primary).
 */
class EndpointComplianceGapServiceTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEVICE_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ComplianceGapRepository repository;
    private EndpointComplianceGapService service;

    @BeforeEach
    void setUp() {
        repository = mock(ComplianceGapRepository.class);
        service = new EndpointComplianceGapService(repository);
    }

    @Test
    void rejectsNullTenantId() {
        assertThatThrownBy(() -> service.findGaps(null, null, null, 1, 50))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("tenantId required");
    }

    @Test
    void rejectsFreshnessWindowBeyondMax() {
        Duration tooBig = EndpointComplianceGapService.MAX_FRESHNESS_WINDOW.plusSeconds(1);
        assertThatThrownBy(() -> service.findGaps(TENANT, null, tooBig, 1, 50))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("freshnessWindow exceeds max");
    }

    @Test
    void rejectsZeroFreshnessWindow() {
        assertThatThrownBy(() -> service.findGaps(TENANT, null, Duration.ZERO, 1, 50))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("freshnessWindow must be positive");
    }

    @Test
    void rejectsNegativeFreshnessWindow() {
        assertThatThrownBy(() -> service.findGaps(TENANT, null, Duration.ofDays(-1), 1, 50))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("freshnessWindow must be positive");
    }

    @Test
    void emptyResultReturnsEmpty() {
        when(repository.findGapDevices(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(repository.countGapDevices(any(), any(), any()))
                .thenReturn(0L);

        ComplianceGapResponse response = service.findGaps(TENANT, null, null, 1, 50);

        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isZero();
        assertThat(response.filterEcho()).containsKey("gapTypes");
        assertThat(response.filterEcho()).containsEntry("page", 1);
        assertThat(response.filterEcho()).containsEntry("pageSize", 50);
    }

    @Test
    void caps_pageSize_at_max() {
        when(repository.findGapDevices(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(repository.countGapDevices(any(), any(), any())).thenReturn(0L);

        ComplianceGapResponse response = service.findGaps(TENANT, null, null, 1, 999);
        assertThat(response.pageSize()).isEqualTo(EndpointComplianceGapService.MAX_PAGE_SIZE);
    }

    @Test
    void normalizes_page_to_min_1() {
        when(repository.findGapDevices(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(repository.countGapDevices(any(), any(), any())).thenReturn(0L);

        ComplianceGapResponse response = service.findGaps(TENANT, null, null, 0, 50);
        assertThat(response.page()).isEqualTo(1);
    }

    @Test
    void singleDeviceWithRdpEnabledGap() {
        Timestamp startupTs = Timestamp.from(Instant.parse("2026-06-02T08:00:00Z"));

        when(repository.findGapDevices(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{
                        DEVICE_1, "HALILKOOLUB735", "HALILKOOLUB735",
                        Boolean.TRUE, startupTs,
                        null, null
                }));
        when(repository.countGapDevices(any(), any(), any()))
                .thenReturn(1L);

        ComplianceGapResponse response = service.findGaps(
                TENANT, Set.of(ComplianceGapType.RDP_ENABLED), null, 1, 50);

        assertThat(response.items()).hasSize(1);
        DeviceComplianceGap item = response.items().get(0);
        assertThat(item.deviceName()).isEqualTo("HALILKOOLUB735");
        assertThat(item.gapCount()).isEqualTo(1);
        assertThat(item.gaps()).hasSize(1);
        assertThat(item.gaps().get(0).type()).isEqualTo("rdp_enabled");
        assertThat(item.gaps().get(0).details()).containsEntry("rdpEnabled", true);
        assertThat(item.gapStrength()).isEqualTo("strong");
    }

    @Test
    void singleDeviceWithBothGaps() {
        Timestamp startupTs = Timestamp.from(Instant.parse("2026-06-02T08:00:00Z"));
        Timestamp hotfixTs = Timestamp.from(Instant.parse("2026-06-02T09:00:00Z"));

        when(repository.findGapDevices(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{
                        DEVICE_1, "SRB-AIDENETIMPC", "Lab Desktop",
                        Boolean.TRUE, startupTs,
                        Integer.valueOf(5), hotfixTs
                }));
        when(repository.countGapDevices(any(), any(), any()))
                .thenReturn(1L);

        ComplianceGapResponse response = service.findGaps(
                TENANT,
                Set.of(ComplianceGapType.RDP_ENABLED, ComplianceGapType.PENDING_SECURITY_UPDATES),
                null, 1, 50);

        assertThat(response.items()).hasSize(1);
        DeviceComplianceGap item = response.items().get(0);
        assertThat(item.deviceName()).isEqualTo("Lab Desktop");
        assertThat(item.gapCount()).isEqualTo(2);
        assertThat(item.gaps()).extracting("type")
                .containsExactlyInAnyOrder("rdp_enabled", "pending_security_updates");
        // lastSeen = max of both collected_at
        assertThat(item.lastSeen()).isEqualTo(Instant.parse("2026-06-02T09:00:00Z"));
    }

    @Test
    void displayNameFallbackToHostname() {
        Timestamp startupTs = Timestamp.from(Instant.parse("2026-06-02T08:00:00Z"));

        when(repository.findGapDevices(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.<Object[]>of(new Object[]{
                        DEVICE_2, "HOST-A", null,  // null display_name
                        Boolean.TRUE, startupTs,
                        null, null
                }));
        when(repository.countGapDevices(any(), any(), any()))
                .thenReturn(1L);

        ComplianceGapResponse response = service.findGaps(
                TENANT, null, null, 1, 50);

        DeviceComplianceGap item = response.items().get(0);
        assertThat(item.deviceName()).isEqualTo("HOST-A");
    }
}
