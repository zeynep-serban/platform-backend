package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.model.CommandStatus;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointAdminCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminEndpointCommandController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointCommandControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMMAND_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointAdminCommandService commandService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void createCommandReturnsCommandStatus() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(commandService.createCommand(eq(context), eq(DEVICE_ID), any())).thenReturn(commandDto());

        mockMvc.perform(post("/api/v1/admin/endpoint-commands")
                        .contentType("application/json")
                        .content("""
                                {
                                  "deviceId": "22222222-2222-2222-2222-222222222222",
                                  "type": "COLLECT_INVENTORY",
                                  "idempotencyKey": "inventory-001",
                                  "reason": "inventory refresh",
                                  "payload": {"requestedDetail": "basic"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(COMMAND_ID.toString()))
                .andExpect(jsonPath("$.deviceId").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$.type").value("COLLECT_INVENTORY"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.payload.reason").value("inventory refresh"));

        verify(commandService).createCommand(eq(context), eq(DEVICE_ID), any());
    }

    @Test
    void getCommandReturnsCommandStatus() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(commandService.getCommand(context, COMMAND_ID)).thenReturn(commandDto());

        mockMvc.perform(get("/api/v1/admin/endpoint-commands/{commandId}", COMMAND_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(COMMAND_ID.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void listDeviceCommandsReturnsCommands() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(commandService.listDeviceCommands(context, DEVICE_ID)).thenReturn(List.of(commandDto()));

        mockMvc.perform(get("/api/v1/admin/endpoint-devices/{deviceId}/commands", DEVICE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(COMMAND_ID.toString()))
                .andExpect(jsonPath("$[0].type").value("COLLECT_INVENTORY"));
    }

    @Test
    void listCommandsReturnsTenantCommands() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(commandService.listCommands(context, null)).thenReturn(List.of(commandDto()));

        mockMvc.perform(get("/api/v1/admin/endpoint-commands"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(COMMAND_ID.toString()))
                .andExpect(jsonPath("$[0].status").value("QUEUED"));
    }

    private AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin@example.com");
    }

    private EndpointCommandDto commandDto() {
        Instant now = Instant.parse("2026-04-28T10:00:00Z");
        return new EndpointCommandDto(
                COMMAND_ID,
                TENANT_ID,
                DEVICE_ID,
                CommandType.COLLECT_INVENTORY,
                "inventory-001",
                CommandStatus.QUEUED,
                Map.of("reason", "inventory refresh"),
                100,
                0,
                3,
                null,
                null,
                now,
                null,
                "admin@example.com",
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now,
                null
        );
    }
}
