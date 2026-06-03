package com.example.audiogateway.service;

/**
 * Audio session lifecycle state machine.
 *
 * <p>PR-gw-01A scope:
 * <ul>
 *   <li>{@code STARTED}: session created via POST /sessions; awaiting chunks (PR-gw-01B/C/D)</li>
 *   <li>{@code FINISHING}: finish requested; dispatcher flush in progress (mock OK in A)</li>
 *   <li>{@code FINISHED}: dispatcher flush complete; immutable terminal state</li>
 * </ul>
 *
 * <p>Streaming-related transitions (chunk admission, queue full, WS close) are out of
 * PR-gw-01A scope — slice B/C/D extends this enum if needed (e.g. {@code STREAMING},
 * {@code ERROR}).
 */
public enum SessionState {
    STARTED,
    FINISHING,
    FINISHED
}
