package com.example.endpointadmin.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

public class DeviceCredentialAuthenticationToken extends AbstractAuthenticationToken {

    private final DeviceCredentialResult principal;

    private DeviceCredentialAuthenticationToken(DeviceCredentialResult principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_ENDPOINT_AGENT")));
        this.principal = principal;
        setAuthenticated(true);
    }

    public static DeviceCredentialAuthenticationToken authenticated(DeviceCredentialResult result) {
        return new DeviceCredentialAuthenticationToken(result);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public DeviceCredentialResult getPrincipal() {
        return principal;
    }
}
