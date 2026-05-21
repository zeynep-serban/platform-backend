package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointRequestNonce;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface EndpointRequestNonceRepository extends JpaRepository<EndpointRequestNonce, UUID> {

    boolean existsByCredential_IdAndNonce(UUID credentialId, String nonce);

    @Modifying
    @Query("delete from EndpointRequestNonce nonce where nonce.expiresAt < :expiresAt")
    int deleteExpiredBefore(@Param("expiresAt") Instant expiresAt);
}
