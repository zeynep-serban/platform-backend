package com.example.user.controller;

import com.example.commonauth.AuthenticatedUserLookupService;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.user.UserApplication;
import com.example.user.config.TestSecurityConfig;
import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FINDING 2 — filter-ordering behaviour test for the Keycloak user
 * lazy-provision bridge.
 *
 * <p>The {@code UserControllerV1Test} / {@code NotificationPreferencesControllerV1Test}
 * auto-provision cases pass even if {@code KeycloakUserAutoProvisionFilter}
 * never runs, because every {@code @RequestMapping} controller resolves the
 * current user through {@code CurrentUserResolver}, which re-applies the
 * gate as a safety net. They therefore do <em>not</em> prove the filter
 * runs in the right place.
 *
 * <p>{@code AuthzProxyControllerV1#getMyAuthorization}
 * ({@code GET /api/v1/authz/me}) is the genuinely filter-dependent
 * consumer: it does <strong>not</strong> call {@code CurrentUserResolver},
 * it reads {@code ScopeContextHolder}, which is populated by
 * {@code ScopeContextFilter}. {@code ScopeContextFilter} resolves the
 * numeric backend user id via {@code AuthenticatedUserLookupService}
 * (email → {@code users.id} SQL lookup). For an M365 first-login that
 * lookup only finds a row if {@code KeycloakUserAutoProvisionFilter}
 * already created it — and the filter is registered (see
 * {@code AutoProvisionConfig}) at order {@code LOWEST_PRECEDENCE - 20},
 * which is earlier than {@code ScopeContextFilter}'s
 * {@code LOWEST_PRECEDENCE - 10}.
 *
 * <p>So: a first-login {@code GET /api/v1/authz/me} that comes back with a
 * <em>numeric</em> {@code userId} (the new {@code users.id}) — and a
 * freshly created {@code users} row — proves the auto-provision filter ran
 * before {@code ScopeContextFilter}. If the ordering were wrong, the row
 * would not exist when {@code ScopeContextFilter} runs and the response
 * {@code userId} would fall back to the JWT subject ({@code kc-sub-*}).
 *
 * <h2>Test wiring</h2>
 * <ul>
 *   <li>{@code erp.openfga.enabled=true} — otherwise {@code ScopeContextFilter}
 *       short-circuits to the dev scope ({@code userId="dev-user"}) and
 *       never calls the user-id lookup, so the assertion would be
 *       meaningless.</li>
 *   <li>A {@code @Primary} mocked {@link OpenFgaAuthzService} whose scope
 *       reads return empty / {@code check}=false — so the OpenFGA fetch
 *       <em>succeeds</em> (no real server) and {@code ScopeContextFilter}
 *       does not fall back to the dev scope. {@code ScopeContext.userId()}
 *       is then exactly the resolved id.</li>
 *   <li>{@code scope.cache.enabled=false} — skips the
 *       {@code RemoteAuthzVersionProvider} HTTP probe, keeping the test
 *       deterministic and fast.</li>
 *   <li>A {@code @Primary} {@link AuthenticatedUserLookupService} test
 *       subclass — the production class probes table existence with
 *       PostgreSQL's {@code to_regclass()}, which H2 does not implement,
 *       so under H2 its email→id lookup is silently skipped and the
 *       numeric-id resolution can never happen. The subclass keeps all
 *       of the production claim-extraction logic and only swaps in a
 *       portable {@code SELECT id FROM users WHERE lower(email)=?}
 *       lookup — i.e. it reproduces exactly what production Postgres
 *       does, so the filter-ordering assertion stays genuine.</li>
 * </ul>
 */
@SpringBootTest(classes = UserApplication.class, webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({TestSecurityConfig.class, AuthzMeAutoProvisionIntegrationTest.OpenFgaStubConfig.class})
@TestPropertySource(properties = {
        "SECURITY_JWT_ISSUER=auth-service",
        "SECURITY_JWT_AUDIENCE=user-service,frontend",
        // OpenFGA ON so ScopeContextFilter actually resolves the numeric
        // user id (instead of short-circuiting to the dev scope).
        "erp.openfga.enabled=true",
        // Cache off → ScopeContextFilter skips the RemoteAuthzVersionProvider
        // HTTP probe; deterministic + no 2s connect timeout.
        "scope.cache.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
class AuthzMeAutoProvisionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void cleanDb() {
        userRepository.deleteAll();
    }

    /**
     * M365 first-login {@code GET /api/v1/authz/me}: a gate-passing JWT
     * with no pre-existing {@code users} row. The auto-provision filter
     * must create the row before {@code ScopeContextFilter} runs, so the
     * response carries the new numeric {@code users.id} — not the
     * {@code kc-sub-*} subject.
     */
    @Test
    void authzMe_m365FirstLogin_filterCreatesRowBeforeScopeContext_userIdIsNumeric() throws Exception {
        String email = "authz-me-first@example.com";
        String subject = "kc-sub-authz-me-first";
        assertThat(userRepository.findByEmail(email)).isEmpty();

        String token = issueM365Token(email, subject,
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        MvcResult result = mockMvc.perform(get("/api/v1/authz/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        // (a) the request created the backend users row.
        User provisioned = userRepository.findByEmail(email).orElseThrow();
        assertThat(provisioned.getKcSubject()).isEqualTo(subject);
        assertThat(provisioned.getRole()).isEqualTo("USER");

        // (b) the response userId is the NEW numeric users.id — proving
        //     KeycloakUserAutoProvisionFilter ran before ScopeContextFilter
        //     (else the email→id lookup would miss and userId would be the
        //     kc-sub-* subject string).
        String expectedUserId = String.valueOf(provisioned.getId());
        mockMvc.perform(get("/api/v1/authz/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(expectedUserId));

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"userId\":\"" + expectedUserId + "\"");
        assertThat(body).doesNotContain(subject);   // not the kc-sub-* string
        assertThat(body).doesNotContain("dev-user"); // not the dev fallback
    }

    /**
     * Issues an RS256 token simulating an M365 first-login: allow-listed
     * issuer ({@code platform-test}), the {@code entra_tid} marker claim,
     * {@code email_verified=true}, an explicit {@code sub} — and crucially
     * NO numeric {@code userId} claim, so this is a brand-new identity
     * with no established backend profile.
     */
    private String issueM365Token(String email, String subject, String entraTid) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuer("platform-test")
                .audience(List.of("user-service"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", email)
                .claim("entra_tid", entraTid)
                .claim("email_verified", Boolean.TRUE)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * Test wiring for {@code GET /api/v1/authz/me} filter-ordering proof.
     */
    @TestConfiguration
    static class OpenFgaStubConfig {

        /**
         * Replaces the real OpenFGA-backed {@link OpenFgaAuthzService}
         * with a mock that returns empty scopes / {@code check}=false.
         * This lets {@code ScopeContextFilter}'s OpenFGA fetch
         * <em>succeed</em> (there is no OpenFGA server in the test) so it
         * does not fall back to the dev scope — {@code ScopeContext.userId()}
         * then reflects exactly the id resolved by
         * {@link AuthenticatedUserLookupService}.
         */
        @Bean
        @Primary
        OpenFgaAuthzService stubOpenFgaAuthzService() {
            OpenFgaAuthzService svc = mock(OpenFgaAuthzService.class);
            when(svc.listObjectIds(anyString(), anyString(), anyString())).thenReturn(Set.of());
            when(svc.check(anyString(), anyString(), anyString(), anyString())).thenReturn(false);
            return svc;
        }

        /**
         * H2-portable {@link AuthenticatedUserLookupService}. The
         * production class checks table existence with PostgreSQL's
         * {@code to_regclass()} before its email→id {@code SELECT};
         * H2 has no {@code to_regclass}, so under H2 the check fails,
         * the SQL is skipped, and {@code resolve()} can never return a
         * numeric id (it falls back to the JWT subject) — which would
         * make the filter-ordering assertion meaningless.
         *
         * <p>This subclass keeps every bit of the production
         * claim-extraction logic via {@code super.resolve(jwt)} and only
         * fills in the numeric id with a portable
         * {@code SELECT id FROM users WHERE lower(email)=?} — exactly
         * the lookup production Postgres performs. The filter-ordering
         * proof therefore remains genuine: the id resolves only because
         * {@code KeycloakUserAutoProvisionFilter} created the row before
         * {@code ScopeContextFilter} ran.
         */
        @Bean
        @Primary
        AuthenticatedUserLookupService h2PortableUserLookupService(JdbcTemplate jdbcTemplate) {
            return new AuthenticatedUserLookupService(jdbcTemplate, "users") {
                @Override
                public ResolvedAuthenticatedUser resolve(Jwt jwt) {
                    ResolvedAuthenticatedUser base = super.resolve(jwt);
                    if (base.numericUserId() != null || base.email() == null) {
                        return base;
                    }
                    Long id = jdbcTemplate.query(
                            "select id from users where lower(email) = ? limit 1",
                            rs -> rs.next() ? rs.getLong("id") : null,
                            base.email().toLowerCase(Locale.ROOT));
                    if (id == null) {
                        return base;
                    }
                    return new ResolvedAuthenticatedUser(id, Long.toString(id), base.email());
                }
            };
        }
    }
}
