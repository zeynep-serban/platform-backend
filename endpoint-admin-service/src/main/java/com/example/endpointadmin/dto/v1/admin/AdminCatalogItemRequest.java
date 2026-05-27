package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.CatalogInstallerType;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogRiskTier;
import com.example.endpointadmin.model.CatalogSilentArgsPolicy;
import com.example.endpointadmin.model.CatalogSourceTrust;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CatalogVersionPolicyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * BE-020 — admin create/update payload for an
 * {@link com.example.endpointadmin.model.EndpointSoftwareCatalogItem}.
 *
 * <p>{@code catalogItemId} is the stable slug; tenant-scoped uniqueness is
 * enforced by the DB unique constraint
 * {@code uq_endpoint_software_catalog_items_tenant_catalog_item}.
 *
 * <p>{@code detectionRule} is a raw JSON object whose shape is validated at
 * the service layer by {@code DetectionRuleValidator}; only the MVP allowlist
 * of discriminator types is accepted. Raw command / shell variants are
 * service-rejected.
 *
 * <p>{@code silentArgsPolicy} is a policy keyword (e.g. {@code "DEFAULT"} /
 * {@code "VENDOR_RECOMMENDED"}); arbitrary command-line args are never
 * accepted here.
 */
public record AdminCatalogItemRequest(
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*",
                message = "catalogItemId must be a slug "
                        + "([A-Za-z0-9._-], not starting with '.' or '-').")
        String catalogItemId,

        @NotNull
        CatalogProvider provider,

        @NotNull
        CatalogSourceType sourceType,

        @NotBlank
        @Size(max = 64)
        String sourceName,

        @NotNull
        CatalogSourceTrust sourceTrust,

        @NotBlank
        @Size(max = 128)
        String packageId,

        @NotBlank
        @Size(max = 256)
        String displayName,

        @NotBlank
        @Size(max = 128)
        String publisher,

        @NotNull
        CatalogVersionPolicyType versionPolicyType,

        @Size(max = 64)
        String versionPolicyValue,

        CatalogInstallerType installerType,

        CatalogSilentArgsPolicy silentArgsPolicy,

        @Size(min = 64, max = 64,
                message = "sha256 must be a 64-character hex digest.")
        @Pattern(regexp = "[0-9a-fA-F]{64}",
                message = "sha256 must be a hex SHA-256 digest.")
        String sha256,

        @Size(max = 256)
        String provenance,

        @NotNull
        Map<String, Object> detectionRule,

        @NotNull
        CatalogRiskTier riskTier
) {
}
