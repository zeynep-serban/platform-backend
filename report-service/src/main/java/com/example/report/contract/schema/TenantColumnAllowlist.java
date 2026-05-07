package com.example.report.contract.schema;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Phase 2 Program 1d — Tenant column allowlist registry.
 *
 * <p>Codex iter-4 §1d-AGREE absorb: source-table-keyed allowlist driving
 * RC-004 ({@code rowFilter.scopeType=COMPANY} column membership check).
 * Loaded from {@code classpath:contract/tenant-column-allowlist.json};
 * test override via constructor.
 *
 * <p>Build-time only: no {@code @Component}, explicit constructor.
 */
public final class TenantColumnAllowlist {

    private static final Logger log = LoggerFactory.getLogger(TenantColumnAllowlist.class);

    public static final String DEFAULT_PATH = "classpath:contract/tenant-column-allowlist.json";

    private final Map<String, List<String>> allowlist;

    public TenantColumnAllowlist(ResourceLoader resourceLoader,
                                  ObjectMapper objectMapper,
                                  String path) {
        this.allowlist = loadAllowlist(resourceLoader, objectMapper, path);
    }

    public TenantColumnAllowlist(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this(resourceLoader, objectMapper, DEFAULT_PATH);
    }

    /**
     * Constructor accepting a pre-built map (test convenience).
     */
    public TenantColumnAllowlist(Map<String, List<String>> allowlist) {
        this.allowlist = Collections.unmodifiableMap(new LinkedHashMap<>(allowlist));
    }

    /**
     * Returns true if the source table allows the given column as a tenant
     * boundary discriminator. Underscore-prefixed metadata keys (e.g.
     * {@code _comment}) are skipped.
     */
    public boolean allows(String sourceTable, String column) {
        if (sourceTable == null || column == null) {
            return false;
        }
        List<String> cols = allowlist.get(sourceTable);
        return cols != null && cols.contains(column);
    }

    /** Whether the source table appears in the allowlist at all. */
    public boolean knowsTable(String sourceTable) {
        if (sourceTable == null) {
            return false;
        }
        return allowlist.containsKey(sourceTable);
    }

    public int tableCount() {
        return allowlist.size();
    }

    private static Map<String, List<String>> loadAllowlist(ResourceLoader resourceLoader,
                                                              ObjectMapper objectMapper,
                                                              String path) {
        try {
            Resource resource = resourceLoader.getResource(path);
            if (!resource.exists()) {
                log.warn("Tenant column allowlist not found at {}", path);
                return Map.of();
            }
            try (InputStream in = resource.getInputStream()) {
                Map<String, List<String>> raw = objectMapper.readValue(
                        in, new TypeReference<Map<String, List<String>>>() {});
                // Filter underscore-prefixed metadata keys (e.g. _comment).
                Map<String, List<String>> sanitized = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> e : raw.entrySet()) {
                    if (e.getKey() != null && !e.getKey().startsWith("_")) {
                        sanitized.put(e.getKey(), List.copyOf(e.getValue()));
                    }
                }
                log.info("Loaded tenant column allowlist: tables={} path={}",
                        sanitized.size(), path);
                return Collections.unmodifiableMap(sanitized);
            }
        } catch (IOException e) {
            log.error("Failed to load tenant column allowlist from {}", path, e);
            return Map.of();
        }
    }
}
