package com.serban.notify.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body for {@code DELETE /api/v1/notify/preferences/me}
 * (Faz 23.6 PR-A1).
 *
 * <p>The restore-defaults endpoint hard-deletes every preference row
 * owned by the caller in one transaction; the response carries the
 * count for audit / UX feedback (e.g. "5 kural temizlendi").
 *
 * <p>The shape is a typed record (not a {@code Map}) so the FE contract
 * stays stable as the OpenAPI artefact (Codex thread {@code 019e0376}
 * iter-2 absorb).
 */
public record PreferenceRestoreDefaultsResponse(
    @JsonProperty("deletedCount") int deletedCount
) {}
