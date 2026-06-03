package com.example.audiogateway.dto;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Audio chunk payload — read-only ByteBuffer + length + SHA-256 hash.
 *
 * <p>Codex {@code 019e8c26} iter-2 + {@code 019e8df2} iter-2 AGREE PR-gw-01B3 scope:
 * dispatcher payload type canonical {@code ByteBuffer.asReadOnlyBuffer()} — single-use
 * InputStream YASAK; mutability riskini kapatmak için read-only buffer.
 *
 * <p>{@code sha256} internal idempotency identity; response body/error envelope'a TAM
 * hash KOYMA (Codex iter-2 PII boundary). Audit/log için prefix veya internal-only.
 */
public record AudioChunkPayload(ByteBuffer bytes, int length, String sha256) {

    public AudioChunkPayload {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(sha256, "sha256");
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative");
        }
    }

    public static AudioChunkPayload of(final byte[] raw, final String sha256Hex) {
        return new AudioChunkPayload(
                ByteBuffer.wrap(raw.clone()).asReadOnlyBuffer(), raw.length, sha256Hex);
    }
}
