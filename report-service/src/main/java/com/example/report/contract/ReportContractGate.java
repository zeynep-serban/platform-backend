package com.example.report.contract;

import com.example.report.contract.exceptions.ContractExceptionEntry;
import com.example.report.contract.exceptions.ExceptionsRegistry;
import com.example.report.contract.report.ContractGateSummary;
import com.example.report.contract.report.ContractReport;
import com.example.report.contract.report.ContractViolation;
import com.example.report.contract.schema.RegistrySweep;
import com.example.report.contract.schema.ReportDefinitionSchemaValidator;
import com.example.report.contract.schema.TenantColumnAllowlist;
import com.example.report.registry.ReportDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Phase 2 Program 1d — Report contract gate orchestrator.
 *
 * <p>Codex iter-4 §1d-AGREE absorb (thread 019e0119): registry-level orchestrator
 * that wires schema validation, semantic RC rules, and exception registry into
 * a single fail-closed gate. {@code ContractValidator} stays a single-report
 * semantic runner; this class adds the registry sweep + binding + exceptions
 * application.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link RegistrySweep} reads raw JSON files; surfaces
 *       {@code REPORT_FILE_LOAD_ERROR}, {@code REPORT_SCHEMA_INVALID},
 *       {@code REPORT_KEY_DUPLICATE}.</li>
 *   <li>Schema-invalid files → semantic RC EARLY-EXIT (no Jackson bind).</li>
 *   <li>Schema-valid files → {@code ObjectMapper.treeToValue → ReportDefinition}.</li>
 *   <li>Each bound def runs through {@link ContractValidator#validate(ReportDefinition)}.</li>
 *   <li>{@link ExceptionsRegistry#apply(List)} suppresses RC-XXX violations
 *       covered by valid (non-expired, within-horizon) exception entries;
 *       meta-violations are NOT suppressible.</li>
 * </ol>
 *
 * <p>Build-time only: no {@code @Component}. Test-classpath instantiation
 * via {@link #create()}; CI invocation via {@code ReportContractGateTest}.
 */
public final class ReportContractGate {

    private static final Logger log = LoggerFactory.getLogger(ReportContractGate.class);

    private final ObjectMapper objectMapper;
    private final ReportDefinitionSchemaValidator schemaValidator;
    private final ContractValidator contractValidator;
    private final ExceptionsRegistry exceptionsRegistry;
    private final ResourceLoader resourceLoader;
    private final String reportsPattern;
    private final Clock clock;

    public ReportContractGate(ObjectMapper objectMapper,
                               ReportDefinitionSchemaValidator schemaValidator,
                               ContractValidator contractValidator,
                               ExceptionsRegistry exceptionsRegistry,
                               ResourceLoader resourceLoader,
                               String reportsPattern,
                               Clock clock) {
        this.objectMapper = objectMapper;
        this.schemaValidator = schemaValidator;
        this.contractValidator = contractValidator;
        this.exceptionsRegistry = exceptionsRegistry;
        this.resourceLoader = resourceLoader;
        this.reportsPattern = reportsPattern;
        this.clock = clock;
    }

    /** Backward-compat constructor (system clock). */
    public ReportContractGate(ObjectMapper objectMapper,
                               ReportDefinitionSchemaValidator schemaValidator,
                               ContractValidator contractValidator,
                               ExceptionsRegistry exceptionsRegistry,
                               ResourceLoader resourceLoader,
                               String reportsPattern) {
        this(objectMapper, schemaValidator, contractValidator, exceptionsRegistry,
                resourceLoader, reportsPattern, Clock.systemUTC());
    }

    /**
     * Default factory: standard classpath resources, system clock, real
     * tenant column allowlist, and exceptions.json. Loads exceptions registry
     * before returning.
     */
    public static ReportContractGate create() {
        return create(Clock.systemUTC());
    }

    /**
     * Phase 2 Program 1e (Codex iter-7 §1e-AGREE absorb): factory with
     * injectable clock for deterministic Markdown expiry-day calculations.
     */
    public static ReportContractGate create(Clock clock) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ResourceLoader loader = new DefaultResourceLoader();
        ReportDefinitionSchemaValidator schemaValidator =
                new ReportDefinitionSchemaValidator(mapper);
        TenantColumnAllowlist allowlist = new TenantColumnAllowlist(loader, mapper);
        ContractValidator contractValidator = ContractValidator.withDefaultRules(allowlist);
        ExceptionsRegistry exceptions = new ExceptionsRegistry(
                loader, mapper, "classpath:reports/exceptions.json", clock);
        exceptions.load();
        return new ReportContractGate(mapper, schemaValidator, contractValidator,
                exceptions, loader, "classpath*:reports/*.json", clock);
    }

    /**
     * Backward-compat: returns the filtered violations as a {@link ContractReport}.
     * Single source of truth is {@link #gateDetailed()}.
     */
    public ContractReport gate() {
        ContractGateSummary summary = gateDetailed();
        return new ContractReport(summary.filteredViolations(), summary.reportCount());
    }

    /**
     * Phase 2 Program 1e (Codex iter-7 §1e-AGREE absorb): detailed gate run
     * returning raw + filtered + suppression events + exception inventory.
     * Used by sticky PR comment Markdown writer + JSON artifact.
     */
    public ContractGateSummary gateDetailed() {
        List<ContractViolation> aggregate = new ArrayList<>();
        Map<String, JsonNode> validNodes = new HashMap<>();
        int reportCount = 0;

        try {
            // PathMatchingResourcePatternResolver default constructor uses
            // ClassUtils.getDefaultClassLoader for classpath:*.json patterns.
            // Passing a DefaultResourceLoader as parent resolves single
            // resources but breaks pattern globbing — keep default.
            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(reportsPattern);
            Map<String, String> keyToFile = new HashMap<>();

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || filename.startsWith("exceptions")) {
                    continue;
                }
                reportCount++;

                JsonNode node;
                try (InputStream in = resource.getInputStream()) {
                    node = objectMapper.readTree(in);
                } catch (IOException e) {
                    aggregate.add(ContractViolation.fail(
                            "REPORT_FILE_LOAD_ERROR",
                            filename,
                            "_file",
                            "Failed to parse JSON: " + e.getClass().getSimpleName()
                                    + ": " + e.getMessage()));
                    continue;
                }

                String resolvedKey = resolveKey(node, filename);
                List<ContractViolation> schemaViolations =
                        schemaValidator.validate(node, resolvedKey);
                aggregate.addAll(schemaViolations);

                // Duplicate key detection (independent of schema validity).
                if (node.has("key") && node.get("key").isTextual()) {
                    String key = node.get("key").asText();
                    if (key != null && !key.isBlank()) {
                        String prev = keyToFile.put(key, filename);
                        if (prev != null) {
                            aggregate.add(ContractViolation.fail(
                                    "REPORT_KEY_DUPLICATE",
                                    key,
                                    "_registry",
                                    "Duplicate report key '" + key
                                            + "' in files: " + prev + " AND " + filename));
                        }
                    }
                }

                if (schemaViolations.isEmpty()) {
                    validNodes.put(resolvedKey, node);
                }
            }
        } catch (IOException e) {
            log.error("ReportContractGate failed to scan {}: {}", reportsPattern, e.getMessage());
            aggregate.add(ContractViolation.fail(
                    "REPORT_FILE_LOAD_ERROR",
                    "_registry",
                    "_sweep",
                    "Sweep failed for pattern " + reportsPattern + ": "
                            + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        // Bind + run semantic RC rules only on schema-valid files.
        for (Map.Entry<String, JsonNode> e : validNodes.entrySet()) {
            ReportDefinition def;
            try {
                def = objectMapper.treeToValue(e.getValue(), ReportDefinition.class);
            } catch (Exception bindErr) {
                aggregate.add(ContractViolation.fail(
                        "REPORT_FILE_LOAD_ERROR",
                        e.getKey(),
                        "_bind",
                        "Failed to bind ReportDefinition: "
                                + bindErr.getClass().getSimpleName() + ": " + bindErr.getMessage()));
                continue;
            }
            aggregate.addAll(contractValidator.validate(def));
        }

        // Apply exception suppression (RC-XXX semantic only; meta NOT suppressible).
        // Codex iter-7 §1e-AGREE absorb: applyDetailed → explicit suppression events
        // (avoids raw-minus-filtered identity-equality pitfall).
        ExceptionsRegistry.ApplyResult applyResult = exceptionsRegistry.applyDetailed(aggregate);

        log.info("ReportContractGate completed: reports={} violations={} (raw={}) suppressed={}",
                reportCount, applyResult.filtered().size(), applyResult.raw().size(),
                applyResult.suppressions().size());

        return new ContractGateSummary(
                reportCount,
                applyResult.raw(),
                applyResult.filtered(),
                applyResult.suppressions(),
                List.copyOf(exceptionsRegistry.allEntries()),
                clock.instant());
    }

    private String resolveKey(@Nullable JsonNode node, String filename) {
        if (node != null && node.has("key") && node.get("key").isTextual()) {
            String declared = node.get("key").asText();
            if (declared != null && !declared.isBlank()) {
                return declared;
            }
        }
        return filename != null ? filename : "_unknown";
    }

    public ReportDefinitionSchemaValidator schemaValidator() {
        return schemaValidator;
    }

    public ContractValidator contractValidator() {
        return contractValidator;
    }

    public ExceptionsRegistry exceptionsRegistry() {
        return exceptionsRegistry;
    }

    /** Convenience read-only view of pattern (debug/audit). */
    public String reportsPattern() {
        return reportsPattern;
    }

    /**
     * Static helper: collapse a violations list down to the violations that
     * still survived suppression at meta level (i.e. would still fail the
     * gate). Useful for tests asserting expected debt coverage.
     */
    public static List<ContractViolation> failures(ContractReport report) {
        if (report == null) {
            return Collections.emptyList();
        }
        return report.failures();
    }
}
