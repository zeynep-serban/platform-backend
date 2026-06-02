package com.example.permission.repository;

import com.example.permission.audit.AuditReadScope;
import com.example.permission.audit.ImpersonationActionPredicate;
import com.example.permission.dto.audit.TopUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PR-D2.5a (Codex 019e8708 plan + 019e8721 post-impl REVISE absorb):
 * Native PG queries for weekly audit digest aggregation.
 *
 * <p>iter-1 absorb fixes:
 * <ul>
 *   <li><b>P1 read-scope leak</b>: GENERIC_AUDIT scope explicitly excludes
 *       IMPERSONATION_* events from aggregation. Adds {@code AND
 *       (action IS NULL OR action NOT IN (:impersonationActions))} predicate.
 *       IMPERSONATION_AUDIT scope includes only those actions (mirror of
 *       AuditEventService.matchesScope semantics).</li>
 *   <li><b>P1 identity fallback</b>: Digest identity =
 *       {@code COALESCE(performed_by::text, NULLIF(user_email, ''))}.
 *       Legacy events with only {@code user_email} (no userId) are counted
 *       in distinctUserCount and topUsers.</li>
 *   <li><b>P1 timezone correctness</b>: Removed {@code AT TIME ZONE 'UTC'}
 *       since {@code occurred_at} is {@code TIMESTAMP without time zone}
 *       and Java {@link Instant} parameters are bound via
 *       {@link Timestamp#from(Instant)} which writes UTC wall-clock.
 *       PG {@code DATE_TRUNC('week', ...)} on TIMESTAMP without TZ uses
 *       wall-clock — deterministic UTC if column written UTC.</li>
 * </ul>
 *
 * <p>All aggregation pushed to DB (Codex No Fake Work guard).
 */
@Repository
public class AuditEventDigestRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public List<Object[]> findWeeklyTotals(Instant dateFrom,
                                           Instant dateTo,
                                           String action,
                                           String service,
                                           String level,
                                           String userIdentity,
                                           String search,
                                           AuditReadScope scope) {
        String sql = "SELECT " +
                "  DATE_TRUNC('week', occurred_at) AS week_start, " +
                "  EXTRACT(ISOYEAR FROM occurred_at)::int AS iso_year, " +
                "  EXTRACT(WEEK FROM occurred_at)::int AS iso_week, " +
                "  COUNT(*) AS total_count, " +
                "  COUNT(DISTINCT COALESCE(performed_by::text, NULLIF(user_email, ''))) AS distinct_user_count " +
                "FROM permission_audit_events " +
                "WHERE occurred_at >= :dateFrom AND occurred_at < :dateTo " +
                buildScopeClause(scope) +
                buildFilterClauses(action, service, level, userIdentity, search) +
                "GROUP BY week_start, iso_year, iso_week " +
                "ORDER BY week_start ASC";

        Query query = bindFilters(entityManager.createNativeQuery(sql),
                dateFrom, dateTo, action, service, level, userIdentity, search, scope);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    public List<Object[]> findActionBreakdown(Instant dateFrom,
                                              Instant dateTo,
                                              String action,
                                              String service,
                                              String level,
                                              String userIdentity,
                                              String search,
                                              AuditReadScope scope) {
        String sql = "SELECT " +
                "  DATE_TRUNC('week', occurred_at) AS week_start, " +
                "  COALESCE(action, '<null>') AS action_key, " +
                "  COUNT(*) AS action_count " +
                "FROM permission_audit_events " +
                "WHERE occurred_at >= :dateFrom AND occurred_at < :dateTo " +
                buildScopeClause(scope) +
                buildFilterClauses(action, service, level, userIdentity, search) +
                "GROUP BY week_start, action_key " +
                "ORDER BY week_start ASC, action_key ASC";

        Query query = bindFilters(entityManager.createNativeQuery(sql),
                dateFrom, dateTo, action, service, level, userIdentity, search, scope);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    public List<Object[]> findServiceBreakdown(Instant dateFrom,
                                               Instant dateTo,
                                               String action,
                                               String service,
                                               String level,
                                               String userIdentity,
                                               String search,
                                               AuditReadScope scope) {
        String sql = "SELECT " +
                "  DATE_TRUNC('week', occurred_at) AS week_start, " +
                "  COALESCE(service, '<null>') AS service_key, " +
                "  COUNT(*) AS service_count " +
                "FROM permission_audit_events " +
                "WHERE occurred_at >= :dateFrom AND occurred_at < :dateTo " +
                buildScopeClause(scope) +
                buildFilterClauses(action, service, level, userIdentity, search) +
                "GROUP BY week_start, service_key " +
                "ORDER BY week_start ASC, service_key ASC";

        Query query = bindFilters(entityManager.createNativeQuery(sql),
                dateFrom, dateTo, action, service, level, userIdentity, search, scope);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    /**
     * Per-week top-K users by event count.
     *
     * <p>Identity key: {@code COALESCE(performed_by::text, NULLIF(user_email, ''))}.
     * Deterministic tie-break: count DESC, identity_key ASC.
     *
     * @return rows: [weekStart, performedBy (Long, may be null), userEmail (String, may be null), eventCount]
     */
    public List<Object[]> findTopUsersPerWeek(Instant dateFrom,
                                              Instant dateTo,
                                              String action,
                                              String service,
                                              String level,
                                              String userIdentity,
                                              String search,
                                              AuditReadScope scope,
                                              int topK) {
        // Identity key NOT NULL guard: exclude rows where BOTH performed_by
        // and user_email are null (truly anonymous system events).
        String sql = "SELECT week_start, performed_by, user_email, event_count " +
                "FROM ( " +
                "  SELECT " +
                "    DATE_TRUNC('week', occurred_at) AS week_start, " +
                "    COALESCE(performed_by::text, NULLIF(user_email, '')) AS identity_key, " +
                "    MAX(performed_by) AS performed_by, " +
                "    MAX(user_email) AS user_email, " +
                "    COUNT(*) AS event_count, " +
                "    ROW_NUMBER() OVER ( " +
                "      PARTITION BY DATE_TRUNC('week', occurred_at) " +
                "      ORDER BY COUNT(*) DESC, COALESCE(performed_by::text, NULLIF(user_email, '')) ASC " +
                "    ) AS rn " +
                "  FROM permission_audit_events " +
                "  WHERE occurred_at >= :dateFrom AND occurred_at < :dateTo " +
                "    AND (performed_by IS NOT NULL OR (user_email IS NOT NULL AND user_email <> '')) " +
                buildScopeClause(scope) +
                buildFilterClauses(action, service, level, userIdentity, search) +
                "  GROUP BY week_start, identity_key " +
                ") ranked " +
                "WHERE rn <= :topK " +
                "ORDER BY week_start ASC, rn ASC";

        Query query = bindFilters(entityManager.createNativeQuery(sql),
                dateFrom, dateTo, action, service, level, userIdentity, search, scope);
        query.setParameter("topK", topK);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    // ──────────────────────────────────────────────────────────────────────

    /**
     * P1 read-scope clause (Codex 019e8721 absorb): GENERIC_AUDIT excludes
     * IMPERSONATION_* actions; IMPERSONATION_AUDIT includes only those.
     */
    private static String buildScopeClause(AuditReadScope scope) {
        return switch (scope) {
            case GENERIC_AUDIT -> "AND (action IS NULL OR action NOT IN (:impersonationActions)) ";
            case IMPERSONATION_AUDIT -> "AND action IN (:impersonationActions) ";
        };
    }

    private static String buildFilterClauses(String action,
                                             String service,
                                             String level,
                                             String userIdentity,
                                             String search) {
        StringBuilder sb = new StringBuilder();
        if (action != null && !action.isBlank()) {
            sb.append("AND action = :action ");
        }
        if (service != null && !service.isBlank()) {
            sb.append("AND service = :service ");
        }
        if (level != null && !level.isBlank()) {
            sb.append("AND level = :level ");
        }
        if (userIdentity != null && !userIdentity.isBlank()) {
            // Identity filter: try numeric -> performed_by; else string -> user_email.
            // SQL handles both via OR — Java caller binds :userIdAsLong (nullable) +
            // :userEmail (nullable). Server-side normalization happens in caller.
            sb.append("AND (performed_by::text = :userIdentity OR user_email = :userIdentity) ");
        }
        if (search != null && !search.isBlank()) {
            sb.append("AND (user_email ILIKE :search OR details ILIKE :search) ");
        }
        return sb.toString();
    }

    private static Query bindFilters(Query query,
                                     Instant dateFrom,
                                     Instant dateTo,
                                     String action,
                                     String service,
                                     String level,
                                     String userIdentity,
                                     String search,
                                     AuditReadScope scope) {
        query.setParameter("dateFrom", Timestamp.from(dateFrom));
        query.setParameter("dateTo", Timestamp.from(dateTo));
        // P2 absorb (Codex 019e8721 iter-2): use Hibernate setParameterList
        // for IN/NOT IN collection binding (Set<String> via setParameter is
        // unreliable across JPA providers; List + setParameterList is the
        // canonical Hibernate path).
        java.util.List<String> impersonationActions =
                java.util.List.copyOf(ImpersonationActionPredicate.allActions());
        query.unwrap(org.hibernate.query.NativeQuery.class)
                .setParameterList("impersonationActions", impersonationActions);
        if (action != null && !action.isBlank()) {
            query.setParameter("action", action);
        }
        if (service != null && !service.isBlank()) {
            query.setParameter("service", service);
        }
        if (level != null && !level.isBlank()) {
            query.setParameter("level", level);
        }
        if (userIdentity != null && !userIdentity.isBlank()) {
            query.setParameter("userIdentity", userIdentity);
        }
        if (search != null && !search.isBlank()) {
            query.setParameter("search", "%" + search + "%");
        }
        return query;
    }

    /**
     * Convert a {@link Timestamp} (PG TIMESTAMP without time zone) to
     * {@link Instant} treating the wall-clock value as UTC.
     *
     * <p>Java code writes {@code Timestamp.from(instant)} which stores the
     * UTC wall-clock; the inverse read here recovers the {@link Instant}
     * without host-timezone drift regardless of JVM default timezone.
     */
    public static Instant timestampToInstantUtc(Timestamp ts) {
        if (ts == null) return null;
        return OffsetDateTime.of(ts.toLocalDateTime(), ZoneOffset.UTC).toInstant();
    }

    public static <K, V> Map<K, V> linkedMap() {
        return new LinkedHashMap<>();
    }

    /**
     * Bucket per-week breakdown rows into {@code weekStart -> (key -> count)}.
     */
    public static Map<Instant, Map<String, Long>> bucketBreakdown(List<Object[]> rows) {
        Map<Instant, Map<String, Long>> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            Instant weekStart = timestampToInstantUtc((Timestamp) r[0]);
            String key = (String) r[1];
            long count = ((Number) r[2]).longValue();
            out.computeIfAbsent(weekStart, k -> linkedMap()).put(key, count);
        }
        return out;
    }

    /**
     * Bucket top-users rows into {@code weekStart -> List<TopUser>}.
     * Row shape: [weekStart, performedBy (Number, may be null), userEmail
     * (String, may be null), eventCount].
     */
    public static Map<Instant, List<TopUser>> bucketTopUsers(List<Object[]> rows) {
        Map<Instant, List<TopUser>> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            Instant weekStart = timestampToInstantUtc((Timestamp) r[0]);
            Long performedBy = r[1] == null ? null : ((Number) r[1]).longValue();
            String userEmail = (String) r[2];
            long count = ((Number) r[3]).longValue();
            out.computeIfAbsent(weekStart, k -> new ArrayList<>())
                    .add(new TopUser(performedBy, userEmail, count));
        }
        return out;
    }
}
