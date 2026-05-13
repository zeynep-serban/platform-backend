package com.example.auth.impersonation;

import com.example.auth.impersonation.ImpersonationController.StartResponse;
import com.example.auth.impersonation.ImpersonationController.StartSessionRequest;
import com.example.auth.user.RemoteUserResponse;
import com.example.auth.user.UserServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Codex thread {@code 019e1bed} REVISE-3 absorb — unit coverage for the
 * impersonation authority boundary checks added in this PR. Spring Boot
 * integration tests for the full token-exchange + session-create path
 * live elsewhere; this file pins the three branches Codex called out:
 *
 *   - self-impersonation rejected BEFORE user-service resolution
 *   - target subject resolved from user-service when omitted
 *   - missing kc_subject yields TARGET_SUBJECT_UNRESOLVABLE (422)
 */
@ExtendWith(MockitoExtension.class)
class ImpersonationControllerSelfGuardTest {

    private static final String BROKER_AZP = "impersonation-broker";
    private static final String ADMIN_SUBJECT = "admin-kc-uuid";
    private static final String TARGET_SUBJECT = "target-kc-uuid";

    @Mock
    private KeycloakBrokerClient brokerClient;

    @Mock
    private ImpersonationSessionClient sessionClient;

    @Mock
    private SuperAdminAuthority superAdminAuthority;

    @Mock
    private ImpersonationAuditClient auditClient;

    @Mock
    private UserServiceClient userServiceClient;

    @Mock
    private HttpServletRequest httpRequest;

    private ImpersonationController controller;

    @BeforeEach
    void setUp() {
        controller = new ImpersonationController(
                brokerClient, sessionClient, superAdminAuthority,
                auditClient, userServiceClient, BROKER_AZP);
    }

    private Jwt adminJwt(Long userIdClaim) {
        Map<String, Object> headers = Map.of("alg", "RS256");
        Map<String, Object> claims = Map.of(
                "sub", ADMIN_SUBJECT,
                "userId", userIdClaim,
                "email", "admin@example.com",
                "azp", "frontend"
        );
        return new Jwt(
                "fake-token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                headers,
                claims);
    }

