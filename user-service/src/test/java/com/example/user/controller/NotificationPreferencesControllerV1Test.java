package com.example.user.controller;

import com.example.user.UserApplication;
import com.example.user.config.TestSecurityConfig;
import com.example.user.model.User;
import com.example.user.model.UserNotificationPreference;
import com.example.user.repository.UserAuditEventRepository;
import com.example.user.repository.UserNotificationPreferenceRepository;
import com.example.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
class NotificationPreferencesControllerV1Test {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserNotificationPreferenceRepository userNotificationPreferenceRepository;

    @Autowired
    private UserAuditEventRepository userAuditEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDb() {
        userAuditEventRepository.deleteAll();
        userNotificationPreferenceRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void getPreferences_invalidToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Keycloak user lazy-provision bridge: an M365 first-login (allow-listed
     * issuer + {@code entra_tid} marker) with no backend profile is now
     * auto-provisioned and the preferences request proceeds (returns the
     * default channel set), instead of the old {@code 403 PROFILE_MISSING}.
     * Previously {@code getPreferences_missingLocalProfile_returns403}.
     */
    @Test
    void getPreferences_m365FirstLogin_autoProvisionsAndReturnsDefaults() throws Exception {
        String email = "m365-prefs@example.com";
        org.assertj.core.api.Assertions.assertThat(userRepository.findByEmail(email)).isEmpty();

        String token = issueM365Token(email, "66666666-6666-6666-6666-666666666666");
        mockMvc.perform(get("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences", hasSize(4)));

        User provisioned = userRepository.findByEmail(email).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(provisioned.getRole()).isEqualTo("USER");
        org.assertj.core.api.Assertions.assertThat(provisioned.isEnabled()).isTrue();
    }

    /**
     * Gate fail-closed: a JWT whose issuer is NOT on the auto-provision
     * allowlist (the default {@code issueToken(...)} uses {@code auth-service})
     * still yields {@code 403 PROFILE_MISSING} when no profile exists.
     */
    @Test
    void getPreferences_missingProfile_disallowedIssuer_returns403() throws Exception {
        String token = issueToken("missing@example.com");
        mockMvc.perform(get("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("PROFILE_MISSING")));
    }

    @Test
    void getPreferences_existingProfile_returnsDefaults() throws Exception {
        User existing = ensureUserExists("user@example.com");
        String token = issueToken(existing.getEmail());
        mockMvc.perform(get("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences", hasSize(4)))
                .andExpect(jsonPath("$.preferences[0].channel").isNotEmpty())
                .andExpect(jsonPath("$.preferences[0].version").isNumber());
    }

    @Test
    void getPreferenceSnapshot_returnsChannelState() throws Exception {
        User existing = ensureUserExists("snapshot@example.com");
        String token = issueToken(existing.getEmail());

        mockMvc.perform(get("/api/v1/notification-preferences/email")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceKey").value("notification-preference:snapshot@example.com:email"))
                .andExpect(jsonPath("$.channel").value("email"))
                .andExpect(jsonPath("$.enabled").isBoolean())
                .andExpect(jsonPath("$.version").isNumber());
    }

    @Test
    void patchPreferences_unknownChannel_returns400() throws Exception {
        User existing = ensureUserExists("user@example.com");
        String token = issueToken(existing.getEmail());
        Map<String, Object> body = Map.of(
                "preferences", List.of(Map.of("channel", "fax", "enabled", true))
        );
        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("unknown_channel")));
    }

    @Test
    void patchPreferences_updatesAndReturnsPreferences() throws Exception {
        User existing = ensureUserExists("user@example.com");
        String token = issueToken(existing.getEmail());
        Map<String, Object> body = Map.of(
                "preferences", List.of(
                        Map.of("channel", "email", "enabled", false),
                        Map.of("channel", "in_app", "enabled", true, "frequency", "daily")
                )
        );
        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences", hasSize(4)))
                .andExpect(content().string(containsString("\"channel\":\"email\"")))
                .andExpect(content().string(containsString("\"enabled\":false")))
                .andExpect(content().string(containsString("\"version\":")));
    }

    @Test
    void patchPreferences_invalidJson_returns400() throws Exception {
        User existing = ensureUserExists("user@example.com");
        String token = issueToken(existing.getEmail());
        mockMvc.perform(patch("/api/v1/notification-preferences")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("ERR_BAD_REQUEST")));
    }

    @Test
    void putPreference_updatesAndReturnsAckWithAudit() throws Exception {
        User existing = ensureUserExists("sync@example.com");
        UserNotificationPreference existingPreference = userNotificationPreferenceRepository.save(emailPreference(existing.getId(), true, "immediate"));
        String token = issueToken(existing.getEmail());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/notification-preferences/email")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", false,
                                "frequency", "daily",
                                "expectedVersion", existingPreference.getVersion() == null ? 0 : existingPreference.getVersion(),
                                "source", "mobile-offline-queue",
                                "attemptCount", 2,
                                "queueActionId", "qa-notification-success-01"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")))
                .andExpect(jsonPath("$.channel").value("email"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.frequency").value("daily"))
                .andExpect(jsonPath("$.errorCode").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.conflictReason").value(org.hamcrest.Matchers.nullValue()));

        UserNotificationPreference updated = userNotificationPreferenceRepository
                .findByUserIdAndChannel(existing.getId(), "email")
                .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(updated.isEnabled()).isFalse();
        org.assertj.core.api.Assertions.assertThat(updated.getFrequency()).isEqualTo("daily");
        org.assertj.core.api.Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getEventType())
                .isEqualTo("USER_NOTIFICATION_PREFERENCE_SYNCED");
    }

    @Test
    void putPreference_versionConflict_returns409() throws Exception {
        User existing = ensureUserExists("sync-conflict@example.com");
        UserNotificationPreference existingPreference = userNotificationPreferenceRepository.save(emailPreference(existing.getId(), true, "immediate"));
        String token = issueToken(existing.getEmail());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/notification-preferences/email")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "enabled", false,
                                "frequency", "daily",
                                "expectedVersion", (existingPreference.getVersion() == null ? 0 : existingPreference.getVersion()) + 1,
                                "source", "mobile-offline-queue",
                                "attemptCount", 3,
                                "queueActionId", "qa-notification-conflict-01"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("conflict"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")))
                .andExpect(jsonPath("$.channel").value("email"))
                .andExpect(jsonPath("$.errorCode").value("USER_NOTIFICATION_PREFERENCE_SYNC_CONFLICT"))
                .andExpect(jsonPath("$.conflictReason").value("STALE_EXPECTED_VERSION"));

        org.assertj.core.api.Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(userAuditEventRepository.findAll().getFirst().getEventType())
                .isEqualTo("USER_NOTIFICATION_PREFERENCE_SYNC_CONFLICT");
    }

    private User ensureUserExists(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User();
            user.setEmail(email);
            user.setName(email);
            user.setPassword("x");
            user.setEnabled(true);
            user.setRole("USER");
            return userRepository.save(user);
        });
    }

    private UserNotificationPreference emailPreference(Long userId, boolean enabled, String frequency) {
        UserNotificationPreference preference = new UserNotificationPreference();
        preference.setUserId(userId);
        preference.setChannel("email");
        preference.setEnabled(enabled);
        preference.setFrequency(frequency);
        return preference;
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
     * ({@code platform-test}), the {@code entra_tid} marker claim,
     * {@code email_verified=true} and a deterministic {@code sub}
     * ({@code kc-sub-<email-local-part>}). No backend profile is created
     * up front — the auto-provision bridge is expected to create it.
     */
    private String issueM365Token(String email, String entraTid) {
        Instant now = Instant.now();
        String localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("kc-sub-" + localPart.toLowerCase())
                .issuer("platform-test")
                .audience(List.of("user-service"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .claim("email", email)
                .claim("entra_tid", entraTid)
                .claim("email_verified", Boolean.TRUE)
                .claim("permissions", List.of("VIEW_USERS", "MANAGE_USERS"))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
