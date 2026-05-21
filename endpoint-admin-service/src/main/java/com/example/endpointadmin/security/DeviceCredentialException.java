package com.example.endpointadmin.security;

public class DeviceCredentialException extends RuntimeException {

    private final String errorCode;

    public DeviceCredentialException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
