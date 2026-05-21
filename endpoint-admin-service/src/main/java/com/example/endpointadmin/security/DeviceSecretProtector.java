package com.example.endpointadmin.security;

public interface DeviceSecretProtector {

    ProtectedDeviceSecret protect(String plainSecret);

    String reveal(String encryptedSecret);
}
