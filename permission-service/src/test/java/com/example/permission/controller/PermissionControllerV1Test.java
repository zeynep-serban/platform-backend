package com.example.permission.controller;

import com.example.permission.dto.PermissionResponse;
import com.example.permission.repository.PermissionRepository;
import com.example.permission.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PermissionControllerV1.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(roles = "ADMIN")
class PermissionControllerV1Test {

    @Autowired
    private MockMvc mockMvc;

    // ImpersonationContextFilter (@Component) is auto-picked up by the
    // @WebMvcTest slice and transitively requires ImpersonationContextExtractor;
    // mock it so the slice ApplicationContext loads.
    @MockitoBean
    private com.example.permission.security.ImpersonationContextExtractor impersonationContextExtractor;

    @MockitoBean
    private PermissionService permissionService;

    @MockitoBean
    private PermissionRepository permissionRepository;

    @Test
    void assignRoleReturnsAuditId() throws Exception {
        PermissionResponse response = new PermissionResponse();
        response.setAssignmentId(10L);
        response.setUserId(5L);
        response.setRoleId(99L);
        response.setRoleName("MANAGER");
        response.setModuleKey("USER_MANAGEMENT");
        response.setAssignedAt(Instant.parse("2025-11-29T10:00:00Z"));
        response.setAuditId("audit-assign-1");
        response.setPermissions(Set.of("VIEW_USERS"));
        response.setPermissionModules(Map.of("USER_MANAGEMENT", "MANAGE"));

        when(permissionService.assignRole(org.mockito.ArgumentMatchers.any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/permissions/assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":5,
                                  "companyId":1,
                                  "roleId":99,
                                  "assignedBy":44
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.auditId").value("audit-assign-1"));
    }

    @Test
    void updateAssignmentReturnsAuditId() throws Exception {
        PermissionResponse response = new PermissionResponse();
        response.setAssignmentId(77L);
        response.setUserId(15L);
        response.setAuditId("audit-update-2");

        when(permissionService.updateAssignment(org.mockito.ArgumentMatchers.any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/permissions/assignments/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":15,
                                  "roleId":99,
                                  "performedBy":52
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditId").value("audit-update-2"));
    }

    @Test
    void revokeRoleReturnsAuditId() throws Exception {
        when(permissionService.revokeRole(88L, 11L)).thenReturn("audit-revoke-3");

        mockMvc.perform(delete("/api/v1/permissions/assignments/{assignmentId}", 88)
                        .param("performedBy", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditId").value("audit-revoke-3"))
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
