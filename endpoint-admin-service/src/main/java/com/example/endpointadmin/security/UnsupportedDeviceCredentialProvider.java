package com.example.endpointadmin.security;

public class UnsupportedDeviceCredentialProvider implements DeviceCredentialProvider {

    @Override
    public String providerId() {
        return "unsupported";
    }

    @Override
    public DeviceCredentialResult authenticate(DeviceCredentialRequest request) {
        throw new DeviceCredentialException(
                "DEVICE_CREDENTIAL_PROVIDER_NOT_CONFIGURED",
                "Device credential provider is not configured."
        );
    }
}
