package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointCommandResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EndpointCommandResultRepository extends JpaRepository<EndpointCommandResult, UUID> {

    Optional<EndpointCommandResult> findByCommand_Id(UUID commandId);
}
