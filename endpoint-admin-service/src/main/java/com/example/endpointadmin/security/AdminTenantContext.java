package com.example.endpointadmin.security;

import java.util.UUID;

public record AdminTenantContext(UUID tenantId, String subject) {
}
