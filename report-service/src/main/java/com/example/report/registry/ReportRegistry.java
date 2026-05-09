package com.example.report.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

@Component
public class ReportRegistry {

    private static final Logger log = LoggerFactory.getLogger(ReportRegistry.class);
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_.]*$");

    private final ConcurrentHashMap<String, ReportDefinition> definitions = new ConcurrentHashMap<>();
    /**
     * Codex 019e0d06 iter-2 absorb: side-channel storage for the
     * {@code tenantBoundary} JSON block. Kept out of {@link ReportDefinition}
     * record to avoid breaking the 49 existing positional-construction
     * call sites (test fixtures + DashboardQueryEngine). Populated during
     * {@link #loadDefinitions()} by re-reading the same JSON resource.
     */
    private final ConcurrentHashMap<String, TenantBoundary> tenantBoundaries = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final String definitionsPath;

    public ReportRegistry(ObjectMapper objectMapper,
                          @Value("${report.definitions-path:classpath:reports/}") String definitionsPath) {
        this.objectMapper = objectMapper;
        this.definitionsPath = definitionsPath;
    }

    @PostConstruct
    public void loadDefinitions() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            String pattern = definitionsPath.endsWith("/") ? definitionsPath + "*.json" : definitionsPath + "/*.json";
            Resource[] resources = resolver.getResources(pattern);

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                // Phase 2 Program 1e (Codex iter-8 §1e-AGREE absorb): exceptions.json
                // and exceptions-test.json are governance artifacts handled by
                // ExceptionsRegistry — not report definitions. Skip by filename
                // guard so startup doesn't emit a spurious bind-error log.
                if (filename != null && filename.startsWith("exceptions")) {
                    continue;
                }
                try {
                    // Read once into the typed record (existing path)…
                    byte[] bytes = resource.getInputStream().readAllBytes();
                    ReportDefinition def = objectMapper.readValue(bytes, ReportDefinition.class);
                    validate(def);
                    definitions.put(def.key(), def);
                    // …and a second pass extracts the typed tenantBoundary
                    // block (Codex 019e0d06 iter-2). Side-channel keeps the
                    // record signature stable.
                    com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(bytes);
                    com.fasterxml.jackson.databind.JsonNode tbNode = root.get("tenantBoundary");
                    if (tbNode != null && tbNode.isObject()) {
                        TenantBoundary tb = objectMapper.treeToValue(tbNode, TenantBoundary.class);
                        if (tb != null) {
                            tenantBoundaries.put(def.key(), tb);
                        }
                    }
                    log.info("Loaded report definition: {} ({})", def.key(), def.title());
                } catch (Exception e) {
                    log.error("Failed to load report definition from {}: {}", filename, e.getMessage());
                }
            }

            log.info("Report registry initialized with {} definitions", definitions.size());
            // Codex 019e0d06 iter-3 §1+§2: post-parse fail-closed validation
            // for schemaMode=current + tenantBoundary consistency.
            validateTenantBoundaryConsistency();
        } catch (IOException e) {
            log.warn("Could not scan report definitions directory: {}", e.getMessage());
        }
    }

    public Optional<ReportDefinition> get(String key) {
        return Optional.ofNullable(definitions.get(key));
    }

    public Collection<ReportDefinition> getAll() {
        return definitions.values();
    }

    /**
     * Codex 019e0d06 iter-2: typed {@link TenantBoundary} side-channel
     * lookup. Returns {@link Optional#empty()} for legacy reports that
     * have no {@code tenantBoundary} block in JSON.
     */
    public Optional<TenantBoundary> getTenantBoundary(String key) {
        return Optional.ofNullable(tenantBoundaries.get(key));
    }

    public List<String> getCategories() {
        return definitions.values().stream()
                .map(ReportDefinition::category)
                .distinct()
                .sorted()
                .toList();
    }

    private static final Pattern UNSAFE_SQL = Pattern.compile(
            "(?i)\\b(DROP|DELETE|UPDATE|INSERT|EXEC|EXECUTE|xp_|sp_|ALTER|CREATE|TRUNCATE|MERGE)\\b");

    private void validate(ReportDefinition def) {
        if (def.source() != null && !def.source().isBlank() && !SAFE_IDENTIFIER.matcher(def.source()).matches()) {
            throw new IllegalArgumentException(
                    "Report source '" + def.source() + "' contains unsafe characters. Only alphanumeric, underscore, and dot allowed.");
        }
        // Codex 019e0d06 iter-2 §3 absorb: yearly+current resolver-driven mod'larda
        // sourceSchema null/blank kabul (resolver runtime üretir). Literal mod'larda
        // SAFE_IDENTIFIER zorunlu.
        if (def.sourceSchema() != null && !def.sourceSchema().isBlank()
                && !SAFE_IDENTIFIER.matcher(def.sourceSchema()).matches()) {
            throw new IllegalArgumentException(
                    "Report sourceSchema '" + def.sourceSchema() + "' contains unsafe characters. Only alphanumeric, underscore, and dot allowed.");
        }
        if (def.hasSourceQuery()) {
            if (UNSAFE_SQL.matcher(def.sourceQuery()).find()) {
                throw new IllegalArgumentException(
                        "Report sourceQuery in '" + def.key() + "' contains unsafe SQL keywords.");
            }
        }
        for (ColumnDefinition col : def.columns()) {
            if (!SAFE_IDENTIFIER.matcher(col.field()).matches()) {
                throw new IllegalArgumentException(
                        "Column field '" + col.field() + "' in report '" + def.key() + "' contains unsafe characters.");
            }
        }
    }

    /**
     * Codex 019e0d06 iter-3 §1+§2 BLOCKER absorb: schemaMode=current için
     * tenantBoundary.schemaResolver=workcube-current-company zorunlu (startup
     * fail-closed). Aksi halde QueryEngine dispatch artık schemaMode=current
     * üzerinden current resolver'a gidiyor; tenantBoundary mismatch hâlâ
     * runtime'da `[null].[TABLE]` riski oluştururdu.
     *
     * <p>Validate metodu private; bu metod loadDefinitions sonunda tek seferlik
     * çağrılır (post-parse, side-channel doluyken).
     */
    private void validateTenantBoundaryConsistency() {
        for (ReportDefinition def : definitions.values()) {
            if (!"current".equals(def.schemaMode())) {
                continue;
            }
            TenantBoundary tb = tenantBoundaries.get(def.key());
            if (tb == null) {
                throw new IllegalStateException(
                        "Report '" + def.key() + "' has schemaMode=current but no tenantBoundary "
                                + "block in JSON. tenantBoundary.schemaResolver=workcube-current-company "
                                + "required (Codex 019e0d06 iter-3 fail-closed).");
            }
            if (!tb.isCurrentCompanyResolver()) {
                throw new IllegalStateException(
                        "Report '" + def.key() + "' has schemaMode=current but tenantBoundary"
                                + ".schemaResolver='" + tb.schemaResolver() + "'. Required: "
                                + "'workcube-current-company' (Codex 019e0d06 iter-3 fail-closed).");
            }
        }
    }
}
