package com.example.auth.impersonation;

import com.example.auth.impersonation.ImpersonationSessionClient.ActiveSessionExistsException;
import com.example.auth.impersonation.ImpersonationSessionClient.SessionResource;
import com.example.auth.impersonation.ImpersonationSessionClient.StartRequest;
import com.example.auth.impersonation.KeycloakBrokerClient.DecodedClaims;
import com.example.auth.impersonation.KeycloakBrokerClient.ExchangeResult;
import com.example.auth.impersonation.KeycloakBrokerClient.TokenExchangeException;
import com.example.auth.user.RemoteUserResponse;
import com.example.auth.user.UserServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * User Impersonation v1 — auth-service public broker controller.
 *
 * <p>Codex 019e0dfb iter-26 mimari split:
 * <ul>
 *   <li>This controller = identity broker (public endpoint, KC token exchange)</li>
 *   <li>permission-service = session/audit authority (DB write via internal API)</li>
 * </ul>
 *
 * <p>Critical flow contract (Codex iter-19/26):
 * <ol>
 *   <li>SuperAdmin authentication (PreAuthorize)</li>
 *   <li>Nested impersonation reject — admin token azp must NOT be broker</li>
 *   <li>Reason validation (min 10 chars)</li>
 *   <li>KC token exchange (subject_token=admin, requested_subject=target)</li>
 *   <li>Decode exchanged token: iss/jti/sid/sub/exp</li>
 *   <li>permission-service internal session create (DB authority)</li>
 *   <li>**Token frontend'e ANCAK DB insert commit sonrası döner**</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/impersonation/sessions")
