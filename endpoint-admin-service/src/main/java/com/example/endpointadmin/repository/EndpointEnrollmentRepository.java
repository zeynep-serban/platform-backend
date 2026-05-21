package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointEnrollment;
import com.example.endpointadmin.model.EnrollmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndpointEnrollmentRepository extends JpaRepository<EndpointEnrollment, UUID> {

    Optional<EndpointEnrollment> findByEnrollmentTokenHashAndStatus(String enrollmentTokenHash,
                                                                    EnrollmentStatus status);

    Optional<EndpointEnrollment> findByEnrollmentTokenHash(String enrollmentTokenHash);

    List<EndpointEnrollment> findByTenantIdAndStatus(UUID tenantId, EnrollmentStatus status);

    List<EndpointEnrollment> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<EndpointEnrollment> findByStatusAndExpiresAtBefore(EnrollmentStatus status, Instant expiresAt);

    @Modifying
    @Query("""
            UPDATE EndpointEnrollment enrollment
            SET enrollment.status = com.example.endpointadmin.model.EnrollmentStatus.CONSUMED,
                enrollment.device = :device,
                enrollment.consumedAt = :consumedAt
            WHERE enrollment.id = :id
              AND enrollment.status = com.example.endpointadmin.model.EnrollmentStatus.PENDING
              AND enrollment.expiresAt > :consumedAt
            """)
    int markConsumed(@Param("id") UUID id,
                     @Param("device") com.example.endpointadmin.model.EndpointDevice device,
                     @Param("consumedAt") Instant consumedAt);

    @Modifying
    @Query("""
            UPDATE EndpointEnrollment enrollment
            SET enrollment.status = com.example.endpointadmin.model.EnrollmentStatus.EXPIRED
            WHERE enrollment.id = :id
              AND enrollment.status = com.example.endpointadmin.model.EnrollmentStatus.PENDING
              AND enrollment.expiresAt <= :now
            """)
    int markExpired(@Param("id") UUID id, @Param("now") Instant now);
}
