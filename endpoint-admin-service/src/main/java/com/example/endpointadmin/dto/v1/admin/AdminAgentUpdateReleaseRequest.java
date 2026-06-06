package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.AgentUpdateChannel;
import com.example.endpointadmin.model.AgentUpdateSigningTier;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminAgentUpdateReleaseRequest(
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*",
                message = "releaseId must be a slug "
                        + "([A-Za-z0-9._-], not starting with '.' or '-').")
        String releaseId,

        @NotNull
        AgentUpdateChannel channel,

        @NotBlank
        @Size(max = 64)
        String targetVersion,

        @NotBlank
        @Size(max = 2048)
        String binaryUrl,

        @Size(max = 2048)
        String manifestUrl,

        @NotBlank
        @Size(min = 64, max = 64)
        @Pattern(regexp = "[A-Fa-f0-9]{64}")
        String sha256,

        @Size(min = 128, max = 128)
        @Pattern(regexp = "[A-Fa-f0-9]{128}")
        String sha512,

        @NotBlank
        @Size(max = 96)
        String signerThumbprint,

        @NotNull
        AgentUpdateSigningTier signingTier,

        @Min(1)
        @Max(524288000)
        long maxBytes,

        @Size(max = 2048)
        String releaseNotes
) {
}
