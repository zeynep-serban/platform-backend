package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.EndpointAuditEventDto;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointAuditService;
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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminEndpointAuditController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointAuditControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID COMMAND_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointAuditService auditService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void listAuditEventsReturnsFilteredTenantEvents() throws Exception {
        AdminTenantContext context = new AdminTenantContext(TENANT_ID, "admin@example.com");
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(auditService.listEvents(TENANT_ID, DEVICE_ID, COMMAND_ID, "ENDPOINT_COMMAND_CREATED", 25))
                .thenReturn(List.of(new EndpointAuditEventDto(
                        UUID.fromString("44444444-4444-4444-4444-444444444444"),
                        TENANT_ID,
                        DEVICE_ID,
                        COMMAND_ID,
                        "ENDPOINT_COMMAND_CREATED",
                        "CREATE_COMMAND",
                        "admin@example.com",
                        "inventory-001",
                        Map.of("commandType", "COLLECT_INVENTORY"),
                        null,
                        Map.of("status", "QUEUED"),
                        Instant.parse("2026-04-28T10:00:00Z")
                )));

        mockMvc.perform(get("/api/v1/admin/endpoint-audit-events")
                        .param("deviceId", DEVICE_ID.toString())
                        .param("commandId", COMMAND_ID.toString())
                        .param("eventType", "ENDPOINT_COMMAND_CREATED")
                        .param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$[0].deviceId").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$[0].commandId").value(COMMAND_ID.toString()))
                .andExpect(jsonPath("$[0].eventType").value("ENDPOINT_COMMAND_CREATED"))
                .andExpect(jsonPath("$[0].action").value("CREATE_COMMAND"));

        verify(auditService).listEvents(TENANT_ID, DEVICE_ID, COMMAND_ID, "ENDPOINT_COMMAND_CREATED", 25);
    }
}
