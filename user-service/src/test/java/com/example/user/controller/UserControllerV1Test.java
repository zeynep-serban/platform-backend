package com.example.user.controller;

import com.example.user.UserApplication;
import com.example.user.config.TestSecurityConfig;
import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import com.example.user.repository.UserAuditEventRepository;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = UserApplication.class, webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = {
        "SECURITY_JWT_ISSUER=auth-service",
        "SECURITY_JWT_AUDIENCE=user-service,frontend",
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
class UserControllerV1Test {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserAuditEventRepository userAuditEventRepository;

    @BeforeEach
    void cleanDb() {
        userAuditEventRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void listUsers_invalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listUsers_invalidSignature_returns401() throws Exception {
        String token = issueToken("tamper@example.com") + "x";
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Keycloak user lazy-provision bridge + activation gate: an M365
     * first-login (allow-listed issuer + {@code entra_tid} marker) with no
     * backend profile is auto-provisioned as a <em>passive</em>
     * ({@code enabled=false}) row, and the request itself is rejected
     * {@code 403 ACCOUNT_DISABLED} — the profile awaits admin activation
     * ("admin manually authorizes" model). The filter still provisions the
     * row before {@code CurrentUserResolver} gates the request.
     */
    @Test
    void listUsers_m365FirstLogin_autoProvisionsPassiveAndReturns403() throws Exception {
        String email = "m365-first@example.com";
        Assertions.assertThat(userRepository.findByEmail(email)).isEmpty();

        String token = issueM365Token(email, "11111111-1111-1111-1111-111111111111");
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("ACCOUNT_DISABLED")));

        User provisioned = userRepository.findByEmail(email).orElseThrow();
        Assertions.assertThat(provisioned.getRole()).isEqualTo("USER");      // least-privilege
        Assertions.assertThat(provisioned.isEnabled()).isFalse();            // passive — admin must activate
        Assertions.assertThat(provisioned.getCompanyId()).isNull();
        Assertions.assertThat(provisioned.getKcSubject()).isEqualTo("kc-sub-m365-first");
    }

    /**
     * Gate fail-closed: a JWT whose issuer is NOT on the auto-provision
     * allowlist still yields {@code 403 PROFILE_MISSING} when no backend
     * profile exists. The default test {@code issueToken(...)} uses issuer
     * {@code auth-service}, which is not allow-listed.
     */
    @Test
    void listUsers_missingProfile_disallowedIssuer_returns403() throws Exception {
        String token = issueToken("missing@example.com");
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("PROFILE_MISSING")));
    }

    /**
     * Gate fail-closed: an allow-listed issuer but NO {@code entra_tid}
     * marker claim (local Keycloak-native account) is not auto-provisioned
     * while {@code auto-provision.allow-local-keycloak} stays false, so a
     * missing profile still yields {@code 403 PROFILE_MISSING}.
     */
    @Test
    void listUsers_missingProfile_allowedIssuerButNoEntraTid_returns403() throws Exception {
        String token = issueTokenWithIssuer("local-kc@example.com", "platform-test", null, null);
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("PROFILE_MISSING")));
    }

    /**
     * {@code email_verified=false} blocks auto-provision even with an
     * allow-listed issuer and the {@code entra_tid} marker.
     */
    @Test
    void listUsers_m365FirstLogin_emailNotVerified_returns403() throws Exception {
        String token = issueTokenWithIssuer("unverified@example.com", "platform-test",
                "22222222-2222-2222-2222-222222222222", Boolean.FALSE);
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("PROFILE_MISSING")));
    }

    /**
     * A numeric {@code userId} claim that no longer maps to a row is a
     * stale token / deleted profile — fail closed with
     * {@code 403 PROFILE_MISSING}; it must NOT be treated as a first login
     * and auto-provisioned.
     */
    @Test
    void listUsers_staleNumericUserId_returns403_notAutoProvisioned() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("kc-sub-stale")
                .issuer("platform-test")
                .audience(List.of("user-service"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", "stale@example.com")
                .claim("entra_tid", "33333333-3333-3333-3333-333333333333")
                .claim("userId", 999999)
                .claim("permissions", List.of("VIEW_USERS", "MANAGE_USERS"))
                .build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("PROFILE_MISSING")));

        Assertions.assertThat(userRepository.findByEmail("stale@example.com")).isEmpty();
    }

    /**
     * Auto-provision is idempotent: a second M365 request for the same
     * subject reuses the passive row created on the first request — no
     * duplicate. Both requests are gated {@code 403 ACCOUNT_DISABLED}
     * (the row stays passive until an admin activates it).
     */
    @Test
    void listUsers_m365SecondLogin_reusesSameRow() throws Exception {
        String email = "m365-repeat@example.com";
        String token = issueM365Token(email, "44444444-4444-4444-4444-444444444444");

        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
        Long firstId = userRepository.findByEmail(email).orElseThrow().getId();

        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        Assertions.assertThat(userRepository.findAll().stream()
                        .filter(u -> email.equals(u.getEmail())).count())
                .isEqualTo(1L);
        Assertions.assertThat(userRepository.findByEmail(email).orElseThrow().getId())
                .isEqualTo(firstId);
    }

    /**
     * Email case-normalization: a token whose email is mixed-case
     * provisions a passive row with the canonical lowercase email. The
     * request is gated {@code 403 ACCOUNT_DISABLED}; the row is still
     * created with the normalized email.
     */
    @Test
    void listUsers_m365FirstLogin_normalizesEmailCase() throws Exception {
        String mixedCaseEmail = "Mixed.Case@Example.com";
        String token = issueM365Token(mixedCaseEmail, "55555555-5555-5555-5555-555555555555");

        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());

        Assertions.assertThat(userRepository.findByEmail("Mixed.Case@Example.com")).isEmpty();
        User provisioned = userRepository.findByEmailIgnoreCase("mixed.case@example.com").orElseThrow();
        Assertions.assertThat(provisioned.getEmail()).isEqualTo("mixed.case@example.com");
        Assertions.assertThat(userRepository.findAll().stream()
                        .filter(u -> "mixed.case@example.com".equals(u.getEmail())).count())
                .isEqualTo(1L);
    }

    @Test
    void listUsers_existingProfile_returns200() throws Exception {
        User existing = ensureUserExists("admin@example.com");
        String token = issueToken(existing.getEmail());
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void listUsers_frontendAudienceToken_returns200() throws Exception {
        ensureUserExists("admin@example.com");
        String token = issueToken("admin@example.com", List.of("frontend"));
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void getUser_returnsDetailDto() throws Exception {
        User saved = ensureUserExists("detail@example.com");
        String token = issueToken(saved.getEmail());

        mockMvc.perform(get("/api/v1/users/{id}", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("detail@example.com")));
    }

    /**
     * Session 47 stabilization revert (Codex 019e1df7 follow-up): the
     * V1 detail payload MUST NOT expose {@code kcSubject}. The Session
     * 47 hotfix chain (#160/#162) leaked it because the
     * {@code ServiceTokenAuthenticationFilter} was misconfigured
     * (defaulting to unreachable {@code localhost:8081/realms/serban}),
     * forcing auth-service to fall back to the public path + admin JWT.
     * platform-k8s-gitops PR #543 fixed the {@code SERVICE_AUTH_*}
     * config; this revert restores the internal service-token contract
     * and removes the public leak.
     */
    @Test
    void getUser_v1_detailDoesNotExposeKcSubject() throws Exception {
        User saved = ensureUserExists("kcsubject-detail@example.com");
        saved.setKcSubject("11111111-2222-3333-4444-555555555555");
        userRepository.save(saved);
        String token = issueToken(saved.getEmail());

        mockMvc.perform(get("/api/v1/users/{id}", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("kcSubject"))));
    }

    @Test
    void getUser_notFound_returns404() throws Exception {
        User saved = ensureUserExists("missing-profile@example.com");
        String token = issueToken(saved.getEmail());
        Long missingId = saved.getId() + 999;

        mockMvc.perform(get("/api/v1/users/{id}", missingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void getUserByEmail_returnsDetail() throws Exception {
        User saved = ensureUserExists("lookup@example.com");
        String token = issueToken(saved.getEmail());

        mockMvc.perform(get("/api/v1/users/by-email")
                        .param("email", saved.getEmail())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("lookup@example.com")));
    }

    @Test
    void getUserByEmail_notFound_returns404() throws Exception {
        User saved = ensureUserExists("owner@example.com");
        String token = issueToken(saved.getEmail());

        mockMvc.perform(get("/api/v1/users/by-email")
                        .param("email", "notfound@example.com")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCurrentUserProfile_returnsDetailDto() throws Exception {
        User saved = ensureUserExists("profile-me@example.com");
        saved.setName("Profile Owner");
        User persisted = userRepository.save(saved);
        String token = issueToken(persisted.getEmail());

        mockMvc.perform(get("/api/v1/users/me/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(persisted.getId()))
                .andExpect(jsonPath("$.name").value("Profile Owner"))
                .andExpect(jsonPath("$.email").value("profile-me@example.com"));
    }

    @Test
    void updateCurrentUserProfile_updatesDisplayNameWithoutUserUpdatePermission() throws Exception {
        User saved = ensureUserExists("self-update@example.com");
        saved.setName("Before Name");
        User persisted = userRepository.save(saved);
        String token = issueToken(persisted.getEmail(), List.of("frontend"));

        mockMvc.perform(put("/api/v1/users/me/profile")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "After Name"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(persisted.getId()))
                .andExpect(jsonPath("$.name").value("After Name"))
                .andExpect(jsonPath("$.email").value("self-update@example.com"));

        User updated = userRepository.findById(persisted.getId()).orElseThrow();
        Assertions.assertThat(updated.getName()).isEqualTo("After Name");
        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getEventType())
                .isEqualTo("USER_UPDATE");
    }

    @Test
    void updateActivation_returnsAckWithAudit() throws Exception {
        // The activation target starts passive; the request is made by a
        // separate enabled admin — a passive account cannot itself transact
        // (CurrentUserResolver ACCOUNT_DISABLED gate).
        User target = ensureUserExists("activate-target@example.com");
        target.setEnabled(false);
        userRepository.save(target);
        User admin = ensureUserExists("activate-admin@example.com");
        String token = issueToken(admin.getEmail());

        mockMvc.perform(put("/api/v1/users/{id}/activation", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")));

        User updated = userRepository.findById(target.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(updated.isEnabled()).isTrue();
        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
    }

    /**
     * Activation gate, full positive path through the real endpoints: an
     * M365 first-login is gated {@code 403 ACCOUNT_DISABLED}; a separate
     * enabled admin activates the passive row via {@code PUT /activation};
     * the same M365 token then transacts {@code 200}.
     */
    @Test
    void listUsers_m365User_afterAdminActivation_returns200() throws Exception {
        String email = "m365-activated@example.com";
        String token = issueM365Token(email, "77777777-7777-7777-7777-777777777777");

        // First login — auto-provisioned passive, request gated.
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("ACCOUNT_DISABLED")));
        Long m365UserId = userRepository.findByEmail(email).orElseThrow().getId();

        // A separate enabled admin activates the passive row via the real
        // PUT /activation endpoint.
        User admin = ensureUserExists("m365-activation-admin@example.com");
        mockMvc.perform(put("/api/v1/users/{id}/activation", m365UserId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(admin.getEmail()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", true))))
                .andExpect(status().isOk());

        // The same M365 token now transacts.
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void getCurrentSessionTimeoutSnapshot_returnsProfileState() throws Exception {
        User saved = ensureUserExists("snapshot@example.com");
        saved.setSessionTimeoutMinutes(22);
        userRepository.save(saved);
        String token = issueToken(saved.getEmail());

        mockMvc.perform(get("/api/v1/users/me/session-timeout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceKey").value("profile:snapshot@example.com"))
                .andExpect(jsonPath("$.sessionTimeoutMinutes").value(22))
                .andExpect(jsonPath("$.version").isNumber());
    }

    @Test
    void updateCurrentSessionTimeout_returnsAckAndAudit() throws Exception {
        User saved = ensureUserExists("timeout@example.com");
        saved.setSessionTimeoutMinutes(15);
        User persisted = userRepository.save(saved);
        String token = issueToken(persisted.getEmail());

        mockMvc.perform(put("/api/v1/users/me/session-timeout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionTimeoutMinutes", 21,
                                "expectedVersion", persisted.getVersion() == null ? 0 : persisted.getVersion(),
                                "source", "mobile-offline-queue",
                                "attemptCount", 2,
                                "queueActionId", "qa-success-01"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")))
                .andExpect(jsonPath("$.sessionTimeoutMinutes").value(21))
                .andExpect(jsonPath("$.version").value((persisted.getVersion() == null ? 0 : persisted.getVersion()) + 1))
                .andExpect(jsonPath("$.errorCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.conflictReason").value(org.hamcrest.Matchers.nullValue()));

        User updated = userRepository.findById(persisted.getId()).orElseThrow();
        Assertions.assertThat(updated.getSessionTimeoutMinutes()).isEqualTo(21);
        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getEventType())
                .isEqualTo("USER_SESSION_TIMEOUT_SYNCED");
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getDetails())
                .contains("attempt=2")
                .contains("queueActionId=qa-success-01");
    }

    @Test
    void updateCurrentSessionTimeout_versionConflict_returns409() throws Exception {
        User saved = ensureUserExists("conflict@example.com");
        saved.setSessionTimeoutMinutes(18);
        User persisted = userRepository.save(saved);
        String token = issueToken(persisted.getEmail());

        mockMvc.perform(put("/api/v1/users/me/session-timeout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionTimeoutMinutes", 25,
                                "expectedVersion", (persisted.getVersion() == null ? 0 : persisted.getVersion()) + 1,
                                "source", "mobile-offline-queue",
                                "attemptCount", 3,
                                "queueActionId", "qa-conflict-01"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("conflict"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")))
                .andExpect(jsonPath("$.sessionTimeoutMinutes").value(18))
                .andExpect(jsonPath("$.errorCode").value("USER_SESSION_TIMEOUT_SYNC_CONFLICT"))
                .andExpect(jsonPath("$.conflictReason").value("STALE_EXPECTED_VERSION"));

        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getEventType())
                .isEqualTo("USER_SESSION_TIMEOUT_SYNC_CONFLICT");
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getDetails())
                .contains("attempt=3")
                .contains("queueActionId=qa-conflict-01");
    }

    @Test
    void getCurrentLocaleSnapshot_returnsProfileState() throws Exception {
        User saved = ensureUserExists("locale-snapshot@example.com");
        saved.setLocale("de");
        userRepository.save(saved);
        String token = issueToken(saved.getEmail());

        mockMvc.perform(get("/api/v1/users/me/locale")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceKey").value("profile:locale-snapshot@example.com"))
                .andExpect(jsonPath("$.locale").value("de"))
                .andExpect(jsonPath("$.version").isNumber());
    }

    @Test
    void updateCurrentLocale_returnsAckAndAudit() throws Exception {
        User saved = ensureUserExists("locale-ok@example.com");
        saved.setLocale("tr");
        User persisted = userRepository.save(saved);
        String token = issueToken(persisted.getEmail());

        mockMvc.perform(put("/api/v1/users/me/locale")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "locale", "en",
                                "expectedVersion", persisted.getVersion() == null ? 0 : persisted.getVersion(),
                                "source", "mobile-offline-queue",
                                "attemptCount", 2,
                                "queueActionId", "qa-locale-success-01"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")))
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.version").value((persisted.getVersion() == null ? 0 : persisted.getVersion()) + 1))
                .andExpect(jsonPath("$.errorCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.conflictReason").value(org.hamcrest.Matchers.nullValue()));

        User updated = userRepository.findById(persisted.getId()).orElseThrow();
        Assertions.assertThat(updated.getLocale()).isEqualTo("en");
        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getEventType())
                .isEqualTo("USER_LOCALE_SYNCED");
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getDetails())
                .contains("attempt=2")
                .contains("queueActionId=qa-locale-success-01");
    }

    @Test
    void updateCurrentLocale_versionConflict_returns409() throws Exception {
        User saved = ensureUserExists("locale-conflict@example.com");
        saved.setLocale("es");
        User persisted = userRepository.save(saved);
        String token = issueToken(persisted.getEmail());

        mockMvc.perform(put("/api/v1/users/me/locale")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "locale", "de",
                                "expectedVersion", (persisted.getVersion() == null ? 0 : persisted.getVersion()) + 1,
                                "source", "mobile-offline-queue",
                                "attemptCount", 3,
                                "queueActionId", "qa-locale-conflict-01"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("conflict"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")))
                .andExpect(jsonPath("$.locale").value("es"))
                .andExpect(jsonPath("$.errorCode").value("USER_LOCALE_SYNC_CONFLICT"))
                .andExpect(jsonPath("$.conflictReason").value("STALE_EXPECTED_VERSION"));

        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getEventType())
                .isEqualTo("USER_LOCALE_SYNC_CONFLICT");
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getDetails())
                .contains("attempt=3")
                .contains("queueActionId=qa-locale-conflict-01");
    }

    @Test
    void getCurrentTimezoneSnapshot_returnsProfileState() throws Exception {
        User saved = ensureUserExists("timezone-snapshot@example.com");
        saved.setTimezone("Europe/Berlin");
        userRepository.save(saved);
        String token = issueToken(saved.getEmail());

        mockMvc.perform(get("/api/v1/users/me/timezone")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceKey").value("profile:timezone-snapshot@example.com"))
                .andExpect(jsonPath("$.timezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.version").isNumber());
    }

    @Test
    void updateCurrentTimezone_returnsAckAndAudit() throws Exception {
        User saved = ensureUserExists("timezone-ok@example.com");
        saved.setTimezone("Europe/Istanbul");
        User persisted = userRepository.save(saved);
        String token = issueToken(persisted.getEmail());

        mockMvc.perform(put("/api/v1/users/me/timezone")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timezone", "Europe/Berlin",
                                "expectedVersion", persisted.getVersion() == null ? 0 : persisted.getVersion(),
                                "source", "mobile-offline-queue",
                                "attemptCount", 2,
                                "queueActionId", "qa-timezone-success-01"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")))
                .andExpect(jsonPath("$.timezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.version").value((persisted.getVersion() == null ? 0 : persisted.getVersion()) + 1))
                .andExpect(jsonPath("$.errorCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.conflictReason").value(org.hamcrest.Matchers.nullValue()));

        User updated = userRepository.findById(persisted.getId()).orElseThrow();
        Assertions.assertThat(updated.getTimezone()).isEqualTo("Europe/Berlin");
        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getEventType())
                .isEqualTo("USER_TIMEZONE_SYNCED");
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getDetails())
                .contains("attempt=2")
                .contains("queueActionId=qa-timezone-success-01");
    }

    @Test
    void updateCurrentTimezone_versionConflict_returns409() throws Exception {
        User saved = ensureUserExists("timezone-conflict@example.com");
        saved.setTimezone("Europe/London");
        User persisted = userRepository.save(saved);
        String token = issueToken(persisted.getEmail());

        mockMvc.perform(put("/api/v1/users/me/timezone")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timezone", "America/New_York",
                                "expectedVersion", (persisted.getVersion() == null ? 0 : persisted.getVersion()) + 1,
                                "source", "mobile-offline-queue",
                                "attemptCount", 3,
                                "queueActionId", "qa-timezone-conflict-01"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("conflict"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")))
                .andExpect(jsonPath("$.timezone").value("Europe/London"))
                .andExpect(jsonPath("$.errorCode").value("USER_TIMEZONE_SYNC_CONFLICT"))
                .andExpect(jsonPath("$.conflictReason").value("STALE_EXPECTED_VERSION"));

        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getEventType())
                .isEqualTo("USER_TIMEZONE_SYNC_CONFLICT");
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getDetails())
                .contains("attempt=3")
                .contains("queueActionId=qa-timezone-conflict-01");
    }

    @Test
    void getCurrentDateFormatSnapshot_returnsProfileState() throws Exception {
        User saved = ensureUserExists("date-format-snapshot@example.com");
        saved.setDateFormat("yyyy-MM-dd");
        userRepository.save(saved);
        String token = issueToken(saved.getEmail());

        mockMvc.perform(get("/api/v1/users/me/date-format")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceKey").value("profile:date-format-snapshot@example.com"))
                .andExpect(jsonPath("$.dateFormat").value("yyyy-MM-dd"))
                .andExpect(jsonPath("$.version").isNumber());
    }

    @Test
    void updateCurrentDateFormat_returnsAckAndAudit() throws Exception {
        User saved = ensureUserExists("date-format-ok@example.com");
        saved.setDateFormat("dd.MM.yyyy");
        User persisted = userRepository.save(saved);
        String token = issueToken(persisted.getEmail());

        mockMvc.perform(put("/api/v1/users/me/date-format")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "dateFormat", "yyyy-MM-dd",
                                "expectedVersion", persisted.getVersion() == null ? 0 : persisted.getVersion(),
                                "source", "mobile-offline-queue",
                                "attemptCount", 2,
                                "queueActionId", "qa-date-format-success-01"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")))
                .andExpect(jsonPath("$.dateFormat").value("yyyy-MM-dd"))
                .andExpect(jsonPath("$.version").value((persisted.getVersion() == null ? 0 : persisted.getVersion()) + 1))
                .andExpect(jsonPath("$.errorCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.conflictReason").value(org.hamcrest.Matchers.nullValue()));

        User updated = userRepository.findById(persisted.getId()).orElseThrow();
        Assertions.assertThat(updated.getDateFormat()).isEqualTo("yyyy-MM-dd");
        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getEventType())
                .isEqualTo("USER_DATE_FORMAT_SYNCED");
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getDetails())
                .contains("attempt=2")
                .contains("queueActionId=qa-date-format-success-01");
    }

    @Test
    void updateCurrentDateFormat_versionConflict_returns409() throws Exception {
        User saved = ensureUserExists("date-format-conflict@example.com");
        saved.setDateFormat("MM/dd/yyyy");
        User persisted = userRepository.save(saved);
        String token = issueToken(persisted.getEmail());

        mockMvc.perform(put("/api/v1/users/me/date-format")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "dateFormat", "yyyy-MM-dd",
                                "expectedVersion", (persisted.getVersion() == null ? 0 : persisted.getVersion()) + 1,
                                "source", "mobile-offline-queue",
                                "attemptCount", 3,
                                "queueActionId", "qa-date-format-conflict-01"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("conflict"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")))
                .andExpect(jsonPath("$.dateFormat").value("MM/dd/yyyy"))
                .andExpect(jsonPath("$.errorCode").value("USER_DATE_FORMAT_SYNC_CONFLICT"))
                .andExpect(jsonPath("$.conflictReason").value("STALE_EXPECTED_VERSION"));

        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getEventType())
                .isEqualTo("USER_DATE_FORMAT_SYNC_CONFLICT");
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getDetails())
                .contains("attempt=3")
                .contains("queueActionId=qa-date-format-conflict-01");
    }

    @Test
    void getCurrentTimeFormatSnapshot_returnsProfileState() throws Exception {
        User saved = ensureUserExists("time-format-snapshot@example.com");
        saved.setTimeFormat("hh:mm a");
        userRepository.save(saved);
        String token = issueToken(saved.getEmail());

        mockMvc.perform(get("/api/v1/users/me/time-format")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceKey").value("profile:time-format-snapshot@example.com"))
                .andExpect(jsonPath("$.timeFormat").value("hh:mm a"))
                .andExpect(jsonPath("$.version").isNumber());
    }

    @Test
    void updateCurrentTimeFormat_returnsAckAndAudit() throws Exception {
        User saved = ensureUserExists("time-format-ok@example.com");
        saved.setTimeFormat("HH:mm");
        User persisted = userRepository.save(saved);
        String token = issueToken(persisted.getEmail());

        mockMvc.perform(put("/api/v1/users/me/time-format")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeFormat", "hh:mm a",
                                "expectedVersion", persisted.getVersion() == null ? 0 : persisted.getVersion(),
                                "source", "mobile-offline-queue",
                                "attemptCount", 2,
                                "queueActionId", "qa-time-format-success-01"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")))
                .andExpect(jsonPath("$.timeFormat").value("hh:mm a"))
                .andExpect(jsonPath("$.version").value((persisted.getVersion() == null ? 0 : persisted.getVersion()) + 1))
                .andExpect(jsonPath("$.errorCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.conflictReason").value(org.hamcrest.Matchers.nullValue()));

        User updated = userRepository.findById(persisted.getId()).orElseThrow();
        Assertions.assertThat(updated.getTimeFormat()).isEqualTo("hh:mm a");
        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getEventType())
                .isEqualTo("USER_TIME_FORMAT_SYNCED");
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getDetails())
                .contains("attempt=2")
                .contains("queueActionId=qa-time-format-success-01");
    }

    @Test
    void updateCurrentTimeFormat_versionConflict_returns409() throws Exception {
        User saved = ensureUserExists("time-format-conflict@example.com");
        saved.setTimeFormat("HH.mm");
        User persisted = userRepository.save(saved);
        String token = issueToken(persisted.getEmail());

        mockMvc.perform(put("/api/v1/users/me/time-format")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "timeFormat", "hh:mm a",
                                "expectedVersion", (persisted.getVersion() == null ? 0 : persisted.getVersion()) + 1,
                                "source", "mobile-offline-queue",
                                "attemptCount", 3,
                                "queueActionId", "qa-time-format-conflict-01"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("conflict"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")))
                .andExpect(jsonPath("$.timeFormat").value("HH.mm"))
                .andExpect(jsonPath("$.errorCode").value("USER_TIME_FORMAT_SYNC_CONFLICT"))
                .andExpect(jsonPath("$.conflictReason").value("STALE_EXPECTED_VERSION"));

        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getEventType())
                .isEqualTo("USER_TIME_FORMAT_SYNC_CONFLICT");
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getDetails())
                .contains("attempt=3")
                .contains("queueActionId=qa-time-format-conflict-01");
    }

    @Test
    void updateUser_recordsUserAuditEvent() throws Exception {
        User saved = ensureUserExists("mutate@example.com");
        saved.setRole("USER");
        saved.setSessionTimeoutMinutes(15);
        userRepository.save(saved);
        String token = issueToken(saved.getEmail());

        mockMvc.perform(put("/api/v1/users/{id}", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "role", "ADMIN",
                                "sessionTimeoutMinutes", 16
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.sessionTimeoutMinutes").value(16));

        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getDetails())
                .contains("role:USER->ADMIN")
                .contains("sessionTimeoutMinutes:15->16");
    }

    // ── Soft-delete (Codex 019ea573, #770 Phase 2 — UserActions "Sil") ──

    @Test
    void deleteUser_softDeletesTarget_excludesFromListAndGet() throws Exception {
        User admin = ensureUserExists("delete-admin@example.com");
        User target = ensureUserExists("delete-target@example.com");
        String token = issueToken(admin.getEmail());

        mockMvc.perform(delete("/api/v1/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")));

        // Tombstone set in DB.
        User reloaded = userRepository.findById(target.getId()).orElseThrow();
        Assertions.assertThat(reloaded.getDeletedAt()).isNotNull();
        Assertions.assertThat(reloaded.isDeleted()).isTrue();

        // INSERT-only USER_DELETE audit event recorded.
        Assertions.assertThat(userAuditEventRepository.findAll().stream()
                        .anyMatch(e -> "USER_DELETE".equals(e.getEventType())))
                .isTrue();

        // GET by id → 404 (active-only read).
        mockMvc.perform(get("/api/v1/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());

        // List excludes the tombstone.
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=100&dataSource=client")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        containsString("delete-target@example.com"))));
    }

    @Test
    void deleteUser_self_returns400_andDoesNotTombstone() throws Exception {
        User admin = ensureUserExists("self-delete@example.com");
        String token = issueToken(admin.getEmail());

        mockMvc.perform(delete("/api/v1/users/{id}", admin.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("CANNOT_DELETE_SELF")));

        Assertions.assertThat(userRepository.findById(admin.getId()).orElseThrow().isDeleted())
                .isFalse();
    }

    // NOTE: the missing-permission (403) path is intentionally NOT asserted
    // here. The MockMvc/H2 harness resolves an OpenFGA ScopeContext with
    // superAdmin=true, which short-circuits requirePermissionWithCompanyScope
    // before the permission check — so no controller-integration test in this
    // suite can negatively assert the gate (none of the existing mutation
    // tests do either). The DELETE endpoint uses the same
    // requirePermissionWithCompanyScope(USER_DELETE, companyId) gate that the
    // proven PUT /{id} (USER_UPDATE) endpoint uses; missing-permission denial
    // is covered by live E2E with a scoped (non-superadmin) token.

    @Test
    void deletedUser_cannotTransact_returns403_USER_DELETED() throws Exception {
        User admin = ensureUserExists("choke-admin@example.com");
        User victim = ensureUserExists("choke-victim@example.com");
        // Soft-delete the victim through the real endpoint.
        mockMvc.perform(delete("/api/v1/users/{id}", victim.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(admin.getEmail())))
                .andExpect(status().isOk());

        // The victim's own token is now refused at the resolver choke point —
        // the tombstone gate takes precedence and reports USER_DELETED.
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + issueToken(victim.getEmail())))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("USER_DELETED")));
    }

    @Test
    void restoreUser_clearsTombstone_userVisibleAgain() throws Exception {
        User admin = ensureUserExists("restore-admin@example.com");
        User target = ensureUserExists("restore-target@example.com");
        String token = issueToken(admin.getEmail());

        mockMvc.perform(delete("/api/v1/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/users/{id}/restore", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")));

        User reloaded = userRepository.findById(target.getId()).orElseThrow();
        Assertions.assertThat(reloaded.getDeletedAt()).isNull();
        Assertions.assertThat(reloaded.isDeleted()).isFalse();

        Assertions.assertThat(userAuditEventRepository.findAll().stream()
                        .anyMatch(e -> "USER_RESTORE".equals(e.getEventType())))
                .isTrue();

        // GET by id resolves again after restore.
        mockMvc.perform(get("/api/v1/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    /**
     * Codex 019ea6f6 iter-2 regression guard: create/provision stores canonical
     * lowercase email; the active by-email lookup is ignore-case, so a
     * mixed-case query resolves the row (no authn/read regression). After
     * soft-delete the same mixed-case lookup is 404 (active-only).
     */
    @Test
    void getUserByEmail_mixedCaseLookup_resolvesActiveThenTombstone404() throws Exception {
        User caller = ensureUserExists("mixedcase-admin@example.com");
        User target = ensureUserExists("mixedlogin@example.com"); // stored lowercase
        String token = issueToken(caller.getEmail());

        mockMvc.perform(get("/api/v1/users/by-email")
                        .param("email", "MixedLogin@Example.COM")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mixedlogin@example.com")));

        mockMvc.perform(delete("/api/v1/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/by-email")
                        .param("email", "MixedLogin@Example.COM")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void restoreUser_notDeleted_returns409() throws Exception {
        User admin = ensureUserExists("restore-noop-admin@example.com");
        User target = ensureUserExists("restore-noop-target@example.com");
        String token = issueToken(admin.getEmail());

        mockMvc.perform(post("/api/v1/users/{id}/restore", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("USER_NOT_DELETED")));
    }

    private User ensureUserExists(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User();
            user.setEmail(email);
            user.setName(email);
            user.setPassword("x");
            user.setEnabled(true);
            user.setRole("ADMIN");
            return userRepository.save(user);
        });
    }

    private String issueToken(String email) {
        return issueToken(email, List.of("user-service"));
    }

    private String issueToken(String email, List<String> audience) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(email)
                .issuer("auth-service")
                .audience(audience == null || audience.isEmpty() ? Collections.singletonList("user-service") : audience)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", email)
                .claim("permissions", List.of("VIEW_USERS", "MANAGE_USERS"))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /**
     * Issues a token simulating an M365 first-login: allow-listed issuer
     * ({@code platform-test}), the {@code entra_tid} marker claim and a
     * deterministic {@code sub} derived from the email local-part
     * ({@code kc-sub-<local-part>}). No numeric {@code userId} claim — this
     * is a brand-new identity with no backend profile.
     */
    private String issueM365Token(String email, String entraTid) {
        return issueTokenWithIssuer(email, "platform-test", entraTid, Boolean.TRUE);
    }

    /**
     * Issues a token with an explicit issuer and optional {@code entra_tid}
     * / {@code email_verified} claims, used to exercise the auto-provision
     * gate. {@code sub} is {@code kc-sub-<email-local-part>}.
     *
     * @param email         the email / preferred_username claim value
     * @param issuer        the JWT {@code iss}
     * @param entraTid      the M365 marker claim; omitted when null
     * @param emailVerified the {@code email_verified} claim; omitted when null
     */
    private String issueTokenWithIssuer(String email, String issuer, String entraTid, Boolean emailVerified) {
        Instant now = Instant.now();
        String localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .subject("kc-sub-" + localPart.toLowerCase())
                .issuer(issuer)
                .audience(List.of("user-service"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", email)
                .claim("permissions", List.of("VIEW_USERS", "MANAGE_USERS"));
        if (entraTid != null) {
            builder.claim("entra_tid", entraTid);
        }
        if (emailVerified != null) {
            builder.claim("email_verified", emailVerified);
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(builder.build())).getTokenValue();
    }
}
