package com.example.permission.controller;

import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.example.permission.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.example.permission.service.AccessRoleService;
import com.example.permission.service.PermissionService;
import com.example.permission.service.TupleSyncService;
import com.example.permission.service.UserScopeService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@WebMvcTest(controllers = AccessControllerV1.class)
@Import(SecurityConfig.class)
class AccessControllerV1ScopeSecurityTest {

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

    @Disabled("ADR-0012 Phase 3: @PreAuthorize removed, @RequireModule interceptor needs @WebMvcTest integration — TODO")
    @Test
    void whenMissingScopeManagePermission_thenScopeCrudIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/roles/users/1/scopes")
                        .with(jwt().authorities(new SimpleGrantedAuthority("other-permission")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType": "COMPANY", "scopeRefId": "123"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/roles/users/1/scopes/COMPANY/123")
                        .param("permissionCode", "permission-scope-manage")
                        .with(jwt().authorities(new SimpleGrantedAuthority("something-else"))))
                .andExpect(status().isForbidden());
    }

    @Disabled("ADR-0012 Phase 3: @PreAuthorize removed, @RequireModule interceptor needs @WebMvcTest integration — TODO")
    @Test
    void whenHasScopeManagePermission_thenScopeCrudSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/roles/users/1/scopes")
                        .with(jwt().authorities(new SimpleGrantedAuthority("permission-scope-manage")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType": "COMPANY", "scopeRefId": "123"}
                                """))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(delete("/api/v1/roles/users/1/scopes/COMPANY/123")
                        .param("permissionCode", "permission-scope-manage")
                        .with(jwt().authorities(new SimpleGrantedAuthority("permission-scope-manage"))))
                .andExpect(status().isNoContent());
    }
}
