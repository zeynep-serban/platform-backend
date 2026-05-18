package com.example.user.security;

import com.example.user.config.AutoProvisionProperties;
import com.example.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure decision component for the Keycloak user lazy-provision bridge.
 *
 * <p>Given a {@link Jwt} principal, decides whether the platform may
 * auto-create a backend {@code users} row, and — when allowed — produces
 * the {@link UserService.LazyProvisionCommand} describing the row to
 * create. It holds no state and performs no I/O, so it can be exercised
 * by both {@link KeycloakUserAutoProvisionFilter} (the main hook) and
 * {@code CurrentUserResolver} (the safety net) without divergence.
 *
 * <p>The gate is fail-closed. Auto-provision fires only when ALL hold:
 * <ol>
 *   <li>the bridge is enabled ({@code auto-provision.enabled});</li>
 *   <li>the JWT issuer ({@code iss}) is on the configured allowlist;</li>
 *   <li>the JWT carries a non-blank {@code sub};</li>
 *   <li>the JWT carries an email ({@code email} or
 *       {@code preferred_username});</li>
 *   <li>if an {@code email_verified} claim is present, it is {@code true};</li>
 *   <li>the JWT carries the M365 marker claim {@code entra_tid} — unless
 *       {@code auto-provision.allow-local-keycloak} is enabled;</li>
 *   <li>the JWT does NOT carry a numeric {@code userId} claim — a numeric
 *       {@code userId} marks an already-established profile, so if it no
 *       longer resolves the token is stale / the profile was deleted, not
 *       a first login; that case must fail closed with
 *       {@code 403 PROFILE_MISSING} and never be re-provisioned.</li>
 * </ol>
 * A token failing any check is left to fail closed with
 * {@code 403 PROFILE_MISSING} when no profile exists.
 */
@Component
public class JwtAutoProvisionGate {

    private static final Logger log = LoggerFactory.getLogger(JwtAutoProvisionGate.class);

    /** M365 / Entra ID tenant marker claim — present on brokered M365 tokens. */
    static final String ENTRA_TENANT_CLAIM = "entra_tid";

    /**
     * Sanity check for the resolved email. The gate accepts
     * {@code preferred_username} as the email when the {@code email}
     * claim is absent, and an M365 UPN is usually email-format but not
     * guaranteed. {@code User.email} carries {@code @Email}/{@code @NotBlank}
     * bean-validation constraints, so a non-email-shaped value would fail
     * deeper in the persist path; rejecting it here keeps the gate
     * fail-closed and the failure observable as a {@code invalid-email}
     * deny reason. Deliberately permissive (single {@code @}, dotted
     * domain, no whitespace) — not a full RFC 5322 validator.
     */
    private static final Pattern EMAIL_FORMAT =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final AutoProvisionProperties properties;

    public JwtAutoProvisionGate(AutoProvisionProperties properties) {
        this.properties = properties;
    }

    /**
     * Outcome of a gate evaluation.
     *
     * @param allowed true when auto-provision may proceed
     * @param command the row to create — non-null only when {@code allowed}
     * @param denyReason short machine-ish reason when {@code !allowed}
     */
    public record Decision(boolean allowed, UserService.LazyProvisionCommand command, String denyReason) {

        static Decision deny(String reason) {
            return new Decision(false, null, reason);
        }

        static Decision allow(UserService.LazyProvisionCommand command) {
            return new Decision(true, command, null);
        }
    }