    @Test
    void rejectsSelfImpersonationBeforeResolution() {
        Jwt adminJwt = adminJwt(1L);
        StartSessionRequest request = new StartSessionRequest(
                1L, null, "admin@example.com", "Codex 019e1bed self-guard regression test");

        ResponseEntity<StartResponse> response = controller.startSession(request, adminJwt, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("SELF_IMPERSONATION_FORBIDDEN");
        // Critical assertion: user-service is NEVER called for self-target —
        // the id-equality short-circuit fires before subject resolution.
        verify(userServiceClient, never()).findUserById(anyLong());
        // No KC token-exchange, no session persist either.
        verify(brokerClient, never()).exchange(any(), any());
        verify(sessionClient, never()).startSession(any());
        // Audit BLOCKED row must be written so compliance trails see the attempt.
        ArgumentCaptor<ImpersonationAuditClient.AuditPayload> captor =
                ArgumentCaptor.forClass(ImpersonationAuditClient.AuditPayload.class);
        verify(auditClient).writeBlocked(captor.capture());
        assertThat(captor.getValue().errorCode()).isEqualTo("SELF_IMPERSONATION_FORBIDDEN");
        assertThat(captor.getValue().impersonatorUserId()).isEqualTo(1L);
        assertThat(captor.getValue().targetUserId()).isEqualTo(1L);
        assertThat(captor.getValue().targetEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void rejectsUnresolvableTargetWith422() {
        Jwt adminJwt = adminJwt(1L);
        StartSessionRequest request = new StartSessionRequest(
                42L, null, "ghost@example.com", "Codex 019e1bed unresolvable target regression test");
        // user-service responds but kcSubject is null (pre-V16 backfill).
        RemoteUserResponse stub = new RemoteUserResponse();
        stub.setId(42L);
        stub.setEmail("ghost@example.com");
        stub.setKcSubject(null);
        stub.setEnabled(true);
        when(userServiceClient.findUserById(42L)).thenReturn(Optional.of(stub));

        ResponseEntity<StartResponse> response = controller.startSession(request, adminJwt, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("TARGET_SUBJECT_UNRESOLVABLE");
        verify(brokerClient, never()).exchange(any(), any());
        verify(sessionClient, never()).startSession(any());
        ArgumentCaptor<ImpersonationAuditClient.AuditPayload> captor =
                ArgumentCaptor.forClass(ImpersonationAuditClient.AuditPayload.class);
        verify(auditClient).writeBlocked(captor.capture());
        assertThat(captor.getValue().errorCode()).isEqualTo("TARGET_SUBJECT_UNRESOLVABLE");
    }

    @Test
    void rejectsUnresolvableTargetWhenUserServiceReturnsEmpty() {
        Jwt adminJwt = adminJwt(1L);
        StartSessionRequest request = new StartSessionRequest(
                999L, null, "missing@example.com", "Codex 019e1bed user-service 404 regression test");
        when(userServiceClient.findUserById(999L)).thenReturn(Optional.empty());

        ResponseEntity<StartResponse> response = controller.startSession(request, adminJwt, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().errorCode()).isEqualTo("TARGET_SUBJECT_UNRESOLVABLE");
        verify(brokerClient, never()).exchange(any(), any());
        ArgumentCaptor<ImpersonationAuditClient.AuditPayload> captor =
                ArgumentCaptor.forClass(ImpersonationAuditClient.AuditPayload.class);
        verify(auditClient).writeBlocked(captor.capture());
        assertThat(captor.getValue().errorCode()).isEqualTo("TARGET_SUBJECT_UNRESOLVABLE");
        assertThat(captor.getValue().targetEmail()).isEqualTo("missing@example.com");
    }

    @Test
    void rejectsDisabledTargetWith403() {
        Jwt adminJwt = adminJwt(1L);
        StartSessionRequest request = new StartSessionRequest(
                77L, null, "blocked@example.com", "Codex 019e1bed disabled-target regression test");
        // user-service returns a disabled platform user with valid kcSubject.
        // Backend must reject BEFORE KC token-exchange.
        RemoteUserResponse stub = new RemoteUserResponse();
        stub.setId(77L);
        stub.setEmail("blocked@example.com");
        stub.setKcSubject("blocked-kc-uuid");
        stub.setEnabled(false);
        when(userServiceClient.findUserById(77L)).thenReturn(Optional.of(stub));

        ResponseEntity<StartResponse> response = controller.startSession(request, adminJwt, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("TARGET_USER_DISABLED");
        // Policy reject — token-exchange + session create skipped.
        verify(brokerClient, never()).exchange(any(), any());
        verify(sessionClient, never()).startSession(any());
        ArgumentCaptor<ImpersonationAuditClient.AuditPayload> captor =
                ArgumentCaptor.forClass(ImpersonationAuditClient.AuditPayload.class);
        verify(auditClient).writeBlocked(captor.capture());
        assertThat(captor.getValue().errorCode()).isEqualTo("TARGET_USER_DISABLED");
    }

    @Test
    void rejectsSubjectEqualitySelfImpersonation() {
        Jwt adminJwt = adminJwt(1L);
        // Different platform id, but user-service returns the admin's own KC
        // subject — the post-resolution self-guard catches it.
        StartSessionRequest request = new StartSessionRequest(
                42L, null, "alias@example.com", "Codex 019e1bed subject-equality self-guard regression test");
        RemoteUserResponse stub = new RemoteUserResponse();
        stub.setId(42L);
        stub.setEmail("alias@example.com");
        stub.setKcSubject(ADMIN_SUBJECT);
        stub.setEnabled(true);
        when(userServiceClient.findUserById(42L)).thenReturn(Optional.of(stub));

        ResponseEntity<StartResponse> response = controller.startSession(request, adminJwt, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().errorCode()).isEqualTo("SELF_IMPERSONATION_FORBIDDEN");
        verify(brokerClient, never()).exchange(any(), any());
    }
}
