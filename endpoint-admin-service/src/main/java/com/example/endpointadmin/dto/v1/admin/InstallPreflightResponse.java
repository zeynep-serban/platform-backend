package com.example.endpointadmin.dto.v1.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * BE-021A — install-preflight decision wire payload (Faz 22.5).
 *
 * <p>Returned by
 * {@code GET /api/v1/admin/endpoint-devices/{deviceId}/install-preflight?catalogItemId=...}.
 * The response is the canonical PASS / WARN / BLOCK contract that
 * AG-027 (and operator UI WEB-012) consumes before issuing an install
 * command. The decision is computed on-demand from the BE-020 catalog
 * row, the BE-020I inventory snapshot, and the AG-026A wingetEgress
 * evidence — there is no persisted decision row (Codex 019e6b88
 * plan-time AGREE).
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code decision} — PASS, WARN, BLOCK. Never null.
 *   <li>{@code catalogItemId} — public slug (matches the BE-020 admin
 *       endpoint convention).
 *   <li>{@code catalogItemUuid} — internal UUID (operational identifier
 *       for cross-referencing audit / metrics).
 *   <li>{@code deviceId} — same UUID as the path segment, echoed for
 *       audit / tooling that consumes the response body alone.
 *   <li>{@code evaluatedAt} — backend evaluation timestamp.
 *   <li>{@code installedState} — INSTALLED | NOT_INSTALLED | UNKNOWN.
 *       UNKNOWN is the safe default for detection rules the inventory
 *       cannot definitively evaluate (FILE_EXISTS / FILE_SHA256), or
 *       when {@code appsAvailable=false} (Codex 019e6b88: never write
 *       false NOT_INSTALLED — that would falsely authorize a re-install
 *       prompt for an unknown state).
 *   <li>{@code evidence} — references to the rows / commands the
 *       decision was computed against. AG-027 / BE-021 must recompute
 *       the decision at command-creation time using these refs to detect
 *       drift (catalog revoked, inventory replaced, egress regressed).
 *   <li>{@code reasons} — every reason code the evaluator emitted
 *       (informational; reading order does not imply severity).
 *   <li>{@code blockingReasons} — subset of {@code reasons} that drove
 *       the BLOCK decision. Empty for PASS / WARN.
 *   <li>{@code warnings} — non-blocking reasons (PASS-with-warning or
 *       WARN; never causes BLOCK).
 *   <li>{@code requirements} — human-readable bullet items the operator
 *       must satisfy before re-evaluating (often empty for PASS; mirrors
 *       the BLOCK reasons in plain English for UI surface).
 * </ul>
 *
 * <p>The response is intentionally non-mutating: GET only, no audit
 * write on read. AG-027 command creation (which IS mutating) carries
 * its own audit chain.
 */
public record InstallPreflightResponse(
        InstallPreflightDecision decision,
        String catalogItemId,
        UUID catalogItemUuid,
        UUID deviceId,
        Instant evaluatedAt,
        InstalledState installedState,
        InstallPreflightEvidence evidence,
        List<String> reasons,
        List<String> blockingReasons,
        List<String> warnings,
        List<String> requirements) {

    public enum InstallPreflightDecision {
        PASS,
        WARN,
        BLOCK
    }

    public enum InstalledState {
        INSTALLED,
        NOT_INSTALLED,
        UNKNOWN
    }

    /**
     * Evidence pointer block: AG-027 + BE-021 recompute the decision at
     * command-creation time using these refs. Reuse of a cached PASS
     * response is explicitly forbidden (Codex 019e6b88 drift control).
     *
     * <p>Codex 019e6ba4 iter-1 absorb (P2#3): the block now carries the
     * inventory snapshot row version (Hibernate {@code @Version})
     * alongside the summary / full / egress command-result IDs.
     * Together they let AG-027 detect three independent drift signals
     * at command-creation time:
     *
     * <ul>
     *   <li>Catalog row revoked or disabled between preflight and
     *       command-create: detected via {@code catalogRowVersion}
     *       + {@code catalogLastUpdatedAt}.</li>
     *   <li>Inventory snapshot replaced (apps[] ingest, summary
     *       update, wingetEgress ingest) between preflight and
     *       command-create: detected via {@code inventorySnapshotRowVersion}
     *       (each ingest bumps {@code @Version}).</li>
     *   <li>Specific evidence stream replaced: detected via the
     *       individual {@code latestSummaryCommandResultId} /
     *       {@code latestFullCommandResultId} /
     *       {@code latestWingetEgressCommandResultId} pointers.</li>
     * </ul>
     *
     * <p>All fields nullable — the response is valid even when the
     * underlying evidence is missing (the matching reason codes will
     * appear in {@code reasons} / {@code blockingReasons}).
     */
    public record InstallPreflightEvidence(
            UUID inventorySnapshotId,
            Long inventorySnapshotRowVersion,
            Instant inventoryUpdatedAt,
            Instant summaryCollectedAt,
            Instant appsCollectedAt,
            UUID latestSummaryCommandResultId,
            UUID latestFullCommandResultId,
            UUID latestWingetEgressCommandResultId,
            Instant wingetEgressCollectedAt,
            Integer wingetEgressSchemaVersion,
            Long catalogRowVersion,
            Instant catalogLastUpdatedAt) {
    }
}
