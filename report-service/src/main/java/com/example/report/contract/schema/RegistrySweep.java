package com.example.report.contract.schema;

import com.example.report.contract.report.ContractViolation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Phase 2 Program 1c — Registry-level (sweep) meta-rules.
 *
 * <p>Spec absorb (Codex iter-2): single-report rules cover shape; sweep
 * rules cover cross-report invariants:
 * <ul>
 *   <li>{@code REPORT_KEY_DUPLICATE} — two report files share a {@code key}</li>
 *   <li>{@code REPORT_FILE_LOAD_ERROR} — a registry file fails to parse</li>
 * </ul>
 *
 * <p>Build-time only: no @Component. Caller is ContractValidator entry point.
 * Each entry walks {@code reports/*.json} (excluding {@code exceptions.json},
 * which is handled by ExceptionsRegistry).
 */
public final class RegistrySweep {

    private static final Logger log = LoggerFactory.getLogger(RegistrySweep.class);

    private final ObjectMapper objectMapper;
    private final ReportDefinitionSchemaValidator schemaValidator;
    private final String reportsPattern;

    /**
     * Default sweep over classpath*:reports/&#42;.json (excluding exceptions.json).
     * Phase 2 Program 1d fix: {@code classpath:} prefix returns only the first
     * classpath entry — under Surefire test classpath, this yielded only
     * {@code exceptions-test.json}. {@code classpath*:} enumerates all entries
     * (production main + test resources).
     */
    public RegistrySweep(ObjectMapper objectMapper,
                          ReportDefinitionSchemaValidator schemaValidator) {
        this(objectMapper, schemaValidator, "classpath*:reports/*.json");
    }

    public RegistrySweep(ObjectMapper objectMapper,
                          ReportDefinitionSchemaValidator schemaValidator,
                          String reportsPattern) {
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
        this.reportsPattern = reportsPattern;
    }

    /**
     * Walk all registry files; surface load errors + schema violations +
     * duplicate-key violations.
     *
     * @return aggregated meta-violations from sweep
     */
    public List<ContractViolation> sweep() {
        List<ContractViolation> violations = new ArrayList<>();
        Map<String, String> keyToFile = new HashMap<>();

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(reportsPattern);
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || filename.startsWith("exceptions")) {
                    // Exceptions registry artifacts (exceptions.json + exceptions-test.json)
                    // handled by ExceptionsRegistry — not part of report contract.
                    continue;
                }

                JsonNode node;
                try (InputStream in = resource.getInputStream()) {
                    node = objectMapper.readTree(in);
                } catch (IOException e) {
                    violations.add(ContractViolation.fail(
                            "REPORT_FILE_LOAD_ERROR",
                            filename,
                            "_file",
                            "Failed to parse JSON: " + e.getClass().getSimpleName()
                                    + ": " + e.getMessage()));
                    continue;
                }

                // Resolve report key from JSON (preferred) or fall back to filename.
                // Matches ReportDefinitionSchemaValidator.resolveReportKey contract so
                // violations carry stable reportKey identity even on shape failures.
                String resolvedKey = filename;
                if (node.has("key") && node.get("key").isTextual()) {
                    String declaredKey = node.get("key").asText();
                    if (declaredKey != null && !declaredKey.isBlank()) {
                        resolvedKey = declaredKey;
                    }
                }

                // Schema validation (raw JSON layer)
                List<ContractViolation> schemaViolations = schemaValidator.validate(node, resolvedKey);
                violations.addAll(schemaViolations);

                // Duplicate key detection (regardless of schema validity — surface dup early)
                if (node.has("key") && node.get("key").isTextual()) {
                    String key = node.get("key").asText();
                    if (key != null && !key.isBlank()) {
                        String previousFile = keyToFile.put(key, filename);
                        if (previousFile != null) {
                            violations.add(ContractViolation.fail(
                                    "REPORT_KEY_DUPLICATE",
                                    key,
                                    "_registry",
                                    "Duplicate report key '" + key
                                            + "' in files: " + previousFile + " AND " + filename));
                        }
                    }
                }
            }
            log.info("RegistrySweep scanned {} resources; {} violations surfaced",
                    resources.length, violations.size());
        } catch (IOException e) {
            log.error("RegistrySweep failed to scan {}: {}", reportsPattern, e.getMessage());
            violations.add(ContractViolation.fail(
                    "REPORT_FILE_LOAD_ERROR",
                    "_registry",
                    "_sweep",
                    "Sweep failed for pattern " + reportsPattern + ": "
                            + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        return Collections.unmodifiableList(violations);
    }
}
