package com.example.permission.controller;

import com.example.permission.audit.AuditReadScope;
import com.example.permission.audit.ImpersonationAuditEventTypes;
import com.example.permission.dto.AuditEventPageResponse;
import com.example.permission.dto.AuditEventResponse;
import com.example.permission.dto.v1.AuditExportJobResponseDto;
import com.example.permission.service.AuditEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuditEventController.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(username = "admin", roles = "ADMIN")
class AuditEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditEventService auditEventService;

    // PR-D2: PR-B Step 1 (commit a918534) introduced the
    // ImpersonationContextFilter @Component which is auto-picked up by
    // @WebMvcTest slice but transitively requires ImpersonationContextExtractor
    // (and an underlying ImpersonationSessionRepository). The slice fails to
    // load the context unless we mock the extractor here. Pre-existing
    // breakage on origin/main; fixed in this PR.
    @MockitoBean
    private com.example.permission.security.ImpersonationContextExtractor impersonationContextExtractor;

    @Test
    void getByIdReturnsSingleEvent() throws Exception {
        AuditEventResponse event = new AuditEventResponse(
                "123",
                Instant.parse("2025-01-01T00:00:00Z"),
                "a***@example.com",
                "permission-service",
                "INFO",
                "EXPORT_USERS",
                "Users export requested",
                "corr-42",
                Map.of("userId", 10),
                Map.of(),
                Map.of()
        );
        AuditEventPageResponse page = new AuditEventPageResponse(List.of(event), 0, 1);
        when(auditEventService.findByIdPage("123", AuditReadScope.GENERIC_AUDIT)).thenReturn(page);

        mockMvc.perform(get("/api/audit/events")
                        .param("id", "123")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].id").value("123"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void createExportJobReturnsCreated() throws Exception {
        AuditExportJobResponseDto response = new AuditExportJobResponseDto(
                "job-1",
                "COMPLETED",
                "csv",
                "audit-events-job-1.csv",
                "text/csv",
                3,
                "admin@example.com",
                Instant.parse("2026-03-14T10:00:00Z"),
                Instant.parse("2026-03-14T10:00:01Z"),
                null,
                "/api/audit/events/export-jobs/job-1/download"
        );
        when(auditEventService.createExportJob(
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.anyString(),
                org.mockito.Mockito.any(),
                org.mockito.Mockito.any(),
                org.mockito.Mockito.anyMap(),
                eq(AuditReadScope.GENERIC_AUDIT)))
                .thenReturn(response);

        mockMvc.perform(post("/api/audit/events/export-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"format":"csv","sort":"timestamp,desc","filters":{"service":"permission-service"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("job-1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.downloadPath").value("/api/audit/events/export-jobs/job-1/download"));
    }

    // =================================================================
    // PR-D2 (User Impersonation v1): dedicated impersonation audit feed
    // =================================================================

    @Test
    void listImpersonationEvents_passesImpersonationScopeToService() throws Exception {
        AuditEventResponse event = new AuditEventResponse(
                "200",
                Instant.parse("2026-05-09T10:00:00Z"),
                "admin@example.com",
                "permission-service",
                "INFO",
                ImpersonationAuditEventTypes.IMPERSONATION_STARTED,
                "started",
                "corr-200",
                Map.of(),
                Map.of(),
                Map.of()
        );
        AuditEventPageResponse page = new AuditEventPageResponse(List.of(event), 0, 1);
        when(auditEventService.listEvents(anyInt(), anyInt(), any(), any(), eq(AuditReadScope.IMPERSONATION_AUDIT)))
                .thenReturn(page);

        mockMvc.perform(get("/api/audit/events/impersonation").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].action").value(ImpersonationAuditEventTypes.IMPERSONATION_STARTED));

        verify(auditEventService).listEvents(anyInt(), anyInt(), any(), any(), eq(AuditReadScope.IMPERSONATION_AUDIT));
    }

    @Test
    void listImpersonationEvents_strictActionFilter_acceptsAlias() throws Exception {
        AuditEventPageResponse page = new AuditEventPageResponse(List.of(), 0, 0);
        when(auditEventService.listEvents(anyInt(), anyInt(), any(), any(), eq(AuditReadScope.IMPERSONATION_AUDIT)))
                .thenReturn(page);

        mockMvc.perform(get("/api/audit/events/impersonation")
                        .param("filter[action]", "IMPERSONATION")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void listImpersonationEvents_strictActionFilter_acceptsCanonicalCode() throws Exception {
        AuditEventPageResponse page = new AuditEventPageResponse(List.of(), 0, 0);
        when(auditEventService.listEvents(anyInt(), anyInt(), any(), any(), eq(AuditReadScope.IMPERSONATION_AUDIT)))
                .thenReturn(page);

        mockMvc.perform(get("/api/audit/events/impersonation")
                        .param("filter[action]", ImpersonationAuditEventTypes.IMPERSONATION_FAILED)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void listImpersonationEvents_strictActionFilter_rejectsFabricatedCode() throws Exception {
        mockMvc.perform(get("/api/audit/events/impersonation")
                        .param("filter[action]", "FOO_IMPERSONATION_BAR")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        // Service must NOT be invoked when validation rejects.
        verify(auditEventService, never()).listEvents(anyInt(), anyInt(), any(), any(), any(AuditReadScope.class));
    }

    @Test
    void listImpersonationEvents_strictActionFilter_rejectsLowercaseVariant() throws Exception {
        mockMvc.perform(get("/api/audit/events/impersonation")
                        .param("filter[action]", "impersonation_started")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listEvents_genericPath_passesGenericScopeToService() throws Exception {
        AuditEventPageResponse page = new AuditEventPageResponse(List.of(), 0, 0);
        when(auditEventService.listEvents(anyInt(), anyInt(), any(), any(), eq(AuditReadScope.GENERIC_AUDIT)))
                .thenReturn(page);

        mockMvc.perform(get("/api/audit/events").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        verify(auditEventService).listEvents(anyInt(), anyInt(), any(), any(), eq(AuditReadScope.GENERIC_AUDIT));
    }

    @Test
    void exportEvents_passesGenericScopeToService() throws Exception {
        when(auditEventService.exportEvents(any(), any(), any(), eq(AuditReadScope.GENERIC_AUDIT)))
                .thenReturn(List.of());
        when(auditEventService.buildExportPayload(any(), anyString())).thenReturn("[]".getBytes());

        mockMvc.perform(get("/api/audit/events/export").param("format", "json"))
                .andExpect(status().isOk());

        verify(auditEventService).exportEvents(any(), any(), any(), eq(AuditReadScope.GENERIC_AUDIT));
    }

    @Test
    void downloadExportJobReturnsAttachment() throws Exception {
        com.example.permission.model.AuditExportJob job = new com.example.permission.model.AuditExportJob();
        job.setId("job-2");
        job.setFilename("audit-events-job-2.json");
        job.setContentType("application/json");
        job.setPayload("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // PR-D2 iter-3 absorb (Codex 019e10bf P1): controller now passes
        // AuditReadScope.GENERIC_AUDIT so the service can fail-closed on
        // legacy / mismatched-scope jobs.
        when(auditEventService.getCompletedExportJob(eq("job-2"), anyString(), eq(AuditReadScope.GENERIC_AUDIT)))
                .thenReturn(job);

        mockMvc.perform(get("/api/audit/events/export-jobs/job-2/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=audit-events-job-2.json"));

        verify(auditEventService).getCompletedExportJob(eq("job-2"), anyString(), eq(AuditReadScope.GENERIC_AUDIT));
    }
}
