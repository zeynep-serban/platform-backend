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
                    ReportDefinition def = objectMapper.readValue(resource.getInputStream(), ReportDefinition.class);
                    validate(def);
                    definitions.put(def.key(), def);
                    log.info("Loaded report definition: {} ({})", def.key(), def.title());
                } catch (Exception e) {
                    log.error("Failed to load report definition from {}: {}", filename, e.getMessage());
                }
            }

            log.info("Report registry initialized with {} definitions", definitions.size());
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
        if (!SAFE_IDENTIFIER.matcher(def.sourceSchema()).matches()) {
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
}
