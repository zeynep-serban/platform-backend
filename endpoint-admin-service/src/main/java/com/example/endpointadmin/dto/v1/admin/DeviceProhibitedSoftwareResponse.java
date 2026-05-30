package com.example.endpointadmin.dto.v1.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * BE-025 — Per-device prohibited-software findings, read from the last
 * PERSISTED compliance evaluation's evidence (Faz 22.5).
 *
 * <p>The GET endpoint does NOT recompute live (Codex 019e7623 (d) absorb):
 * a live recompute inside a GET would let the device's compliance state and
 * this response diverge, and would make the read path expensive + side-
 * effecting. Instead it reads the {@code prohibitedInstalled} projection
 * the AFTER_COMMIT evaluator already persisted into
 * {@code EndpointComplianceEvaluation.evidence}. Re-evaluation is the
 * existing manager-only force-evaluate action — not this GET.
 *
 * <p>Always returned with HTTP 200 (even when there is no evaluation yet)
 * so a cross-tenant / unknown device is indistinguishable from "no findings
 * yet" — device existence does not leak (mirrors the BE-022Q / BE-024
 * no-leak discipline). The {@code status} field tells the client how to
 * render.
 *
 * @param deviceId       echo of the requested device
 * @param status         OK (an evaluation exists) / NO_EVALUATION (none yet,
 *                       OR unknown / cross-tenant device)
 * @param decision       the persisted device {@link
 *                       com.example.endpointadmin.model.ComplianceDecision}
 *                       at evaluation time (string form; UNAUTHORIZED when a
 *                       prohibited match drove it), or null when none
 * @param evaluatedAt    when that evaluation ran (freshness signal), or null
 * @param inventorySnapshotId the inventory snapshot the evaluation read, or
 *                       null — lets the client correlate the finding set with
 *                       a specific collection
 * @param findings       the prohibited-software findings (possibly empty)
 */
public record DeviceProhibitedSoftwareResponse(
        UUID deviceId,
        Status status,
        String decision,
        Instant evaluatedAt,
        UUID inventorySnapshotId,
        List<ProhibitedSoftwareFindingResponse> findings) {

    /** Why the findings list is (or is not) populated. */
    public enum Status {
        OK,
        NO_EVALUATION
    }

    /** No persisted evaluation (also the unknown / cross-tenant answer). */
    public static DeviceProhibitedSoftwareResponse noEvaluation(UUID deviceId) {
        return new DeviceProhibitedSoftwareResponse(
                deviceId, Status.NO_EVALUATION, null, null, null, List.of());
    }
}
