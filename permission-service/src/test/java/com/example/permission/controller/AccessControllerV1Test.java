package com.example.permission.controller;

import com.example.permission.dto.v1.BulkPermissionsResponseDto;
import com.example.permission.dto.v1.RoleCloneResponseDto;
import com.example.permission.dto.v1.RoleDto;
import com.example.permission.dto.v1.RolePolicyDto;
import com.example.permission.model.PermissionModel;
import com.example.permission.model.Role;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.example.permission.service.AccessRoleService;
import com.example.permission.service.PermissionService;
import com.example.permission.service.TupleSyncService;
import com.example.permission.service.UserScopeService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AccessControllerV1.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(roles = "ADMIN")
class AccessControllerV1Test {

    @Autowired
    private MockMvc mockMvc;

    // ImpersonationContextFilter (@Component) is auto-picked up by the
    // @WebMvcTest slice and transitively requires ImpersonationContextExtractor;
    // mock it so the slice ApplicationContext loads.
    @MockitoBean
    private com.example.permission.security.ImpersonationContextExtractor impersonationContextExtractor;

    @MockitoBean
    private AccessRoleService accessRoleService;

    @MockitoBean
    private UserScopeService userScopeService;

    @MockitoBean
    private UserRoleAssignmentRepository assignmentRepository;

    @MockitoBean
    private RolePermissionRepository rolePermissionRepository;

    @MockitoBean
    private RoleRepository roleRepository;

    @MockitoBean
    private PermissionService permissionService;

    @MockitoBean
    private TupleSyncService tupleSyncService;

    @Test
    void getRoleReturnsRoleDetail() throws Exception {
        var role = new com.example.permission.dto.access.AccessRoleDto(
                2L,
                "USER_MANAGER",
                "desc",
                3,
                false,
                "2025-01-01T00:00:00Z",
                "system",
                List.of(),
                List.of("VIEW_USERS", "MANAGE_USERS")
        );

        when(accessRoleService.getRole(2L)).thenReturn(role);

        mockMvc.perform(get("/api/v1/roles/{roleId}", 2).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.name").value("USER_MANAGER"))
                .andExpect(jsonPath("$.permissions[0]").value("VIEW_USERS"));
    }

    @Test
    void cloneRolePropagatesAuditId() throws Exception {
        RoleDto dto = new RoleDto();
        dto.setId(99L);
        dto.setName("Copy");
        dto.setDescription("desc");
        dto.setMemberCount(0);
        dto.setSystemRole(false);
        dto.setLastModifiedAt("2025-01-01T00:00:00Z");
        dto.setLastModifiedBy("system");
        RolePolicyDto policy = new RolePolicyDto();
        policy.setModuleKey("USER_MANAGEMENT");
        policy.setModuleLabel("Kullanıcı Yönetimi");
        policy.setLevel("MANAGE");
        policy.setLastUpdatedAt("2025-01-01T00:00:00Z");
        policy.setUpdatedBy("system");
        dto.setPolicies(List.of(policy));

        when(accessRoleService.cloneRole(eq(7L), eq("Copy"), eq("desc"), eq(10L)))
                .thenReturn(new RoleCloneResponseDto(dto, "role-audit-123"));

        mockMvc.perform(post("/api/v1/roles/{roleId}/clone", 7)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Copy","description":"desc","performedBy":10}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditId").value("role-audit-123"));
    }

    @Test
    void bulkPermissionsPropagatesAuditId() throws Exception {
        // iter-16: rejectIfGranuleManaged guard now loads the role first.
        // Stub a LEGACY-mode role so the bulk endpoint passes the guard and
        // continues to the service layer (backward compat path).
        Role legacyRole = new Role();
        legacyRole.setId(5L);
        legacyRole.setName("LEGACY_BULK_TEST");
        legacyRole.setPermissionModel(PermissionModel.LEGACY);
        when(roleRepository.findById(5L)).thenReturn(Optional.of(legacyRole));

        when(accessRoleService.bulkUpdateModuleLevel(
                eq(List.of(5L)),
                eq("USER_MANAGEMENT"),
                eq("Kullanıcı Yönetimi"),
                eq("EDIT"),
                eq(42L)
        )).thenReturn(new BulkPermissionsResponseDto(List.of(5L), "bulk-456"));

        mockMvc.perform(patch("/api/v1/roles/{roleId}/permissions/bulk", 5)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "moduleKey":"USER_MANAGEMENT",
                                  "moduleLabel":"Kullanıcı Yönetimi",
                                  "level":"EDIT",
                                  "performedBy":42
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditId").value("bulk-456"));
    }
}
