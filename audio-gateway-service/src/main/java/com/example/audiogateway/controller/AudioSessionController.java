package com.example.audiogateway.controller;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.config.CorrelationIdWebFilter;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.FinishResponse;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;
import com.example.audiogateway.dto.StatusResponse;
import com.example.audiogateway.service.AudioSessionRegistry;
import com.example.audiogateway.service.AudioSessionRegistry.CreateOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.FinishOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.SessionCreateCommand;
import com.example.audiogateway.service.SessionRecord;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Audio Gateway Contract 1.0 — session lifecycle endpoints.
 *
 * <p>ADR-0031 two-server topology (Codex {@code 019e8c26} iter-2 AGREE PR-gw-01A scope):
 * canonical path {@code /api/v1/audio-gateway}; eski {@code /api/meeting-audio} kaldırıldı
 * (pre-prod scope correction, backward-compat alias YOK).
 *
 * <p>PR-gw-01A scope:
 * <ul>
 *   <li>{@code POST /sessions} — start session + {@code Idempotency-Key} header zorunlu</li>
 *   <li>{@code GET /sessions/{id}/status} — lifecycle state snapshot</li>
 *   <li>{@code POST /sessions/{id}/finish} — terminal lifecycle event + dispatcher flush
 *       contract (idempotent same {@code Idempotency-Key} 2nd call)</li>
 * </ul>
 *
 * <p>Out of scope (sonraki slice'lar):
 * <ul>
 *   <li>PR-gw-01B: {@code POST /chunks} REST chunk admission</li>
 *   <li>PR-gw-01C: Redis Streams producer (bucketed 32-partition + consumer group)</li>
 *   <li>PR-gw-01D: {@code WS /stream} WebSocket audio stream</li>
 *   <li>PR-gw-01E: header spoof strip + PII guard + invalid transition matrix</li>
 * </ul>
 *
 * <p>tenantId/userId/correlationId NEVER client-trusted — Gateway JWT-derive eder
 * (3-AI mutabakat Codex {@code 019e879c} explicit RED).
 */
@RestController
@RequestMapping("/api/v1/audio-gateway")
public class AudioSessionController {

    /** Idempotency-Key format guard — Codex {@code 019e8c26} iter-2 AGREE: 16-128 char opaque. */
    private static final Pattern IDEMPOTENCY_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9._:\\-]{16,128}$");

    private final AudioGatewayProperties props;
    private final AudioSessionRegistry registry;

    public AudioSessionController(final AudioGatewayProperties props,
                                  final AudioSessionRegistry registry) {
        this.props = props;
        this.registry = registry;
    }

    // ----- POST /sessions ---------------------------------------------------

    @PostMapping("/sessions")
    public Mono<ResponseEntity<?>> startSession(
            @RequestHeader(value = "Idempotency-Key", required = false) final String idempotencyKey,
            @Valid @RequestBody final StartSessionRequest req,
            @AuthenticationPrincipal final Jwt jwt,
            final ServerWebExchange exchange) {

        final String corrId = correlationId(exchange);

        final ResponseEntity<?> idempErr = validateIdempotencyKey(idempotencyKey, corrId);
        if (idempErr != null) {
            return Mono.just(idempErr);
        }

        final ResponseEntity<?> formatErr = validateFormatGuards(req, corrId);
        if (formatErr != null) {
            return Mono.just(formatErr);
        }

        final ResponseEntity<?> authErr = validateAuth(jwt, corrId);
        if (authErr != null) {
            return Mono.just(authErr);
        }

        final Long tenantId = claimAsLong(jwt, props.getJwt().getTenantClaim());
        final Long userId = claimAsLong(jwt, props.getJwt().getUserClaim());
        if (tenantId == null) {
            return Mono.just(forbidden(props.getJwt().getTenantClaim(), corrId));
        }
        if (userId == null) {
            return Mono.just(forbidden(props.getJwt().getUserClaim(), corrId));
        }

        final long now = Instant.now().toEpochMilli();
        final CreateOutcome outcome = registry.create(new SessionCreateCommand(
                tenantId, userId, req.meetingId(), req.deviceId(), req.language(),
                req.audioFormat(), req.sampleRateHz(), req.channels(),
                idempotencyKey, now));

        return Mono.just(switch (outcome) {
            case CreateOutcome.Created c -> ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(buildStartResponse(c.record(), corrId));
            case CreateOutcome.Replayed r -> ResponseEntity
                    .status(HttpStatus.OK)
                    .body(buildStartResponse(r.record(), corrId));
            case CreateOutcome.IdempotencyConflict ic -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_IDEMPOTENCY_CONFLICT,
                            "Idempotency-Key reused with materially different request "
                                    + "(existing session: " + ic.existingSessionId() + ")",
                            corrId, false));
            case CreateOutcome.RegistryFull rf -> ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_SESSION_REGISTRY_FULL,
                            "Session registry full (capacity=" + rf.capacity() + ")",
                            corrId, true));
        });
    }

    // ----- GET /sessions/{sessionId}/status --------------------------------

    @GetMapping("/sessions/{sessionId}/status")
    public Mono<ResponseEntity<?>> status(
            @PathVariable final String sessionId,
            @AuthenticationPrincipal final Jwt jwt,
            final ServerWebExchange exchange) {

        final String corrId = correlationId(exchange);
        final ResponseEntity<?> authErr = validateAuth(jwt, corrId);
        if (authErr != null) {
            return Mono.just(authErr);
        }

        final Long tenantId = claimAsLong(jwt, props.getJwt().getTenantClaim());
        final Long userId = claimAsLong(jwt, props.getJwt().getUserClaim());
        if (tenantId == null) {
            return Mono.just(forbidden(props.getJwt().getTenantClaim(), corrId));
        }
        if (userId == null) {
            return Mono.just(forbidden(props.getJwt().getUserClaim(), corrId));
        }

        return Mono.just(registry.get(sessionId)
                .filter(r -> tenantId.equals(r.tenantId()) && userId.equals(r.userId()))
                .<ResponseEntity<?>>map(record -> ResponseEntity.ok(new StatusResponse(
                        record.sessionId(),
                        corrId,
                        record.state().name(),
                        0,            // chunkCount — PR-gw-01B/C scope
                        0L,           // lastChunkSeq — PR-gw-01B/C scope
                        record.state() == com.example.audiogateway.service.SessionState.FINISHED
                                ? Math.max(0L, record.finishedAtMs() - record.sessionStartMs())
                                : Math.max(0L, System.currentTimeMillis() - record.sessionStartMs()),
                        record.sessionStartMs(),
                        record.updatedAtMs())))
                .orElseGet(() -> ResponseEntity
                        .status(HttpStatus.NOT_FOUND)
                        .body(ErrorResponse.of(
                                ErrorResponse.CODE_SESSION_NOT_FOUND,
                                "Session not found: " + sessionId,
                                corrId, false))));
    }

    // ----- POST /sessions/{sessionId}/finish -------------------------------

    @PostMapping("/sessions/{sessionId}/finish")
    public Mono<ResponseEntity<?>> finish(
            @PathVariable final String sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) final String idempotencyKey,
            @AuthenticationPrincipal final Jwt jwt,
            final ServerWebExchange exchange) {

        final String corrId = correlationId(exchange);

        final ResponseEntity<?> idempErr = validateIdempotencyKey(idempotencyKey, corrId);
        if (idempErr != null) {
            return Mono.just(idempErr);
        }

        final ResponseEntity<?> authErr = validateAuth(jwt, corrId);
        if (authErr != null) {
            return Mono.just(authErr);
        }

        final Long tenantId = claimAsLong(jwt, props.getJwt().getTenantClaim());
        final Long userId = claimAsLong(jwt, props.getJwt().getUserClaim());
        if (tenantId == null) {
            return Mono.just(forbidden(props.getJwt().getTenantClaim(), corrId));
        }
        if (userId == null) {
            return Mono.just(forbidden(props.getJwt().getUserClaim(), corrId));
        }

        final FinishOutcome outcome = registry.finish(sessionId, idempotencyKey, tenantId, userId);
        return Mono.just(switch (outcome) {
            case FinishOutcome.Finished f -> ResponseEntity.ok(new FinishResponse(
                    f.record().sessionId(), corrId,
                    f.record().state().name(), f.record().finishedAtMs(), false));
            case FinishOutcome.AlreadyFinished af -> ResponseEntity.ok(new FinishResponse(
                    af.record().sessionId(), corrId,
                    af.record().state().name(), af.record().finishedAtMs(), true));
            case FinishOutcome.NotFound nf -> ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_SESSION_NOT_FOUND,
                            "Session not found: " + sessionId,
                            corrId, false));
            case FinishOutcome.OwnerMismatch om -> ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_MEETING_FORBIDDEN,
                            "Session owner mismatch",
                            corrId, false));
            case FinishOutcome.IdempotencyConflict ic -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_IDEMPOTENCY_CONFLICT,
                            "Idempotency-Key reused for already-finished session with different key",
                            corrId, false));
        });
    }

    // ----- helpers ---------------------------------------------------------

    private static String correlationId(final ServerWebExchange exchange) {
        return (String) exchange.getAttributes().get(CorrelationIdWebFilter.ATTR_KEY);
    }

    private ResponseEntity<?> validateIdempotencyKey(final String key, final String corrId) {
        if (key == null || key.isBlank()) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_IDEMPOTENCY_MISSING,
                            "Header '" + props.getIdempotency().getHeaderName() + "' required",
                            corrId, false));
        }
        if (!IDEMPOTENCY_KEY_PATTERN.matcher(key).matches()
                || key.length() < props.getIdempotency().getMinLength()
                || key.length() > props.getIdempotency().getMaxLength()) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_IDEMPOTENCY_INVALID,
                            "Idempotency-Key must be " + props.getIdempotency().getMinLength()
                                    + "-" + props.getIdempotency().getMaxLength()
                                    + " char opaque token [A-Za-z0-9._:-]",
                            corrId, false));
        }
        return null;
    }

    private ResponseEntity<?> validateFormatGuards(final StartSessionRequest req, final String corrId) {
        if (!StartSessionRequest.ALLOWED_SAMPLE_RATES.contains(req.sampleRateHz())) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(
                    ErrorResponse.CODE_FORMAT_REJECTED,
                    "Unsupported sample rate: " + req.sampleRateHz(),
                    corrId, false));
        }
        if (req.channels() != 1) {
            return ResponseEntity.badRequest().body(ErrorResponse.of(
                    ErrorResponse.CODE_FORMAT_REJECTED,
                    "Stereo or multichannel not supported in PoC (channels must be 1)",
                    corrId, false));
        }
        if (!AudioFormat.CLIENT_ALLOWED.contains(req.audioFormat())) {
            return ResponseEntity
                    .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_FORMAT_REJECTED,
                            "Audio format not in CLIENT_ALLOWED: " + req.audioFormat(),
                            corrId, false));
        }
        return null;
    }

    private ResponseEntity<?> validateAuth(final Jwt jwt, final String corrId) {
        if (jwt == null) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_AUTH_INVALID,
                            "JWT principal missing",
                            corrId, false));
        }
        return null;
    }

    private ResponseEntity<?> forbidden(final String claim, final String corrId) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of(
                        ErrorResponse.CODE_MEETING_FORBIDDEN,
                        "JWT missing required claim '" + claim + "'",
                        corrId, false));
    }

    private static Long claimAsLong(final Jwt jwt, final String claim) {
        final Object v = jwt.getClaim(claim);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private StartSessionResponse buildStartResponse(final SessionRecord record, final String corrId) {
        final String base = "/api/v1/audio-gateway/sessions/" + record.sessionId();
        return new StartSessionResponse(
                record.sessionId(),
                corrId,
                base + "/stream",            // PR-gw-01D (planned contract, not functional yet)
                base + "/chunks",            // PR-gw-01B (planned contract, not functional yet)
                base + "/status",            // PR-gw-01A LIVE
                base + "/finish",            // PR-gw-01A LIVE
                record.sessionStartMs());
    }
}
