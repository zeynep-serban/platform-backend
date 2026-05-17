package com.example.permission.controller;

import com.example.permission.dataaccess.AccessScopeException;
import com.example.permission.dataaccess.AccessScopeService;
import com.example.permission.dataaccess.DataAccessScope;
import com.example.permission.dataaccess.DataAccessScopeOutboxEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AccessScopeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AccessScopeExceptionHandler.class)
@WithMockUser(roles = "ADMIN")
class AccessScopeControllerTest {

    private static final UUID USER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Autowired
    private MockMvc mockMvc;

    // ImpersonationContextFilter (@Component) is auto-picked up by the
    // @WebMvcTest slice and transitively requires ImpersonationContextExtractor;
    // mock it so the slice ApplicationContext loads.
    @MockitoBean
    private com.example.permission.security.ImpersonationContextExtractor impersonationContextExtractor;

    @MockitoBean
    private AccessScopeService accessScopeService;

    @Test
    void postGrant_201_validRequest_returnsScopeAndOutboxFields() throws Exception {
        // V25 (Codex 019dd34e hybrid contract): COMPANY scope_ref is the
        // OUR_COMPANY.COMP_ID; encoder emits company:wc-our-company-<id>.
        when(accessScopeService.grant(eq(USER), eq(1L),
                eq(DataAccessScope.ScopeKind.COMPANY), eq("[\"1\"]"), any()))
                .thenReturn(mutationResult(42L, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]",
                        500L, DataAccessScopeOutboxEntry.Status.PENDING));

