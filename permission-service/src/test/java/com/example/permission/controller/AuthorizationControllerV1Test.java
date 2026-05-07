package com.example.permission.controller;

import com.example.permission.dto.v1.AuthzMeResponseDto;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.example.permission.service.AuthenticatedUserLookupService;
import com.example.permission.service.AuthorizationQueryService;
import com.example.permission.service.PermissionService;
import com.example.permission.service.PermissionCatalogService;
import com.example.permission.service.TupleSyncService;
import com.example.permission.dto.PermissionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationControllerV1Test {

    @Mock
    private AuthorizationQueryService authorizationQueryService;

    @Mock
    private AuthenticatedUserLookupService authenticatedUserLookupService;

    @Mock
    private PermissionService permissionService;

    @Mock
    private PermissionCatalogService catalogService;

    @Mock
    private TupleSyncService tupleSyncService;

    @Mock
    private com.example.commonauth.openfga.OpenFgaAuthzService authzService;

    @Mock
    private UserRoleAssignmentRepository assignmentRepository;

    @Mock
    private RolePermissionRepository rolePermissionRepository;

    @Mock
    private com.example.permission.service.AuthzVersionService authzVersionService;

    @InjectMocks
    private AuthorizationControllerV1 controller;

    @Test
    void getMe_usesResolvedNumericUserIdForScopeSummary() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("2fd0e4f7-c9da-4622-b4b6-b90adab28dd4")
                .claim("permissions", List.of("MANAGE_USERS"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(authenticatedUserLookupService.resolve(jwt))
                .thenReturn(new AuthenticatedUserLookupService.ResolvedAuthenticatedUser(15L, "15", "admin@example.com"));
        when(authorizationQueryService.getUserScopeSummary(15L))
                .thenReturn(Map.of("COMPANY", Set.of(11L)));
        when(catalogService.getModuleKeys()).thenReturn(List.of());
        PermissionResponse assignment = new PermissionResponse();
        assignment.setPermissions(Set.of("VIEW_USERS", "MANAGE_USERS"));
        when(permissionService.getAssignments(15L, null, null, null))
                .thenReturn(List.of(assignment));
        when(assignmentRepository.findActiveAssignments(15L)).thenReturn(List.of());

        ResponseEntity<AuthzMeResponseDto> response = controller.getMe(jwt);

        assertEquals(200, response.getStatusCode().value());
        AuthzMeResponseDto body = response.getBody();
        assertNotNull(body);
        assertEquals("15", body.getUserId());
        // Faz 23.5 hardening — Codex thread 019e0316 iter-3 absorb:
        // additive subscriberId mirrors the numeric userId.
        assertEquals(15L, body.getSubscriberId());
        assertEquals(Set.of("VIEW_USERS", "MANAGE_USERS"), body.getPermissions());
        assertEquals(1, body.getAllowedScopes().size());
        assertEquals("COMPANY", body.getAllowedScopes().get(0).scopeType());
        assertEquals(11L, body.getAllowedScopes().get(0).scopeRefId());
    }

    @Test
    void getMe_returnsPermissionsOnlyWhenNumericUserCannotBeResolved() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("2fd0e4f7-c9da-4622-b4b6-b90adab28dd4")
                .claim("permissions", List.of("VIEW_USERS"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(authenticatedUserLookupService.resolve(jwt))
                .thenReturn(new AuthenticatedUserLookupService.ResolvedAuthenticatedUser(null, "2fd0e4f7-c9da-4622-b4b6-b90adab28dd4", null));

        ResponseEntity<AuthzMeResponseDto> response = controller.getMe(jwt);

        assertEquals(200, response.getStatusCode().value());
        AuthzMeResponseDto body = response.getBody();
        assertNotNull(body);
        assertEquals("2fd0e4f7-c9da-4622-b4b6-b90adab28dd4", body.getUserId());
        // Faz 23.5 hardening — UUID fallback path leaves subscriberId
        // null so the alias does not silently leak the drift it was
        // created to fix (Codex thread 019e0316 iter-3 absorb).
        assertNull(body.getSubscriberId());
        assertEquals(Set.of("VIEW_USERS"), body.getPermissions());
        assertEquals(List.of(), body.getAllowedScopes());
        assertEquals(List.of(), body.getScopes());
    }

    @Test
    void getMe_buildsFrontendCompatibleModuleFallbackWhenEnhancedFieldsFail() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("15")
                .claim("permissions", List.of("ACCESS", "AUDIT", "REPORT"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        when(authenticatedUserLookupService.resolve(jwt))
                .thenReturn(new AuthenticatedUserLookupService.ResolvedAuthenticatedUser(15L, "15", "user3@example.com"));
        when(authorizationQueryService.getUserScopeSummary(15L)).thenReturn(Map.of());
        when(catalogService.getModuleKeys()).thenReturn(List.of("ACCESS", "AUDIT", "REPORT", "THEME"));
        when(authzService.check("15", "can_manage", "module", "ACCESS")).thenReturn(false);
        when(authzService.check("15", "can_view", "module", "ACCESS")).thenReturn(true);
        when(authzService.check("15", "can_manage", "module", "AUDIT")).thenReturn(false);
        when(authzService.check("15", "can_view", "module", "AUDIT")).thenReturn(true);
        when(authzService.check("15", "can_manage", "module", "REPORT")).thenReturn(false);
        when(authzService.check("15", "can_view", "module", "REPORT")).thenReturn(true);
        when(authzService.check("15", "can_manage", "module", "THEME")).thenReturn(false);
        when(authzService.check("15", "can_view", "module", "THEME")).thenReturn(false);
        when(assignmentRepository.findActiveAssignments(15L))
                .thenThrow(new RuntimeException("role repository unavailable"));

        ResponseEntity<AuthzMeResponseDto> response = controller.getMe(jwt);

        assertEquals(200, response.getStatusCode().value());
        AuthzMeResponseDto body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getModules());
        assertEquals("VIEW", body.getModules().get("ACCESS"));
        assertEquals("VIEW", body.getModules().get("AUDIT"));
        assertEquals("VIEW", body.getModules().get("REPORT"));
        assertTrue(body.getAllowedModules().contains("ACCESS"));
        assertTrue(body.getAllowedModules().contains("AUDIT"));
        assertTrue(body.getAllowedModules().contains("REPORT"));
    }
    // Codex 019dddb7 iter-42 — /authz/me 5xx contract.
    // Pre-iter-42 the controller returned ResponseEntity.status(503).body(null)
    // on any RuntimeException, which the api-gateway / variant-service chain
    // collapsed to "200 + empty body" on the wire (see live-capture
    // diagnostic in iter-34). The frontend's iter-34 retry was a workaround
    // for that contract violation.
    //
    // Post-iter-42 the broad catch rethrows as ResponseStatusException so
    // the GlobalExceptionHandler emits a typed 503 with a non-empty JSON
    // body. The contract is asserted at the unit layer here AND at the
    // gateway layer in the new gateway integration test.
    @Test
    void getMe_returns503ResponseStatusExceptionWhenDownstreamFails() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("15")
                .claim("permissions", List.of("VIEW_USERS"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(authenticatedUserLookupService.resolve(jwt))
                .thenReturn(new AuthenticatedUserLookupService.ResolvedAuthenticatedUser(15L, "15", "u@example.com"));
        // doGetMe wraps most downstream calls in *Safely helpers; the
        // remaining unguarded path is authzVersionService.getCurrentVersion
        // which fires unconditionally and surfaces RuntimeException to the
        // broad catch.
        when(authzVersionService.getCurrentVersion())
                .thenThrow(new RuntimeException("downstream synthetic failure"));

        org.springframework.web.server.ResponseStatusException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> controller.getMe(jwt)
                );
        assertEquals(503, ex.getStatusCode().value());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("AUTHZ_DEGRADED"),
                "503 reason must carry AUTHZ_DEGRADED so frontend can classify the error");
    }

// ---- B1 (Rev 19): Tests for new /check, /batch-check endpoints ----

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("B1: /authz/check endpoint")
    class CheckEndpoint {

        @Test
        @org.junit.jupiter.api.DisplayName("check returns 200 with allowed=true when OpenFGA allows")
        void check_returnsAllowed() {
            var request = new AuthorizationControllerV1.AuthzCheckRequest("can_view", "report", "HR_REPORTS");
            when(authzService.checkWithReason("0", "can_view", "report", "HR_REPORTS"))
                    .thenReturn(new com.example.commonauth.openfga.OpenFgaAuthzService.CheckResult(true, "ALLOWED"));

            // CNS-004 fix #2: check() signature genisletildi (Jwt param). Mock null → "0" fallback.
            ResponseEntity<Map<String, Object>> response = controller.check(request, null);

            assertEquals(200, response.getStatusCode().value());
            Map<String, Object> body = response.getBody();
            assertNotNull(body);
            assertEquals(true, body.get("allowed"));
            assertEquals("ALLOWED", body.get("reason"));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("check returns 200 with allowed=false when OpenFGA denies (no 403)")
        void check_returnsDenied() {
            var request = new AuthorizationControllerV1.AuthzCheckRequest("can_view", "report", "SECRET_REPORT");
            when(authzService.checkWithReason("0", "can_view", "report", "SECRET_REPORT"))
                    .thenReturn(new com.example.commonauth.openfga.OpenFgaAuthzService.CheckResult(false, "blocked"));

            // CNS-004 fix #2: check() signature genisletildi (Jwt param). Mock null → "0" fallback.
            ResponseEntity<Map<String, Object>> response = controller.check(request, null);

            // B3 semantics: deny is in payload, NOT in HTTP status
            assertEquals(200, response.getStatusCode().value());
            Map<String, Object> body = response.getBody();
            assertNotNull(body);
            assertEquals(false, body.get("allowed"));
            assertEquals("blocked", body.get("reason"));
        }
    }

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("B1: /authz/batch-check endpoint")
    class BatchCheckEndpoint {

        @Test
        @org.junit.jupiter.api.DisplayName("batch-check returns 400 when checks array is empty")
        void batchCheck_emptyReturns400() {
            var request = new AuthorizationControllerV1.BatchCheckRequest(List.of());

            // CNS-004 fix #2: batchCheck() signature genisletildi (Jwt param). Mock null → "0" fallback.
            ResponseEntity<?> response = controller.batchCheck(request, null);

            assertEquals(400, response.getStatusCode().value());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("batch-check returns 400 when >20 checks")
        void batchCheck_tooManyReturns400() {
            var checks = new java.util.ArrayList<AuthorizationControllerV1.AuthzCheckRequest>();
            for (int i = 0; i < 21; i++) {
                checks.add(new AuthorizationControllerV1.AuthzCheckRequest("can_view", "report", "R" + i));
            }
            var request = new AuthorizationControllerV1.BatchCheckRequest(checks);

            // CNS-004 fix #2: batchCheck() signature genisletildi (Jwt param). Mock null → "0" fallback.
            ResponseEntity<?> response = controller.batchCheck(request, null);

            assertEquals(400, response.getStatusCode().value());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("batch-check returns 200 with mixed results")
        void batchCheck_mixedResults() {
            var checks = List.of(
                    new AuthorizationControllerV1.AuthzCheckRequest("can_view", "report", "R1"),
                    new AuthorizationControllerV1.AuthzCheckRequest("can_view", "report", "R2")
            );
            var request = new AuthorizationControllerV1.BatchCheckRequest(checks);

            when(authzService.batchCheck(org.mockito.ArgumentMatchers.eq("0"), org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(List.of(
                            new com.example.commonauth.openfga.OpenFgaAuthzService.CheckResult(true, "ALLOWED"),
                            new com.example.commonauth.openfga.OpenFgaAuthzService.CheckResult(false, "blocked")
                    ));

            // CNS-004 fix #2: batchCheck() signature genisletildi (Jwt param). Mock null → "0" fallback.
            ResponseEntity<?> response = controller.batchCheck(request, null);

            assertEquals(200, response.getStatusCode().value());
        }
    }

    // ---- P1.9: NO_SCOPE explain path ----

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("P1.9: /authz/explain NO_SCOPE path")
    class ExplainNoScope {

        @Test
        @org.junit.jupiter.api.DisplayName("explain with scopeType+scopeRefId outside userScopes returns NO_SCOPE with both permission and scope fields populated")
        void explain_noScope_preservesPermissionAndScopeSlots() {
            when(authorizationQueryService.getUserScopeSummary(15L))
                    .thenReturn(Map.of("COMPANY", Set.of(11L)));
            when(assignmentRepository.findActiveAssignments(15L))
                    .thenReturn(List.of());

            Map<String, String> request = new java.util.HashMap<>();
            request.put("userId", "15");
            request.put("permissionType", "MODULE");
            request.put("permissionKey", "PURCHASE");
            request.put("scopeType", "COMPANY");
            request.put("scopeRefId", "99");

            ResponseEntity<com.example.permission.dto.v1.ExplainResponseDto> response = controller.explain(request);

            assertEquals(200, response.getStatusCode().value());
            com.example.permission.dto.v1.ExplainResponseDto body = response.getBody();
            assertNotNull(body);
            org.junit.jupiter.api.Assertions.assertFalse(body.allowed());
            assertEquals("NO_SCOPE", body.reason());
            assertEquals("MODULE", body.details().permissionType());
            assertEquals("PURCHASE", body.details().permissionKey());
            assertEquals("COMPANY", body.details().scopeType());
            assertEquals(99L, body.details().scopeRefId());
            org.junit.jupiter.api.Assertions.assertNull(body.details().roleName());
            org.junit.jupiter.api.Assertions.assertNull(body.details().grantType());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("explain with scopeType in userScopes falls through to permission evaluation (no NO_SCOPE short-circuit)")
        void explain_scopeInUserScopes_doesNotShortCircuit() {
            when(authorizationQueryService.getUserScopeSummary(15L))
                    .thenReturn(Map.of("COMPANY", Set.of(11L, 35L)));
            when(assignmentRepository.findActiveAssignments(15L))
                    .thenReturn(List.of());

            Map<String, String> request = new java.util.HashMap<>();
            request.put("userId", "15");
            request.put("permissionType", "MODULE");
            request.put("permissionKey", "PURCHASE");
            request.put("scopeType", "COMPANY");
            request.put("scopeRefId", "35");

            ResponseEntity<com.example.permission.dto.v1.ExplainResponseDto> response = controller.explain(request);

            assertEquals(200, response.getStatusCode().value());
            com.example.permission.dto.v1.ExplainResponseDto body = response.getBody();
            assertNotNull(body);
            // NO_ROLE fallback — user has no role assignments, so permission evaluation yields NO_ROLE, not NO_SCOPE
            assertEquals("NO_ROLE", body.reason());
            assertEquals("MODULE", body.details().permissionType());
            assertEquals("PURCHASE", body.details().permissionKey());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("explain with blank scopeType is treated as absent — skips scope check")
        void explain_blankScopeType_skipsScopeCheck() {
            when(authorizationQueryService.getUserScopeSummary(15L))
                    .thenReturn(Map.of());
            when(assignmentRepository.findActiveAssignments(15L))
                    .thenReturn(List.of());

            Map<String, String> request = new java.util.HashMap<>();
            request.put("userId", "15");
            request.put("permissionType", "MODULE");
            request.put("permissionKey", "PURCHASE");
            request.put("scopeType", "");  // blank — ignored
            request.put("scopeRefId", "99");

            ResponseEntity<com.example.permission.dto.v1.ExplainResponseDto> response = controller.explain(request);

            assertEquals(200, response.getStatusCode().value());
            com.example.permission.dto.v1.ExplainResponseDto body = response.getBody();
            assertNotNull(body);
            // Skips NO_SCOPE, falls through to NO_ROLE
            assertEquals("NO_ROLE", body.reason());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("explain with non-numeric scopeRefId returns 400")
        void explain_nonNumericScopeRefId_returns400() {
            when(authorizationQueryService.getUserScopeSummary(15L))
                    .thenReturn(Map.of("COMPANY", Set.of(11L)));

            Map<String, String> request = new java.util.HashMap<>();
            request.put("userId", "15");
            request.put("permissionType", "MODULE");
            request.put("permissionKey", "PURCHASE");
            request.put("scopeType", "COMPANY");
            request.put("scopeRefId", "not-a-number");

            org.springframework.web.server.ResponseStatusException ex =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            org.springframework.web.server.ResponseStatusException.class,
                            () -> controller.explain(request));
            assertEquals(400, ex.getStatusCode().value());
            assertTrue(ex.getReason() != null && ex.getReason().contains("scopeRefId"));
        }
    }
}
