package com.example.user.service;

import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import com.example.user.security.JwtAutoProvisionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Single source of truth for resolving the backend {@link User} profile
 * of the currently authenticated principal.
 *
 * <p>Before this component the resolution logic was copy-pasted into
 * {@code UserControllerV1}, the legacy {@code UserController} and
 * {@code NotificationPreferencesControllerV1} — three drifting copies of
 * the same {@code requireCurrentUser()} method. They now all delegate
 * here.
 *
 * <p>This resolver is also the <em>safety net</em> for the Keycloak user
 * lazy-provision bridge. The main hook is
 * {@link com.example.user.security.KeycloakUserAutoProvisionFilter},
 * which provisions the profile before the request reaches a controller.
 * If that filter did not run (e.g. a path it excludes, or future wiring
 * changes), this resolver re-applies the exact same
 * {@link JwtAutoProvisionGate} so an M365 first-login still resolves
 * instead of falling through to {@code 403 PROFILE_MISSING}.
 *
 * <h2>403 {@code PROFILE_MISSING} — narrowed, not removed</h2>
 * The resolver still throws {@code 403 PROFILE_MISSING} (fail-closed) when:
 * <ul>
 *   <li>a numeric {@code userId} claim is present but no longer maps to a
 *       row — a stale token / deleted profile, NOT a first login;</li>
 *   <li>the JWT is outside the auto-provision gate (issuer/tenant not
 *       allowed, or missing the M365 marker) and no profile exists;</li>
 *   <li>the JWT is missing an email or {@code sub} and no profile exists.</li>
 * </ul>
 */
@Component
public class CurrentUserResolver {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserResolver.class);

    private final UserRepository userRepository;
    private final UserService userService;
    private final JwtAutoProvisionGate autoProvisionGate;

    public CurrentUserResolver(UserRepository userRepository,
                               UserService userService,
                               JwtAutoProvisionGate autoProvisionGate) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.autoProvisionGate = autoProvisionGate;
    }

    /**
     * Resolves the {@link User} for the current {@link SecurityContextHolder}
     * authentication, lazily provisioning an M365 first-login profile when
     * the auto-provision gate permits.
     *
     * @return the resolved backend {@link User}
     * @throws ResponseStatusException {@code 401} when unauthenticated,
     *         {@code 403 PROFILE_MISSING} per the narrowed rules above
     */
    public User resolveCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof User user) {
            return user;
        }

        if (principal instanceof Jwt jwt) {
            return resolveFromJwt(jwt, authentication);
        }

        // Non-JWT, non-User principal (e.g. service token / local api-key
        // UserDetails) — resolve by name only, never auto-provision.
        String username = authentication.getName();
        if (!StringUtils.hasText(username)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması gerekli");
        }
        return userRepository.findByEmailIgnoreCase(username)
                .orElseThrow(() -> {
                    log.warn("Kimliği doğrulanan kullanıcı için yerel profil yok: {}", username);
                    return new ResponseStatusException(HttpStatus.FORBIDDEN, "PROFILE_MISSING");
                });
    }

    private User resolveFromJwt(Jwt jwt, Authentication authentication) {
        // 1. Numeric userId claim — an established profile id. If it no
        //    longer resolves the token is stale / the profile was
        //    deleted: fail closed, do NOT treat as a first login.
        Long numericUserId = extractNumericUserId(jwt);
        if (numericUserId != null) {
            return userRepository.findById(numericUserId)
                    .orElseThrow(() -> {
                        log.warn("JWT userId mevcut ancak yerel profil yok (stale token): {}", numericUserId);
                        return new ResponseStatusException(HttpStatus.FORBIDDEN, "PROFILE_MISSING");
                    });
        }

        // 2. Gate-passing JWT (M365 bridge identity) — delegate to the
        //    single subject-aware implementation. The gate is what
        //    certifies the JWT `sub` is a trustworthy Keycloak subject
        //    UUID; only then may UserService#lazyProvisionFromJwt link
        //    it to a profile (kcSubject hit → return / email-match →
        //    backfill or 403 IDENTITY_LINK_CONFLICT / no match → insert).
        //
        //    This MUST be driven by the gate decision's command, NOT by
        //    the raw jwt.getSubject(): local/service issuers (e.g.
        //    `auth-service`) put the *email* in `sub`, and backfilling an
        //    email into kc_subject — or version-bumping the row — would
        //    be a bug. Such tokens fail the gate and take path 3 below.
        //
        //    lazyProvisionFromJwt may throw 403 IDENTITY_LINK_CONFLICT;
        //    that is intentional fail-closed and must NOT be downgraded
        //    to PROFILE_MISSING.
        JwtAutoProvisionGate.Decision decision = autoProvisionGate.evaluate(jwt);
        if (decision.allowed()) {
            log.debug("CurrentUserResolver resolving M365 identity via lazy-provision bridge sub={} email={}",
                    decision.command().kcSubject(), decision.command().email());
            return userService.lazyProvisionFromJwt(decision.command());
        }

        // 3. Gate-denied JWT — NOT an auto-provision bridge identity.
        //    Resolve an existing profile with a plain, non-mutating
        //    lookup (kcSubject then canonical email): no backfill, no
        //    identity-link conflict, no version bump — `sub` here is not
        //    a certified Keycloak subject. Fail closed when none exists.
        String subject = blankToNull(jwt.getSubject());
        String email = firstNonBlank(
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("preferred_username"),
                authentication.getName());
        String canonicalEmail = UserService.canonicalEmail(email);

        Optional<User> existing = findExistingWithoutProvision(subject, canonicalEmail);
        if (existing.isPresent()) {
            return existing.get();
        }

        log.warn("Keycloak kullanıcısı için yerel profil yok ve auto-provision reddedildi (reason={}): email={}",
                decision.denyReason(), canonicalEmail);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "PROFILE_MISSING");
    }

    /**
     * Plain non-mutating profile lookup for a gate-denied JWT — matches
     * by {@code kcSubject} then case-insensitive email and returns the
     * row as-is. Deliberately does NOT backfill {@code kcSubject} or
     * apply the identity-link-conflict rule: a gate-denied JWT's
     * {@code sub} is not a certified Keycloak subject (local/service
     * issuers put the email there), so writing it onto a profile — and
     * bumping the optimistic-locking {@code @Version} — would be wrong.
     */
    private Optional<User> findExistingWithoutProvision(String subject, String canonicalEmail) {
        if (StringUtils.hasText(subject)) {
            Optional<User> bySubject = userRepository.findByKcSubject(subject);
            if (bySubject.isPresent()) {
                return bySubject;
            }
        }
        if (StringUtils.hasText(canonicalEmail)) {
            return userRepository.findByEmailIgnoreCase(canonicalEmail);
        }
        return Optional.empty();
    }

    private static Long extractNumericUserId(Jwt jwt) {
        Object rawUserId = jwt.getClaim("userId");
        if (rawUserId instanceof Number num) {
            return num.longValue();
        }
        if (rawUserId instanceof String str) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
