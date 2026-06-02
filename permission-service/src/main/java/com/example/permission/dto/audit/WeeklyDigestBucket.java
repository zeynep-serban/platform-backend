package com.example.permission.dto.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * PR-D2.5a (Codex 019e8708 AGREE option A constrained): Single week's
 * aggregated audit event digest.
 *
 * <p>ISO week semantics: {@code weekStart} is Monday 00:00 UTC, {@code weekEnd}
 * is Sunday 23:59:59 UTC. {@code isoYear} + {@code isoWeek} follow ISO-8601
 * (week containing first Thursday of the year is week 1). Pazartesi/Pazar
 * boundary semantics tested in WeeklyDigestBucketWeekBoundaryTest.
 *
 * <p>{@code totalEventCount} is the raw row count (no scope masking applied
 * beyond filter pushdown). {@code distinctUserCount} counts distinct
 * {@code performed_by} user IDs; null/system events excluded from this metric.
 *
 * <p>{@code actionBreakdown} and {@code serviceBreakdown} are dense maps:
 * keys with zero events are omitted. Sorted alphabetically for stable JSON
 * serialization (LinkedHashMap insertion order from repository ORDER BY).
 *
 * <p>{@code topUsers} list is bounded by the request's {@code topK} (default
 * 5, max 20 enforced server-side). Deterministic tie-break: eventCount DESC,
 * userId ASC.
 */
public record WeeklyDigestBucket(
        Instant weekStart,
        Instant weekEnd,
        int isoYear,
        int isoWeek,
        long totalEventCount,
        long distinctUserCount,
        Map<String, Long> actionBreakdown,
        Map<String, Long> serviceBreakdown,
        List<TopUser> topUsers
) {
}
