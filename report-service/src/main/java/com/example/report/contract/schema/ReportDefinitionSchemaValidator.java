package com.example.report.contract.schema;

import com.example.report.contract.report.ContractViolation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Phase 2 Program 1c — JSON Schema (Draft 2020-12) validator for report
 * definitions.
 *
 * <p>Spec absorb: schema validation runs on raw JSON BEFORE Jackson binding —
 * malformed reports surface {@code REPORT_SCHEMA_INVALID} FAIL early-exit so
 * semantic RC rules (RC-000..RC-010) skip them. This guards against shape
 * drift in the registry without coupling validator to runtime POJO.
 *
 * <p>Codex iter-2 absorb (thread 019e0119) AGREE / ready_for_impl=true:
 * <ul>
 *   <li>JsonSchema instance singleton (load 1x, validate N times)</li>
 *   <li>Constructor injection for test isolation (no static globals)</li>
 *   <li>Field path = stable instance location (networknt JSONPath-style,
 *       e.g. {@code $.columns[3].type})</li>
 *   <li>Per-report violation cap (first 20 + summary) for PR feedback bloat</li>
 * </ul>
 *
 * <p>Build-time only: no @Component / @Configuration. Caller is
 * ContractValidator entry point; production runtime path inactive.
 */
public final class ReportDefinitionSchemaValidator {

    private static final Logger log = LoggerFactory.getLogger(ReportDefinitionSchemaValidator.class);

    /** Codex iter-2 absorb: per-report violation cap (PR feedback bloat guard). */
    public static final int MAX_VIOLATIONS_PER_REPORT = 20;

    private final JsonSchema schema;
    private final ObjectMapper objectMapper;

    /**
     * Test/production constructor with explicit dependencies.
     *
     * @param objectMapper   Jackson mapper for raw JSON parse
     * @param schemaResource classpath:/file: URL of report-definition.schema.json
     * @param resourceLoader Spring resource loader
     */
    public ReportDefinitionSchemaValidator(ObjectMapper objectMapper,
                                            String schemaResource,
                                            ResourceLoader resourceLoader) {
        this.objectMapper = objectMapper;
        this.schema = loadSchema(resourceLoader, schemaResource);
    }

    /**
     * Default constructor: loads from classpath:contract/report-definition.schema.json.
     */
    public ReportDefinitionSchemaValidator(ObjectMapper objectMapper) {
        this(objectMapper, "classpath:contract/report-definition.schema.json",
                new DefaultResourceLoader());
    }

    /**
     * Validate raw JSON against report-definition schema. Returns violations
     * (FAIL severity) for each shape mismatch, capped at
     * {@link #MAX_VIOLATIONS_PER_REPORT} with summary entry.
     *
     * @param rawJson  raw JSON content of one report file
     * @param reportPath path identifier (e.g. file name or registry key) for
     *                   violation reportKey field — used when JSON has no key
     * @return list of {@link ContractViolation} (empty if schema-valid)
     */
    public List<ContractViolation> validate(String rawJson, String reportPath) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of(ContractViolation.fail(
                    "REPORT_FILE_LOAD_ERROR",
                    reportPath != null ? reportPath : "_unknown",
                    "_file",
                    "Report file is empty or null"));
        }

        JsonNode jsonNode;
        try {
            jsonNode = objectMapper.readTree(rawJson);
        } catch (IOException e) {
            return List.of(ContractViolation.fail(
                    "REPORT_FILE_LOAD_ERROR",
                    reportPath != null ? reportPath : "_unknown",
                    "_file",
                    "Failed to parse JSON: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage()));
        }

        // Codex absorb: prefer JSON-declared key over file-name path for traceability.
        String resolvedKey = resolveReportKey(jsonNode, reportPath);

        Set<ValidationMessage> messages = schema.validate(jsonNode);
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }

        List<ContractViolation> violations = new ArrayList<>();
        int count = 0;
        for (ValidationMessage msg : messages) {
            if (count >= MAX_VIOLATIONS_PER_REPORT) {
                int remaining = messages.size() - MAX_VIOLATIONS_PER_REPORT;
                violations.add(ContractViolation.fail(
                        "REPORT_SCHEMA_INVALID",
                        resolvedKey,
                        "_summary",
                        "Schema violations capped at " + MAX_VIOLATIONS_PER_REPORT
                                + "; " + remaining + " more not shown"));
                break;
            }
            violations.add(ContractViolation.fail(
                    "REPORT_SCHEMA_INVALID",
                    resolvedKey,
                    msg.getInstanceLocation().toString(),
                    msg.getMessage()));
            count++;
        }
        return violations;
    }

    /**
     * Convenience overload: validate via JsonNode + key already resolved.
     * Used when the caller has already loaded + parsed JSON.
     */
    public List<ContractViolation> validate(JsonNode jsonNode, String reportKey) {
        if (jsonNode == null || jsonNode.isMissingNode()) {
            return List.of(ContractViolation.fail(
                    "REPORT_FILE_LOAD_ERROR",
                    reportKey != null ? reportKey : "_unknown",
                    "_file",
                    "Report JSON node is null or missing"));
        }

        Set<ValidationMessage> messages = schema.validate(jsonNode);
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }

        List<ContractViolation> violations = new ArrayList<>();
        int count = 0;
        for (ValidationMessage msg : messages) {
            if (count >= MAX_VIOLATIONS_PER_REPORT) {
                int remaining = messages.size() - MAX_VIOLATIONS_PER_REPORT;
                violations.add(ContractViolation.fail(
                        "REPORT_SCHEMA_INVALID",
                        reportKey,
                        "_summary",
                        "Schema violations capped at " + MAX_VIOLATIONS_PER_REPORT
                                + "; " + remaining + " more not shown"));
                break;
            }
            violations.add(ContractViolation.fail(
                    "REPORT_SCHEMA_INVALID",
                    reportKey,
                    msg.getInstanceLocation().toString(),
                    msg.getMessage()));
            count++;
        }
        return violations;
    }

    private String resolveReportKey(JsonNode node, String reportPath) {
        if (node != null && node.has("key") && node.get("key").isTextual()) {
            String k = node.get("key").asText();
            if (k != null && !k.isBlank()) {
                return k;
            }
        }
        return reportPath != null ? reportPath : "_unknown";
    }

    private JsonSchema loadSchema(ResourceLoader resourceLoader, String schemaResource) {
        try {
            Resource resource = resourceLoader.getResource(schemaResource);
            if (!resource.exists()) {
                throw new IllegalStateException("report-definition.schema.json not found at "
                        + schemaResource);
            }
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            try (InputStream in = resource.getInputStream()) {
                JsonSchema loaded = factory.getSchema(in);
                log.info("Loaded report-definition schema from {}", schemaResource);
                return loaded;
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load report-definition schema from " + schemaResource, e);
        }
    }
}
