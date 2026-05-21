package com.example.endpointadmin.service;

import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.agent.AgentHeartbeatRequest;
import com.example.endpointadmin.dto.v1.agent.AgentHeartbeatResponse;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointHeartbeat;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointHeartbeatRepository;
import com.example.endpointadmin.security.DeviceCredentialException;
import com.example.endpointadmin.security.DeviceCredentialResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TimeConfig.class, EndpointHeartbeatService.class})
class EndpointHeartbeatServiceTest {

    @Autowired
    private EndpointHeartbeatService heartbeatService;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointHeartbeatRepository heartbeatRepository;

    @Test
    void recordHeartbeatUpdatesDeviceAndStoresPayload() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.OFFLINE));
        AgentHeartbeatRequest request = new AgentHeartbeatRequest(
                "install-1",
                "PC-001",
                null,
                OsType.WINDOWS,
                "amd64",
                "0.2.0",
                "Windows 11 Pro",
                "ONLINE",
                List.of("COLLECT_INVENTORY", "LIST_LOCAL_USERS"),
                Instant.parse("2026-04-28T10:00:00Z"),
                Map.of("cpuCount", 8, "memoryMb", 16384),
                List.of(Map.of("username", "local.user", "enabled", true)),
                Map.of("queueDepth", 0)
        );

        AgentHeartbeatResponse response = heartbeatService.recordHeartbeat(
                new DeviceCredentialResult(device.getId().toString(), UUID.randomUUID().toString(), Instant.now()),
                request,
                "10.10.10.50"
        );

        assertThat(response.accepted()).isTrue();
        assertThat(response.deviceId()).isEqualTo(device.getId());
        assertThat(response.status()).isEqualTo(DeviceStatus.ONLINE);

        EndpointDevice updated = deviceRepository.findById(device.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(DeviceStatus.ONLINE);
        assertThat(updated.getLastSeenAt()).isNotNull();
        assertThat(updated.getAgentVersion()).isEqualTo("0.2.0");
        assertThat(updated.getOsVersion()).isEqualTo("Windows 11 Pro");
        assertThat(updated.getOsType()).isEqualTo(OsType.WINDOWS);

        List<EndpointHeartbeat> heartbeats = heartbeatRepository.findTop20ByDevice_IdOrderByReceivedAtDesc(device.getId());
        assertThat(heartbeats).hasSize(1);
        EndpointHeartbeat heartbeat = heartbeats.get(0);
        assertThat(heartbeat.getTenantId()).isEqualTo(device.getTenantId());
        assertThat(heartbeat.getIpAddress()).isEqualTo("10.10.10.50");
        assertThat(heartbeat.getPayload())
                .containsEntry("installId", "install-1")
                .containsEntry("hostname", "PC-001")
                .containsEntry("state", "ONLINE")
                .containsEntry("architecture", "amd64")
                .containsKey("inventory")
                .containsKey("localUsers")
                .containsKey("metrics");
        assertThat(heartbeat.getPayload().get("capabilities"))
                .isEqualTo(List.of("COLLECT_INVENTORY", "LIST_LOCAL_USERS"));
    }

    @Test
    void recordHeartbeatRequiresAuthenticatedDevice() {
        AgentHeartbeatRequest request = heartbeatRequest();

        assertThatThrownBy(() -> heartbeatService.recordHeartbeat(null, request, "127.0.0.1"))
                .isInstanceOf(DeviceCredentialException.class)
                .extracting("errorCode")
                .isEqualTo("DEVICE_AUTH_REQUIRED");
    }

    @Test
    void recordHeartbeatRejectsDecommissionedDevice() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(DeviceStatus.DECOMMISSIONED));

        assertThatThrownBy(() -> heartbeatService.recordHeartbeat(
                new DeviceCredentialResult(device.getId().toString(), UUID.randomUUID().toString(), Instant.now()),
                heartbeatRequest(),
                "127.0.0.1"
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Endpoint device is decommissioned.");
    }

    private AgentHeartbeatRequest heartbeatRequest() {
        return new AgentHeartbeatRequest(
                "install-1",
                "PC-001",
                OsType.WINDOWS,
                null,
                "amd64",
                "0.2.0",
                "Windows 11 Pro",
                "ONLINE",
                List.of("COLLECT_INVENTORY"),
                Instant.now(),
                Map.of(),
                List.of(),
                Map.of()
        );
    }

    private EndpointDevice device(DeviceStatus status) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(UUID.randomUUID());
        device.setHostname("PC-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setOsVersion("Windows 10");
        device.setAgentVersion("0.1.0");
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        device.setDomainName("corp.local");
        device.setStatus(status);
        return device;
    }
}
