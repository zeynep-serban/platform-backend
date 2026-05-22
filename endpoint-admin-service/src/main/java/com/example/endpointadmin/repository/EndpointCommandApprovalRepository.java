package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointCommandApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EndpointCommandApprovalRepository extends JpaRepository<EndpointCommandApproval, UUID> {

    Optional<EndpointCommandApproval> findByCommandId(UUID commandId);
}
