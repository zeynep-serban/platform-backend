package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Wave-12 PR-5 — actor in the policy-change approval contract. Mirrors
 * the platform-web {@code ApprovalActor} type ({@code id}, {@code name},
 * {@code role}). The {@code id} is the persisted subject (Keycloak
 * {@code sub} or equivalent stable identifier); {@code name} and
 * {@code role} are display-only echo fields.
 */
public record ApprovalActorDto(
        @NotBlank @Size(max = 255) String id,
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 64) String role
) {
}
