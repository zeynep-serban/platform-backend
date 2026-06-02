package com.example.permission.dto.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * PR-D2.5a (Codex 019e8708 AGREE option A constrained): Top-level response
 * for {@code GET /api/audit/events/digest}.
 *
 * <p>{@code weeks} is ordered by {@code weekStart} ASC. Empty list when the
 * requested date range contains no events (200 OK + empty array, not 404).
 *
 * <p>{@code filterEcho} returns the effective normalized filters used by the
 * aggregation query — operators inspecting the response can verify their
 * intended scope. Includes resolved {@code dateFrom}/{@code dateTo} (after
 * any default expansion or coercion), {@code topK}, and the applied
 * action/service/level/user filters. Does NOT echo back the raw query
 * string verbatim.
 *
 * <p>{@code computedAt} is the server-side wall-clock at response build time
 * (NOT cached; recomputed per request).
 *
 * @param weeks       Ordered ISO weeks (asc by weekStart).
 * @param filterEcho  Normalized filters that produced the result.
 * @param computedAt  Server timestamp at response build (UTC).
 */
public record AuditWeeklyDigestResponse(
        List<WeeklyDigestBucket> weeks,
        Map<String, Object> filterEcho,
        Instant computedAt
) {
}
