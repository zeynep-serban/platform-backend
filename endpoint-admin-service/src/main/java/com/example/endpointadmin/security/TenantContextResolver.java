package com.example.endpointadmin.security;

public interface TenantContextResolver {

    AdminTenantContext resolveRequired();
}
