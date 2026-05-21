package com.example.endpointadmin.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Component
public class JwtTenantContextResolver implements TenantContextResolver {

    private static final List<String> TENANT_UUID_CLAIMS = List.of(
            "tenant_id",
            "tenantId",
            "organization_id",
            "organizationId",
            "org_id",
            "orgId",
            "company_uuid",
            "companyUuid"
    );
    private static final List<String> COMPANY_ID_CLAIMS = List.of("companyId", "company_id");

    private final UUID localDefaultTenantId;
    private final Environment environment;

    public JwtTenantContextResolver(
            @Value("${endpoint-admin.tenant.local-default-tenant-id:}") String localDefaultTenantId,
            Environment environment
    ) {
        this.localDefaultTenantId = parseUuid(localDefaultTenantId);
        this.environment = environment;
    }

    @Override
    public AdminTenantContext resolveRequired() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return localContextOrReject();
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            UUID tenantId = resolveTenantId(jwt);
            String subject = firstNonBlank(jwt.getSubject(),
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("preferred_username"));
            if (tenantId != null && subject != null && !subject.isBlank()) {
                return new AdminTenantContext(tenantId, subject);
            }
        }
        return localContextOrReject();
    }

    private UUID resolveTenantId(Jwt jwt) {
        for (String claimName : TENANT_UUID_CLAIMS) {
            UUID parsed = parseUuid(claimToString(jwt.getClaim(claimName)));
            if (parsed != null) {
                return parsed;
            }
        }
        for (String claimName : COMPANY_ID_CLAIMS) {
            String companyId = claimToString(jwt.getClaim(claimName));
            if (companyId != null && !companyId.isBlank()) {
                return UUID.nameUUIDFromBytes(("company:" + companyId.trim()).getBytes(StandardCharsets.UTF_8));
            }
        }
        return null;
    }

    private AdminTenantContext localContextOrReject() {
        if (isLocalLikeProfile() && localDefaultTenantId != null) {
            return new AdminTenantContext(localDefaultTenantId, "local-dev");
        }
        throw new ResponseStatusException(UNAUTHORIZED, "Tenant context could not be resolved from authenticated principal.");
    }

    private boolean isLocalLikeProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("local".equalsIgnoreCase(profile) || "dev".equalsIgnoreCase(profile) || "test".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private static String claimToString(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? String.valueOf(number.longValue()) : String.valueOf(value);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
