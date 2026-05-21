package com.example.endpointadmin.service;

import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.admin.CreateEndpointCommandRequest;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.model.CommandResultStatus;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointCommand;
import com.example.endpointadmin.model.EndpointCommandResult;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.OsType;
import com.example.endpointadmin.repository.EndpointAuditEventRepository;
import com.example.endpointadmin.repository.EndpointCommandRepository;
import com.example.endpointadmin.repository.EndpointCommandResultRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({TimeConfig.class, EndpointAdminCommandService.class, EndpointAuditService.class})
class EndpointAdminCommandServiceTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private EndpointAdminCommandService commandService;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    @Autowired
    private EndpointCommandRepository commandRepository;

    @Autowired
    private EndpointCommandResultRepository resultRepository;

    @Autowired
    private EndpointAuditEventRepository auditRepository;

    @Test
    void createCommandStoresQueuedCommandAndAuditEventIdempotently() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(TENANT_ID, "PC-001"));
        CreateEndpointCommandRequest request = new CreateEndpointCommandRequest(
                CommandType.COLLECT_INVENTORY,
                "inventory-001",
                "inventory refresh",
                Map.of("requestedDetail", "basic"),
                10,
                2,
                null,
                Instant.now().plusSeconds(3600),
                null
        );

        EndpointCommandDto created = commandService.createCommand(adminContext(), device.getId(), request);
        EndpointCommandDto replayed = commandService.createCommand(adminContext(), device.getId(), request);

        assertThat(replayed.id()).isEqualTo(created.id());
        assertThat(created.deviceId()).isEqualTo(device.getId());
        assertThat(created.type()).isEqualTo(CommandType.COLLECT_INVENTORY);
        assertThat(created.status()).isEqualTo(CommandStatus.QUEUED);
        assertThat(created.priority()).isEqualTo(10);
        assertThat(created.maxAttempts()).isEqualTo(2);
        assertThat(created.issuedBySubject()).isEqualTo("admin@example.com");
        assertThat(created.payload())
                .containsEntry("requestedDetail", "basic")
                .containsEntry("reason", "inventory refresh");

        EndpointCommand stored = commandRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getIdempotencyKey()).isEqualTo("inventory-001");
        assertThat(auditRepository.findTop50ByTenantIdOrderByOccurredAtDesc(TENANT_ID))
                .extracting("eventType")
                .contains("ENDPOINT_COMMAND_CREATED");
    }

    @Test
    void createCommandRejectsDisabledSensitiveCommandType() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(TENANT_ID, "PC-001"));
        CreateEndpointCommandRequest request = new CreateEndpointCommandRequest(
                CommandType.CHANGE_LOCAL_PASSWORD,
                "password-001",
                "blocked",
                Map.of("username", "local.user"),
                null,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> commandService.createCommand(adminContext(), device.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Command type is not enabled");
    }

    @Test
    void getCommandIncludesAgentResultWhenPresent() {
        EndpointDevice device = deviceRepository.saveAndFlush(device(TENANT_ID, "PC-001"));
        EndpointCommandDto created = commandService.createCommand(adminContext(), device.getId(), inventoryRequest("inventory-result"));
        EndpointCommand command = commandRepository.findById(created.id()).orElseThrow();
        command.setStatus(CommandStatus.SUCCEEDED);
        command.setCompletedAt(Instant.now());
        commandRepository.saveAndFlush(command);

        EndpointCommandResult result = new EndpointCommandResult();
        result.setTenantId(TENANT_ID);
        result.setCommand(command);
        result.setDevice(device);
        result.setResultStatus(CommandResultStatus.SUCCEEDED);
        result.setResultPayload(Map.of("summary", "done"));
        result.setExitCode(0);
        result.setReportedAt(Instant.now());
        resultRepository.saveAndFlush(result);

        EndpointCommandDto response = commandService.getCommand(adminContext(), created.id());

        assertThat(response.status()).isEqualTo(CommandStatus.SUCCEEDED);
        assertThat(response.result()).isNotNull();
        assertThat(response.result().status()).isEqualTo(CommandResultStatus.SUCCEEDED);
        assertThat(response.result().payload()).containsEntry("summary", "done");
    }

    @Test
    void listDeviceCommandsScopesByTenantAndDevice() {
        EndpointDevice first = deviceRepository.saveAndFlush(device(TENANT_ID, "PC-001"));
        EndpointDevice second = deviceRepository.saveAndFlush(device(TENANT_ID, "PC-002"));
        EndpointDevice otherTenant = deviceRepository.saveAndFlush(device(UUID.randomUUID(), "PC-003"));
        EndpointCommandDto firstCommand = commandService.createCommand(adminContext(), first.getId(), inventoryRequest("inventory-first"));
        commandService.createCommand(adminContext(), second.getId(), inventoryRequest("inventory-second"));
        commandService.createCommand(new AdminTenantContext(otherTenant.getTenantId(), "other@example.com"),
                otherTenant.getId(), inventoryRequest("inventory-other"));

        var commands = commandService.listDeviceCommands(adminContext(), first.getId());

        assertThat(commands).extracting(EndpointCommandDto::id).containsExactly(firstCommand.id());
    }

    @Test
    void listCommandsReturnsTenantCommandsWhenDeviceFilterIsEmpty() {
        EndpointDevice first = deviceRepository.saveAndFlush(device(TENANT_ID, "PC-001"));
        EndpointDevice second = deviceRepository.saveAndFlush(device(TENANT_ID, "PC-002"));
        EndpointCommandDto firstCommand = commandService.createCommand(adminContext(), first.getId(), inventoryRequest("inventory-first"));
        EndpointCommandDto secondCommand = commandService.createCommand(adminContext(), second.getId(), inventoryRequest("inventory-second"));

        var commands = commandService.listCommands(adminContext(), null);

        assertThat(commands).extracting(EndpointCommandDto::id)
                .containsExactlyInAnyOrder(firstCommand.id(), secondCommand.id());
    }

    private AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin@example.com");
    }

    private CreateEndpointCommandRequest inventoryRequest(String idempotencyKey) {
        return new CreateEndpointCommandRequest(
                CommandType.COLLECT_INVENTORY,
                idempotencyKey,
                "inventory refresh",
                Map.of("requestedDetail", "basic"),
                null,
                null,
                null,
                null,
                null
        );
    }

    private EndpointDevice device(UUID tenantId, String hostname) {
        EndpointDevice device = new EndpointDevice();
        device.setTenantId(tenantId);
        device.setHostname(hostname + "-" + UUID.randomUUID());
        device.setOsType(OsType.WINDOWS);
        device.setOsVersion("Windows 11");
        device.setAgentVersion("0.2.0");
        device.setMachineFingerprint("fp-" + UUID.randomUUID());
        device.setDomainName("corp.local");
        device.setStatus(DeviceStatus.ONLINE);
        device.setLastSeenAt(Instant.now());
        return device;
    }
}
