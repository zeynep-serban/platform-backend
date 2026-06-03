package com.example.audiogateway.service;

/**
 * Audio session lifecycle state machine.
 *
 * <p>PR-gw-01A + PR-gw-01B-core scope (Codex {@code 019e8d78} iter-2 AGREE):
 * <ul>
 *   <li>{@code STARTED}: session created via POST /sessions; awaiting first chunk</li>
 *   <li>{@code STREAMING}: first chunk admitted; chunk admission continues (PR-gw-01B-core)</li>
 *   <li>{@code FINISHING}: finish requested; dispatcher flush in progress (mock OK in A/B; real C)</li>
 *   <li>{@code FINISHED}: dispatcher flush complete; immutable terminal state</li>
 * </ul>
 *
 * <p>Valid transitions:
 * <ul>
 *   <li>STARTED → STREAMING (first chunk admitted)</li>
 *   <li>STARTED → FINISHED (finish without any chunk — terminal lifecycle event)</li>
 *   <li>STREAMING → STREAMING (subsequent chunks)</li>
 *   <li>STREAMING → FINISHED (finish after chunks)</li>
 * </ul>
 *
 * <p>Invalid transitions yield {@code 409 AUDIO_GATEWAY_INVALID_TRANSITION}: chunk admission
 * after FINISHED, finish from FINISHING, etc. Detailed transition matrix hardening is
 * PR-gw-01E scope.
 */
public enum SessionState {
    STARTED,
    STREAMING,
    FINISHING,
    FINISHED
}
