package com.example.permission.security;

import com.example.permission.security.ImpersonationContextExtractor.ImpersonationContext;
import com.example.permission.security.ImpersonationContextExtractor.ImpersonationContextException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * jti_session_lookup binding enforcement filter (Codex iter-27 P0 absorb).
 *
 * <p>Runs AFTER Spring Security's bearer auth filter. If the resolved
 * Authentication is a broker-issued (azp=impersonation-broker) JWT, the
 * filter calls {@link ImpersonationContextExtractor} to enforce the live
 * DB session contract. Token revocation, expiry, and explicit stop are
 * detected here even though the access token is still cryptographically
 * valid by KC's clock.
 *
 * <p>Outcomes:
 * <ul>
 *   <li>Non-broker token → pass through unchanged (normal users/services).</li>
 *   <li>Broker token + valid active session → store {@link ImpersonationContext}
 *       as request attribute {@code impersonation.context} and set a request
 *       header-equivalent attribute used downstream by audit hooks.</li>
 *   <li>Broker token + missing/expired/inactive session → fail-closed 403
 *       with {@code IMPERSONATION_SESSION_REQUIRED} error code; chain halted.</li>
 * </ul>
 *
 * <p>Codex iter-27 mandate: this filter closes the security gap where a
 * stopped/revoked session's exchanged token would still be honored by
 * permission-service until KC exp because no auth-side enforcement was
 * wired to the extractor.
 */
@Component
public class ImpersonationContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationContextFilter.class);

    public static final String REQUEST_ATTRIBUTE = "impersonation.context";

    private final ImpersonationContextExtractor extractor;

    public ImpersonationContextFilter(ImpersonationContextExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Jwt jwt = extractJwt(auth);

        if (jwt == null) {
            // No JWT (anonymous, internal API key auth, actuator) → pass through.
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Optional<ImpersonationContext> ctx = extractor.extract(jwt);
            ctx.ifPresent(c -> request.setAttribute(REQUEST_ATTRIBUTE, c));
        } catch (ImpersonationContextException e) {
            log.warn("Fail-closed impersonation context: {} path={}", e.getMessage(), request.getRequestURI());
            writeJsonError(response, HttpStatus.FORBIDDEN, e.errorCode(), e.getMessage());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Jwt extractJwt(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken();
        }
        return null;
    }

    private void writeJsonError(HttpServletResponse response,
                                HttpStatus status,
                                String errorCode,
                                String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String safeMsg = message == null ? "" : message.replace("\"", "\\\"");
        String body = String.format(
                "{\"error\":\"%s\",\"message\":\"%s\"}", errorCode, safeMsg);
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
