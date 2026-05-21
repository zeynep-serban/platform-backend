package com.example.endpointadmin.dto.v1.agent;

import com.example.endpointadmin.model.OsType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ConsumeEnrollmentRequest(
        @NotBlank
        @Size(max = 512)
        String enrollmentToken,

        @NotBlank
        @Size(max = 255)
        String hostname,

        @NotNull
        OsType osType,

        @Size(max = 255)
        String osVersion,

        @NotBlank
        @Size(max = 128)
        String agentVersion,

        @NotBlank
        @Size(max = 512)
        String machineFingerprint,

        @Size(max = 255)
        String domainName
) {
}
