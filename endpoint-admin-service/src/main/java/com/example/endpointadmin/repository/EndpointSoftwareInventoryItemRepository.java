package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * BE-020I — Spring Data JPA repository for
 * {@link EndpointSoftwareInventoryItem}.
 *
 * <p>All queries are tenant + device scoped. The device-detail admin
 * endpoint pages items with optional case-insensitive {@code q}
 * (display name), publisher and install-source filters.
 */
public interface EndpointSoftwareInventoryItemRepository
        extends JpaRepository<EndpointSoftwareInventoryItem, UUID> {

    // BE-020I lower(bytea) fix (Codex thread 019e73cf PARTIAL absorb).
    //
    // Live testai bug 2026-05-29: PostgreSQL JDBC driver bound nullable
    // String params (:q, :publisher) with bytea typing, so PG's overload
    // resolver hit `lower(bytea)` (does not exist) and the query failed
    // with SQLGrammarException. Hibernate 6 / PG JDBC do not infer
    // varchar from a null String parameter when the binding context is
    // wider than a single string-only function.
    //
    // Fix: explicit JPQL `cast(:param as string)` forces Hibernate to
    // emit `CAST(? AS varchar)` so PG resolves `lower(varchar)`
    // (= `lower(text)`) unambiguously. Cast applied at every reference
    // (both the null guard and the lower(...) call) so the SQL is
    // self-consistent and not relying on PG short-circuit evaluation
    // for the null branch.
    //
    // Sister repository EndpointSoftwareInventorySnapshotRepository
    // carries the same pattern (softwareName + publisher) and is fixed
    // in the same PR (systemic vs single-point fix).
    @Query("""
            select i
            from EndpointSoftwareInventoryItem i
            where i.tenantId = :tenantId
              and i.deviceId = :deviceId
              and (cast(:q as string) is null
                   or lower(i.displayName) like lower(concat('%', cast(:q as string), '%')))
              and (cast(:publisher as string) is null
                   or lower(i.publisher) = lower(cast(:publisher as string)))
              and (:installSource is null
                   or i.installSource = :installSource)
            order by lower(i.displayName)
            """)
    Page<EndpointSoftwareInventoryItem> pageByTenantDeviceWithFilters(
            @Param("tenantId") UUID tenantId,
            @Param("deviceId") UUID deviceId,
            @Param("q") String q,
            @Param("publisher") String publisher,
            @Param("installSource")
                com.example.endpointadmin.model.SoftwareInstallSource installSource,
            Pageable pageable);
}
