package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointDeviceCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EndpointDeviceCredentialRepository extends JpaRepository<EndpointDeviceCredential, UUID> {

    Optional<EndpointDeviceCredential> findByDevice_Id(UUID deviceId);

    Optional<EndpointDeviceCredential> findByCredentialKeyId(String credentialKeyId);

    Optional<EndpointDeviceCredential> findByCredentialKeyIdAndActiveTrue(String credentialKeyId);
}
