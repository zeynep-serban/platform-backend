package com.example.audiogateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Start a recording session — POST /api/meeting-audio/sessions client→Gateway request.
 *
 * <p>tenantId / userId fields are NEVER trusted from client; Gateway derives them
 * from JWT after validation (Codex RED).
 */
public record StartSessionRequest(

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^MTG-[0-9]{4}-[0-9]{1,8}$",
                message = "meetingId must match MTG-YYYY-N format")
        String meetingId,

        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$",
                message = "deviceId opaque token, 1-64 chars, [A-Za-z0-9._-]")
        String deviceId,

        @NotBlank
        @Size(min = 2, max = 10)
        @Pattern(regexp = "^[a-z]{2}(-[A-Z]{2})?$",
                message = "language ISO 639-1 (e.g. tr, en, de) optionally with region")
        String language,

        @NotNull
        AudioFormat audioFormat,

        @NotNull
        Integer sampleRateHz,

        @NotNull
        Integer channels,

        @Size(max = 64)
        String idempotencyKey
) {

    /** Allowed sample rates (Hz) — Codex {@code 019e879c} fixed enum. */
    public static final java.util.Set<Integer> ALLOWED_SAMPLE_RATES = java.util.Set.of(16_000, 48_000);
}
