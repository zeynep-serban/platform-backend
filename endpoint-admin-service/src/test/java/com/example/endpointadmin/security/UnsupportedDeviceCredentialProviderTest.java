package com.example.endpointadmin.security;

import java.time.Instant;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class UnsupportedDeviceCredentialProviderTest {

    private final UnsupportedDeviceCredentialProvider provider = new UnsupportedDeviceCredentialProvider();

    @Test
    void providerId_returnsUnsupported() {
        Assertions.assertThat(provider.providerId()).isEqualTo("unsupported");
    }

    @Test
    void authenticate_whenProviderNotConfigured_throwsTypedException() {
        DeviceCredentialRequest request = new DeviceCredentialRequest(
                "device-1",
                "key-1",
                "signature",
                Instant.parse("2026-04-28T10:00:00Z"),
                "nonce",
                "payload"
        );

        Assertions.assertThatThrownBy(() -> provider.authenticate(request))
                .isInstanceOf(DeviceCredentialException.class)
                .hasMessage("Device credential provider is not configured.")
                .extracting("errorCode")
                .isEqualTo("DEVICE_CREDENTIAL_PROVIDER_NOT_CONFIGURED");
    }
}
