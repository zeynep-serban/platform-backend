package com.example.endpointadmin.service;

import com.example.endpointadmin.audit.NoOpAuditChainLock;
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
import com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Import;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IsolatedH2DataJpaTest
@Import({TimeConfig.class, EndpointAdminCommandService.class, EndpointAuditService.class,
        NoOpAuditChainLock.class,
        // BE-021 — createInstall path depends on the install preflight
        // service to recompute the decision at command-creation time.
        EndpointInstallPreflightService.class})
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
    void manualCollectInventoryOptsInToDeviceHealthAndOutdatedSoftware() {
        // Faz 22.5 keystone — the operator-initiated "Envanteri Şimdi Topla"
        // full collect (this generic admin command surface) MUST opt the agent
        // in to the AG-033 device-health and AG-036 outdated-software probes,
        // or those drawer views can never be populated. The wire key names are
        // the frozen agent contract (boolPayload(command.Payload, "...")) and
        // must be EXACTLY includeDeviceHealth / includeOutdatedSoftware,
        // camelCase, set to boolean true.
        EndpointDevice device = deviceRepository.saveAndFlush(device(TENANT_ID, "PC-OPTIN"));
        CreateEndpointCommandRequest request = new CreateEndpointCommandRequest(
                CommandType.COLLECT_INVENTORY,
                "inventory-optin-001",
                "full collect",
                // Mirror the web client's WEB-018 payload (hardware/software/
                // winget egress) — those must be preserved untouched while the
                // backend adds the two missing health/outdated opt-ins.
                Map.of("includeHardware", true,
                        "includeSoftware", true,
                        "includeWinGetEgress", true),
                null,
                null,
                null,
                null,
                null
        );

        EndpointCommandDto created = commandService.createCommand(adminContext(), device.getId(), request);

        // Exact agent-contract key names, boolean true (not "true" / 1).
        assertThat(created.payload())
                .containsEntry("includeDeviceHealth", true)
                .containsEntry("includeOutdatedSoftware", true)
                // The web-client opt-ins are preserved exactly.
                .containsEntry("includeHardware", true)
                .containsEntry("includeSoftware", true)
                .containsEntry("includeWinGetEgress", true);
        assertThat(created.payload().get("includeDeviceHealth")).isInstanceOf(Boolean.class);
        assertThat(created.payload().get("includeOutdatedSoftware")).isInstanceOf(Boolean.class);

        // Persisted payload (what the agent claim path actually serves) carries
        // the opt-ins too, not just the DTO projection.
        EndpointCommand stored = commandRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getPayload())
                .containsEntry("includeDeviceHealth", true)
                .containsEntry("includeOutdatedSoftware", true);
    }

    @Test
    void collectInventoryWithNoPayloadStillOptsInToHealthAndOutdated() {
        // The legacy zero-payload COLLECT_INVENTORY (a caller that sends no
        // opt-in map at all) must still get the two probes — the backend
        // guarantees the documented collect-now data path independent of the
        // client remembering the bits.
        EndpointDevice device = deviceRepository.saveAndFlush(device(TENANT_ID, "PC-NOPAYLOAD"));
        CreateEndpointCommandRequest request = new CreateEndpointCommandRequest(
                CommandType.COLLECT_INVENTORY,
                "inventory-nopayload-001",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        EndpointCommandDto created = commandService.createCommand(adminContext(), device.getId(), request);

        assertThat(created.payload())
                .containsEntry("includeDeviceHealth", true)
                .containsEntry("includeOutdatedSoftware", true);
    }

    @Test
    void collectInventoryRespectsExplicitDeviceHealthOptOut() {
        // AG-025H opt-out boundary: an explicit caller-supplied false is NOT
        // overwritten. Only an absent key is defaulted to true. This keeps a
        // future lightweight caller's opt-out honoured.
        EndpointDevice device = deviceRepository.saveAndFlush(device(TENANT_ID, "PC-OPTOUT"));
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("includeDeviceHealth", false);
        CreateEndpointCommandRequest request = new CreateEndpointCommandRequest(
                CommandType.COLLECT_INVENTORY,
                "inventory-optout-001",
                null,
                payload,
                null,
                null,
                null,
                null,
                null
        );

        EndpointCommandDto created = commandService.createCommand(adminContext(), device.getId(), request);

        assertThat(created.payload())
                // Explicit opt-out preserved.
                .containsEntry("includeDeviceHealth", false)
                // The unset key is still defaulted on.
                .containsEntry("includeOutdatedSoftware", true);
    }

    @Test
    void createCommandRejectsInstallSoftwareOnGenericEndpoint() {
        // BE-021 (Codex 019e6dfb iter-3 P0-1) + AG-028 Phase 1 (Codex plan-time
        // iter-2 absorb): INSTALL_SOFTWARE cannot be created via the generic
        // /commands surface — that would bypass the preflight recompute. The
        // dedicated /installs path is the only legal route. Phase 1 migrates
        // the rejection from 409 to 422 (DEDICATED_PATH_ONLY set; INSTALL +
        // UNINSTALL).
        EndpointDevice device = deviceRepository.saveAndFlush(device(TENANT_ID, "PC-INSTALL"));
        CreateEndpointCommandRequest request = new CreateEndpointCommandRequest(
                CommandType.INSTALL_SOFTWARE,
                "install-generic-001",
                "should be rejected",
                Map.of("catalogItemUuid", UUID.randomUUID().toString()),
                null,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> commandService.createCommand(adminContext(), device.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY)
                .hasMessageContaining("INSTALL_SOFTWARE must be created via");
    }

    @Test
    void createCommandRejectsUninstallSoftwareOnGenericEndpoint() {
        // AG-028 Phase 1 (Codex plan-time iter-2 absorb): UNINSTALL_SOFTWARE
        // also belongs to the DEDICATED_PATH_ONLY set — generic /commands
        // surface rejects it with 422 so callers must use the dedicated
        // POST /api/v1/admin/endpoint-devices/{deviceId}/uninstalls path
        // (where the propose/approve maker-checker + capability +
        // provenance gates live).
        EndpointDevice device = deviceRepository.saveAndFlush(device(TENANT_ID, "PC-UNINSTALL"));
        CreateEndpointCommandRequest request = new CreateEndpointCommandRequest(
                CommandType.UNINSTALL_SOFTWARE,
                "uninstall-generic-001",
                "should be rejected",
                Map.of("catalogItemUuid", UUID.randomUUID().toString()),
                null,
                null,
                null,
                null,
                null
        );

        assertThatThrownBy(() -> commandService.createCommand(adminContext(), device.getId(), request))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.UNPROCESSABLE_ENTITY)
                .hasMessageContaining("UNINSTALL_SOFTWARE must be created via");
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