        mockMvc.perform(post("/api/v1/access/scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "%s",
                                  "orgId": 1,
                                  "scopeKind": "COMPANY",
                                  "scopeRef": "[\\"1\\"]"
                                }
                                """.formatted(USER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scopeId").value(42))
                .andExpect(jsonPath("$.scopeKind").value("COMPANY"))
                .andExpect(jsonPath("$.openFgaObjectType").value("company"))
                .andExpect(jsonPath("$.openFgaObjectId").value("wc-our-company-1"))
                .andExpect(jsonPath("$.tupleSyncStatus").value("PENDING"))
                .andExpect(jsonPath("$.outboxId").value(500))
                .andExpect(jsonPath("$.processedAt").doesNotExist());
    }

    @Test
    void postGrant_400_blankScopeRef() throws Exception {
        mockMvc.perform(post("/api/v1/access/scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "%s",
                                  "orgId": 1,
                                  "scopeKind": "COMPANY",
                                  "scopeRef": ""
                                }
                                """.formatted(USER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postGrant_409_alreadyGranted() throws Exception {
        when(accessScopeService.grant(any(), any(), any(), any(), any()))
                .thenThrow(new AccessScopeException.ScopeAlreadyGrantedException(
                        "Active scope already exists", new RuntimeException("uq_scope_active_assignment")));

        mockMvc.perform(post("/api/v1/access/scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "%s",
                                  "orgId": 1,
                                  "scopeKind": "COMPANY",
                                  "scopeRef": "[\\"1\\"]"
                                }
                                """.formatted(USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ScopeAlreadyGranted"));
    }

    @Test
    void postGrant_422_lineageViolation() throws Exception {
        when(accessScopeService.grant(any(), any(), any(), any(), any()))
                .thenThrow(new AccessScopeException.ScopeValidationException(
                        "Scope reference rejected by data_access lineage guard: invalid scope_ref",
                        new RuntimeException()));

        mockMvc.perform(post("/api/v1/access/scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "%s",
                                  "orgId": 1,
                                  "scopeKind": "COMPANY",
                                  "scopeRef": "[\\"99999\\"]"
                                }
                                """.formatted(USER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ScopeValidation"));
    }

    @Test
    void deleteRevoke_204_validId() throws Exception {
        when(accessScopeService.revoke(eq(7L), any()))
                .thenReturn(mutationResult(7L, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]",
                        501L, DataAccessScopeOutboxEntry.Status.PENDING));

        mockMvc.perform(delete("/api/v1/access/scope/{id}", 7L))
                .andExpect(status().isNoContent());

        verify(accessScopeService).revoke(eq(7L), any());
    }

    @Test
    void deleteRevoke_404_notFound() throws Exception {
        when(accessScopeService.revoke(eq(999L), any()))
                .thenThrow(new AccessScopeException.ScopeNotFoundException(999L));

        mockMvc.perform(delete("/api/v1/access/scope/{id}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ScopeNotFound"))
                .andExpect(jsonPath("$.scope_id").value(999));
    }

    @Test
    void deleteRevoke_409_alreadyRevoked() throws Exception {
        when(accessScopeService.revoke(eq(8L), any()))
                .thenThrow(new AccessScopeException.ScopeAlreadyRevokedException(
                        8L, Instant.parse("2026-04-26T10:00:00Z")));

        mockMvc.perform(delete("/api/v1/access/scope/{id}", 8L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ScopeAlreadyRevoked"))
                .andExpect(jsonPath("$.scope_id").value(8));
    }

    @Test
    void getList_200_returnsActiveScopesAsListItems() throws Exception {
        when(accessScopeService.listActiveScopes(USER, 1L))
                .thenReturn(List.of(
                        scope(1L, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]"),
                        scope(2L, DataAccessScope.ScopeKind.PROJECT, "[\"1204\"]")
                ));

        mockMvc.perform(get("/api/v1/access/scope")
                        .param("userId", USER.toString())
                        .param("orgId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].scopeKind").value("COMPANY"))
                .andExpect(jsonPath("$[1].scopeKind").value("PROJECT"))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    void postGrant_503_whenServiceUnavailable_returnsServiceUnavailable() {
        var controller = new AccessScopeController(java.util.Optional.empty());
        var request = new com.example.permission.dto.access.ScopeGrantRequest(
                USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", null);

        var response = controller.grant(request);

        org.assertj.core.api.Assertions.assertThat(response.getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        org.assertj.core.api.Assertions.assertThat(response.getBody()).isNull();
    }

    @Test
    void deleteRevoke_503_whenServiceUnavailable_returnsServiceUnavailable() {
        var controller = new AccessScopeController(java.util.Optional.empty());

        var response = controller.revoke(7L, null);

        org.assertj.core.api.Assertions.assertThat(response.getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void getList_503_whenServiceUnavailable_returnsServiceUnavailable() {
        var controller = new AccessScopeController(java.util.Optional.empty());

        var response = controller.list(USER, 1L);

        org.assertj.core.api.Assertions.assertThat(response.getStatusCode())
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        org.assertj.core.api.Assertions.assertThat(response.getBody()).isNull();
    }

    private static AccessScopeService.ScopeMutationResult mutationResult(
            Long scopeId,
            DataAccessScope.ScopeKind kind,
            String scopeRef,
            Long outboxId,
            DataAccessScopeOutboxEntry.Status status) {
        DataAccessScope scope = scope(scopeId, kind, scopeRef);
        DataAccessScopeOutboxEntry outbox = new DataAccessScopeOutboxEntry();
        outbox.setId(outboxId);
        outbox.setScopeId(scopeId);
        outbox.setAction(DataAccessScopeOutboxEntry.Action.GRANT);
        outbox.setStatus(status);
        outbox.setNextAttemptAt(Instant.parse("2026-04-28T12:00:00Z"));
        outbox.setCreatedAt(Instant.parse("2026-04-28T12:00:00Z"));
        return new AccessScopeService.ScopeMutationResult(scope, outbox);
    }

    private static DataAccessScope scope(Long id, DataAccessScope.ScopeKind kind, String scopeRef) {
        var s = new DataAccessScope();
        s.setId(id);
        s.setUserId(USER);
        s.setOrgId(1L);
        s.setScopeKind(kind);
        s.setScopeSourceSchema("workcube_mikrolink");
        // V25 contract — mirror the source-of-truth mapping inside
        // AccessScopeService.expectedSourceTable; controller responses must
        // serialize the same source_table the service writes to PG.
        s.setScopeSourceTable(switch (kind) {
            case COMPANY -> "OUR_COMPANY";
            case PROJECT -> "PRO_PROJECTS";
            case BRANCH -> "BRANCH";
            case DEPOT -> "DEPARTMENT";
        });
        s.setScopeRef(scopeRef);
        s.setGrantedAt(Instant.parse("2026-04-25T10:00:00Z"));
        return s;
    }
}