    /**
     * Evaluates the gate for the supplied JWT principal.
     *
     * @param jwt the validated JWT principal (never null)
     * @return an allow decision carrying the provision command, or a deny
     *         decision carrying the reason
     */
    public Decision evaluate(Jwt jwt) {
        if (jwt == null) {
            return Decision.deny("not-a-jwt");
        }
        if (!properties.isEnabled()) {
            return Decision.deny("auto-provision-disabled");
        }

        // Read `iss` as a raw claim, not via Jwt#getIssuer(): Keycloak
        // realm issuers are full URLs but local/test issuers (e.g.
        // "auth-service", "platform-test") are plain strings, and
        // getIssuer() throws IllegalArgumentException on a non-URL value.
        String issuer = readIssuer(jwt);
        if (!properties.isIssuerAllowed(issuer)) {
            return Decision.deny("issuer-not-allowed:" + issuer);
        }

        // A numeric userId claim means an established backend profile —
        // never a first login. If it does not resolve the caller path
        // (CurrentUserResolver) fails closed with 403 PROFILE_MISSING; the
        // gate must not re-provision a row for a stale/deleted profile.
        if (hasNumericUserIdClaim(jwt)) {
            return Decision.deny("has-user-id-claim");
        }

        String subject = blankToNull(jwt.getSubject());
        if (subject == null) {
            return Decision.deny("missing-sub");
        }

        String email = firstNonBlank(jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username"));
        if (email == null) {
            return Decision.deny("missing-email");
        }

        Optional<Boolean> emailVerified = readEmailVerified(jwt);
        if (emailVerified.isPresent() && !emailVerified.get()) {
            return Decision.deny("email-not-verified");
        }

        boolean hasEntraTenant = StringUtils.hasText(jwt.getClaimAsString(ENTRA_TENANT_CLAIM));
        if (!hasEntraTenant && !properties.isAllowLocalKeycloak()) {
            return Decision.deny("missing-entra-tid");
        }

        String canonicalEmail = UserService.canonicalEmail(email);
        // The email may have come from `preferred_username` (M365 UPN),
        // which is not guaranteed to be email-shaped. User.email is
        // @Email/@NotBlank — fail closed here rather than at persist time.
        if (canonicalEmail == null || !EMAIL_FORMAT.matcher(canonicalEmail).matches()) {
            return Decision.deny("invalid-email");
        }

        String displayName = resolveDisplayName(jwt, canonicalEmail);
        return Decision.allow(new UserService.LazyProvisionCommand(subject, canonicalEmail, displayName));
    }

    /**
     * Resolves the display name for the lazily-created profile, in order:
     * the {@code name} claim, then {@code given_name} + {@code family_name},
     * then the email local-part.
     *
     * @param jwt            the JWT principal
     * @param canonicalEmail the already-canonicalised email (fallback source)
     * @return a non-blank display name
     */
    static String resolveDisplayName(Jwt jwt, String canonicalEmail) {
        String name = blankToNull(jwt.getClaimAsString("name"));
        if (name != null) {
            return name;
        }
        String given = blankToNull(jwt.getClaimAsString("given_name"));
        String family = blankToNull(jwt.getClaimAsString("family_name"));
        if (given != null && family != null) {
            return given + " " + family;
        }
        if (given != null) {
            return given;
        }
        if (family != null) {
            return family;
        }
        if (canonicalEmail != null) {
            int at = canonicalEmail.indexOf('@');
            String local = at > 0 ? canonicalEmail.substring(0, at) : canonicalEmail;
            if (StringUtils.hasText(local)) {
                return local;
            }
        }
        return canonicalEmail;
    }

    /**
     * Returns true when the JWT carries a {@code userId} claim that is (or
     * parses to) a number — the marker of an already-established backend
     * profile. A non-numeric {@code userId} is ignored (treated as absent).
     */
    private static boolean hasNumericUserIdClaim(Jwt jwt) {
        Object raw = jwt.getClaim("userId");
        if (raw instanceof Number) {
            return true;
        }
        if (raw instanceof String str && !str.isBlank()) {
            try {
                Long.parseLong(str.trim());
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * Reads the {@code iss} claim as a plain string. {@link Jwt#getIssuer()}
     * coerces the claim to a {@code URL} and throws
     * {@link IllegalArgumentException} for non-URL issuers (local/test
     * issuers such as {@code auth-service}); this reads the raw value so
     * both URL and short-name issuers resolve.
     */
    private static String readIssuer(Jwt jwt) {
        Object raw = jwt.getClaim("iss");
        if (raw == null) {
            return null;
        }
        if (raw instanceof String str) {
            return str;
        }
        // java.net.URL or other representation — toString() yields the
        // issuer text in all cases the allowlist needs to match.
        return raw.toString();
    }

    private static Optional<Boolean> readEmailVerified(Jwt jwt) {
        Object raw = jwt.getClaim("email_verified");
        if (raw == null) {
            return Optional.empty();
        }
        if (raw instanceof Boolean bool) {
            return Optional.of(bool);
        }
        if (raw instanceof String str && !str.isBlank()) {
            return Optional.of(Boolean.parseBoolean(str.trim()));
        }
        // Unrecognised representation — treat as "present but unusable":
        // fail closed rather than silently provisioning.
        log.warn("Unrecognised email_verified claim type {} — treating as not verified", raw.getClass());
        return Optional.of(false);
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
