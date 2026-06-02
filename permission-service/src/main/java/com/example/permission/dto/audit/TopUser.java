package com.example.permission.dto.audit;

/**
 * PR-D2.5a (Codex 019e8708 AGREE option A constrained): Single top-user entry
 * within a weekly digest bucket.
 *
 * <p>Identity field choice (R2-Q1 from plan): canonical identity = {@code userId}
 * (numeric, immutable). {@code userEmail} is the display key — may be null for
 * legacy events or system actors. Deterministic tie-break order (count desc,
 * then userId asc) guarantees reproducible top-K rendering.
 *
 * @param userId      Numeric user ID (immutable; null only for system events
 *                    where {@code performed_by} is unset).
 * @param userEmail   Display email at time of aggregation (may be stale; null
 *                    if user record purged but events remain).
 * @param eventCount  Count of events attributed to this user in the week.
 */
public record TopUser(
        Long userId,
        String userEmail,
        long eventCount
) {
}
