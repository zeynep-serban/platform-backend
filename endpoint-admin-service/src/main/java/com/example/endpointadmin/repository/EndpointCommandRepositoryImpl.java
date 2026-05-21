package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointCommand;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public class EndpointCommandRepositoryImpl implements EndpointCommandRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${spring.jpa.properties.hibernate.default_schema:endpoint_admin_service}")
    private String schema;

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public List<EndpointCommand> claimDeliverableBatch(String workerId,
                                                       Instant now,
                                                       Instant lockedUntil,
                                                       int batchSize) {
        String commandsTable = qualified("endpoint_commands");
        String devicesTable = qualified("endpoint_devices");
        String sql = """
                UPDATE %s
                SET status = 'DELIVERED',
                    locked_by = :workerId,
                    locked_until = :lockedUntil,
                    attempt_count = attempt_count + 1,
                    delivered_at = COALESCE(delivered_at, :now),
                    updated_at = :now
                WHERE id IN (
                    SELECT c.id
                    FROM %s c
                    JOIN %s d ON d.id = c.device_id
                    WHERE c.status = 'QUEUED'
                      AND c.visible_after_at <= :now
                      AND (c.expires_at IS NULL OR c.expires_at > :now)
                      AND d.status = 'ONLINE'
                    ORDER BY c.priority ASC, c.issued_at ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                RETURNING *
                """.formatted(commandsTable, commandsTable, devicesTable);

        return entityManager
                .createNativeQuery(sql, EndpointCommand.class)
                .setParameter("workerId", workerId)
                .setParameter("now", now)
                .setParameter("lockedUntil", lockedUntil)
                .setParameter("batchSize", batchSize)
                .getResultList();
    }

    private String qualified(String tableName) {
        String resolvedSchema = schema == null ? "" : schema.trim();
        if (resolvedSchema.isBlank()) {
            return tableName;
        }
        if (!resolvedSchema.matches("[A-Za-z0-9_]+")) {
            throw new IllegalStateException("Invalid endpoint admin schema name.");
        }
        return resolvedSchema + "." + tableName;
    }
}
