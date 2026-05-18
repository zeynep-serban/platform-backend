package com.example.user.security;

import com.example.user.config.AutoProvisionProperties;
import com.example.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-logic unit tests for {@link JwtAutoProvisionGate} — the
 * config-driven gate of the Keycloak user lazy-provision bridge. Every
 * deny branch and the allow path (including display-name resolution and
 * email canonicalisation) is exercised without any Spring context.
 */
class JwtAutoProvisionGateTest {

    private JwtAutoProvisionGate gateWith(AutoProvisionProperties props) {
        return new JwtAutoProvisionGate(props);
    }

    private AutoProvisionProperties defaultProps() {
        AutoProvisionProperties props = new AutoProvisionProperties();
        props.setEnabled(true);
        props.setAllowedIssuers(List.of("platform-test", "serban"));
        props.setAllowLocalKeycloak(false);
        return props;
    }

    /** Builds a JWT with the given claims; header alg is irrelevant to the gate. */
    private Jwt jwt(Map<String, Object> claims) {
        Map<String, Object> headers = Map.of("alg", "RS256");
        Map<String, Object> safeClaims = new HashMap<>(claims);
        // Jwt requires at least one claim; callers always supply some.
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(600), headers, safeClaims);
    }

    private Map<String, Object> m365Claims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "kc-subject-uuid");
        claims.put("iss", "platform-test");
        claims.put("email", "person@example.com");
        claims.put("entra_tid", "tenant-uuid");
        return claims;
    }

    @Test
    void evaluate_allowsM365FirstLogin_withAllRequiredClaims() {
        JwtAutoProvisionGate gate = gateWith(defaultProps());

        JwtAutoProvisionGate.Decision decision = gate.evaluate(jwt(m365Claims()));

        assertThat(decision.allowed()).isTrue();
        UserService.LazyProvisionCommand command = decision.command();
        assertThat(command.kcSubject()).isEqualTo("kc-subject-uuid");
        assertThat(command.email()).isEqualTo("person@example.com");
    }

    @Test
    void evaluate_deniesWhenDisabled() {
        AutoProvisionProperties props = defaultProps();
        props.setEnabled(false);

        JwtAutoProvisionGate.Decision decision = gateWith(props).evaluate(jwt(m365Claims()));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).isEqualTo("auto-provision-disabled");
    }

    @Test
    void evaluate_deniesWhenIssuerNotAllowed() {
        Map<String, Object> claims = m365Claims();
        claims.put("iss", "auth-service"); // not on allowlist

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).startsWith("issuer-not-allowed");
    }

    @Test
    void evaluate_allowsIssuerByRealmShortName_whenConfiguredAsFullUrl() {
        Map<String, Object> claims = m365Claims();
        claims.put("iss", "https://kc.example.com/realms/serban");

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        // allowlist holds "serban" — matched against the realm short-name.
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void evaluate_deniesWhenSubjectMissing() {
        Map<String, Object> claims = m365Claims();
        claims.remove("sub");

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).isEqualTo("missing-sub");
    }

    @Test
    void evaluate_deniesWhenEmailMissing() {
        Map<String, Object> claims = m365Claims();
        claims.remove("email");

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).isEqualTo("missing-email");
    }

    @Test
    void evaluate_fallsBackToPreferredUsername_whenEmailClaimAbsent() {
        Map<String, Object> claims = m365Claims();
        claims.remove("email");
        claims.put("preferred_username", "fallback@example.com");

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.command().email()).isEqualTo("fallback@example.com");
    }

    @Test
    void evaluate_deniesWhenEmailVerifiedFalse() {
        Map<String, Object> claims = m365Claims();
        claims.put("email_verified", false);

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).isEqualTo("email-not-verified");
    }

    @Test
    void evaluate_allowsWhenEmailVerifiedTrue() {
        Map<String, Object> claims = m365Claims();
        claims.put("email_verified", true);

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void evaluate_deniesWhenEntraTidMissing_andLocalKeycloakNotAllowed() {
        Map<String, Object> claims = m365Claims();
        claims.remove("entra_tid");

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).isEqualTo("missing-entra-tid");
    }

    @Test
    void evaluate_allowsWhenEntraTidMissing_butLocalKeycloakAllowed() {
        AutoProvisionProperties props = defaultProps();
        props.setAllowLocalKeycloak(true);
        Map<String, Object> claims = m365Claims();
        claims.remove("entra_tid");

        JwtAutoProvisionGate.Decision decision = gateWith(props).evaluate(jwt(claims));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void evaluate_canonicalisesEmailToLowercase() {
        Map<String, Object> claims = m365Claims();
        claims.put("email", "Mixed.Case@Example.COM");

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.command().email()).isEqualTo("mixed.case@example.com");
    }

    @Test
    void evaluate_resolvesDisplayName_fromNameClaim() {
        Map<String, Object> claims = m365Claims();
        claims.put("name", "Ada Lovelace");

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.command().displayName()).isEqualTo("Ada Lovelace");
    }

    @Test
    void evaluate_resolvesDisplayName_fromGivenAndFamilyName_whenNameAbsent() {
        Map<String, Object> claims = m365Claims();
        claims.put("given_name", "Grace");
        claims.put("family_name", "Hopper");

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.command().displayName()).isEqualTo("Grace Hopper");
    }

    @Test
    void evaluate_resolvesDisplayName_fromEmailLocalPart_whenNoNameClaims() {
        Map<String, Object> claims = m365Claims();
        claims.put("email", "jdoe@example.com");

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.command().displayName()).isEqualTo("jdoe");
    }

    @Test
    void evaluate_deniesWhenNumericUserIdClaimPresent() {
        // A numeric userId marks an established profile — a stale/deleted
        // one must fail closed with 403, not be re-provisioned.
        Map<String, Object> claims = m365Claims();
        claims.put("userId", 4242);

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).isEqualTo("has-user-id-claim");
    }

    @Test
    void evaluate_deniesWhenStringNumericUserIdClaimPresent() {
        Map<String, Object> claims = m365Claims();
        claims.put("userId", "777");

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).isEqualTo("has-user-id-claim");
    }

    @Test
    void evaluate_allowsWhenUserIdClaimIsNonNumeric() {
        // A non-numeric userId is not a backend id — treated as absent,
        // so a genuine first-login still provisions.
        Map<String, Object> claims = m365Claims();
        claims.put("userId", "not-a-number");

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void evaluate_deniesWhenPreferredUsernameIsNotEmailFormat() {
        // FINDING 3 — the gate falls back to preferred_username when the
        // `email` claim is absent. An M365 UPN is usually email-shaped
        // but not guaranteed; a non-email value must fail closed
        // (User.email carries @Email/@NotBlank) with reason invalid-email.
        Map<String, Object> claims = m365Claims();
        claims.remove("email");
        claims.put("preferred_username", "DOMAIN\\jdoe"); // legacy down-level logon name

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).isEqualTo("invalid-email");
    }

    @Test
    void evaluate_deniesWhenEmailClaimIsNotEmailFormat() {
        // Even an explicit `email` claim is sanity-checked — a value
        // missing the @/dotted-domain shape fails closed.
        Map<String, Object> claims = m365Claims();
        claims.put("email", "not-an-email");

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).isEqualTo("invalid-email");
    }

    @Test
    void evaluate_allowsWhenPreferredUsernameIsEmailFormat() {
        // The common M365 case — the UPN *is* email-shaped — still
        // passes the new format check.
        Map<String, Object> claims = m365Claims();
        claims.remove("email");
        claims.put("preferred_username", "upn.user@example.com");

        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(jwt(claims));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.command().email()).isEqualTo("upn.user@example.com");
    }

    @Test
    void evaluate_deniesNullJwt() {
        JwtAutoProvisionGate.Decision decision = gateWith(defaultProps()).evaluate(null);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denyReason()).isEqualTo("not-a-jwt");
    }
}
