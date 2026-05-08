package com.example.commonauth.scope;

import com.example.commonauth.AuthenticatedUserLookupService;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Servlet filter that populates {@link ScopeContextHolder} on every request.
 *
 * Production (openfga.enabled=true):
 *   JWT → extract userId → OpenFGA listObjects → ScopeContext
 *
 * Dev/permitAll (openfga.enabled=false):
 *   YAML config → ScopeContext with static dev scope IDs
 *
 * Always clears the context after the request completes (finally block).
 */
public class ScopeContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ScopeContextFilter.class);

    private final OpenFgaAuthzService authzService;
    private final OpenFgaProperties properties;
    private final AuthenticatedUserLookupService authenticatedUserLookupService;
    private final ScopeContextCache scopeContextCache;
    private final AuthzVersionProvider versionProvider;

    public ScopeContextFilter(OpenFgaAuthzService authzService, OpenFgaProperties properties) {
        this(authzService, properties, null, null, null);
    }

    public ScopeContextFilter(OpenFgaAuthzService authzService,
                              OpenFgaProperties properties,
                              AuthenticatedUserLookupService authenticatedUserLookupService) {
        this(authzService, properties, authenticatedUserLookupService, null, null);
    }

    public ScopeContextFilter(OpenFgaAuthzService authzService,
                              OpenFgaProperties properties,
                              AuthenticatedUserLookupService authenticatedUserLookupService,
                              ScopeContextCache scopeContextCache,
                              AuthzVersionProvider versionProvider) {
        this.authzService = authzService;
        this.properties = properties;
        this.authenticatedUserLookupService = authenticatedUserLookupService;
        this.scopeContextCache = scopeContextCache;
        this.versionProvider = versionProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            ScopeContext ctx = buildScopeContext(request);
            ScopeContextHolder.set(ctx);
            log.debug("ScopeContext set: userId={}, companies={}, superAdmin={}",
                    ctx.userId(), ctx.allowedCompanyIds(), ctx.superAdmin());
            filterChain.doFilter(request, response);
        } finally {
            ScopeContextHolder.clear();
        }
    }

    private ScopeContext buildScopeContext(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return buildDevScopeContext();
        }

        String userId = extractUserId();
        if (userId == null) {
            log.debug("No authenticated user — returning empty scope");
            return ScopeContext.empty(null);
        }

        // P0: Cache lookup — avoid 5 OpenFGA calls on repeat requests
        if (scopeContextCache != null && scopeContextCache.isEnabled() && versionProvider != null) {
            long version = versionProvider.getCurrentVersion();
            String cacheKey = ScopeContextCache.cacheKey(
                    userId, version, properties.getStoreId(), properties.getModelId());
            ScopeContext cached = scopeContextCache.get(cacheKey);
            if (cached != null) {
                log.debug("ScopeContext cache HIT for user:{}", userId);
                return cached;
            }
            try {
                ScopeContext ctx = fetchScopeFromOpenFga(userId);
                scopeContextCache.put(cacheKey, ctx);
                return ctx;
            } catch (Exception e) {
                log.error("Failed to build ScopeContext from OpenFGA for user {}, falling back to dev scope", userId, e);
                return buildDevScopeContext();
            }
        }

        try {
            return fetchScopeFromOpenFga(userId);
        } catch (Exception e) {
            log.error("Failed to build ScopeContext from OpenFGA for user {}, falling back to dev scope", userId, e);
            return buildDevScopeContext();
        }
    }

    /**
     * Fetch scope from OpenFGA — all 5 calls run in PARALLEL (SK-2 optimization).
     * Previous sequential execution: 5 × 6-8ms = 30-40ms.
     * Parallel execution: max(6-8ms) = 6-8ms (75% reduction on cache miss).
     */
    private ScopeContext fetchScopeFromOpenFga(String userId) {
        // Codex thread 019e0891 iter-2 AGREE absorb (2026-05-08 PR-BE-9 Phase 2):
        // Faz 21.3 ADR-0008 "explicit-scope contract" canonical model uses
        // `viewer: [user]` for branch (and warehouse/company/project). Previous
        // code read `member` for branch — relation undefined in the canonical
        // model, so the listObjects call returned empty and any user with
        // branch scope appeared scope-less to ScopeContextHolder. Realigning
        // all four object types on `viewer` matches what TupleSyncService
        // (line ~158-171) writes after PR-BE-9 Phase 1.
        var companyFuture = CompletableFuture.supplyAsync(
                () -> authzService.listObjectIds(userId, "viewer", "company"));
        var projectFuture = CompletableFuture.supplyAsync(
                () -> authzService.listObjectIds(userId, "viewer", "project"));
        var warehouseFuture = CompletableFuture.supplyAsync(
                () -> authzService.listObjectIds(userId, "viewer", "warehouse"));
        var branchFuture = CompletableFuture.supplyAsync(
                () -> authzService.listObjectIds(userId, "viewer", "branch"));
        var adminFuture = CompletableFuture.supplyAsync(
                () -> authzService.check(userId, "admin", "organization", "default"));

        CompletableFuture.allOf(companyFuture, projectFuture, warehouseFuture, branchFuture, adminFuture).join();

        boolean isSuperAdmin = adminFuture.join();
        if (isSuperAdmin) {
            return ScopeContext.superAdmin(userId);
        }
        return new ScopeContext(userId, companyFuture.join(), projectFuture.join(),
                warehouseFuture.join(), branchFuture.join(), false);
    }

    private ScopeContext buildDevScopeContext() {
        OpenFgaProperties.DevScope dev = properties.getDevScope();
        return new ScopeContext(
                "dev-user",
                dev.getCompanyIds(),
                dev.getProjectIds(),
                dev.getWarehouseIds(),
                dev.isSuperAdmin()
        );
    }

    private String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof Jwt jwt) {
            if (authenticatedUserLookupService != null) {
                var resolved = authenticatedUserLookupService.resolve(jwt);
                if (resolved.responseUserId() != null) {
                    return resolved.responseUserId();
                }
            }
            Object userIdClaim = jwt.getClaim("userId");
            if (userIdClaim != null) {
                return String.valueOf(userIdClaim);
            }
            return jwt.getSubject();
        }
        if (principal instanceof String s && !"anonymousUser".equals(s)) {
            return s;
        }
        return null;
    }
}
