package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.EndpointAgentServiceStatusDto;
import com.example.endpointadmin.security.DeviceCredentialProvider;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EndpointAgentStatusService {

    private final String serviceName;
    private final DeviceCredentialProvider deviceCredentialProvider;
    private final Clock clock;

    public EndpointAgentStatusService(
            @Value("${spring.application.name:endpoint-admin-service}") String serviceName,
            DeviceCredentialProvider deviceCredentialProvider,
            Clock clock
    ) {
        this.serviceName = serviceName;
        this.deviceCredentialProvider = deviceCredentialProvider;
        this.clock = clock;
    }

    public EndpointAgentServiceStatusDto currentStatus() {
        return new EndpointAgentServiceStatusDto(
                serviceName,
                "UP",
                "v1",
                deviceCredentialProvider.providerId(),
                clock.instant()
        );
    }
}
