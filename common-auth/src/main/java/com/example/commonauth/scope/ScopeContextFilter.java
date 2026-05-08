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

/**
 * Servlet filter that populates {@link ScopeContextHolder} on every request.
 *
 * Production (openfga.enabled=true):
 *   JWT → extract userId → {@link OpenFgaScopeReader} → ScopeContext
 *
 * Dev/permitAll (openfga.enabled=false):
 *   YAML config → ScopeContext with static dev scope IDs
 *
 * Always clears the context after the request completes (finally block).
 *
 * <p>Codex thread 019e0891 iter-2 AGREE absorb (PR-BE-10 Phase 3): the
 * OpenFGA fetch logic was extracted into {@link OpenFgaScopeReader} so
 * permission-service controllers (which do NOT register this filter)
 * can read the same authoritative scope view via the same parallel
 * fetch + cache + relation map.
 */
public class ScopeContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ScopeContextFilter.class);

    private final OpenFgaProperties properties;
    private final AuthenticatedUserLookupService authenticatedUserLookupService;
    private final OpenFgaScopeReader scopeReader;

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
        this.properties = properties;
        this.authenticatedUserLookupService = authenticatedUserLookupService;
        this.scopeReader = new OpenFgaScopeReader(
                authzService, properties, scopeContextCache, versionProvider);
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

        // OpenFgaScopeReader handles cache + parallel fetch internally
        // and propagates exceptions; preserve the pre-PR-BE-10
        // ScopeContextFilter contract: production OpenFGA fail → dev
        // scope fallback (NOT empty), so a misconfigured/outage cluster
        // still serves the dev YAML scope rather than failing all
        // requests with empty scope.
        try {
            return scopeReader.readScopeContext(userId);
        } catch (Exception e) {
            log.error("Failed to build ScopeContext from OpenFGA for user {}, falling back to dev scope", userId, e);
            return buildDevScopeContext();
        }
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
