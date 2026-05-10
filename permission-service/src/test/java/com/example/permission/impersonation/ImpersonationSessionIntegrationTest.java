package com.example.permission.impersonation;

import com.example.permission.audit.ImpersonationAuditEventTypes;
import com.example.permission.audit.ImpersonationAuditWriter;
import com.example.permission.model.ImpersonationSession;
import com.example.permission.model.PermissionAuditEvent;
import com.example.permission.repository.ImpersonationSessionRepository;
import com.example.permission.repository.PermissionAuditEventRepository;
import com.example.permission.service.ImpersonationSessionService;
import com.example.permission.service.ImpersonationSessionService.ActiveSessionExistsException;
import com.example.permission.service.ImpersonationSessionService.ImpersonationConstraintException;
import com.example.permission.service.ImpersonationSessionService.StartSessionRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * User Impersonation v1 — V19 schema + service integration test.
 *
 * <p>Codex 019e0dfb iter-27 absorb Step 2f: Hibernate {@code validate}
 * + JPA round-trip + DB constraint enforcement against a real PostgreSQL
 * (V19 INET, TIMESTAMPTZ, partial UNIQUE indexes, CHECK constraints).
 *
 * <p>Surface:
 * <ul>
 *   <li>Hibernate validates {@link ImpersonationSession} +
 *       {@link PermissionAuditEvent} V19 columns against the live schema.</li>
 *   <li>{@link ImpersonationSessionService#startSession} happy path:
 *       session row + audit row IMPERSONATION_STARTED both committed.</li>
 *   <li>Single-active-session policy: second startSession for same
 *       impersonator throws {@link ActiveSessionExistsException}
 *       (ux_impersonation_sessions_one_active_per_impersonator).</li>
 *   <li>no_self_subject CHECK: impersonator subject == target subject
 *       rejected.</li>
 *   <li>{@link ImpersonationSessionService#stopSession} marks STOPPED
 *       + writes IMPERSONATION_STOPPED audit row.</li>
 *   <li>{@link ImpersonationSessionService#sweepExpired} flips expired
 *       ACTIVE rows to EXPIRED.</li>
 *   <li>{@link ImpersonationAuditWriter#writeBlocked} fills all V19
 *       impersonation context columns including null sessionId.</li>
 * </ul>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("impersonation-it")
@Tag("integration")
class ImpersonationSessionIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("permdb_imp_it")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void primaryDsProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ImpersonationSessionService sessionService;

    @Autowired
    private ImpersonationSessionRepository sessionRepository;

    @Autowired
    private PermissionAuditEventRepository auditRepository;

    @Autowired
    private ImpersonationAuditWriter auditWriter;

    @PersistenceContext
    private EntityManager em;

    @Test
    void contextLoads_hibernateValidatesV19Schema() {
        assertThat(sessionService).isNotNull();
        assertThat(sessionRepository).isNotNull();
        assertThat(auditRepository).isNotNull();
        assertThat(em).isNotNull();
    }

    @Test
    void startSession_happyPath_persistsSessionAndStartedAuditRow() {
        long auditBefore = auditRepository.count();
        StartSessionRequest req = sampleRequest("admin-sub-1", "target-sub-1", 60);

        ImpersonationSession saved = sessionService.startSession(req);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(ImpersonationSession.Status.ACTIVE);
        assertThat(saved.getJti()).isEqualTo(req.jti());
        assertThat(sessionRepository.findById(saved.getId())).isPresent();

        // V19 audit row joined via session id
        long auditAfter = auditRepository.count();
        assertThat(auditAfter)
                .as("startSession must produce IMPERSONATION_STARTED audit row")
                .isEqualTo(auditBefore + 1);
        Optional<PermissionAuditEvent> auditRow = auditRepository.findAll().stream()
                .filter(e -> ImpersonationAuditEventTypes.IMPERSONATION_STARTED.equals(e.getEventType()))
                .filter(e -> saved.getId().equals(e.getImpersonationSessionId()))
                .findFirst();
        assertThat(auditRow).as("STARTED row keyed by sessionId must be present").isPresent();
        PermissionAuditEvent ev = auditRow.get();
        assertThat(ev.isImpersonated()).isTrue();
        assertThat(ev.getImpersonatorUserId()).isEqualTo(req.impersonatorUserId());
        assertThat(ev.getImpersonatorSubject()).isEqualTo(req.impersonatorSubject());
        assertThat(ev.getTargetUserId()).isEqualTo(req.targetUserId());
        assertThat(ev.getTargetSubject()).isEqualTo(req.targetSubject());
        assertThat(ev.getImpersonationReason()).isEqualTo(req.reason());
    }

    @Test
    void startSession_secondActive_throwsActiveSessionExists() {
        StartSessionRequest first = sampleRequest("admin-sub-2", "target-sub-2a", 60);
        sessionService.startSession(first);

        StartSessionRequest second = new StartSessionRequest(
                first.impersonatorUserId(),
                first.impersonatorSubject(),
                first.impersonatorEmail(),
                999L,
                "target-sub-2b",
                "target2b@example.com",
                first.issuer(),
                "jti-second-" + UUID.randomUUID(),
                "sid-second",
                "second start attempt should fail",
                Instant.now().plus(60, ChronoUnit.MINUTES),
                null, null, null);

        assertThatThrownBy(() -> sessionService.startSession(second))
                .isInstanceOf(ActiveSessionExistsException.class);
    }

    @Test
    void startSession_subjectSelfImpersonation_rejectedByCheckConstraint() {
        StartSessionRequest req = new StartSessionRequest(
                100L, "admin-sub-self", "admin@example.com",
                100L, "admin-sub-self", "admin@example.com",
                "https://issuer.example/realm",
                "jti-self-" + UUID.randomUUID(),
                "sid-self",
                "self impersonation should be rejected",
                Instant.now().plus(60, ChronoUnit.MINUTES),
                null, null, null);

        assertThatThrownBy(() -> sessionService.startSession(req))
                .isInstanceOfAny(
                        ImpersonationConstraintException.class,
                        DataIntegrityViolationException.class);
    }

    @Test
    void stopSession_marksStoppedAndWritesAudit() {
        StartSessionRequest req = sampleRequest("admin-sub-3", "target-sub-3", 60);
        ImpersonationSession saved = sessionService.startSession(req);

        long auditBefore = auditRepository.count();
        boolean stopped = sessionService.stopSession(saved.getId(), "USER_STOP");

        assertThat(stopped).isTrue();
        Optional<ImpersonationSession> reloaded = sessionRepository.findById(saved.getId());
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getStatus()).isEqualTo(ImpersonationSession.Status.STOPPED);
        assertThat(reloaded.get().getEndedAt()).isNotNull();

        long auditAfter = auditRepository.count();
        assertThat(auditAfter).isEqualTo(auditBefore + 1);
        Optional<PermissionAuditEvent> stopRow = auditRepository.findAll().stream()
                .filter(e -> ImpersonationAuditEventTypes.IMPERSONATION_STOPPED.equals(e.getEventType()))
                .filter(e -> saved.getId().equals(e.getImpersonationSessionId()))
                .findFirst();
        assertThat(stopRow).as("STOPPED row keyed by sessionId must be present").isPresent();
    }

    @Test
    void sweepExpired_marksExpiredButStillActiveRowsAsExpired() {
        // Use direct repository save to bypass service-level sweep guard
        // and create an ALREADY-expired row.
        ImpersonationSession s = new ImpersonationSession();
        s.setImpersonatorUserId(200L);
        s.setImpersonatorSubject("admin-sub-sweep");
        s.setImpersonatorEmail("sweep-admin@example.com");
        s.setTargetUserId(201L);
        s.setTargetSubject("target-sub-sweep");
        s.setTargetEmail("sweep-target@example.com");
        s.setIssuer("https://issuer.example/realm");
        s.setJti("jti-sweep-" + UUID.randomUUID());
        s.setSid("sid-sweep");
        s.setReason("sweep test seed row");
        s.setStartedAt(Instant.now().minus(2, ChronoUnit.HOURS));
        s.setExpiresAt(Instant.now().minus(30, ChronoUnit.MINUTES));
        s.setStatus(ImpersonationSession.Status.ACTIVE);
        ImpersonationSession seeded = sessionRepository.save(s);

        int swept = sessionService.sweepExpired();
        assertThat(swept).isGreaterThanOrEqualTo(1);

        ImpersonationSession reloaded = sessionRepository.findById(seeded.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ImpersonationSession.Status.EXPIRED);
        assertThat(reloaded.getEndedReason()).isEqualTo("SYSTEM_SWEEP");
    }

    @Test
    void auditWriter_writeBlocked_fillsV19ColumnsWithoutSessionId() {
        ImpersonationAuditWriter.ImpersonationAuditContext ctx =
                new ImpersonationAuditWriter.ImpersonationAuditContext(
                        null,
                        300L,
                        "admin-sub-blocked",
                        "blocked-admin@example.com",
                        301L,
                        "target-sub-blocked",
                        "blocked-target@example.com",
                        "blocked test reason");

        long before = auditRepository.count();
        auditWriter.writeBlocked(ctx, "corr-blocked", "INSUFFICIENT_AUTHORITY", "INSUFFICIENT_AUTHORITY");
        assertThat(auditRepository.count()).isEqualTo(before + 1);

        Optional<PermissionAuditEvent> blockedRow = auditRepository.findAll().stream()
                .filter(e -> ImpersonationAuditEventTypes.IMPERSONATION_BLOCKED.equals(e.getEventType()))
                .filter(e -> "corr-blocked".equals(e.getCorrelationId()))
                .findFirst();
        assertThat(blockedRow).isPresent();
        PermissionAuditEvent ev = blockedRow.get();
        assertThat(ev.getImpersonationSessionId()).isNull();
        assertThat(ev.isImpersonated()).isTrue();
        assertThat(ev.getImpersonatorUserId()).isEqualTo(300L);
        assertThat(ev.getImpersonatorSubject()).isEqualTo("admin-sub-blocked");
        assertThat(ev.getTargetUserId()).isEqualTo(301L);
        assertThat(ev.getTargetSubject()).isEqualTo("target-sub-blocked");
        assertThat(ev.getImpersonationReason()).isEqualTo("blocked test reason");
        assertThat(ev.getLevel()).isEqualTo("WARN");
    }

    private StartSessionRequest sampleRequest(String adminSub, String targetSub, int ttlMinutes) {
        long uid = Math.abs(UUID.randomUUID().hashCode());
        return new StartSessionRequest(
                uid,
                adminSub,
                adminSub + "@example.com",
                uid + 1,
                targetSub,
                targetSub + "@example.com",
                "https://issuer.example/realm",
                "jti-" + UUID.randomUUID(),
                "sid-" + UUID.randomUUID(),
                "happy path integration test",
                Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES),
                null,
                "Mozilla/5.0 it-test",
                null);
    }
}
