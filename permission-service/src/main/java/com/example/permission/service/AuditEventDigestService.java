package com.example.permission.service;

import com.example.permission.audit.AuditReadScope;
import com.example.permission.dto.audit.AuditWeeklyDigestResponse;
import com.example.permission.dto.audit.TopUser;
import com.example.permission.dto.audit.WeeklyDigestBucket;
import com.example.permission.repository.AuditEventDigestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PR-D2.5a (Codex 019e8708 AGREE option A constrained): Source-owned weekly
 * audit event digest aggregation.
 *
 * <p>Orchestrates 4 native PG queries (totals, action breakdown, service
 * breakdown, top-K users) and merges them into per-week
 * {@link WeeklyDigestBucket} entries. All aggregation pushed to the database
 * — NO client-side reduce over paged events (HARD RULE No Fake Work guard;
 * Codex 019e8708 explicit rejection of option B/C).
 *
 * <p>Acceptance constraints enforced server-side:
 * <ul>
 *   <li>{@code dateFrom} and {@code dateTo} required (400 if absent or
 *       unparseable)</li>
 *   <li>{@code dateFrom < dateTo} required</li>
 *   <li>Date range max bounded ({@link #MAX_RANGE_DAYS} days)</li>
 *   <li>{@code topK} bounded {@code [1, MAX_TOP_K]} (default
 *       {@link #DEFAULT_TOP_K})</li>
 * </ul>
 */
@Service
public class AuditEventDigestService {

    private static final Logger log = LoggerFactory.getLogger(AuditEventDigestService.class);

    /** Default top-K user count if request omits. */
    public static final int DEFAULT_TOP_K = 5;

    /** Hard server-side max for top-K. Codex 019e8708 risk mitigation. */
    public static final int MAX_TOP_K = 20;

    /**
     * Hard server-side max for date range. Codex 019e8708 risk mitigation
     * — prevents unbounded full-table aggregation. Roughly one year.
     */
    public static final int MAX_RANGE_DAYS = 366;

    private final AuditEventDigestRepository digestRepository;

    public AuditEventDigestService(AuditEventDigestRepository digestRepository) {
        this.digestRepository = digestRepository;
    }

    public AuditWeeklyDigestResponse aggregate(Instant dateFrom,
                                               Instant dateTo,
                                               String action,
                                               String service,
                                               String level,
                                               String userIdentity,
                                               String search,
                                               Integer topKRaw,
                                               AuditReadScope scope) {
        // ── Validation (fail-closed, 400 on violation) ────────────────────
        if (dateFrom == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dateFrom required (ISO-8601, e.g. 2026-05-26T00:00:00Z)");
        }
        if (dateTo == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dateTo required (ISO-8601, e.g. 2026-06-01T23:59:59Z)");
        }
        if (!dateFrom.isBefore(dateTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "dateFrom must be strictly before dateTo");
        }
        // P2 absorb (Codex 019e8721): full Duration compare, not toDays() truncation
        Duration range = Duration.between(dateFrom, dateTo);
        Duration maxRange = Duration.ofDays(MAX_RANGE_DAYS);
        if (range.compareTo(maxRange) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Date range exceeds max " + MAX_RANGE_DAYS + " days (got " + range + ")");
        }

        int topK = topKRaw == null ? DEFAULT_TOP_K : topKRaw;
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "topK out of bounds [1, " + MAX_TOP_K + "] (got " + topK + ")");
        }

        if (scope == null) {
            // Default to GENERIC_AUDIT — never silently expand caller's view.
            scope = AuditReadScope.GENERIC_AUDIT;
        }

        // ── Run 4 native queries ──────────────────────────────────────────
        List<Object[]> totalRows = digestRepository.findWeeklyTotals(
                dateFrom, dateTo, action, service, level, userIdentity, search, scope);
        List<Object[]> actionRows = digestRepository.findActionBreakdown(
                dateFrom, dateTo, action, service, level, userIdentity, search, scope);
        List<Object[]> serviceRows = digestRepository.findServiceBreakdown(
                dateFrom, dateTo, action, service, level, userIdentity, search, scope);
        List<Object[]> topUserRows = digestRepository.findTopUsersPerWeek(
                dateFrom, dateTo, action, service, level, userIdentity, search, scope, topK);

        // ── Merge into per-week buckets (preserves week_start ASC order) ──
        Map<Instant, Map<String, Long>> actionByWeek =
                AuditEventDigestRepository.bucketBreakdown(actionRows);
        Map<Instant, Map<String, Long>> serviceByWeek =
                AuditEventDigestRepository.bucketBreakdown(serviceRows);
        Map<Instant, List<TopUser>> topUsersByWeek =
                AuditEventDigestRepository.bucketTopUsers(topUserRows);

        List<WeeklyDigestBucket> weeks = new ArrayList<>(totalRows.size());
        for (Object[] row : totalRows) {
            Instant weekStart = AuditEventDigestRepository.timestampToInstantUtc((Timestamp) row[0]);
            int isoYear = ((Number) row[1]).intValue();
            int isoWeek = ((Number) row[2]).intValue();
            long totalCount = ((Number) row[3]).longValue();
            long distinctUsers = ((Number) row[4]).longValue();

            // weekEnd = weekStart + 7 days - 1 second (inclusive Sunday 23:59:59)
            Instant weekEnd = weekStart.plus(Duration.ofDays(7).minusSeconds(1));

            // Defensive: ISO year/week from PG may differ from JDK's IsoFields
            // for boundary weeks (PG uses ISO 8601 by default; cross-check).
            LocalDate weekStartLocal = OffsetDateTime.ofInstant(weekStart, ZoneOffset.UTC).toLocalDate();
            int jdkIsoYear = weekStartLocal.get(IsoFields.WEEK_BASED_YEAR);
            int jdkIsoWeek = weekStartLocal.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
            if (jdkIsoYear != isoYear || jdkIsoWeek != isoWeek) {
                log.debug("ISO week drift: PG=({},{}) JDK=({},{}) weekStart={}",
                        isoYear, isoWeek, jdkIsoYear, jdkIsoWeek, weekStart);
                // Trust PG result; JDK cross-check is informational.
            }

            Map<String, Long> actionBreakdown = actionByWeek.getOrDefault(
                    weekStart, Collections.emptyMap());
            Map<String, Long> serviceBreakdown = serviceByWeek.getOrDefault(
                    weekStart, Collections.emptyMap());
            List<TopUser> topUsers = topUsersByWeek.getOrDefault(
                    weekStart, Collections.emptyList());

            weeks.add(new WeeklyDigestBucket(
                    weekStart, weekEnd, isoYear, isoWeek,
                    totalCount, distinctUsers,
                    actionBreakdown, serviceBreakdown, topUsers));
        }

        // ── Build filter echo ─────────────────────────────────────────────
        Map<String, Object> filterEcho = new LinkedHashMap<>();
        filterEcho.put("dateFrom", dateFrom.toString());
        filterEcho.put("dateTo", dateTo.toString());
        filterEcho.put("topK", topK);
        if (action != null && !action.isBlank()) filterEcho.put("action", action);
        if (service != null && !service.isBlank()) filterEcho.put("service", service);
        if (level != null && !level.isBlank()) filterEcho.put("level", level);
        if (userIdentity != null && !userIdentity.isBlank()) filterEcho.put("user", userIdentity);
        if (search != null && !search.isBlank()) filterEcho.put("search", search);
        filterEcho.put("scope", scope.name());

        return new AuditWeeklyDigestResponse(weeks, filterEcho, Instant.now());
    }
}
