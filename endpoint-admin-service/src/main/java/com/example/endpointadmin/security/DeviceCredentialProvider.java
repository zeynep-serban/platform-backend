package com.example.endpointadmin.security;

public interface DeviceCredentialProvider {

    String providerId();

    DeviceCredentialResult authenticate(DeviceCredentialRequest request);
}
