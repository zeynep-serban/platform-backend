package com.example.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Configuration for the Keycloak user lazy-provision bridge
 * ({@link com.example.user.security.KeycloakUserAutoProvisionFilter}).
 *
 * <p>Background: Microsoft 365 employees authenticate through Keycloak
 * identity brokering and receive a valid JWT, but the platform 2-stage
 * user model only ever created the backend {@code users} row through the
 * explicit {@code POST /api/v1/users/internal/provision} call. An M365
 * first-login therefore arrives with a valid token but no profile,
 * tripping {@code 403 PROFILE_MISSING}. This bridge lazily creates the
 * row on first request.
 *
 * <p>The gate is intentionally explicit and config-driven — auto-provision
 * never fires on "any JWT". A token must come from an allow-listed issuer
 * and (unless {@link #isAllowLocalKeycloak()} is enabled) carry the M365
 * {@code entra_tid} marker claim.
 *
 * <p>Properties (prefix {@code auto-provision}):
 * <ul>
 *   <li>{@code auto-provision.enabled} — master switch (default true)</li>
 *   <li>{@code auto-provision.allowed-issuers} — comma-separated JWT
 *       {@code iss} values eligible for auto-provision
 *       (default {@code platform-test,serban})</li>
 *   <li>{@code auto-provision.allow-local-keycloak} — when true, local
 *       Keycloak accounts without an {@code entra_tid} claim are also
 *       auto-provisioned (default false)</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "auto-provision")
public class AutoProvisionProperties {

    /** Master switch for the lazy-provision bridge. */
    private boolean enabled = true;

    /**
     * JWT {@code iss} values that are eligible for auto-provision.
     * A token whose issuer is not in this list still fails closed with
     * {@code 403 PROFILE_MISSING} when no backend profile exists.
     *
     * <p>The allowlist is matched both against the raw issuer string and
     * against its realm short-name (last path segment) so that whether
     * the deployment configures {@code serban} or
     * {@code https://kc.example.com/realms/serban}, both resolve.
     */
    private List<String> allowedIssuers = new ArrayList<>(List.of("platform-test", "serban"));

    /**
     * When false (default), only tokens carrying the M365 {@code entra_tid}
     * marker claim are auto-provisioned; local Keycloak-native accounts are
     * left to the explicit provision endpoint. Set true to also lazily
     * provision local Keycloak accounts.
     */
    private boolean allowLocalKeycloak = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowedIssuers() {
        return allowedIssuers;
    }

    public void setAllowedIssuers(List<String> allowedIssuers) {
        this.allowedIssuers = allowedIssuers == null ? new ArrayList<>() : allowedIssuers;
    }

    public boolean isAllowLocalKeycloak() {
        return allowLocalKeycloak;
    }

    public void setAllowLocalKeycloak(boolean allowLocalKeycloak) {
        this.allowLocalKeycloak = allowLocalKeycloak;
    }

    /**
     * Returns true when the supplied JWT {@code iss} value is on the
     * allowlist — matched against both the full issuer string and its
     * realm short-name (last {@code /}-delimited segment).
     *
     * @param issuer the JWT {@code iss} claim value, may be null
     * @return true when the issuer (or its realm short-name) is allow-listed
     */
    public boolean isIssuerAllowed(String issuer) {
        if (issuer == null || issuer.isBlank() || allowedIssuers == null) {
            return false;
        }
        String trimmed = issuer.trim();
        String realm = trimmed;
        int slash = trimmed.lastIndexOf('/');
        if (slash >= 0 && slash < trimmed.length() - 1) {
            realm = trimmed.substring(slash + 1);
        }
        for (String allowed : allowedIssuers) {
            if (allowed == null) {
                continue;
            }
            String candidate = allowed.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (candidate.equalsIgnoreCase(trimmed) || candidate.equalsIgnoreCase(realm)) {
                return true;
            }
        }
        return false;
    }

    /** Lower-cased view used for log lines without leaking original casing inconsistently. */
    public String describe() {
        return "AutoProvisionProperties{enabled=" + enabled
                + ", allowedIssuers=" + allowedIssuers
                + ", allowLocalKeycloak=" + allowLocalKeycloak + "}";
    }

    static String canonicalIssuer(String issuer) {
        return issuer == null ? null : issuer.trim().toLowerCase(Locale.ROOT);
    }
}
