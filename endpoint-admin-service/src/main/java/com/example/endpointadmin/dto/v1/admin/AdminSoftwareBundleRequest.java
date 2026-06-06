package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * BE-029 — create payload for an approved package bundle.
 *
 * <p>The request intentionally carries catalog item slugs, not package ids.
 * The service resolves every slug to an existing APPROVED+enabled catalog row.
 */
public record AdminSoftwareBundleRequest(
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*",
                message = "bundleId must be a slug "
                        + "([A-Za-z0-9._-], not starting with '.' or '-').")
        String bundleId,

        @NotBlank
        @Size(max = 256)
        String displayName,

        @Size(max = 1024)
        String description,

        @NotEmpty
        @Size(max = 32)
        List<
                @NotBlank
                @Size(max = 128)
                @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
                String> catalogItemIds
) {
}
