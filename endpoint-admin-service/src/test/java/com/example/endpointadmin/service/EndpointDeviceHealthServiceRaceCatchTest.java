package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDeviceHealthSnapshot;
import com.example.endpointadmin.repository.EndpointDeviceHealthSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE — Mockito unit test for the {@link EndpointDeviceHealthService}
 * race-catch branch (Faz 22.5, AG-033). Mirrors the BE-022
 * {@code EndpointHardwareInventoryServiceRaceCatchTest}.
 *
 * <p>Proves the SERVICE'S catch block maps a concurrent insert collision
 * to the existing row, after both idempotency probes
 * ({@code findBySourceCommandResultId} + the payload-hash dedupe
 * {@code findByTenantDeviceAndPayloadHash}) miss.
 */
class EndpointDeviceHealthServiceRaceCatchTest {

    @Test
    void raceCatchReturnsExistingSnapshotInsteadOfPropagatingTheException() {
        EndpointDeviceHealthSnapshotRepository repository =
                mock(EndpointDeviceHealthSnapshotRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        EndpointDeviceHealthService service =
                new EndpointDeviceHealthService(repository, events);

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        EndpointDevice device = device(tenantId, deviceId);
        EndpointCommand command = command(device);
        EndpointCommandResult result = result(device, command);
        UUID commandResultId = result.getId();

        EndpointDeviceHealthSnapshot existing = new EndpointDeviceHealthSnapshot();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        existing.setDeviceId(deviceId);
        existing.setSourceCommandResultId(commandResultId);

        // Probe #1 (source_command_result_id) — empty, then inside the
        // catch returns the racing winner's row.
        when(repository.findBySourceCommandResultId(commandResultId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));

        // Payload-hash dedupe probe — empty (no identical row yet).
        // Matchers use captured local UUIDs (NOT mocked getters) so the
        // matcher evaluation does not interfere with mock stubbing.
        when(repository.findByTenantDeviceAndPayloadHash(
                eq(tenantId), eq(deviceId), any(String.class),
                any(Pageable.class)))
                .thenReturn(List.of());

        // saveAndFlush trips the DB partial UNIQUE constraint.
        when(repository.saveAndFlush(any(EndpointDeviceHealthSnapshot.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint "
                                + "\"uq_endpoint_device_health_snapshots_source_cmd_result\""));

        EndpointDeviceHealthSnapshot returned =
                service.ingest(device, command, result, effectiveDetails());

        assertThat(returned).isSameAs(existing);
        verify(repository, times(2)).findBySourceCommandResultId(commandResultId);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void raceCatchRethrowsWhenSourceCommandResultIdIsNull() {
        EndpointDeviceHealthSnapshotRepository repository =
                mock(EndpointDeviceHealthSnapshotRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        EndpointDeviceHealthService service =
                new EndpointDeviceHealthService(repository, events);

        UUID tenantId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        EndpointDevice device = device(tenantId, deviceId);
        EndpointCommand command = command(device);
        EndpointCommandResult result = null; // null → null source_command_result_id

        when(repository.findByTenantDeviceAndPayloadHash(
                eq(tenantId), eq(deviceId), any(String.class),
                any(Pageable.class)))
                .thenReturn(List.of());
        when(repository.saveAndFlush(any(EndpointDeviceHealthSnapshot.class)))
                .thenThrow(new DataIntegrityViolationException("some other check violated"));

        assertThatThrownBy(() ->
                service.ingest(device, command, result, effectiveDetails()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("some other check violated");

        verify(events, never()).publishEvent(any());
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static EndpointDevice device(UUID tenantId, UUID deviceId) {
        EndpointDevice device = mock(EndpointDevice.class);
        when(device.getId()).thenReturn(deviceId);
        when(device.getTenantId()).thenReturn(tenantId);
        return device;
    }

    private static EndpointCommand command(EndpointDevice device) {
        UUID commandId = UUID.randomUUID();
        EndpointCommand command = mock(EndpointCommand.class);
        when(command.getId()).thenReturn(commandId);
        when(command.getDevice()).thenReturn(device);
        return command;
    }

    private static EndpointCommandResult result(EndpointDevice device, EndpointCommand command) {
        UUID resultId = UUID.randomUUID();
        EndpointCommandResult result = mock(EndpointCommandResult.class);
        when(result.getId()).thenReturn(resultId);
        when(result.getReportedAt()).thenReturn(Instant.now());
        return result;
    }

    private static Map<String, Object> effectiveDetails() {
        Map<String, Object> dh = new LinkedHashMap<>();
        dh.put("schemaVersion", 1);
        dh.put("supported", true);
        dh.put("probeComplete", true);
        dh.put("anyLowDisk", false);
        dh.put("fixedDiskCount", 0);
        dh.put("fixedDisksTruncated", false);
        dh.put("maxFixedDisks", 64);
        dh.put("sourceUsed", "win32");
        dh.put("probeDurationMs", 5);
        dh.put("fixedDisks", List.of());

        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("deviceHealth", dh);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventory", inventory);
        return details;
    }
}
