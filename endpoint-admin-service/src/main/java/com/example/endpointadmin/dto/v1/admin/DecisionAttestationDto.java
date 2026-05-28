package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Wave-12 PR-5 — nested {@code attestation} block on an {@code attest}
 * decision record. Mirrors the platform-web {@code DecisionAttestation}
 * type ({@code statement} + {@code acceptedAt}).
 */
public record DecisionAttestationDto(
        @NotBlank @Size(max = 4096) String statement,
        @NotNull Instant acceptedAt
) {
}
