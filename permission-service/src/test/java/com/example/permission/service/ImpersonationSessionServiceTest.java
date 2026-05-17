package com.example.permission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.permission.audit.ImpersonationAuditWriter;
import com.example.permission.model.ImpersonationSession;
import com.example.permission.repository.ImpersonationSessionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ImpersonationSessionService}.
 *
 * <p>Regression guard for the admin force-revoke HTTP 500: the
 * {@code impersonation_sessions.ended_reason} column is {@code VARCHAR(50)}
 * (a short-code field). The revoke path used to write the operator's
 * free-text reason straight into it, so a reason longer than 50 chars
 * overflowed the column → {@code DataIntegrityViolationException} → HTTP 500
 * instead of completing the revoke. The fix persists the fixed short code
 * {@code ADMIN_REVOKE} and keeps the free-text reason in the audit event.
 */
class ImpersonationSessionServiceTest {

    private final ImpersonationSessionRepository sessionRepository =
            mock(ImpersonationSessionRepository.class);
    private final ImpersonationAuditWriter auditWriter =
            mock(ImpersonationAuditWriter.class);
    private final ImpersonationSessionService service =
            new ImpersonationSessionService(sessionRepository, auditWriter);

    @Test
    void revokeSession_writesShortCodeToEndedReason_notFreeTextReason() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(sampleSession(sessionId)));
        when(sessionRepository.stopSession(eq(sessionId), any(Instant.class), any()))
                .thenReturn(1);

        // Operator free-text reason far longer than ended_reason VARCHAR(50).
        String longReason =
                "Incident response: compromised actor session force-terminated by on-call superadmin";
        assertThat(longReason.length()).isGreaterThan(50);

        boolean revoked = service.revokeSession(
                sessionId, longReason, 999L, "op-sub", "op@example.com", "corr-1");

        assertThat(revoked).isTrue();
        // ended_reason (3rd stopSession arg) must be the fixed short code —
        // never the operator's free-text reason (which would overflow VARCHAR(50)).
        verify(sessionRepository).stopSession(eq(sessionId), any(Instant.class), eq("ADMIN_REVOKE"));
        // The operator's full free-text reason is still preserved by the
        // IMPERSONATION_REVOKED audit event.
        verify(auditWriter).writeRevokedByOperator(
                any(), eq("corr-1"), eq(longReason), eq(999L), eq("op-sub"), eq("op@example.com"));
    }

    private ImpersonationSession sampleSession(UUID id) {
        ImpersonationSession s = new ImpersonationSession();
        s.setId(id);
        s.setImpersonatorUserId(1L);
        s.setImpersonatorSubject("admin-sub");
        s.setImpersonatorEmail("admin@example.com");
        s.setTargetUserId(2L);
        s.setTargetSubject("target-sub");
        s.setTargetEmail("target@example.com");
        s.setReason("start reason");
        s.setJti("jti-" + id);
        return s;
    }
}
