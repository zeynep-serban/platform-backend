package com.example.permission.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.example.permission.model.PermissionAuditEvent;
import com.example.permission.security.SecurityConfig;
import com.example.permission.service.AuditEventService;

@WebMvcTest(controllers = InternalAuditEventController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "security.internal-api-key.enabled=true",
        "security.internal-api-key.value=test-internal-key"
})
class InternalAuditEventControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    // ImpersonationContextFilter (@Component) is auto-picked up by the
    // @WebMvcTest slice and transitively requires ImpersonationContextExtractor;
    // mock it so the slice ApplicationContext loads.
    @MockitoBean
    private com.example.permission.security.ImpersonationContextExtractor impersonationContextExtractor;

    @MockitoBean
    private AuditEventService auditEventService;

    @Test
    void ingestEvent_requiresInternalApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/internal/audit/events")
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventType": "SESSION_CREATED",
                                  "performedBy": 99,
                                  "userEmail": "user@example.com",
                                  "service": "auth-service",
                                  "level": "INFO",
                                  "action": "SESSION_CREATED"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ingestEvent_acceptsValidInternalApiKey() throws Exception {
        PermissionAuditEvent saved = new PermissionAuditEvent();
        saved.setId(77L);
        saved.setOccurredAt(Instant.parse("2026-03-13T20:00:00Z"));
        when(auditEventService.recordMirroredEvent(any())).thenReturn(saved);

        mockMvc.perform(post("/api/v1/internal/audit/events")
                        .header("X-Internal-Api-Key", "test-internal-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventType": "SESSION_CREATED",
                                  "performedBy": 99,
                                  "userEmail": "user@example.com",
                                  "service": "auth-service",
                                  "level": "INFO",
                                  "action": "SESSION_CREATED",
                                  "details": "Session created for user@example.com in company scope 42"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.auditId").value("77"));
    }

    /**
     * Closes the live {@code 400 VALIDATION_ERROR} loop seen on testai
     * (2026-05-07) where {@code REPORT_ACCESS} mirrored events were
     * rejected because the report-service caller couldn't resolve a
     * numeric {@code performedBy} from the JWT {@code sub} UUID. The
     * DB column is nullable; the DTO now matches.
     */
    @Test
    void ingestEvent_acceptsNullPerformedBy() throws Exception {
        PermissionAuditEvent saved = new PermissionAuditEvent();
        saved.setId(78L);
        saved.setOccurredAt(Instant.parse("2026-05-07T05:35:32Z"));
        when(auditEventService.recordMirroredEvent(any())).thenReturn(saved);

        mockMvc.perform(post("/api/v1/internal/audit/events")
                        .header("X-Internal-Api-Key", "test-internal-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventType": "REPORT_ACCESS",
                                  "userEmail": "user@example.com",
                                  "service": "report-service",
                                  "level": "INFO",
                                  "action": "REPORT_ACCESS",
                                  "details": "fin-muhasebe-detay report accessed",
                                  "correlationId": "trace-abc"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.auditId").value("78"));
    }
}
