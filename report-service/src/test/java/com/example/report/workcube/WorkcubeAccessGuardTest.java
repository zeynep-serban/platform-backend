package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class WorkcubeAccessGuardTest {

    private final PermissionResolver permissionResolver = mock(PermissionResolver.class);
    private final WorkcubeAccessGuard guard = new WorkcubeAccessGuard(permissionResolver);

    @Test
    void deniesNullAuthentication() {
        assertThat(guard.isInterimAdmin(null)).isFalse();
    }

    @Test
    void deniesNonJwtAuthentication() {
        Authentication anon = new AnonymousAuthenticationToken(
                "key",
                "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        assertThat(guard.isInterimAdmin(anon)).isFalse();
    }

    @Test
    void deniesWhenAuthzMeIsNull() {
        Jwt jwt = jwt("u1");
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);
        when(permissionResolver.getAuthzMe(jwt)).thenReturn(null);

        assertThat(guard.isInterimAdmin(token)).isFalse();
    }

    @Test
    void deniesWhenSuperAdminIsFalse() {
        Jwt jwt = jwt("u2");
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);
        AuthzMeResponse resp = new AuthzMeResponse();
        resp.setSuperAdmin(false);
        when(permissionResolver.getAuthzMe(jwt)).thenReturn(resp);

        assertThat(guard.isInterimAdmin(token)).isFalse();
    }

    @Test
    void deniesWhenSuperAdminIsNull() {
        Jwt jwt = jwt("u3");
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);
        AuthzMeResponse resp = new AuthzMeResponse(); // superAdmin field defaults to null
        when(permissionResolver.getAuthzMe(jwt)).thenReturn(resp);

        assertThat(guard.isInterimAdmin(token)).isFalse();
    }

    @Test
    void deniesWhenPermissionResolverThrows() {
        Jwt jwt = jwt("u4");
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);
        when(permissionResolver.getAuthzMe(jwt))
                .thenThrow(new RuntimeException("openfga unreachable"));

        assertThat(guard.isInterimAdmin(token)).isFalse();
    }

    @Test
    void allowsWhenSuperAdminTrue() {
        Jwt jwt = jwt("u5");
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);
        AuthzMeResponse resp = new AuthzMeResponse();
        resp.setSuperAdmin(true);
        when(permissionResolver.getAuthzMe(jwt)).thenReturn(resp);

        assertThat(guard.isInterimAdmin(token)).isTrue();
    }

    private Jwt jwt(String sub) {
        return new Jwt(
                "token-" + sub,
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("sub", sub, "preferred_username", sub + "@test"));
    }
}
