package com.example.endpointadmin.security;

public record ProtectedDeviceSecret(String encryptedSecret, String encryptionKeyVersion) {
}
