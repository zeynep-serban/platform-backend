package com.example.permission.controller;

import com.example.permission.dto.v1.AuthzUserScopesResponseDto;
import com.example.permission.dto.v1.AuthzMeResponseDto;
import com.example.permission.dto.v1.AuthzScopeSummaryDto;
import com.example.permission.dto.v1.ExplainResponseDto;
import com.example.permission.dto.v1.PermissionCatalogDto;
import com.example.permission.dto.v1.ScopeSummaryDto;
import com.example.permission.dto.v1.UserAssignmentRequestDto;
import com.example.permission.model.GrantType;
import com.example.permission.model.PermissionType;
import com.example.permission.model.RolePermission;
import com.example.permission.model.UserRoleAssignment;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.example.permission.service.AuthenticatedUserLookupService;
import com.example.permission.service.AuthorizationQueryService;
import com.example.permission.service.PermissionCatalogService;
import com.example.permission.service.PermissionService;
import com.example.permission.service.AuthzVersionService;
import com.example.permission.service.TupleSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/authz")
public class AuthorizationControllerV1 {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationControllerV1.class);
    private static final CacheControl SCOPES_CACHE_CONTROL = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final AuthorizationQueryService authorizationQueryService;
    private final AuthenticatedUserLookupService authenticatedUserLookupService;
    private final PermissionService permissionService;
    private final PermissionCatalogService catalogService;
    private final TupleSyncService tupleSyncService;
    private final OpenFgaAuthzService authzService;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final AuthzVersionService authzVersionService;

    public AuthorizationControllerV1(
            AuthorizationQueryService authorizationQueryService,
            AuthenticatedUserLookupService authenticatedUserLookupService,
            PermissionService permissionService,
            PermissionCatalogService catalogService,
            @org.springframework.lang.Nullable TupleSyncService tupleSyncService,
            @org.springframework.lang.Nullable OpenFgaAuthzService authzService,
            UserRoleAssignmentRepository assignmentRepository,
            RolePermissionRepository rolePermissionRepository,
            @org.springframework.lang.Nullable AuthzVersionService authzVersionService
    ) {
        this.authorizationQueryService = authorizationQueryService;
        this.authenticatedUserLookupService = authenticatedUserLookupService;
        this.permissionService = permissionService;
        this.catalogService = catalogService;
        this.tupleSyncService = tupleSyncService;
        this.authzService = authzService;
        this.assignmentRepository = assignmentRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.authzVersionService = authzVersionService;
    }

    @GetMapping("/version")
    public ResponseEntity<Map<String, Long>> getVersion() {
        return ResponseEntity.ok(Map.of("authzVersion", authzVersionService.getCurrentVersion()));
    }

    @GetMapping("/user/{userId}/scopes")
    public ResponseEntity<AuthzUserScopesResponseDto> getUserScopes(@PathVariable Long userId) {
        AuthzUserScopesResponseDto response = authorizationQueryService.getUserScopes(userId);
        if (response.getItems() == null || response.getItems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AUTHZ-404");
        }
        return ResponseEntity.ok()
                .cacheControl(SCOPES_CACHE_CONTROL)
                .body(response);
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public ResponseEntity<AuthzMeResponseDto> getMe(@AuthenticationPrincipal Jwt jwt) {
        try {
            return doGetMe(jwt);
        } catch (RuntimeException ex) {
            // Codex 019dddb7 iter-42 — never return 5xx with a null body.
            // The legacy ResponseEntity.status(503).body(null) produced a
            // payload that downstream consumers couldn't differentiate
            // from a successful response with no fields, so the variant
            // service collapsed both paths to "empty AuthzMeResponse" and
            // the frontend's iter-34 empty-body retry was needed to mask
            // the race. By rethrowing as ResponseStatusException the
            // GlobalExceptionHandler converts the failure to a 503 with a
            // populated JSON ErrorResponse — contract: "5xx ⇒ non-empty
            // error JSON, 2xx ⇒ non-empty AuthzMeResponse JSON".
            log.error("Authz /me beklenmeyen hata; 503 + AUTHZ_DEGRADED rethrow. cause={}",
                    ex.getMessage(), ex);
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTHZ_DEGRADED: authz service degraded; retry",
                    ex);
        }
    }

    private ResponseEntity<AuthzMeResponseDto> doGetMe(Jwt jwt) {
        if (jwt == null) {
            // D-103: Local/dev profile fallback — return superAdmin response
            // when JWT is not available (SecurityConfigLocal permitAll mode)
            log.info("authz/me: JWT null — returning dev superAdmin fallback");
            AuthzMeResponseDto devDto = new AuthzMeResponseDto();
            devDto.setUserId("dev-admin");
            devDto.setSuperAdmin(true);
            devDto.setPermissions(Set.of("admin"));
            devDto.setAllowedModules(List.of("USER_MANAGEMENT", "ACCESS", "AUDIT", "REPORT", "THEME", "WAREHOUSE", "PURCHASE"));
            devDto.setModules(Map.of(
                    "USER_MANAGEMENT", "MANAGE", "ACCESS", "MANAGE", "AUDIT", "MANAGE",
                    "REPORT", "MANAGE", "THEME", "MANAGE", "WAREHOUSE", "MANAGE", "PURCHASE", "MANAGE"));
            devDto.setScopes(List.of());
            devDto.setAllowedScopes(List.of());
            devDto.setRoles(List.of());
            devDto.setActions(Map.of());
            devDto.setReports(Map.of());
            devDto.setAuthzVersion(authzVersionService != null ? authzVersionService.getCurrentVersion() : 0L);
            return ResponseEntity.ok(devDto);
        }
        AuthzMeResponseDto dto = new AuthzMeResponseDto();
        var resolvedUser = resolveAuthenticatedUser(jwt);
        dto.setUserId(resolvedUser.responseUserId());

            Long numericUserId = resolvedUser.numericUserId();
            // Faz 23.5 hardening (Codex thread 019e0316 iter-3 AGREE):
            // additive `subscriberId` mirrors the numeric DB user id when
            // resolution succeeds; UUID/sub fallbacks leave it null so the
            // alias does not silently leak the drift it was created to fix.
            dto.setSubscriberId(numericUserId);

            // Legacy permissions (backward compat)
            dto.setPermissions(resolvePermissionsSafely(jwt, numericUserId));
            // SuperAdmin: check OpenFGA organization admin first, then fall back to permissions list
            boolean orgAdmin = checkOrganizationAdmin(numericUserId);
            boolean permsAdmin = dto.getPermissions().stream()
                            .anyMatch(p -> p != null && p.equalsIgnoreCase("admin"));
            boolean isSuperAdmin = orgAdmin || permsAdmin;
            // 2026-04-29: diagnostic detayı (numericUserId + email) DEBUG seviyesinde,
            // PII/polling-noise minimumde. Codex 019dd818 PARTIAL feedback (REVISE diag-log):
            // "polling ile sık çalışır; email/numeric ID PII üretir". DEBUG flag-bazlı
            // observability — incident sırasında aktif edilir, normalde kapalı.
            if (log.isDebugEnabled()) {
                log.debug("authz/me: numericUserId={}, orgAdmin={}, permsAdmin={}, superAdmin={}",
                        numericUserId, orgAdmin, permsAdmin, isSuperAdmin);
            }
            dto.setSuperAdmin(isSuperAdmin);

            // Scopes (existing)
            Map<String, Set<Long>> scopeSummary = resolveScopeSummarySafely(numericUserId);
            List<AuthzScopeSummaryDto> scopes = scopeSummary.entrySet().stream()
                    .map(e -> new AuthzScopeSummaryDto(e.getKey(), e.getValue().stream().toList()))
                    .collect(Collectors.toList());
            dto.setScopes(scopes);
            List<ScopeSummaryDto> allowedScopes = scopes.stream()
                    .flatMap(s -> s.getRefIds().stream().map(id -> new ScopeSummaryDto(s.getScopeType(), id)))
                    .toList();
            dto.setAllowedScopes(allowedScopes);

            // STORY-0318: Enhanced response fields
            if (numericUserId != null) {
                populateEnhancedFieldsSafely(dto, numericUserId);
            }

            applyFrontendCompatibilityFallback(dto, jwt);
            dto.setAuthzVersion(authzVersionService.getCurrentVersion());
        return ResponseEntity.ok(dto);
    }

    // ---- Object-level authorization checks (B1: moved from core-data-service) ----

    public record AuthzCheckRequest(String relation, String objectType, String objectId) {}

    /**
     * Single object-level authorization check via OpenFGA.
     * Returns 200 with {allowed, reason} — never 403 for deny (deny is in payload).
     */
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> check(
            @RequestBody AuthzCheckRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
        String userId = resolveUserId(jwt);

        var result = authzService.checkWithReason(
                userId,
                request.relation(),
                request.objectType(),
                request.objectId()
        );

        return ResponseEntity.ok(Map.of(
                "allowed", result.allowed(),
                "reason", result.reason()
        ));
    }

    /**
     * Resolve numeric DB user id from incoming JWT for OpenFGA `user:<id>` refs.
     *
     * 2026-04-19 OI-03 canary fix (Codex thread 019da4b5): prior implementation
     * returned the raw JWT `sub` (Keycloak UUID) when no `uid` claim was present,
     * but OpenFGA tuples are written with the numeric DB id (e.g. `user:1205`).
     * Browser tokens (from `frontend` KC client) carry only `sub` + `email`, so
     * every `/authz/check` for a persona user produced `user:<KC-UUID>` vs stored
     * `user:<numeric-id>` — 93.66% mismatch in canary k6 matrix.
     *
     * Priority order:
     *  1. ScopeContextHolder (legacy, filter-registered path)
     *  2. AuthenticatedUserLookupService.resolve(jwt) numericUserId —
     *     tries JWT `userId`/`uid` claim, then email → DB lookup (same path
     *     as /authz/me, keeps the two endpoints consistent).
     *  3. Final fallback: JWT `sub` / `"0"` for local permitAll profile.
     */
    private String resolveUserId(org.springframework.security.oauth2.jwt.Jwt jwt) {
        var scope = com.example.commonauth.scope.ScopeContextHolder.get();
        if (scope != null && scope.userId() != null && !"0".equals(scope.userId())) {
            return scope.userId();
        }
        if (jwt != null) {
            try {
                var resolved = authenticatedUserLookupService.resolve(jwt);
                if (resolved.numericUserId() != null) {
                    return Long.toString(resolved.numericUserId());
                }
            } catch (RuntimeException ex) {
                log.warn("Authz /check numeric userId resolution failed; falling back to JWT claims. cause={}", ex.getMessage());
            }
            Object uid = jwt.getClaim("uid");
            if (uid != null) {
                return uid.toString();
            }
            String sub = jwt.getSubject();
            if (sub != null && !sub.isBlank()) {
                return sub;
            }
        }
        return "0";  // local profile / permitAll fallback
    }

    /**
     * Batch object-level authorization check — multiple checks in a single request.
     * Max 20 checks per call.
     */
    public record BatchCheckRequest(java.util.List<AuthzCheckRequest> checks) {}
    public record BatchCheckItem(boolean allowed, String reason,
                                 String relation, String objectType, String objectId) {}

    @PostMapping("/batch-check")
    public ResponseEntity<?> batchCheck(
            @RequestBody BatchCheckRequest request,
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt) {
        if (request.checks() == null || request.checks().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "checks array is required"));
        }
        if (request.checks().size() > 20) {
            return ResponseEntity.badRequest().body(Map.of("error", "Max 20 checks per batch request"));
        }

        // CNS-20260415-004: resolveUserId() JWT bazli — ScopeContextFilter persona
        // token'lari icin guvenilir degildi (Codex bulgu #2).
        String userId = resolveUserId(jwt);

        var batchRequests = request.checks().stream()
                .map(c -> new OpenFgaAuthzService.BatchCheckRequest(
                        c.relation(), c.objectType(), c.objectId()))
                .toList();

        var checkResults = authzService.batchCheck(userId, batchRequests);

        List<BatchCheckItem> results = new java.util.ArrayList<>();
        for (int i = 0; i < request.checks().size(); i++) {
            var c = request.checks().get(i);
            var r = checkResults.get(i);
            results.add(new BatchCheckItem(r.allowed(), r.reason(),
                    c.relation(), c.objectType(), c.objectId()));
        }

        return ResponseEntity.ok(Map.of("results", results));
    }

    /**
     * Object-level explain — exposes OpenFGA expand for "Why can't I access?" feature.
     */
    @PostMapping("/object-explain")
    public ResponseEntity<Map<String, Object>> objectExplain(@RequestBody AuthzCheckRequest request) {
        var scope = com.example.commonauth.scope.ScopeContextHolder.get();
        String userId = scope != null ? scope.userId() : "0";

        Map<String, Object> result = authzService.explainAccess(
                userId,
                request.relation(),
                request.objectType(),
                request.objectId()
        );

        return ResponseEntity.ok(result);
    }

    /**
     * Permission catalog — all available permission granules.
     * Replaces hardcoded module lists.
     */
    @GetMapping("/catalog")
    public ResponseEntity<PermissionCatalogDto> getCatalog() {
        return ResponseEntity.ok(catalogService.getCatalog());
    }

    /**
     * List all module keys. Replaces hardcoded module lists in frontend.
     */
    @GetMapping("/modules")
    public ResponseEntity<List<String>> getModules() {
        return ResponseEntity.ok(catalogService.getModuleKeys());
    }

    /**
     * Get roles assigned to a user.
     */
    @GetMapping("/users/{userId}/roles")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getUserRoles(@PathVariable Long userId) {
        List<UserRoleAssignment> assignments = assignmentRepository.findActiveAssignments(userId);
        List<Map<String, Object>> roles = assignments.stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("roleId", a.getRole().getId());
                    m.put("roleName", a.getRole().getName());
                    m.put("assignedAt", a.getAssignedAt() != null ? a.getAssignedAt().toString() : null);
                    return m;
                })
                .toList();
        return ResponseEntity.ok(roles);
    }

    /**
     * Assign roles + scopes to a user.
     */
    @PostMapping("/users/{userId}/assignments")
    public ResponseEntity<Map<String, Object>> assignUserRolesAndScopes(
            @PathVariable Long userId,
            @RequestBody UserAssignmentRequestDto request) {
        // Deactivate existing assignments
        List<UserRoleAssignment> existing = assignmentRepository.findActiveAssignments(userId);
        for (UserRoleAssignment assignment : existing) {
            assignment.setActive(false);
            assignmentRepository.save(assignment);
        }

        // Create new assignments for each role
        List<Long> roleIds = request.roleIds() != null ? request.roleIds() : List.of();
        for (Long roleId : roleIds) {
            var assignRequest = new com.example.permission.dto.PermissionAssignRequest();
            assignRequest.setUserId(userId);
            assignRequest.setRoleId(roleId);
            permissionService.assignRole(assignRequest);
        }

        // Sync scope tuples (skip individual version increments)
        if (request.scopes() != null) {
            var scopes = request.scopes();
            tupleSyncService.syncScopeTuples(
                    String.valueOf(userId),
                    scopes.companyIds(),
                    scopes.projectIds(),
                    scopes.warehouseIds(),
                    scopes.branchIds(),
                    true
            );
        }

        // Refresh feature tuples (union of all roles, deny-wins — skip individual version increment)
        List<RolePermission> allPermissions = rolePermissionRepository.findByRoleIdIn(roleIds);
        tupleSyncService.syncFeatureTuplesForUser(String.valueOf(userId), allPermissions, true);

        // P0: Single version increment after all tuple syncs complete
        authzVersionService.incrementVersion();

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "roleIds", roleIds,
                "status", "assigned"
        ));
    }

    /**
     * Explain why a user can or cannot perform a specific permission.
     */
    @PostMapping("/explain")
    @Transactional(readOnly = true)
    public ResponseEntity<ExplainResponseDto> explain(@RequestBody Map<String, String> request) {
        String userIdStr = request.get("userId");
        String permTypeStr = request.get("permissionType");
        String permKey = request.get("permissionKey");
        // Optional scope check: "can user access company:35?"
        String scopeType = request.get("scopeType");     // company, project, warehouse, branch
        String scopeRefIdStr = request.get("scopeRefId"); // e.g. "35"

        if (userIdStr == null || permTypeStr == null || permKey == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId, permissionType, permissionKey required");
        }

        Long userId;
        try {
            userId = Long.parseLong(userIdStr);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be numeric");
        }
        PermissionType permType;
        try {
            permType = PermissionType.valueOf(permTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid permissionType: " + permTypeStr);
        }

        Map<String, List<Long>> userScopes = buildScopeMap(userId);

        // P1.9: Scope-level denial check (if both scopeType + scopeRefId provided).
        // NO_SCOPE preserves permType/permKey in details and carries scopeType/scopeRefId
        // in dedicated fields via deniedNoScope — previous code overloaded the
        // permissionType/permissionKey slots with scope values (regression fix).
        if (scopeType != null && !scopeType.isBlank() && scopeRefIdStr != null && !scopeRefIdStr.isBlank()) {
            Long refId;
            try {
                refId = Long.parseLong(scopeRefIdStr);
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scopeRefId must be numeric");
            }
            List<Long> scopeIds = userScopes.getOrDefault(scopeType, List.of());
            if (!scopeIds.contains(refId)) {
                List<String> userRoles = resolveUserRoleNames(userId);
                return ResponseEntity.ok(ExplainResponseDto.deniedNoScope(
                        permTypeStr, permKey, scopeType, refId, userRoles, userScopes));
            }
        }

        // Get user's roles
        List<UserRoleAssignment> assignments = assignmentRepository.findActiveAssignments(userId);
        List<String> userRoles = assignments.stream()
                .map(a -> a.getRole().getName())
                .distinct()
                .toList();

        if (assignments.isEmpty()) {
            return ResponseEntity.ok(ExplainResponseDto.denied(
                    "NO_ROLE", permTypeStr, permKey, null, null, userRoles, userScopes));
        }

        // Get all permissions from user's roles
        List<Long> roleIds = assignments.stream()
                .map(a -> a.getRole().getId())
                .distinct()
                .toList();
        List<RolePermission> allPermissions = rolePermissionRepository.findByRoleIdIn(roleIds);

        // Resolve effective grants
        Map<String, TupleSyncService.ResolvedGrant> effective = tupleSyncService.resolveEffectiveGrants(allPermissions);
        String compositeKey = permType.name() + ":" + permKey;
        TupleSyncService.ResolvedGrant grant = effective.get(compositeKey);

        if (grant == null) {
            return ResponseEntity.ok(ExplainResponseDto.denied(
                    "NO_PERMISSION", permTypeStr, permKey, null, null, userRoles, userScopes));
        }

        if (grant.grantType() == GrantType.DENY) {
            return ResponseEntity.ok(ExplainResponseDto.denied(
                    "DENIED_BY_ROLE", permTypeStr, permKey,
                    grant.sourceRole(), "DENY", userRoles, userScopes));
        }

        return ResponseEntity.ok(ExplainResponseDto.allowed(
                permTypeStr, permKey, grant.sourceRole(),
                grant.grantType().name(), userRoles, userScopes));
    }

    private List<String> resolveUserRoleNames(Long userId) {
        return assignmentRepository.findActiveAssignments(userId).stream()
                .map(a -> a.getRole().getName())
                .distinct()
                .toList();
    }

    // --- Private helpers ---

    private void populateEnhancedFields(AuthzMeResponseDto dto, Long numericUserId) {
        List<UserRoleAssignment> assignments = assignmentRepository.findActiveAssignments(numericUserId);
        List<String> roleNames = assignments.stream()
                .map(a -> a.getRole().getName())
                .distinct()
                .toList();
        dto.setRoles(roleNames);

        List<Long> roleIds = assignments.stream()
                .map(a -> a.getRole().getId())
                .distinct()
                .toList();

        if (roleIds.isEmpty()) return;

        List<RolePermission> allPermissions = rolePermissionRepository.findByRoleIdIn(roleIds);
        Map<String, TupleSyncService.ResolvedGrant> effective = tupleSyncService.resolveEffectiveGrants(allPermissions);

        Map<String, String> modules = new LinkedHashMap<>();
        Map<String, String> actions = new LinkedHashMap<>();
        Map<String, String> reports = new LinkedHashMap<>();

        for (var entry : effective.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            PermissionType type = PermissionType.valueOf(parts[0]);
            String key = parts[1];
            String grantStr = entry.getValue().grantType().name();

            switch (type) {
                case MODULE -> modules.put(key, grantStr);
                case ACTION -> actions.put(key, grantStr);
                case REPORT -> reports.put(key, grantStr);
                // PAGE, FIELD removed in V10 migration (TB-21)
            }
        }

        dto.setModules(modules);
        dto.setActions(actions);
        dto.setReports(reports);
    }

    private Map<String, List<Long>> buildScopeMap(Long userId) {
        Map<String, Set<Long>> scopeSummary = authorizationQueryService.getUserScopeSummary(userId);
        Map<String, List<Long>> result = new LinkedHashMap<>();
        scopeSummary.forEach((k, v) -> result.put(k, new ArrayList<>(v)));
        return result;
    }

    private Set<String> resolvePermissions(Jwt jwt, Long numericUserId) {
        if (numericUserId == null) {
            return jwtPermissions(jwt);
        }

        // OpenFGA-first: check each module from catalog
        String userId = String.valueOf(numericUserId);
        Set<String> resolved = new LinkedHashSet<>();

        for (String module : catalogService.getModuleKeys()) {
            if (authzService.check(userId, "can_manage", "module", module)) {
                resolved.add(module);
            } else if (authzService.check(userId, "can_view", "module", module)) {
                resolved.add(module);
            }
        }

        if (!resolved.isEmpty()) {
            return resolved;
        }

        // Fallback: DB-based resolution (legacy)
        Set<String> dbPermissions = permissionService.getAssignments(numericUserId, null, null, null).stream()
                .flatMap(assignment -> assignment.getPermissions() == null
                        ? java.util.stream.Stream.empty()
                        : assignment.getPermissions().stream())
                .filter(permission -> permission != null && !permission.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return dbPermissions;
    }

    private AuthenticatedUserLookupService.ResolvedAuthenticatedUser resolveAuthenticatedUser(Jwt jwt) {
        try {
            return authenticatedUserLookupService.resolve(jwt);
        } catch (RuntimeException ex) {
            log.warn("Authz /me kullanıcı çözümleme başarısız; JWT fallback kullanılacak. cause={}", ex.getMessage());
            return new AuthenticatedUserLookupService.ResolvedAuthenticatedUser(
                    null,
                    fallbackResponseUserId(jwt),
                    fallbackEmail(jwt)
            );
        }
    }

    /**
     * Check if the user is an organization admin via OpenFGA.
     * Falls back to false on any error (fail-closed).
     */
    private boolean checkOrganizationAdmin(Long numericUserId) {
        if (numericUserId == null) {
            return false;
        }
        try {
            return authzService.check(String.valueOf(numericUserId), "admin", "organization", "default");
        } catch (RuntimeException ex) {
            log.warn("Authz /me OpenFGA organization admin check failed; defaulting to false. cause={}", ex.getMessage());
            return false;
        }
    }

    private Set<String> resolvePermissionsSafely(Jwt jwt, Long numericUserId) {
        try {
            return resolvePermissions(jwt, numericUserId);
        } catch (RuntimeException ex) {
            log.warn("Authz /me izin çözümleme başarısız; JWT permissions fallback kullanılacak. cause={}", ex.getMessage());
            return jwtPermissions(jwt);
        }
    }

    private Map<String, Set<Long>> resolveScopeSummarySafely(Long numericUserId) {
        if (numericUserId == null) {
            return Collections.emptyMap();
        }
        try {
            return authorizationQueryService.getUserScopeSummary(numericUserId);
        } catch (RuntimeException ex) {
            log.warn("Authz /me scope summary çözümleme başarısız; boş scope dönülecek. cause={}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private void populateEnhancedFieldsSafely(AuthzMeResponseDto dto, Long numericUserId) {
        try {
            populateEnhancedFields(dto, numericUserId);
        } catch (RuntimeException ex) {
            log.warn("Authz /me enhanced field çözümleme başarısız; boş enhanced alanlarla devam edilecek. cause={}", ex.getMessage());
        }
    }

    private void applyFrontendCompatibilityFallback(AuthzMeResponseDto dto, Jwt jwt) {
        if (dto.getPermissions() == null) {
            dto.setPermissions(Set.of());
        }
        if (dto.getScopes() == null) {
            dto.setScopes(List.of());
        }
        if (dto.getAllowedScopes() == null) {
            dto.setAllowedScopes(List.of());
        }
        if (dto.getRoles() == null) {
            dto.setRoles(extractJwtRoles(jwt));
        }
        if (dto.getActions() == null) {
            dto.setActions(Map.of());
        }
        if (dto.getReports() == null) {
            dto.setReports(Map.of());
        }
        Map<String, String> moduleGrants = dto.getModules();
        if (moduleGrants == null || moduleGrants.isEmpty()) {
            moduleGrants = deriveModuleGrants(dto.getPermissions(), dto.getRoles());
            dto.setModules(moduleGrants);
        }

        if (dto.getAllowedModules() == null || dto.getAllowedModules().isEmpty()) {
            dto.setAllowedModules(List.copyOf(moduleGrants.keySet()));
        }
    }

    private AuthzMeResponseDto buildJwtFallbackResponse(Jwt jwt) {
        AuthzMeResponseDto dto = new AuthzMeResponseDto();
        Set<String> permissions = safeJwtPermissions(jwt);
        List<String> roles = extractJwtRoles(jwt);
        Map<String, String> moduleGrants = deriveModuleGrants(permissions, roles);

        dto.setUserId(fallbackResponseUserId(jwt));
        dto.setPermissions(permissions);
        dto.setAllowedModules(List.copyOf(moduleGrants.keySet()));
        dto.setScopes(List.of());
        dto.setAllowedScopes(List.of());
        dto.setRoles(roles);
        dto.setModules(moduleGrants);
        dto.setActions(Map.of());
        dto.setReports(Map.of());
        dto.setSuperAdmin(
                permissions.stream().anyMatch(p -> p != null && p.equalsIgnoreCase("admin"))
                        || roles.stream().anyMatch(role -> role != null && role.equalsIgnoreCase("admin"))
        );
        return dto;
    }

    private Set<String> safeJwtPermissions(Jwt jwt) {
        try {
            return jwtPermissions(jwt);
        } catch (RuntimeException ex) {
            log.warn("JWT permissions parse edilemedi; boş permissions döndürülecek. cause={}", ex.getMessage());
            return Set.of();
        }
    }

    private List<String> extractJwtRoles(Jwt jwt) {
        if (jwt == null) {
            return List.of();
        }
        Object realmAccessClaim = jwt.getClaim("realm_access");
        if (!(realmAccessClaim instanceof Map<?, ?> realmAccess)) {
            return List.of();
        }
        Object rolesClaim = realmAccess.get("roles");
        if (!(rolesClaim instanceof Collection<?> rolesCollection)) {
            return List.of();
        }
        return rolesCollection.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private Map<String, String> deriveModuleGrants(Set<String> permissions, List<String> roles) {
        Map<String, String> modules = new LinkedHashMap<>();

        if (permissions != null) {
            for (String permission : permissions) {
                registerModuleGrant(modules, permission);
            }
        }

        if (roles != null) {
            for (String role : roles) {
                if (role != null && role.equalsIgnoreCase("admin")) {
                    for (String module : catalogService.getModuleKeys()) {
                        upsertGrant(modules, module, "MANAGE");
                    }
                }
            }
        }

        return modules;
    }

    private void registerModuleGrant(Map<String, String> modules, String rawPermission) {
        String value = rawPermission == null ? "" : rawPermission.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            return;
        }

        switch (value) {
            case "ACCESS", "ACCESS-READ", "VIEW_ACCESS", "ACCESS-WRITE", "ACCESS-MANAGE", "ROLE-READ", "ROLE-WRITE", "ROLE-MANAGE", "PERMISSION-MANAGE" ->
                    upsertGrant(modules, "ACCESS", value.contains("MANAGE") || value.contains("WRITE") ? "MANAGE" : "VIEW");
            case "AUDIT", "AUDIT-READ", "VIEW_AUDIT" ->
                    upsertGrant(modules, "AUDIT", "VIEW");
            case "REPORT", "REPORT_VIEW", "VIEW_REPORTS", "REPORT_EXPORT", "REPORT_MANAGE" ->
                    upsertGrant(modules, "REPORT", value.contains("MANAGE") ? "MANAGE" : "VIEW");
            case "THEME", "THEME_ADMIN" ->
                    upsertGrant(modules, "THEME", value.contains("ADMIN") ? "MANAGE" : "VIEW");
            case "USER_MANAGEMENT", "USER-READ", "VIEW_USERS", "MANAGE_USERS", "USER-UPDATE", "USER-CREATE", "USER-DELETE", "USER-EXPORT", "USER-IMPORT" ->
                    upsertGrant(modules, "USER_MANAGEMENT", value.contains("MANAGE") || value.contains("CREATE") || value.contains("UPDATE") || value.contains("DELETE") ? "MANAGE" : "VIEW");
            case "WAREHOUSE", "WAREHOUSE_VIEW", "WAREHOUSE_MANAGE" ->
                    upsertGrant(modules, "WAREHOUSE", value.contains("MANAGE") ? "MANAGE" : "VIEW");
            case "PURCHASE", "PURCHASE_VIEW", "PURCHASE_MANAGE", "CREATE_PO", "DELETE_PO", "APPROVE_PURCHASE" ->
                    upsertGrant(modules, "PURCHASE", value.contains("MANAGE") || value.equals("CREATE_PO") || value.equals("DELETE_PO") || value.equals("APPROVE_PURCHASE") ? "MANAGE" : "VIEW");
            default -> {
                if (catalogService.getModuleKeys().contains(value)) {
                    upsertGrant(modules, value, "VIEW");
                }
            }
        }
    }

    private void upsertGrant(Map<String, String> modules, String module, String candidateGrant) {
        if (module == null || module.isBlank()) {
            return;
        }
        String normalizedGrant = "MANAGE".equalsIgnoreCase(candidateGrant) ? "MANAGE" : "VIEW";
        String existingGrant = modules.get(module);
        if (existingGrant == null || ("MANAGE".equals(normalizedGrant) && !"MANAGE".equals(existingGrant))) {
            modules.put(module, normalizedGrant);
        }
    }

    private String fallbackResponseUserId(Jwt jwt) {
        return firstNonBlank(
                stringClaim(jwt, "userId"),
                stringClaim(jwt, "uid"),
                jwt.getSubject(),
                fallbackEmail(jwt)
        );
    }

    private String fallbackEmail(Jwt jwt) {
        return firstNonBlank(jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username"));
    }

    private Set<String> jwtPermissions(Jwt jwt) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        if (permissions == null || permissions.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(permissions);
    }

    private String stringClaim(Jwt jwt, String claimName) {
        Object value = jwt.getClaim(claimName);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
