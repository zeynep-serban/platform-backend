package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointProhibitedSoftwareRule;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchMode;
import com.example.endpointadmin.model.ProhibitedSoftwareMatchType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link EndpointProhibitedSoftwareRule} — BE-025
 * (Faz 22.5 prohibited software detection).
 *
 * <p>Every query is tenant-scoped at the SQL layer. The denylist table is
 * not catalog-bound (no composite FK), so tenant narrowing is the sole
 * isolation boundary and is applied to every query.
 */
public interface EndpointProhibitedSoftwareRuleRepository
        extends JpaRepository<EndpointProhibitedSoftwareRule, UUID> {

    Page<EndpointProhibitedSoftwareRule> findByTenantIdOrderByLastUpdatedAtDesc(
            UUID tenantId, Pageable pageable);

    Optional<EndpointProhibitedSoftwareRule> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Evaluator hot path: enabled prohibited rules for the tenant. The
     * deterministic {@code id ASC} ordering keeps the produced findings /
     * evidence list stable across re-evaluations of the same input.
     */
    List<EndpointProhibitedSoftwareRule> findByTenantIdAndEnabledTrueOrderByIdAsc(UUID tenantId);

    /**
     * Service-level duplicate-rule pre-check feeding a clean 409 (the
     * functional UNIQUE index {@code uq_..._tenant_dedup} is the structural
     * backstop). Compares the CASE/whitespace-normalized patterns so
     * {@code "BitTorrent"} and {@code " bittorrent "} collide the same way
     * the index does. {@code COALESCE(...,'')} folds a NULL pattern to the
     * empty string to match the index's null-folding.
     */
    @Query("""
            SELECT r
              FROM EndpointProhibitedSoftwareRule r
             WHERE r.tenantId = :tenantId
               AND r.matchType = :matchType
               AND r.matchMode = :matchMode
               AND COALESCE(LOWER(TRIM(r.namePattern)), '') = :normalizedName
               AND COALESCE(LOWER(TRIM(r.publisherPattern)), '') = :normalizedPublisher
            """)
    Optional<EndpointProhibitedSoftwareRule> findDuplicate(
            @Param("tenantId") UUID tenantId,
            @Param("matchType") ProhibitedSoftwareMatchType matchType,
            @Param("matchMode") ProhibitedSoftwareMatchMode matchMode,
            @Param("normalizedName") String normalizedName,
            @Param("normalizedPublisher") String normalizedPublisher);
}
