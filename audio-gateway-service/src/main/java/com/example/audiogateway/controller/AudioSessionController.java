package com.example.audiogateway.controller;

import com.example.audiogateway.config.CorrelationIdWebFilter;
import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Audio Gateway Contract 1.0 — session lifecycle endpoints.
 *
 * <p>3-AI mutabakat (Codex {@code 019e879c} + {@code 019e8846} iter-1 absorb +
 * Mavis {@code msg 78} AGREE):
 * - tenantId/userId client-trusted DEĞİL — Gateway JWT claim'inden derive
 * - canonical {@link ErrorResponse} envelope tüm 4xx/5xx için body taşır
 * - claim adı configurable ({@code audio.gateway.jwt.tenant-claim}); backend
 *   pattern uyumlu default {@code companyId}; absent → fail-closed 403
 */
@RestController
@RequestMapping("/api/meeting-audio")
public class AudioSessionController {

    @Value("${audio.gateway.jwt.tenant-claim:companyId}")
    private String tenantClaim;

    @Value("${audio.gateway.jwt.user-claim:userId}")
    private String userClaim;

    @PostMapping("/sessions")
    public Mono<ResponseEntity<?>> startSession(
            @Valid @RequestBody final StartSessionRequest req,
            @AuthenticationPrincipal final Jwt jwt,
            final ServerWebExchange exchange) {

        final String corrId = (String) exchange.getAttributes().get(CorrelationIdWebFilter.ATTR_KEY);

        // 1. Sample rate enum (Codex 019e879c bounded enum)
        if (!StartSessionRequest.ALLOWED_SAMPLE_RATES.contains(req.sampleRateHz())) {
            return Mono.just(ResponseEntity.badRequest().body(ErrorResponse.of(
                    ErrorResponse.CODE_FORMAT_REJECTED,
                    "Unsupported sample rate: " + req.sampleRateHz(),
                    corrId, false)));
        }

        // 2. Channels guard (PoC mono only)
        if (req.channels() != 1) {
            return Mono.just(ResponseEntity.badRequest().body(ErrorResponse.of(
                    ErrorResponse.CODE_FORMAT_REJECTED,
                    "Stereo or multichannel not supported in PoC (channels must be 1)",
                    corrId, false)));
        }

        // 3. Audio format CLIENT_ALLOWED subset (Codex 019e879c whitelist)
        if (!AudioFormat.CLIENT_ALLOWED.contains(req.audioFormat())) {
            return Mono.just(ResponseEntity
                    .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_FORMAT_REJECTED,
                            "Audio format not in CLIENT_ALLOWED: " + req.audioFormat(),
                            corrId, false)));
        }

        // 4. tenantId / userId JWT-derived (Codex 019e879c RED — NEVER client-trusted)
        //    Backend canonical claim: companyId (Long); userId (Long). Configurable.
        //    Absent → fail-closed 403 (Codex 019e8846 iter-1 — sessiz "unknown" YASAK).
        if (jwt == null) {
            // SecurityConfig fail-closed bunu yakalar normalde; defansif kontrol
            return Mono.just(ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_AUTH_INVALID,
                            "JWT principal missing",
                            corrId, false)));
        }

        final Object tenantValue = jwt.getClaim(tenantClaim);
        if (tenantValue == null) {
            return Mono.just(ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_MEETING_FORBIDDEN,
                            "JWT missing required claim '" + tenantClaim + "'",
                            corrId, false)));
        }

        final Object userValue = jwt.getClaim(userClaim);
        if (userValue == null) {
            return Mono.just(ResponseEntity
                    .status(HttpStatus.FORBIDDEN)
                    .body(ErrorResponse.of(
                            ErrorResponse.CODE_MEETING_FORBIDDEN,
                            "JWT missing required claim '" + userClaim + "'",
                            corrId, false)));
        }

        // 5. Build response (internal headers will be derived in downstream PR-queue-01)
        final String sessionId = "SES-" + UUID.randomUUID();
        final long now = Instant.now().toEpochMilli();
        final StartSessionResponse resp = new StartSessionResponse(
                sessionId,
                corrId,
                "/api/meeting-audio/sessions/" + sessionId + "/stream",
                "/api/meeting-audio/sessions/" + sessionId + "/chunks",
                now);
        return Mono.just(ResponseEntity.ok(resp));
    }
}