public class ImpersonationController {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationController.class);
    /**
     * Codex iter-27 P1 absorb: hard-coded yerine config (extractor ile aynı
     * property kullanılır).
     */
    private final String brokerAzp;
    private final KeycloakBrokerClient brokerClient;
    private final ImpersonationSessionClient sessionClient;
    private final SuperAdminAuthority superAdminAuthority;
    private final ImpersonationAuditClient auditClient;
    /**
     * Codex {@code 019e1bed} AGREE — user-service is the authoritative
     * source for the Keycloak subject (UUID) of a target user. Resolving
     * it server-side from {@code targetUserId} removes the need for the
     * admin UI to ask operators for the KC UUID.
     */
    private final UserServiceClient userServiceClient;

    public ImpersonationController(
            KeycloakBrokerClient brokerClient,
            ImpersonationSessionClient sessionClient,
            SuperAdminAuthority superAdminAuthority,
            ImpersonationAuditClient auditClient,
            UserServiceClient userServiceClient,
            @org.springframework.beans.factory.annotation.Value("${auth.impersonation.broker-client-id:impersonation-broker}") String brokerAzp) {
        this.brokerClient = brokerClient;
        this.sessionClient = sessionClient;
        this.superAdminAuthority = superAdminAuthority;
        this.auditClient = auditClient;
        this.userServiceClient = userServiceClient;
        this.brokerAzp = brokerAzp;
    }

    /**
     * POST /api/v1/impersonation/sessions
     *
     * <p>Authorization: SuperAdmin only (PreAuthorize role check).
     */
    @PostMapping
    public ResponseEntity<StartResponse> startSession(
            @Valid @RequestBody StartSessionRequest request,
            @AuthenticationPrincipal Jwt adminJwt,
            HttpServletRequest httpRequest) {

        String correlationId = httpRequest.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = java.util.UUID.randomUUID().toString();
        }

        // Step 1: Nested impersonation guard — admin token broker-issued olamaz
        String adminAzp = adminJwt.getClaimAsString("azp");
        if (brokerAzp.equals(adminAzp)) {
            log.warn("Nested impersonation attempt rejected: admin token azp={}", adminAzp);
            auditClient.writeBlocked(ImpersonationAuditClient.AuditPayload.builder()
                    .impersonatorUserId(extractUserIdClaim(adminJwt))
                    .impersonatorSubject(adminJwt.getSubject())
                    .impersonatorEmail(adminJwt.getClaimAsString("email"))
                    .targetSubject(request.targetSubject())
                    .targetUserId(request.targetUserId())
                    .targetEmail(request.targetEmail())
                    .reason(request.reason())
                    .correlationId(correlationId)
                    .errorCode("NESTED_IMPERSONATION_FORBIDDEN")
                    .message("Cannot impersonate while already impersonating (azp=" + adminAzp + ")")
                    .build());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new StartResponse(null, null, null,
                            "NESTED_IMPERSONATION_FORBIDDEN",
                            "Cannot impersonate while already impersonating"));
        }

        Long impersonatorUserId = extractUserIdClaim(adminJwt);
        String impersonatorSubject = adminJwt.getSubject();
        String impersonatorEmail = adminJwt.getClaimAsString("email");

        if (impersonatorUserId == null || impersonatorSubject == null) {
            auditClient.writeBlocked(ImpersonationAuditClient.AuditPayload.builder()
                    .impersonatorSubject(impersonatorSubject)
                    .impersonatorEmail(impersonatorEmail)
                    .targetSubject(request.targetSubject())
                    .targetUserId(request.targetUserId())
                    .targetEmail(request.targetEmail())
                    .reason(request.reason())
                    .correlationId(correlationId)
                    .errorCode("ADMIN_IDENTITY_MISSING")
                    .message("Admin JWT missing userId or subject claim")
                    .build());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new StartResponse(null, null, null,
                            "ADMIN_IDENTITY_MISSING",
                            "Admin JWT missing userId or subject claim"));
        }

        // Step 1b: Self-id guard BEFORE target subject resolution (Codex
        // {@code 019e1bed} REVISE-1 absorb). Reject by platform user id
        // equality first — that catches the self-target case even when
        // the target's {@code kc_subject} backfill is pending (which
        // would otherwise return TARGET_SUBJECT_UNRESOLVABLE and mask
        // the real reason). Subject-equality guard runs again later as
        // defense-in-depth once the subject is resolved.
        if (impersonatorUserId.equals(request.targetUserId())) {
            log.warn("Self-impersonation attempt rejected (id match): impersonator={} target={}",
                    impersonatorUserId, request.targetUserId());
            auditClient.writeBlocked(ImpersonationAuditClient.AuditPayload.builder()
                    .impersonatorUserId(impersonatorUserId)
                    .impersonatorSubject(impersonatorSubject)
                    .impersonatorEmail(impersonatorEmail)
                    .targetSubject(request.targetSubject())
                    .targetUserId(request.targetUserId())
                    .targetEmail(request.targetEmail())
                    .reason(request.reason())
                    .correlationId(correlationId)
                    .errorCode("SELF_IMPERSONATION_FORBIDDEN")
                    .message("Cannot impersonate your own account")
                    .build());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new StartResponse(null, null, null,
                            "SELF_IMPERSONATION_FORBIDDEN",
                            "Kendi hesabını impersonate edemezsin"));
        }

        // Step 1c: Backend-authoritative target subject resolution (Codex
        // {@code 019e1bed} AGREE C-prime). The admin UI MUST NOT need the
        // Keycloak UUID — it is a server-side internal identifier. If the
        // client did not send {@code targetSubject}, resolve it from
        // {@code targetUserId} via the service-token protected user-service
        // internal endpoint. If still unresolved (user pre-dates V16
        // backfill or user-service unreachable), reject with a specific
        // error so the UI can surface remediation guidance instead of
        // generic auth failures.
        // Codex 019e1bed REVISE-4: target resolution returns both
        // {@code kcSubject} and {@code enabled} from the user-service
        // internal endpoint. We capture the full {@link RemoteUserResponse}
        // so the disabled-user gate (below) can short-circuit before KC
        // token-exchange. Legacy callers that already passed
        // {@code targetSubject} skip the lookup and the disabled gate
        // (defensible — explicit subject implies the caller asserts the
        // user is provisioned correctly).
        String resolvedTargetSubject = request.targetSubject();
        RemoteUserResponse targetRecord = null;
        if (resolvedTargetSubject == null || resolvedTargetSubject.isBlank()) {
            try {
                targetRecord = userServiceClient.findUserById(request.targetUserId(), adminJwt.getTokenValue()).orElse(null);
                if (targetRecord != null) {
                    String kc = targetRecord.getKcSubject();
                    if (kc != null && !kc.isBlank()) {
                        resolvedTargetSubject = kc;
                    }
                }
            } catch (RuntimeException ex) {
                log.warn("user-service lookup failed during target subject resolution: userId={} msg={}",
                        request.targetUserId(), ex.getMessage());
                resolvedTargetSubject = null;
            }
        }
        if (resolvedTargetSubject == null || resolvedTargetSubject.isBlank()) {
            auditClient.writeBlocked(ImpersonationAuditClient.AuditPayload.builder()
                    .impersonatorUserId(impersonatorUserId)
                    .impersonatorSubject(impersonatorSubject)
                    .impersonatorEmail(impersonatorEmail)
                    .targetUserId(request.targetUserId())
                    .targetEmail(request.targetEmail())
                    .reason(request.reason())
                    .correlationId(correlationId)
                    .errorCode("TARGET_SUBJECT_UNRESOLVABLE")
                    .message("Hedef kullanıcı için Keycloak subject çözümlenemedi (V16 backfill veya provisioning eksik)")
                    .build());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new StartResponse(null, null, null,
                            "TARGET_SUBJECT_UNRESOLVABLE",
                            "Hedef kullanıcı için Keycloak subject çözümlenemedi"));
        }

        // Step 1d: Disabled target gate (Codex {@code 019e1bed} REVISE-4
        // absorb). When the lookup returned a record and the target user
        // is disabled, reject before KC token-exchange — policy violation,
        // not authentication failure, so 403 + dedicated errorCode keeps
        // the audit trail readable. The lookup only fires when the
        // caller did NOT supply {@code targetSubject}, so legacy callers
        // that explicitly pass a subject bypass this gate (acceptable —
        // they have already asserted the user is provisioned).
        if (targetRecord != null && !targetRecord.isEnabled()) {
            log.warn("Disabled target impersonation rejected: userId={} email={}",
                    request.targetUserId(), targetRecord.getEmail());
            auditClient.writeBlocked(ImpersonationAuditClient.AuditPayload.builder()
                    .impersonatorUserId(impersonatorUserId)
                    .impersonatorSubject(impersonatorSubject)
                    .impersonatorEmail(impersonatorEmail)
                    .targetSubject(resolvedTargetSubject)
                    .targetUserId(request.targetUserId())
                    .targetEmail(request.targetEmail() != null ? request.targetEmail() : targetRecord.getEmail())
                    .reason(request.reason())
                    .correlationId(correlationId)
                    .errorCode("TARGET_USER_DISABLED")
                    .message("Pasif kullanıcı için impersonation başlatılamaz")
                    .build());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new StartResponse(null, null, null,
                            "TARGET_USER_DISABLED",
                            "Pasif kullanıcı için impersonation başlatılamaz"));
        }

        // Step 1e: Subject-equality self-guard (defense-in-depth, runs
        // after resolution so even a target with mismatched user_id but
        // identical KC subject is rejected).
        if (impersonatorSubject.equals(resolvedTargetSubject)) {
            log.warn("Self-impersonation attempt rejected (subject match): impersonator={} resolvedSubject={}",
                    impersonatorUserId, resolvedTargetSubject);
            auditClient.writeBlocked(ImpersonationAuditClient.AuditPayload.builder()
                    .impersonatorUserId(impersonatorUserId)
                    .impersonatorSubject(impersonatorSubject)
                    .impersonatorEmail(impersonatorEmail)
                    .targetSubject(resolvedTargetSubject)
                    .targetUserId(request.targetUserId())
                    .targetEmail(request.targetEmail())
                    .reason(request.reason())
                    .correlationId(correlationId)
                    .errorCode("SELF_IMPERSONATION_FORBIDDEN")
                    .message("Cannot impersonate your own KC subject")
                    .build());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new StartResponse(null, null, null,
                            "SELF_IMPERSONATION_FORBIDDEN",
                            "Kendi hesabını impersonate edemezsin"));
        }

        // Step 2: SuperAdmin authority check (Codex iter-27 P0 absorb)
        // — JWT @PreAuthorize KC realm role converter eksik olabilir;
        // permission-service authoritative authz source kullanırız.
        if (!superAdminAuthority.isSuperAdmin(impersonatorUserId, adminJwt.getTokenValue())) {
            log.warn("Non-superAdmin impersonation attempt rejected: user_id={}", impersonatorUserId);
            auditClient.writeBlocked(ImpersonationAuditClient.AuditPayload.builder()
                    .impersonatorUserId(impersonatorUserId)
                    .impersonatorSubject(impersonatorSubject)
                    .impersonatorEmail(impersonatorEmail)
                    .targetSubject(resolvedTargetSubject)
                    .targetUserId(request.targetUserId())
                    .targetEmail(request.targetEmail())
                    .reason(request.reason())
                    .correlationId(correlationId)
                    .errorCode("INSUFFICIENT_AUTHORITY")
                    .message("Only super admins can start impersonation sessions")
                    .build());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new StartResponse(null, null, null,
                            "INSUFFICIENT_AUTHORITY",
                            "Only super admins can start impersonation sessions"));
        }

        // Step 3: Token exchange (KC broker)
        ExchangeResult exchange;
        try {
            exchange = brokerClient.exchange(adminJwt.getTokenValue(), resolvedTargetSubject);
        } catch (TokenExchangeException e) {
            log.warn("Token exchange failed for target={}: {}", resolvedTargetSubject, e.getMessage());
            auditClient.writeFailed(ImpersonationAuditClient.AuditPayload.builder()
                    .impersonatorUserId(impersonatorUserId)
                    .impersonatorSubject(impersonatorSubject)
                    .impersonatorEmail(impersonatorEmail)
                    .targetSubject(resolvedTargetSubject)
                    .targetUserId(request.targetUserId())
                    .targetEmail(request.targetEmail())
                    .reason(request.reason())
                    .correlationId(correlationId)
                    .errorCode(e.errorCode())
                    .message("Keycloak token exchange failed: " + e.getMessage())
                    .build());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new StartResponse(null, null, null,
                            e.errorCode(),
                            "Keycloak token exchange failed"));
        }

        DecodedClaims claims = exchange.claims();

        // Step 3a: Defensive — exchanged token sub MUST match request targetSubject
        // (Codex iter-27 P0: prevent audit poisoning by client-provided target).
        if (!resolvedTargetSubject.equals(claims.subject())) {
            log.warn("Exchange subject mismatch: request.targetSubject={} claims.sub={}",
                    resolvedTargetSubject, claims.subject());
            auditClient.writeBlocked(ImpersonationAuditClient.AuditPayload.builder()
                    .impersonatorUserId(impersonatorUserId)
                    .impersonatorSubject(impersonatorSubject)
                    .impersonatorEmail(impersonatorEmail)
                    .targetSubject(resolvedTargetSubject)
                    .targetUserId(request.targetUserId())
                    .targetEmail(request.targetEmail())
                    .reason(request.reason())
                    .correlationId(correlationId)
                    .errorCode("TARGET_SUBJECT_MISMATCH")
                    .message("Exchanged token sub=" + claims.subject() + " != requested " + resolvedTargetSubject)
                    .build());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new StartResponse(null, null, null,
                            "TARGET_SUBJECT_MISMATCH",
                            "Exchanged token subject does not match requested target"));
        }

        // Step 3b: azp must be broker (token actually exchanged through broker client).
        if (!brokerAzp.equals(claims.authorizedParty())) {
            log.warn("Exchange azp not broker: azp={}", claims.authorizedParty());
            auditClient.writeFailed(ImpersonationAuditClient.AuditPayload.builder()
                    .impersonatorUserId(impersonatorUserId)
                    .impersonatorSubject(impersonatorSubject)
                    .impersonatorEmail(impersonatorEmail)
                    .targetSubject(resolvedTargetSubject)
                    .targetUserId(request.targetUserId())
                    .targetEmail(request.targetEmail())
                    .reason(request.reason())
                    .correlationId(correlationId)
                    .errorCode("EXCHANGED_TOKEN_NOT_BROKER_ISSUED")
                    .message("Exchanged token azp=" + claims.authorizedParty() + " != broker " + brokerAzp)
                    .build());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new StartResponse(null, null, null,
                            "EXCHANGED_TOKEN_NOT_BROKER_ISSUED",
                            "Exchanged token azp is not the broker client"));
        }

        // Step 3c: exp validation — must be in the future.
        long nowEpoch = Instant.now().getEpochSecond();
        if (claims.expEpoch() <= nowEpoch) {
            log.warn("Exchange token already expired: exp={} now={}", claims.expEpoch(), nowEpoch);
            auditClient.writeFailed(ImpersonationAuditClient.AuditPayload.builder()
                    .impersonatorUserId(impersonatorUserId)
                    .impersonatorSubject(impersonatorSubject)
                    .impersonatorEmail(impersonatorEmail)
                    .targetSubject(resolvedTargetSubject)
                    .targetUserId(request.targetUserId())
                    .targetEmail(request.targetEmail())
                    .reason(request.reason())
                    .correlationId(correlationId)
                    .errorCode("EXCHANGED_TOKEN_EXPIRED")
                    .message("Exchanged token exp=" + claims.expEpoch() + " <= now=" + nowEpoch)
                    .build());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new StartResponse(null, null, null,
                            "EXCHANGED_TOKEN_EXPIRED",
                            "Exchanged token expired before session creation"));
        }

        // Step 3: permission-service internal session create (DB authority).
        // Codex iter-27 P1 absorb: targetEmail authoritative source = exchanged
        // token claims.email (KC's user record). Client-supplied request.targetEmail
        // is fallback only — prevents audit poisoning where SuperAdmin passes
        // a stale/forged email for the target.
        String resolvedTargetEmail = claims.email() != null && !claims.email().isBlank()
                ? claims.email()
                : request.targetEmail();
        StartRequest internalRequest = new StartRequest(
                impersonatorUserId,
                impersonatorSubject,
                impersonatorEmail,
                request.targetUserId(),
                resolvedTargetSubject,
                resolvedTargetEmail,
                claims.issuer(),
                claims.jti(),
                claims.sid(),
                request.reason(),
                Instant.ofEpochSecond(claims.expEpoch()),
                resolveClientIp(httpRequest),
                httpRequest.getHeader("User-Agent"),
                httpRequest.getHeader("X-Forwarded-For"));

        SessionResource session;
        try {
            session = sessionClient.startSession(internalRequest);
        } catch (ActiveSessionExistsException e) {
            auditClient.writeBlocked(ImpersonationAuditClient.AuditPayload.builder()
                    .impersonatorUserId(impersonatorUserId)
                    .impersonatorSubject(impersonatorSubject)
                    .impersonatorEmail(impersonatorEmail)
                    .targetSubject(resolvedTargetSubject)
                    .targetUserId(request.targetUserId())
                    .targetEmail(request.targetEmail())
                    .reason(request.reason())
                    .correlationId(correlationId)
                    .errorCode(e.errorCode())
                    .message("Active impersonation session already exists for this user")
                    .build());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new StartResponse(null, null, null,
                            e.errorCode(),
                            "Active impersonation session already exists for this user"));
        } catch (ImpersonationSessionClient.ImpersonationSessionClientException e) {
            // Codex iter-27 P0 absorb: downstream session create failures
            // (timeout/500/network) must produce IMPERSONATION_FAILED audit
            // before bubbling 502/503 to the caller.
            log.warn("permission-service session create failed for target={}: {}",
                    resolvedTargetSubject, e.getMessage());
            auditClient.writeFailed(ImpersonationAuditClient.AuditPayload.builder()
                    .impersonatorUserId(impersonatorUserId)
                    .impersonatorSubject(impersonatorSubject)
                    .impersonatorEmail(impersonatorEmail)
                    .targetSubject(resolvedTargetSubject)
                    .targetUserId(request.targetUserId())
                    .targetEmail(request.targetEmail())
                    .reason(request.reason())
                    .correlationId(correlationId)
                    .errorCode("SESSION_PERSIST_FAILED")
                    .message("permission-service session create failed: " + e.getMessage())
                    .build());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(new StartResponse(null, null, null,
                            "SESSION_PERSIST_FAILED",
                            "Failed to persist impersonation session"));
        }

        log.info("Impersonation session started: id={} impersonator={} target={}",
                session.sessionId(), impersonatorUserId, request.targetUserId());

        // Step 4: Token frontend'e ŞİMDİ döner (DB commit sonrası)
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new StartResponse(
                        session.sessionId(),
                        exchange.accessToken(),
                        session.expiresAt(),
                        null,
                        null));
    }

    /**
     * DELETE /api/v1/impersonation/sessions/current
     *
     * <p>MVP contract (Codex iter-29 absorb): only the ORIGINAL admin token
     * (azp != broker) may stop /current. Caller is the impersonator; we
     * lookup their active session via {@code impersonator_user_id == userId}
     * and stop it. Produces IMPERSONATION_STOPPED audit row from
     * permission-service.
     *
     * <p>Broker-issued exchanged token (azp == broker) is rejected with
     * 400 + {@code X-Error-Code: STOP_FROM_BROKER_TOKEN_NOT_SUPPORTED}.
     * The exchanged-token caller has no impersonator_user_id claim and
     * naive lookup would stop the wrong row. PR-D follow-up: add
     * {@code POST /sessions/by-binding/stop} that resolves session via
     * {@code (iss, jti, sid)} for self-stop from inside impersonation.
     */
    @DeleteMapping("/current")
    public ResponseEntity<Void> stopCurrent(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String azp = jwt.getClaimAsString("azp");
        if (brokerAzp.equals(azp)) {
            // Codex iter-29 absorb: broker-issued token (caller is INSIDE
            // impersonation context). MVP contract: only the original admin
            // token can stop /current. The exchanged token caller must
            // either logout (KC) or use the future
            // /sessions/by-binding endpoint (PR-D follow-up).
            // Reject explicitly so we never silently stop the wrong row.
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .header("X-Error-Code", "STOP_FROM_BROKER_TOKEN_NOT_SUPPORTED")
                    .build();
        }

        Long impersonatorUserId = extractUserIdClaim(jwt);
        if (impersonatorUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        var active = sessionClient.getActiveByImpersonator(impersonatorUserId);
        if (active.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        boolean stopped = sessionClient.stopSession(active.get().sessionId(), "USER_STOP");
        return stopped ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * POST /api/v1/impersonation/sessions/{sessionId}/revoke
     *
     * <p>SuperAdmin-only force-revoke endpoint. Distinct from {@code DELETE /current}:
     * a different SuperAdmin can revoke another admin's impersonation session
     * (incident response, compromised actor handling). Audit row records
     * IMPERSONATION_REVOKED with operator identity + reason.
     */
    @PostMapping("/{sessionId}/revoke")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal Jwt jwt,
            @org.springframework.web.bind.annotation.PathVariable UUID sessionId,
            @RequestBody(required = false) RevokeRequest request,
            HttpServletRequest httpRequest) {

        Long operatorUserId = extractUserIdClaim(jwt);
        String operatorSubject = jwt.getSubject();
        String operatorEmail = jwt.getClaimAsString("email");
        if (operatorUserId == null || operatorSubject == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!superAdminAuthority.isSuperAdmin(operatorUserId, jwt.getTokenValue())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String reason = request != null && request.reason() != null
                ? request.reason() : "ADMIN_REVOKE";
        String correlationId = httpRequest.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = java.util.UUID.randomUUID().toString();
        }
        boolean revoked = sessionClient.revokeSession(
                sessionId, reason, operatorUserId, operatorSubject, operatorEmail, correlationId);
        return revoked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * GET /api/v1/impersonation/sessions/active
     */
    @GetMapping("/active")
    public ResponseEntity<SessionResource> getActive(@AuthenticationPrincipal Jwt jwt) {
        Long impersonatorUserId = extractUserIdClaim(jwt);
        if (impersonatorUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return sessionClient.getActiveByImpersonator(impersonatorUserId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private Long extractUserIdClaim(Jwt jwt) {
        Object claim = jwt.getClaim("userId");
        if (claim == null) {
            return null;
        }
        try {
            if (claim instanceof Number n) return n.longValue();
            return Long.parseLong(claim.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @ExceptionHandler(TokenExchangeException.class)
    public ResponseEntity<ErrorResponse> handleExchange(TokenExchangeException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse(e.errorCode(), e.getMessage()));
    }

    @ExceptionHandler(ActiveSessionExistsException.class)
    public ResponseEntity<ErrorResponse> handleActive(ActiveSessionExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(e.errorCode(), e.getMessage()));
    }

    public record StartSessionRequest(
            @NotNull Long targetUserId,
            // Codex 019e1bed AGREE — was @NotBlank; relaxed to optional so the
            // admin UI never has to know/type the Keycloak UUID. The
            // controller resolves the subject server-side from
            // {@code targetUserId} via user-service when this is blank.
            @Size(max = 255) String targetSubject,
            @Size(max = 255) String targetEmail,
            @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record RevokeRequest(@Size(max = 500) String reason) {
    }

    public record StartResponse(
            UUID sessionId,
            String exchangedToken,
            Instant expiresAt,
            String errorCode,
            String errorMessage) {
    }

    public record ErrorResponse(String errorCode, String message) {
    }
}
