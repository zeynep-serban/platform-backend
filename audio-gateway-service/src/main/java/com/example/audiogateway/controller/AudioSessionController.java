package com.example.audiogateway.controller;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.config.CorrelationIdWebFilter;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ChunkAdmissionResponse;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.FinishResponse;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;
import com.example.audiogateway.dto.StatusResponse;
import com.example.audiogateway.service.AudioSessionRegistry;
import com.example.audiogateway.service.AudioSessionRegistry.ChunkOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.ChunkRecordCommand;
import com.example.audiogateway.service.AudioSessionRegistry.CreateOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.FinishOutcome;
import com.example.audiogateway.service.AudioSessionRegistry.SessionCreateCommand;
import com.example.audiogateway.service.SessionRecord;
import com.example.audiogateway.service.SessionState;

import jakarta.validation.Valid;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * Audio Gateway Contract 1.0 — session lifecycle + chunk admission endpoints.
 *
 * <p>ADR-0031 two-server topology (Codex {@code 019e8c26} iter-2 AGREE PR-gw-01A scope +
 * Codex {@code 019e8d78} iter-2 AGREE PR-gw-01B-core scope): canonical path
 * {@code /api/v1/audio-gateway}; eski {@code /api/meeting-audio} kaldırıldı.
 *
 * <p>PR-gw-01A scope (MERGED):
 * <ul>
 *   <li>{@code POST /sessions} — start session + {@code Idempotency-Key} header zorunlu</li>
 *   <li>{@code GET /sessions/{id}/status} — lifecycle state snapshot</li>
 *   <li>{@code POST /sessions/{id}/finish} — terminal lifecycle + idempotent</li>
 * </ul>
 *
 * <p>PR-gw-01B-core scope (bu PR):
 * <ul>
 *   <li>{@code POST /sessions/{id}/chunks} — binary body + X-Audio-* headers; declared/
 *       actual byte length check, format/sample/channels session match, bounded body
 *       aggregation, SHA-256 payload hash, atomic admitChunk via registry domain method,
 *       strict contiguous chunkSeq, idempotent replay (same key + same seq + same hash)</li>
 *   <li>{@code GET /sessions/{id}/status} — real chunkCount + lastChunkSeq (no longer 0)</li>
 * </ul>
 *
 * <p>Out of scope (sonraki slice'lar):
 * <ul>
 *   <li>PR-gw-01B3: AudioChunkDispatcher + NoOp + 429/503 + Retry-After + audit sink</li>
 *   <li>PR-gw-01C: Redis Streams producer (bucketed 32-partition + consumer group)</li>
 *   <li>PR-gw-01D: WebSocket binary + JSON metadata stream</li>
 *   <li>PR-gw-01E: Header spoof strip + PII guard + invalid transition matrix</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/audio-gateway")
public class AudioSessionController {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9._:\\-]{16,128}$");

    // Chunk admission canonical headers (Codex `019e8d78` iter-2 AGREE — binary body + headers)
    private static final String HDR_CHUNK_SEQ = "X-Audio-Chunk-Seq";
    private static final String HDR_CHUNK_STARTED_AT_MS = "X-Audio-Chunk-Started-At-Ms";
    private static final String HDR_AUDIO_FORMAT = "X-Audio-Format";
    private static final String HDR_SAMPLE_RATE_HZ = "X-Audio-Sample-Rate-Hz";
    private static final String HDR_CHANNELS = "X-Audio-Channels";
    private static final String HDR_BYTE_LENGTH = "X-Audio-Byte-Length";

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

        final ResponseEntity<?> formatErr = validateStartFormatGuards(req, corrId);
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

    // ----- POST /sessions/{sessionId}/chunks (PR-gw-01B-core) ---------------

    @PostMapping(value = "/sessions/{sessionId}/chunks",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public Mono<ResponseEntity<?>> admitChunk(
            @PathVariable final String sessionId,
            @RequestHeader(value = "Idempotency-Key", required = false) final String idempotencyKey,
            @AuthenticationPrincipal final Jwt jwt,
            final ServerWebExchange exchange) {

        final String corrId = correlationId(exchange);

        // Idempotency-Key validation
        final ResponseEntity<?> idempErr = validateIdempotencyKey(idempotencyKey, corrId);
        if (idempErr != null) {
            return Mono.just(idempErr);
        }

        // JWT + tenant/user claims
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

        // Header parsing
        final HttpHeaders headers = exchange.getRequest().getHeaders();
        final Long chunkSeq = parseLongHeader(headers, HDR_CHUNK_SEQ);
        final Long chunkStartedAtMs = parseLongHeader(headers, HDR_CHUNK_STARTED_AT_MS);
        final Long declaredByteLength = parseLongHeader(headers, HDR_BYTE_LENGTH);
        final Integer sampleRateHz = parseIntHeader(headers, HDR_SAMPLE_RATE_HZ);
        final Integer channels = parseIntHeader(headers, HDR_CHANNELS);
        final AudioFormat audioFormat = parseAudioFormatHeader(headers);

        if (chunkSeq == null || chunkSeq < 0
                || chunkStartedAtMs == null || chunkStartedAtMs < 0
                || declaredByteLength == null || declaredByteLength < 0
                || sampleRateHz == null || channels == null || audioFormat == null) {
            return Mono.just(ResponseEntity.badRequest().body(ErrorResponse.of(
                    ErrorResponse.CODE_VALIDATION,
                    "Missing or invalid chunk metadata headers (X-Audio-Chunk-Seq/...-Started-At-Ms/"
                            + "...-Byte-Length/X-Audio-Format/X-Audio-Sample-Rate-Hz/X-Audio-Channels)",
                    corrId, false)));
        }

        // Declared byteLength bounds
        final long maxChunkBytes = props.getBounds().getMaxChunkBytes();
        if (declaredByteLength > maxChunkBytes) {
            return Mono.just(ResponseEntity
                    .status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_OVERSIZE,
                            "Declared X-Audio-Byte-Length=" + declaredByteLength + " exceeds max "
                                    + maxChunkBytes,
                            corrId, false)));
        }

        // Format whitelist (CLIENT_ALLOWED subset)
        if (!AudioFormat.CLIENT_ALLOWED.contains(audioFormat)) {
            return Mono.just(ResponseEntity
                    .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_FORMAT_REJECTED,
                            "Audio format not in CLIENT_ALLOWED: " + audioFormat,
                            corrId, false)));
        }
        if (!StartSessionRequest.ALLOWED_SAMPLE_RATES.contains(sampleRateHz)) {
            return Mono.just(ResponseEntity.badRequest().body(ErrorResponse.of(
                    ErrorResponse.CODE_FORMAT_REJECTED,
                    "Unsupported sample rate: " + sampleRateHz,
                    corrId, false)));
        }
        if (channels != 1) {
            return Mono.just(ResponseEntity.badRequest().body(ErrorResponse.of(
                    ErrorResponse.CODE_FORMAT_REJECTED,
                    "Stereo or multichannel not supported in PoC (channels must be 1)",
                    corrId, false)));
        }

        // Session lookup + owner + format/sample/channels match
        final SessionRecord existing = registry.get(sessionId).orElse(null);
        if (existing == null) {
            return Mono.just(ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_SESSION_NOT_FOUND,
                            "Session not found: " + sessionId,
                            corrId, false)));
        }
        if (!tenantId.equals(existing.tenantId()) || !userId.equals(existing.userId())) {
            return Mono.just(ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_MEETING_FORBIDDEN,
                            "Session owner mismatch",
                            corrId, false)));
        }
        if (existing.audioFormat() != audioFormat
                || existing.sampleRateHz() != sampleRateHz
                || existing.channels() != channels) {
            return Mono.just(ResponseEntity
                    .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_FORMAT_REJECTED,
                            "Chunk audio metadata does not match session",
                            corrId, false)));
        }

        // Bounded body aggregation + SHA-256 (Codex `019e8d78` iter-2: maxChunkBytes + 1 fail-fast)
        final long maxAllowed = maxChunkBytes;
        return DataBufferUtils.join(exchange.getRequest().getBody(), (int) (maxAllowed + 1))
                .map(buffer -> {
                    try {
                        final byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .flatMap(bytes -> {
                    if (bytes.length > maxAllowed) {
                        return Mono.just((ResponseEntity<?>) ResponseEntity
                                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                                .body(ErrorResponse.of(
                                        ErrorResponse.CODE_OVERSIZE,
                                        "Actual chunk body size " + bytes.length
                                                + " exceeds max " + maxAllowed,
                                        corrId, false)));
                    }
                    if (bytes.length != declaredByteLength) {
                        return Mono.just((ResponseEntity<?>) ResponseEntity
                                .badRequest()
                                .body(ErrorResponse.of(
                                        ErrorResponse.CODE_VALIDATION,
                                        "Declared X-Audio-Byte-Length=" + declaredByteLength
                                                + " mismatches actual body size " + bytes.length,
                                        corrId, false)));
                    }
                    final String sha256 = sha256Hex(bytes);
                    final long now = Instant.now().toEpochMilli();
                    final ChunkOutcome outcome = registry.admitChunk(new ChunkRecordCommand(
                            sessionId, tenantId, userId, idempotencyKey,
                            chunkSeq, chunkStartedAtMs, bytes.length, sha256, now));
                    return Mono.just((ResponseEntity<?>) handleChunkOutcome(outcome, corrId, chunkSeq));
                })
                .onErrorResume(ex -> {
                    if (ex.getClass().getSimpleName().contains("DataBufferLimit")
                            || ex.getMessage() != null && ex.getMessage().contains("limit")) {
                        return Mono.just(ResponseEntity
                                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                                .body(ErrorResponse.of(
                                        ErrorResponse.CODE_OVERSIZE,
                                        "Chunk body exceeds bounded read limit (" + maxAllowed + ")",
                                        corrId, false)));
                    }
                    return Mono.just(ResponseEntity
                            .status(HttpStatus.BAD_REQUEST)
                            .body(ErrorResponse.of(
                                    ErrorResponse.CODE_VALIDATION,
                                    "Chunk body read failed: " + ex.getClass().getSimpleName(),
                                    corrId, false)));
                });
    }

    private ResponseEntity<?> handleChunkOutcome(final ChunkOutcome outcome, final String corrId,
                                                  final long chunkSeq) {
        return switch (outcome) {
            case ChunkOutcome.Accepted a -> ResponseEntity.ok(
                    withCorrelation(a.response(), corrId));
            case ChunkOutcome.Replayed r -> ResponseEntity.ok(
                    withCorrelation(r.response(), corrId));
            case ChunkOutcome.NotFound nf -> ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_SESSION_NOT_FOUND,
                            "Session not found", corrId, false));
            case ChunkOutcome.OwnerMismatch om -> ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_MEETING_FORBIDDEN,
                            "Session owner mismatch", corrId, false));
            case ChunkOutcome.InvalidState is -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_INVALID_TRANSITION,
                            "Chunk admission not allowed in state " + is.currentState(),
                            corrId, false));
            case ChunkOutcome.OutOfOrder oo -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_CHUNK_OUT_OF_ORDER,
                            "Chunk seq out of order: expected " + oo.expectedChunkSeq()
                                    + ", got " + oo.actualChunkSeq(),
                            corrId, false));
            case ChunkOutcome.IdempotencyConflict ic -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_IDEMPOTENCY_CONFLICT,
                            "Same chunkSeq=" + chunkSeq + " with different Idempotency-Key or payload",
                            corrId, false));
        };
    }

    private static ChunkAdmissionResponse withCorrelation(final ChunkAdmissionResponse response,
                                                           final String corrId) {
        return new ChunkAdmissionResponse(response.sessionId(), corrId, response.chunkSeq(),
                response.chunkCount(), response.receivedAtMs(), response.replayed());
    }

    // ----- GET /sessions/{sessionId}/status (PR-gw-01B-core real values) ----

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
                        // PR-gw-01B-core: gerçek chunkCount + lastChunkSeq (init -1; surface'de
                        // 0 fallback — Codex `019e8d78` iter-1 ambiguity note absorb).
                        (int) Math.min(record.chunkCount(), Integer.MAX_VALUE),
                        Math.max(0L, record.lastAcceptedChunkSeq()),
                        record.state() == SessionState.FINISHED
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

    private ResponseEntity<?> validateStartFormatGuards(final StartSessionRequest req, final String corrId) {
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

    // ----- chunk header parsing helpers (Codex `019e8d78` iter-2 AGREE) ----

    private static Long parseLongHeader(final HttpHeaders headers, final String name) {
        final String v = headers.getFirst(name);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseIntHeader(final HttpHeaders headers, final String name) {
        final Long v = parseLongHeader(headers, name);
        if (v == null || v > Integer.MAX_VALUE || v < Integer.MIN_VALUE) {
            return null;
        }
        return v.intValue();
    }

    private static AudioFormat parseAudioFormatHeader(final HttpHeaders headers) {
        final String v = headers.getFirst(HDR_AUDIO_FORMAT);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return AudioFormat.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String sha256Hex(final byte[] bytes) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private StartSessionResponse buildStartResponse(final SessionRecord record, final String corrId) {
        final String base = "/api/v1/audio-gateway/sessions/" + record.sessionId();
        return new StartSessionResponse(
                record.sessionId(),
                corrId,
                base + "/stream",            // PR-gw-01D (planned contract)
                base + "/chunks",            // PR-gw-01B-core LIVE
                base + "/status",            // PR-gw-01A LIVE
                base + "/finish",            // PR-gw-01A LIVE
                record.sessionStartMs());
    }

    /**
     * ByteBuffer wrapper for dispatcher payload (PR-gw-01B3 placeholder — read-only
     * buffer + length + SHA-256 hash). Codex {@code 019e8d78} iter-2 AGREE.
     */
    @SuppressWarnings("unused")
    private static ByteBuffer toReadOnlyBuffer(final byte[] bytes) {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }
}
