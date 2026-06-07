package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointCommandSecret;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EndpointCommandSecretRepository extends JpaRepository<EndpointCommandSecret, UUID> {

    Optional<EndpointCommandSecret> findByCommand_Id(UUID commandId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select secret
            from EndpointCommandSecret secret
            where secret.command.id = :commandId
            """)
    Optional<EndpointCommandSecret> findByCommandIdForUpdate(@Param("commandId") UUID commandId);
}
