package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.DeploymentRing;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record UpdateDeviceRolloutRequest(
        DeploymentRing deploymentRing,

        @Size(max = 32)
        Set<@Size(max = 64) String> deviceTags
) {
}
