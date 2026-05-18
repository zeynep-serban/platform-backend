package com.example.user.security;

import com.example.user.service.UserService;
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
 * Keycloak user lazy-provision bridge — the main hook.
 *
 * <p>The platform has a 2-stage user model: (1) the Keycloak identity and
 * (2) the backend {@code users} row (the platform profile). Microsoft 365
 * employees are auto-created as Keycloak users via identity brokering and
 * obtain a valid JWT, but historically the backend row was only ever
 * created by the explicit {@code POST /api/v1/users/internal/provision}
 * call. An M365 first-login therefore arrived authenticated but with no
 * profile, and every controller tripped {@code 403 PROFILE_MISSING}.
 *
 * <p>This {@link OncePerRequestFilter} closes that gap: on a request that
 * carries a validated Keycloak JWT it consults {@link JwtAutoProvisionGate}
 * and, when the gate allows, calls {@link UserService#lazyProvisionFromJwt}
 * to create the backend row before the request reaches a controller. The
 * provision call is idempotent — if the row already exists it is a cheap
 * lookup, so the steady-state cost is one indexed read.
 *
 * <h2>Ordering</h2>
 * Registered (see {@code AutoProvisionConfig}) to run AFTER Spring
 * Security has populated the JWT {@code Authentication} and BEFORE
 * {@code com.example.commonauth.scope.ScopeContextFilter}, so the scope
 * filter — which resolves the numeric user id — sees the freshly created
 * row on the very first request.
 *
 * <h2>Scope of action</h2>
 * <ul>
 *   <li>Acts only when the principal is an
 *       {@link org.springframework.security.oauth2.jwt.Jwt} — service
 *       tokens, local API-key {@code UserDetails} and anonymous requests
 *       are skipped.</li>
 *   <li>Excludes internal service-token paths
 *       ({@code /api/**\/internal/**}, covering
 *       {@code /api/v1/users/internal/provision}) and the public
 *       register / by-email paths — see {@link #shouldNotFilter}.</li>
 *   <li>Never aborts the request. The gate decision and any provision
 *       failure are logged; a request that is not auto-provisioned still
 *       proceeds and the controller's {@code CurrentUserResolver} applies
 *       the (narrowed) {@code 403 PROFILE_MISSING} fail-closed rule.</li>
 * </ul>
 */
public class KeycloakUserAutoProvisionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(KeycloakUserAutoProvisionFilter.class);

    private final JwtAutoProvisionGate autoProvisionGate;
    private final UserService userService;

    public KeycloakUserAutoProvisionFilter(JwtAutoProvisionGate autoProvisionGate,
                                           UserService userService) {
        this.autoProvisionGate = autoProvisionGate;
        this.userService = userService;
    }

    /**
     * Excludes paths that must never trigger auto-provision:
     * <ul>
     *   <li>internal service-token paths — {@code /api/users/internal/**}
     *       and {@code /api/v1/users/internal/**}; this also covers the
     *       explicit {@code /internal/provision} endpoint itself;</li>
     *   <li>the public register paths ({@code .../register}) and the
     *       by-email lookup paths, which are {@code permitAll} and not
     *       normal authenticated-user traffic.</li>
     * </ul>
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        if (path.contains("/internal/")) {
            return true;
        }
        if (path.endsWith("/register") || path.contains("/public/register")) {
            return true;
        }
        return path.contains("/by-email");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null
                    && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof Jwt jwt) {
                maybeProvision(jwt);
            }
        } catch (Exception ex) {
            // Auto-provision is best-effort: a failure here must not block
            // the request. The controller resolver will still fail closed
            // with 403 PROFILE_MISSING if no profile ends up resolvable.
            log.warn("Keycloak auto-provision filter hata aldı, istek devam ediyor: {}", ex.getMessage(), ex);
        }
        filterChain.doFilter(request, response);
    }

    private void maybeProvision(Jwt jwt) {
        JwtAutoProvisionGate.Decision decision = autoProvisionGate.evaluate(jwt);
        if (!decision.allowed()) {
            log.debug("Auto-provision atlandı (reason={})", decision.denyReason());
            return;
        }
        UserService.LazyProvisionCommand command = decision.command();
        // Idempotent: returns the existing row if one is already present.
        userService.lazyProvisionFromJwt(command);
        log.debug("Auto-provision ensured backend profile for sub={} email={}",
                command.kcSubject(), command.email());
    }
}
