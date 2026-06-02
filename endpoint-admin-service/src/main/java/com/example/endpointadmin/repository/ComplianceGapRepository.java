package com.example.endpointadmin.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Faz 22.7 D2 (Codex 019e881c AGREE D + 019e88b0 P1+P2 absorb):
 * cross-snapshot compliance gap aggregation native PG queries.
 *
 * <p>Codex 019e88b0 P1 absorb: tables are schema-qualified via
 * {@code spring.jpa.properties.hibernate.default_schema} (default
 * {@code endpoint_admin_service}). Live cluster connection {@code search_path}
 * does NOT include the canonical schema → unqualified table names would
 * raise "relation does not exist" at runtime 500.
 *
 * <p>Codex 019e88b0 P2 absorb: DISTINCT ON uses deterministic tiebreaker
 * {@code ORDER BY tenant_id, device_id, collected_at DESC, created_at DESC, id DESC}
 * to match the canonical latest-per-device semantics used across other
 * endpoint-admin repositories.
 *
 * <p>HARD RULE No Fake Work: all aggregation pushed to DB; NO client-side
 * filtering over fetched rows.
 */
@Repository
public class ComplianceGapRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}")
    private String schema;

    public List<Object[]> findGapDevices(UUID tenantId,
                                         Instant freshnessThreshold,
                                         Set<String> gapTypeWires,
                                         int limit,
                                         int offset) {
        String startupTable = qualified("endpoint_startup_exposure_snapshots");
        String hotfixTable = qualified("endpoint_hotfix_posture_snapshots");
        String devicesTable = qualified("endpoint_devices");

        String sql = """
                WITH latest_startup AS (
                    SELECT DISTINCT ON (tenant_id, device_id)
                        device_id, tenant_id, rdp_enabled, collected_at
                    FROM %s
                    WHERE tenant_id = :tenantId AND collected_at >= :freshnessThreshold
                    ORDER BY tenant_id, device_id, collected_at DESC, created_at DESC, id DESC
                ),
                latest_hotfix AS (
                    SELECT DISTINCT ON (tenant_id, device_id)
                        device_id, tenant_id, pending_total_count, collected_at
                    FROM %s
                    WHERE tenant_id = :tenantId AND collected_at >= :freshnessThreshold
                    ORDER BY tenant_id, device_id, collected_at DESC, created_at DESC, id DESC
                )
                SELECT
                    d.id AS device_id,
                    d.hostname,
                    d.display_name,
                    s.rdp_enabled,
                    s.collected_at AS startup_collected_at,
                    h.pending_total_count,
                    h.collected_at AS hotfix_collected_at
                FROM %s d
                LEFT JOIN latest_startup s ON s.device_id = d.id AND s.tenant_id = d.tenant_id
                LEFT JOIN latest_hotfix h ON h.device_id = d.id AND h.tenant_id = d.tenant_id
                WHERE d.tenant_id = :tenantId
                  AND (
                    (:rdpEnabledRequested = TRUE AND s.rdp_enabled = TRUE)
                    OR
                    (:pendingUpdatesRequested = TRUE AND h.pending_total_count > 0)
                  )
                ORDER BY GREATEST(
                    COALESCE(s.collected_at, '1970-01-01'::timestamp),
                    COALESCE(h.collected_at, '1970-01-01'::timestamp)
                ) DESC, d.hostname ASC
                LIMIT :limit OFFSET :offset
                """.formatted(startupTable, hotfixTable, devicesTable);

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("freshnessThreshold", Timestamp.from(freshnessThreshold));
        query.setParameter("rdpEnabledRequested",
                gapTypeWires.contains("rdp_enabled"));
        query.setParameter("pendingUpdatesRequested",
                gapTypeWires.contains("pending_security_updates"));
        query.setParameter("limit", limit);
        query.setParameter("offset", offset);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    public long countGapDevices(UUID tenantId,
                                Instant freshnessThreshold,
                                Set<String> gapTypeWires) {
        String startupTable = qualified("endpoint_startup_exposure_snapshots");
        String hotfixTable = qualified("endpoint_hotfix_posture_snapshots");
        String devicesTable = qualified("endpoint_devices");

        String sql = """
                WITH latest_startup AS (
                    SELECT DISTINCT ON (tenant_id, device_id)
                        device_id, tenant_id, rdp_enabled, collected_at
                    FROM %s
                    WHERE tenant_id = :tenantId AND collected_at >= :freshnessThreshold
                    ORDER BY tenant_id, device_id, collected_at DESC, created_at DESC, id DESC
                ),
                latest_hotfix AS (
                    SELECT DISTINCT ON (tenant_id, device_id)
                        device_id, tenant_id, pending_total_count, collected_at
                    FROM %s
                    WHERE tenant_id = :tenantId AND collected_at >= :freshnessThreshold
                    ORDER BY tenant_id, device_id, collected_at DESC, created_at DESC, id DESC
                )
                SELECT COUNT(*)
                FROM %s d
                LEFT JOIN latest_startup s ON s.device_id = d.id AND s.tenant_id = d.tenant_id
                LEFT JOIN latest_hotfix h ON h.device_id = d.id AND h.tenant_id = d.tenant_id
                WHERE d.tenant_id = :tenantId
                  AND (
                    (:rdpEnabledRequested = TRUE AND s.rdp_enabled = TRUE)
                    OR
                    (:pendingUpdatesRequested = TRUE AND h.pending_total_count > 0)
                  )
                """.formatted(startupTable, hotfixTable, devicesTable);

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("freshnessThreshold", Timestamp.from(freshnessThreshold));
        query.setParameter("rdpEnabledRequested",
                gapTypeWires.contains("rdp_enabled"));
        query.setParameter("pendingUpdatesRequested",
                gapTypeWires.contains("pending_security_updates"));

        Number count = (Number) query.getSingleResult();
        return count.longValue();
    }

    /**
     * Schema-qualifies a table name (Codex 019e88b0 P1 absorb).
     */
    private String qualified(String tableName) {
        String resolvedSchema = schema == null ? "" : schema.trim();
        if (resolvedSchema.isBlank()) return tableName;
        if (!resolvedSchema.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Invalid endpoint admin schema name.");
        }
        return resolvedSchema + "." + tableName;
    }
}
