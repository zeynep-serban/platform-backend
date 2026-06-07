package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateLocalPasswordChangeRequest(
        @NotBlank
        @Size(max = 128)
        String username,

        @Size(max = 40)
        String idempotencyKey,

        @NotBlank
        @Size(max = 512)
        String reason,

        Instant notBefore,

        Instant expiresAt) {
}
