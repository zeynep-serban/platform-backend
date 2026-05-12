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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void listUsers_missingLocalProfile_returns403() throws Exception {
        String token = issueToken("missing@example.com");
        mockMvc.perform(get("/api/v1/users?page=1&pageSize=10")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("PROFILE_MISSING")));
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
     * Codex 019e1bed REVISE-7 hotfix-3 regression: the V1 detail payload
     * MUST carry {@code kcSubject} so auth-service backend-authoritative
     * impersonation target resolution can read it. The previous REVISE-5
     * hotfix only fixed the legacy {@code UserResponse} (mapped by the
     * old {@code /api/users/{id}} controller); live testai smoke proved
     * {@code GET /api/v1/users/{id}} returns {@code UserDetailDto}, which
     * was missing the field — every impersonation start failed with
     * {@code TARGET_SUBJECT_UNRESOLVABLE} (422) even with a valid backfill.
     */
    @Test
    void getUser_v1_detailExposesKcSubject() throws Exception {
        User saved = ensureUserExists("kcsubject-detail@example.com");
        saved.setKcSubject("11111111-2222-3333-4444-555555555555");
        userRepository.save(saved);
        String token = issueToken(saved.getEmail());

        mockMvc.perform(get("/api/v1/users/{id}", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"kcSubject\":\"11111111-2222-3333-4444-555555555555\"")));
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
        User saved = ensureUserExists("activate@example.com");
        saved.setEnabled(false);
        userRepository.save(saved);
        String token = issueToken(saved.getEmail());

        mockMvc.perform(put("/api/v1/users/{id}/activation", saved.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("active", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.auditId").value(org.hamcrest.Matchers.startsWith("user-")));

        User updated = userRepository.findById(saved.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(updated.isEnabled()).isTrue();
        Assertions.assertThat(userAuditEventRepository.count()).isEqualTo(1);
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
}
