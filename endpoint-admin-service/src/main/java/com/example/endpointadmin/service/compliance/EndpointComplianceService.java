package com.example.endpointadmin.service.compliance;

import com.example.endpointadmin.model.ComplianceDecision;
import com.example.endpointadmin.model.ComplianceEnforcementMode;
import com.example.endpointadmin.model.EndpointComplianceEvaluation;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointDeviceComplianceState;
import com.example.endpointadmin.model.EndpointInstallAudit;
import com.example.endpointadmin.model.EndpointProhibitedSoftwareRule;
import com.example.endpointadmin.model.EndpointSoftwareCatalogItem;
import com.example.endpointadmin.model.EndpointSoftwareCompliancePolicyItem;
import com.example.endpointadmin.model.EndpointSoftwareInventoryItem;
import com.example.endpointadmin.model.EndpointSoftwareInventorySnapshot;
import com.example.endpointadmin.repository.EndpointComplianceEvaluationRepository;
import com.example.endpointadmin.repository.EndpointDeviceComplianceStateRepository;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointInstallAuditRepository;
import com.example.endpointadmin.repository.EndpointProhibitedSoftwareRuleRepository;
import com.example.endpointadmin.repository.EndpointSoftwareCompliancePolicyItemRepository;
import com.example.endpointadmin.repository.EndpointSoftwareInventorySnapshotRepository;
import com.example.endpointadmin.security.AdminTenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * BE-023 — Endpoint Compliance Service (Faz 22.5).
 *
 * <p>Computes the per-device {@link ComplianceDecision} and persists
 * the evaluation result. Codex 019e6bbf iter-3 AGREE. Architecture
 * notes:
 *
 * <ul>
 *   <li><b>Hybrid persistence</b>: every evaluation appends one row to
 *       {@link EndpointComplianceEvaluation} (history audit) and
 *       UPSERTs the latest pointer in
 *       {@link EndpointDeviceComplianceState} inside the same
 *       transaction.</li>
 *   <li><b>Advisory-lock concurrency</b>: each evaluation acquires
 *       {@code pg_try_advisory_xact_lock} on a stable hash of
 *       {@code (tenant, device)}; admin POSTs that miss the lock get
 *       409 + {@code Retry-After: 5}, event-driven AFTER_COMMIT
 *       re-evaluations silently skip (the next inventory commit
 *       will fire another evaluation).</li>
 *   <li><b>Per-stream staleness</b>: the staleness signal carried on
 *       the response is computed per-stream
 *       ({@code summaryCollectedAt}, {@code appsCollectedAt},
 *       {@code wingetEgressCollectedAt}) and aggregated into a
 *       {@code worst} severity ignoring streams that were never
 *       collected (Codex iter-3 critical_finding #1).</li>
 *   <li><b>Machine-enforced tenant integrity</b>: the policy table
 *       carries a composite FK
 *       {@code (catalog_item_id, tenant_id) ->
 *       endpoint_software_catalog_items (id, tenant_id)} that makes
 *       cross-tenant catalog references impossible at the DB layer.</li>
 *   <li><b>Deterministic catalogPolicyHash</b>: every evaluation
 *       carries a SHA-256 over a canonical-sorted JSON projection of
 *       every (policy item, catalog item) pair visible at evaluation
 *       time; a future audit can prove exactly which policy/catalog
 *       set produced the verdict.</li>
 *   <li><b>BE-025 prohibited-software denylist</b>: after the
 *       REQUIRED/FORBIDDEN catalog loop, the enabled tenant-scoped
 *       {@link com.example.endpointadmin.model.EndpointProhibitedSoftwareRule}
 *       rows (NOT catalog-bound) are matched against the same inventory
 *       snapshot by name and/or publisher. A match adds
 *       {@code PROHIBITED_APP_INSTALLED} ({@code Severity.UNAUTHORIZED})
 *       and a {@code matchedItems.prohibitedInstalled} evidence entry.
 *       Detection only — no auto-uninstall.</li>
 *   <li><b>v1 limitations</b>: {@code UNAPPROVED_APP_DETECTED}
 *       (generic "not in catalog") remains deferred along with
 *       an explicit machine-readable scope-matcher DSL. v1
 *       {@code UNAUTHORIZED} is produced by the explicit-deny reasons
 *       {@code FORBIDDEN_APP_INSTALLED} (catalog) and
 *       {@code PROHIBITED_APP_INSTALLED} (denylist). The locked
 *       invariant is the precedence ladder
 *       (UNAUTHORIZED &gt; UNKNOWN &gt; NON_COMPLIANT &gt; COMPLIANT),
 *       not the single reason driving a tier. Scheduled stale sweep
 *       deferred (GET responses surface live per-stream
 *       staleness so clients can re-trigger proactively).</li>
 * </ul>
 */
@Service
public class EndpointComplianceService {

    private static final Logger log = LoggerFactory.getLogger(EndpointComplianceService.class);

    private final EndpointDeviceRepository deviceRepository;
    private final EndpointSoftwareInventorySnapshotRepository snapshotRepository;
    private final EndpointSoftwareCompliancePolicyItemRepository policyRepository;
    private final EndpointComplianceEvaluationRepository evaluationRepository;
    private final EndpointDeviceComplianceStateRepository stateRepository;
    private final EndpointInstallAuditRepository installAuditRepository;
    /** BE-025 — tenant-scoped prohibited-software denylist rules. */
    private final EndpointProhibitedSoftwareRuleRepository prohibitedSoftwareRuleRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper canonicalObjectMapper;
    private final Clock clock;
    /**
     * BE-021 (Codex 019e6dfb iter-3 P0-4): grace window between an
     * install audit's {@code reported_at} and the next inventory
     * snapshot's {@code appsCollectedAt}. Within the window an audit
     * SATISFIES a REQUIRED policy item even when the inventory does
     * not yet show the package; past the window an inventory miss is
     * treated as an uninstall and the audit is invalidated.
     */
    private final Duration installGraceWindow;
    /** Lazily-resolved single-call cached database product name. */
    private volatile Boolean isPostgresDialect;

    public EndpointComplianceService(
            EndpointDeviceRepository deviceRepository,
            EndpointSoftwareInventorySnapshotRepository snapshotRepository,
            EndpointSoftwareCompliancePolicyItemRepository policyRepository,
            EndpointComplianceEvaluationRepository evaluationRepository,
            EndpointDeviceComplianceStateRepository stateRepository,
            EndpointInstallAuditRepository installAuditRepository,
            EndpointProhibitedSoftwareRuleRepository prohibitedSoftwareRuleRepository,
            @Autowired(required = false) JdbcTemplate jdbcTemplate,
            ObjectProvider<Clock> clockProvider,
            @Value("${endpoint-admin.compliance.install-audit-grace-window:PT15M}")
            Duration installGraceWindow) {
        this.deviceRepository = deviceRepository;
        this.snapshotRepository = snapshotRepository;
        this.policyRepository = policyRepository;
        this.evaluationRepository = evaluationRepository;
        this.stateRepository = stateRepository;
        this.installAuditRepository = installAuditRepository;
        this.prohibitedSoftwareRuleRepository = prohibitedSoftwareRuleRepository;
        this.jdbcTemplate = jdbcTemplate;
        // ObjectProvider avoids the @Lazy CGLIB enhancement of java.time.Clock
        // that broke pod boot under Java 21 + Spring Boot LaunchedClassLoader.
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
        this.installGraceWindow = installGraceWindow == null
                ? Duration.ofMinutes(15) : installGraceWindow;
        this.canonicalObjectMapper = new ObjectMapper()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
    }

    // ────────────────────────────────────────────────────────────────
    // Public API

    /**
     * Admin-initiated force evaluation. Throws
     * {@link ConcurrentComplianceEvaluationException} if the per-(tenant,
     * device) advisory lock is held by another transaction.
     */
    @Transactional
    public ComplianceEvaluationOutcome evaluateForAdmin(AdminTenantContext tenant, UUID deviceId) {
        return evaluateInternal(tenant.tenantId(), deviceId, true);
    }

    /**
     * Event-driven background re-evaluation (e.g. AFTER_COMMIT
     * inventory ingest listener). Returns empty if the advisory lock
     * is already held — the next inventory commit will fire another
     * evaluation, so a silent skip is safe.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ComplianceEvaluationOutcome> evaluateForEvent(UUID tenantId, UUID deviceId) {
        try {
            return Optional.of(evaluateInternal(tenantId, deviceId, false));
        } catch (ConcurrentComplianceEvaluationException ex) {
            log.debug("BE-023 event-driven re-eval skipped — advisory lock held tenant={} device={}",
                    tenantId, deviceId);
            return Optional.empty();
        } catch (ResponseStatusException ex) {
            // Device deleted between commit and listener fire. Not an error.
            if (ex.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                log.debug("BE-023 event-driven re-eval skipped — device not found tenant={} device={}",
                        tenantId, deviceId);
                return Optional.empty();
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public Optional<EndpointDeviceComplianceState> getLatest(AdminTenantContext tenant, UUID deviceId) {
        // Validate device tenant ownership (404 if absent) so the
        // caller cannot probe state for foreign devices.
        deviceRepository.findByTenantIdAndId(tenant.tenantId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found."));
        return stateRepository.findByTenantIdAndDeviceId(tenant.tenantId(), deviceId);
    }

    @Transactional(readOnly = true)
    public Optional<EndpointComplianceEvaluation> getEvaluationById(
            AdminTenantContext tenant, UUID evaluationId) {
        return evaluationRepository.findById(evaluationId)
                .filter(e -> Objects.equals(e.getTenantId(), tenant.tenantId()));
    }

    @Transactional(readOnly = true)
    public Page<EndpointDeviceComplianceState> listLatestStates(
            AdminTenantContext tenant, ComplianceDecision decisionFilter, Pageable pageable) {
        if (decisionFilter == null) {
            return stateRepository.findByTenantIdOrderByEvaluatedAtDesc(tenant.tenantId(), pageable);
        }
        return stateRepository.findByTenantIdAndDecisionOrderByEvaluatedAtDesc(
                tenant.tenantId(), decisionFilter, pageable);
    }

    @Transactional(readOnly = true)
    public Page<EndpointComplianceEvaluation> listDeviceHistory(
            AdminTenantContext tenant, UUID deviceId, Pageable pageable) {
        deviceRepository.findByTenantIdAndId(tenant.tenantId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found."));
        return evaluationRepository.findByTenantIdAndDeviceIdOrderByEvaluatedAtDesc(
                tenant.tenantId(), deviceId, pageable);
    }

    /**
     * Compute the staleness report against a persisted state at GET
     * time. Because the latest pointer can be stale relative to the
     * underlying inventory streams (no scheduled sweep in v1), this
     * computes severity from the live snapshot's per-stream
     * timestamps, not the persisted evaluation row.
     */
    /**
     * Compute the catalog/policy hash for the tenant's currently
     * enabled REQUIRED/FORBIDDEN policy rows AND its enabled
     * prohibited-software denylist rules. Used by the GET endpoints to
     * surface a {@code policyDrift} flag: if the live hash differs from
     * the persisted evaluation's {@code catalogPolicyHash}, the policy
     * set has changed since the verdict was last computed and the
     * operator should re-evaluate. Codex 019e6bdf iter-1 absorb.
     *
     * <p>BE-025 (Codex 019e763a REVISE #1): the prohibited-rule set is
     * folded into the SAME hash so that adding / editing / deleting an
     * enabled denylist rule flips {@code policyDrift=true} for a device
     * whose last evaluation predates the change. Without this a
     * previously-COMPLIANT device would silently keep its stale verdict
     * (a newly-banned, already-installed app would never surface a
     * "re-evaluate" CTA).
     */
    @Transactional(readOnly = true)
    public String computeCurrentPolicyHash(UUID tenantId) {
        List<EndpointSoftwareCompliancePolicyItem> policies =
                policyRepository.findEnabledByTenantAndModes(
                        tenantId,
                        List.of(ComplianceEnforcementMode.REQUIRED,
                                ComplianceEnforcementMode.FORBIDDEN));
        // Drift read: deliberately a FRESH read of the CURRENT enabled
        // prohibited-rule set (NOT a passed-in snapshot). This is the live
        // "current policy state" that the controller compares against a
        // persisted evaluation's stored hash — using a stale list here would
        // defeat drift detection entirely.
        List<EndpointProhibitedSoftwareRule> currentProhibitedRules =
                loadEnabledProhibitedRules(tenantId);
        return computePolicyHash(policies, currentProhibitedRules);
    }

    @Transactional(readOnly = true)
    public ComplianceEvaluationOutcome.StalenessReport computeStaleness(
            AdminTenantContext tenant, UUID deviceId) {
        Optional<EndpointSoftwareInventorySnapshot> snapshot =
                snapshotRepository.findByTenantIdAndDevice_Id(tenant.tenantId(), deviceId);
        Instant now = clock.instant();
        if (snapshot.isEmpty()) {
            return new ComplianceEvaluationOutcome.StalenessReport(
                    StalenessSeverity.UNAVAILABLE,
                    StalenessSeverity.UNAVAILABLE,
                    StalenessSeverity.UNAVAILABLE,
                    StalenessSeverity.UNAVAILABLE);
        }
        EndpointSoftwareInventorySnapshot s = snapshot.get();
        StalenessSeverity summary = StalenessSeverity.classify(s.getSummaryCollectedAt(), now);
        StalenessSeverity apps = s.isAppsAvailable()
                ? StalenessSeverity.classify(s.getAppsCollectedAt(), now)
                : StalenessSeverity.UNAVAILABLE;
        StalenessSeverity wingetEgress = s.getWingetEgress() == null
                ? StalenessSeverity.UNAVAILABLE
                : StalenessSeverity.classify(s.getWingetEgressCollectedAt(), now);
        StalenessSeverity worst = StalenessSeverity.worstOf(summary, apps, wingetEgress);
        return new ComplianceEvaluationOutcome.StalenessReport(summary, apps, wingetEgress, worst);
    }

    // ────────────────────────────────────────────────────────────────
    // Internal evaluation pipeline

    private ComplianceEvaluationOutcome evaluateInternal(
            UUID tenantId, UUID deviceId, boolean forceAdmin) {
        EndpointDevice device = deviceRepository.findByTenantIdAndId(tenantId, deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found."));

        boolean locked = tryAcquireLock(tenantId, deviceId);
        if (!locked) {
            if (forceAdmin) {
                throw new ConcurrentComplianceEvaluationException(
                        "Another compliance evaluation is in flight for this device.");
            }
            // Event-driven path: caller maps this to an empty Optional.
            throw new ConcurrentComplianceEvaluationException(
                    "Advisory lock held; event-driven evaluation skipped.");
        }

        Optional<EndpointSoftwareInventorySnapshot> snapshotOpt =
                snapshotRepository.findByTenantIdAndDevice_Id(tenantId, deviceId);
        EndpointSoftwareInventorySnapshot snapshot = snapshotOpt.orElse(null);

        List<EndpointSoftwareCompliancePolicyItem> policies =
                policyRepository.findEnabledByTenantAndModes(
                        tenantId,
                        List.of(ComplianceEnforcementMode.REQUIRED,
                                ComplianceEnforcementMode.FORBIDDEN));

        // BE-025 (Codex 019e763a REVISE #1): load the enabled prohibited-rule
        // set ONCE for this evaluation, unconditionally (apps-available-
        // independent — matching may early-return on it but the hash path
        // always needs it). The SAME in-memory snapshot then feeds BOTH the
        // matching path AND the persisted catalogPolicyHash projection, so the
        // persisted row's decision/evidence and its stored hash can never
        // derive from two different reads that straddle a concurrent
        // prohibited-rule commit under READ_COMMITTED. computeCurrentPolicyHash
        // (the controller's drift read) deliberately keeps its OWN fresh read.
        List<EndpointProhibitedSoftwareRule> prohibitedRules = loadEnabledProhibitedRules(tenantId);

        Instant now = clock.instant();
        EvaluationContext ctx = new EvaluationContext();

        // ─── Telemetry sufficiency / staleness gates ──────────────
        evaluateInventoryGate(snapshot, now, ctx);
        evaluateEgressGate(snapshot, ctx);

        // ─── Policy coverage gate ─────────────────────────────────
        if (policies.isEmpty()) {
            ctx.addReason(ComplianceReason.POLICY_EMPTY);
        }

        // ─── REQUIRED / FORBIDDEN matching ────────────────────────
        Map<UUID, MatchedRequired> matchedRequired = new LinkedHashMap<>();
        Map<UUID, MatchedRequired> outdatedRequired = new LinkedHashMap<>();
        Map<UUID, MatchedRequired> missingRequired = new LinkedHashMap<>();
        Map<UUID, MatchedForbidden> forbiddenInstalled = new LinkedHashMap<>();
        Set<String> unsupportedDetectionPackageIds = new LinkedHashSet<>();

        for (EndpointSoftwareCompliancePolicyItem policy : policies) {
            EndpointSoftwareCatalogItem catalog = policy.getCatalogItem();
            if (catalog == null) {
                // Composite-FK should prevent this, but defence-in-depth:
                ctx.addReason(ComplianceReason.POLICY_CATALOG_ITEM_UNAVAILABLE);
                continue;
            }
            evaluatePolicyItem(policy, catalog, snapshot, device.getId(), now, ctx,
                    matchedRequired, outdatedRequired, missingRequired,
                    forbiddenInstalled, unsupportedDetectionPackageIds);
        }

        // ─── BE-025 prohibited-software denylist matching ─────────
        // Tenant-scoped, NOT catalog-bound: matched directly against the
        // installed inventory by name/publisher. Same telemetry gate as the
        // catalog FORBIDDEN path — only authoritative when the inventory is
        // present and apps are available (an absent / apps-unavailable
        // snapshot already added the UNKNOWN-driving reason; producing
        // prohibited findings off it would be off stale/missing data).
        List<MatchedProhibited> prohibitedInstalled =
                evaluateProhibitedDenylist(prohibitedRules, snapshot, ctx);

        // ─── Decide ───────────────────────────────────────────────
        ComplianceDecision decision = decide(ctx);

        // ─── Evidence + hash ──────────────────────────────────────
        Map<String, Object> evidence = buildEvidence(
                snapshot, policies, matchedRequired, outdatedRequired,
                missingRequired, forbiddenInstalled, prohibitedInstalled,
                unsupportedDetectionPackageIds);
        // BE-025 (Codex 019e763a REVISE #1): the persisted hash folds in the
        // enabled prohibited-rule set so a later prohibited-rule change makes
        // computeCurrentPolicyHash diverge from this stored value → drift. The
        // SAME pre-loaded prohibitedRules snapshot used by the matching path is
        // threaded in here so the persisted decision/evidence and this stored
        // hash are guaranteed to derive from one rule-set read.
        String catalogPolicyHash = computePolicyHash(policies, prohibitedRules);

        Long catalogRowVersionMax = policies.stream()
                .map(p -> p.getCatalogItem() == null ? null : p.getCatalogItem().getVersion())
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);
        Long policyRowVersionMax = policies.stream()
                .map(EndpointSoftwareCompliancePolicyItem::getVersion)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);

        // ─── Persist evaluation + upsert latest pointer ───────────
        EndpointComplianceEvaluation persisted = new EndpointComplianceEvaluation();
        persisted.setTenantId(tenantId);
        persisted.setDeviceId(device.getId());
        persisted.setEvaluatedAt(now);
        persisted.setDecision(decision);
        persisted.setReasons(ctx.allReasonCodes());
        persisted.setBlockingReasons(ctx.blockingReasonCodes());
        persisted.setWarnings(ctx.warningReasonCodes());
        persisted.setEvidence(evidence);
        persisted.setCatalogPolicyHash(catalogPolicyHash);
        persisted.setInventorySnapshotId(snapshot == null ? null : snapshot.getId());
        persisted.setInventorySnapshotRowVersion(snapshot == null ? null : snapshot.getVersion());
        persisted.setCatalogRowVersionMax(catalogRowVersionMax);
        persisted.setPolicyRowVersionMax(policyRowVersionMax);
        EndpointComplianceEvaluation savedEvaluation = evaluationRepository.save(persisted);

        upsertLatestPointer(tenantId, device.getId(), savedEvaluation, now);

        ComplianceEvaluationOutcome.StalenessReport report = buildStalenessReport(snapshot, now);

        return new ComplianceEvaluationOutcome(
                savedEvaluation.getId(),
                device.getId(),
                decision,
                now,
                ctx.allReasonCodes(),
                ctx.blockingReasonCodes(),
                ctx.warningReasonCodes(),
                evidence,
                catalogPolicyHash,
                snapshot == null ? null : snapshot.getId(),
                snapshot == null ? null : snapshot.getVersion(),
                catalogRowVersionMax,
                policyRowVersionMax,
                report);
    }

    // ────────────────────────────────────────────────────────────────
    // Gate evaluators

    private void evaluateInventoryGate(
            EndpointSoftwareInventorySnapshot snapshot, Instant now, EvaluationContext ctx) {
        if (snapshot == null) {
            ctx.addReason(ComplianceReason.INVENTORY_MISSING);
            return;
        }
        if (!snapshot.isSupported()) {
            ctx.addReason(ComplianceReason.INVENTORY_UNSUPPORTED);
            return;
        }
        if (snapshot.isTruncated()) {
            ctx.addReason(ComplianceReason.INVENTORY_TRUNCATED);
        }
        if (!snapshot.isAppsAvailable()) {
            ctx.addReason(ComplianceReason.APPS_UNAVAILABLE);
        }
        StalenessSeverity summary = StalenessSeverity.classify(snapshot.getSummaryCollectedAt(), now);
        StalenessSeverity apps = snapshot.isAppsAvailable()
                ? StalenessSeverity.classify(snapshot.getAppsCollectedAt(), now)
                : StalenessSeverity.UNAVAILABLE;
        if (summary == StalenessSeverity.HARD || apps == StalenessSeverity.HARD) {
            ctx.addReason(ComplianceReason.INVENTORY_STALE_HARD);
        } else if (summary == StalenessSeverity.SOFT || apps == StalenessSeverity.SOFT) {
            ctx.addReason(ComplianceReason.INVENTORY_STALE_SOFT);
        }
    }

    private void evaluateEgressGate(EndpointSoftwareInventorySnapshot snapshot, EvaluationContext ctx) {
        if (snapshot == null) {
            return;
        }
        Map<String, Object> egress = snapshot.getWingetEgress();
        if (egress == null) {
            ctx.addReason(ComplianceReason.WINGET_EGRESS_MISSING);
            return;
        }
        Object supported = egress.get("supported");
        if (!Boolean.TRUE.equals(supported)) {
            ctx.addReason(ComplianceReason.WINGET_EGRESS_UNSUPPORTED);
            return;
        }
        Integer schemaVersion = snapshot.getWingetEgressSchemaVersion();
        if (schemaVersion == null || schemaVersion != 1) {
            ctx.addReason(ComplianceReason.WINGET_EGRESS_SCHEMA_UNSUPPORTED);
        }
    }

    private void evaluatePolicyItem(
            EndpointSoftwareCompliancePolicyItem policy,
            EndpointSoftwareCatalogItem catalog,
            EndpointSoftwareInventorySnapshot snapshot,
            UUID deviceId,
            Instant evaluationStartedAt,
            EvaluationContext ctx,
            Map<UUID, MatchedRequired> matchedRequired,
            Map<UUID, MatchedRequired> outdatedRequired,
            Map<UUID, MatchedRequired> missingRequired,
            Map<UUID, MatchedForbidden> forbiddenInstalled,
            Set<String> unsupportedDetectionPackageIds) {
        // Skip evaluation if inventory has not been ingested or apps
        // are unavailable. The telemetry gate has already added the
        // appropriate UNKNOWN-driving reason; piling additional
        // per-policy noise on top makes the audit row harder to read.
        // BE-021 (Codex 019e6dfb iter-3 P0-4): the install audit fallback
        // is *minimal/conservative* — it only kicks in when inventory is
        // present and apps-available; an audit alone cannot suppress
        // UNKNOWN driven by missing telemetry.
        if (snapshot == null || !snapshot.isAppsAvailable()) {
            return;
        }
        EndpointSoftwareInventoryItem installed = findInstalled(snapshot, catalog);
        switch (policy.getEnforcementMode()) {
            case REQUIRED -> evaluateRequired(policy, catalog, snapshot, deviceId,
                    evaluationStartedAt, installed, ctx,
                    matchedRequired, outdatedRequired, missingRequired,
                    unsupportedDetectionPackageIds);
            case FORBIDDEN -> {
                if (installed != null) {
                    ctx.addReason(ComplianceReason.FORBIDDEN_APP_INSTALLED);
                    forbiddenInstalled.put(catalog.getId(),
                            MatchedForbidden.of(catalog, installed));
                }
            }
            case ALLOWED -> {
                // ALLOWED rows are skipped by the repository query;
                // included here for exhaustiveness only.
            }
        }
    }

    private void evaluateRequired(
            EndpointSoftwareCompliancePolicyItem policy,
            EndpointSoftwareCatalogItem catalog,
            EndpointSoftwareInventorySnapshot snapshot,
            UUID deviceId,
            Instant evaluationStartedAt,
            EndpointSoftwareInventoryItem installed,
            EvaluationContext ctx,
            Map<UUID, MatchedRequired> matchedRequired,
            Map<UUID, MatchedRequired> outdatedRequired,
            Map<UUID, MatchedRequired> missingRequired,
            Set<String> unsupportedDetectionPackageIds) {
        if (installed != null) {
            // Inventory is the primary signal — the install audit is
            // ignored when the package is observably present.
            VersionComparator.Result result = VersionComparator.compare(
                    catalog.getVersionPolicyType(),
                    catalog.getVersionPolicyValue(),
                    installed.getDisplayVersion());
            switch (result) {
                case SATISFIES -> matchedRequired.put(catalog.getId(),
                        MatchedRequired.installed(catalog, installed));
                case VIOLATES -> {
                    ctx.addReason(ComplianceReason.OUTDATED_REQUIRED_APP);
                    outdatedRequired.put(catalog.getId(),
                            MatchedRequired.outdated(catalog, installed));
                }
                case UNSUPPORTED -> {
                    ctx.addReason(ComplianceReason.VERSION_COMPARE_UNSUPPORTED);
                    unsupportedDetectionPackageIds.add(catalog.getPackageId());
                }
            }
            return;
        }
        // BE-021 — inventory miss + apps available → install audit
        // fallback (deterministic selector). The repository query
        // already restricts to SUCCEEDED + SATISFIED and rejects
        // rows committed at or after evaluationStartedAt.
        Optional<EndpointInstallAudit> auditOpt = installAuditRepository
                .findLatestSucceededSatisfiedByTenantDeviceCatalogBefore(
                        policy.getTenantId(), deviceId, catalog.getId(), evaluationStartedAt);
        if (auditOpt.isEmpty()) {
            ctx.addReason(ComplianceReason.MISSING_REQUIRED_APP);
            missingRequired.put(catalog.getId(), MatchedRequired.missing(catalog));
            return;
        }
        EndpointInstallAudit audit = auditOpt.get();
        // Contradiction check: a newer inventory snapshot (collected
        // past the install grace window) that still does not see the
        // package is treated as a post-install uninstall — the audit
        // is invalidated and a WARN explains the contradiction.
        if (snapshot.getAppsCollectedAt() != null
                && audit.getReportedAt() != null
                && snapshot.getAppsCollectedAt().isAfter(
                        audit.getReportedAt().plus(installGraceWindow))) {
            ctx.addReason(ComplianceReason.MISSING_REQUIRED_APP);
            ctx.addReason(ComplianceReason.INSTALL_AUDIT_CONTRADICTED_BY_INVENTORY);
            missingRequired.put(catalog.getId(),
                    MatchedRequired.missingByContradiction(catalog, audit));
            return;
        }
        VersionComparator.Result result = VersionComparator.compare(
                catalog.getVersionPolicyType(),
                catalog.getVersionPolicyValue(),
                audit.getDetectedVersion());
        switch (result) {
            case SATISFIES -> matchedRequired.put(catalog.getId(),
                    MatchedRequired.installedByAudit(catalog, audit));
            case VIOLATES -> {
                ctx.addReason(ComplianceReason.OUTDATED_REQUIRED_APP);
                outdatedRequired.put(catalog.getId(),
                        MatchedRequired.outdatedByAudit(catalog, audit));
            }
            case UNSUPPORTED -> {
                ctx.addReason(ComplianceReason.VERSION_COMPARE_UNSUPPORTED);
                unsupportedDetectionPackageIds.add(catalog.getPackageId());
            }
        }
    }

    /**
     * BE-025 (Codex 019e763a REVISE #1) — single source of the enabled
     * prohibited-rule set for one evaluation. Called ONCE at the start of
     * {@code evaluateInternal} so the matching path and the persisted-hash
     * projection share one in-memory snapshot (no two reads straddling a
     * concurrent commit). Also used by {@code computeCurrentPolicyHash} for
     * its independent fresh drift read.
     *
     * <p>{@code prohibitedSoftwareRuleRepository} is constructor-injected and
     * never null in production wiring. The null guard keeps older unit
     * fixtures that do not supply it from NPE-ing — they simply see an empty
     * denylist (and contribute no rows to the hash).
     */
    private List<EndpointProhibitedSoftwareRule> loadEnabledProhibitedRules(UUID tenantId) {
        if (prohibitedSoftwareRuleRepository == null) {
            return List.of();
        }
        return prohibitedSoftwareRuleRepository
                .findByTenantIdAndEnabledTrueOrderByIdAsc(tenantId);
    }

    /**
     * BE-025 — match the tenant's enabled prohibited-software denylist
     * rules against the inventory snapshot. Returns the de-duplicated
     * list of {@link MatchedProhibited} findings (one per matched item,
     * carrying the rule that fired) and, when non-empty, adds the
     * {@code PROHIBITED_APP_INSTALLED} reason ({@code Severity.UNAUTHORIZED}).
     *
     * <p>Gated on a present + apps-available snapshot for the same reason
     * the catalog FORBIDDEN path is: an absent / apps-unavailable inventory
     * is not authoritative, and the telemetry gate has already driven the
     * decision to UNKNOWN. {@code findInstalled}-style matching is delegated
     * to the pure {@link ProhibitedSoftwareMatcher}.
     *
     * <p>BE-025 (Codex 019e763a REVISE #1): the {@code rules} list is
     * PRE-LOADED by {@code evaluateInternal} and the SAME instance feeds the
     * persisted-hash projection, so the persisted decision/evidence and stored
     * hash never derive from two independent reads.
     */
    private List<MatchedProhibited> evaluateProhibitedDenylist(
            List<EndpointProhibitedSoftwareRule> rules,
            EndpointSoftwareInventorySnapshot snapshot, EvaluationContext ctx) {
        if (snapshot == null || !snapshot.isAppsAvailable() || rules.isEmpty()) {
            return List.of();
        }
        // Stable de-dup: at most one finding per (rule, installed item) pair,
        // keyed on rule id + item id so two rules that both flag the same app
        // each surface once, and one rule flagging two apps surfaces twice.
        Map<String, MatchedProhibited> findings = new LinkedHashMap<>();
        for (EndpointSoftwareInventoryItem item : snapshot.getItems()) {
            for (EndpointProhibitedSoftwareRule rule : rules) {
                if (ProhibitedSoftwareMatcher.matches(rule, item)) {
                    String key = rule.getId() + "|" + item.getId();
                    findings.putIfAbsent(key, MatchedProhibited.of(rule, item));
                }
            }
        }
        if (!findings.isEmpty()) {
            ctx.addReason(ComplianceReason.PROHIBITED_APP_INSTALLED);
        }
        return new ArrayList<>(findings.values());
    }

    /**
     * Decision precedence ladder (Codex 019e6bbf iter-3 AGREE locked):
     * UNAUTHORIZED &gt; UNKNOWN &gt; NON_COMPLIANT &gt; COMPLIANT. BE-025
     * adds {@code PROHIBITED_APP_INSTALLED} as a second
     * {@code Severity.UNAUTHORIZED} source; the ladder is unchanged because
     * {@code decide} keys on severity, not on the specific reason.
     */
    private ComplianceDecision decide(EvaluationContext ctx) {
        if (ctx.hasReasonOfSeverity(ComplianceReason.Severity.UNAUTHORIZED)) {
            return ComplianceDecision.UNAUTHORIZED;
        }
        if (ctx.hasReasonOfSeverity(ComplianceReason.Severity.UNKNOWN)) {
            return ComplianceDecision.UNKNOWN;
        }
        if (ctx.hasReasonOfSeverity(ComplianceReason.Severity.NON_COMPLIANT)) {
            return ComplianceDecision.NON_COMPLIANT;
        }
        return ComplianceDecision.COMPLIANT;
    }

    // ────────────────────────────────────────────────────────────────
    // Concurrency / locking

    private boolean tryAcquireLock(UUID tenantId, UUID deviceId) {
        if (jdbcTemplate == null) {
            return true;
        }
        long key = computeLockKey(tenantId, deviceId);
        try {
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, key);
            return Boolean.TRUE.equals(acquired);
        } catch (DataAccessException ex) {
            // Codex 019e6bdf iter-1 absorb: a JDBC failure on the
            // advisory-lock SQL must NOT silently disable
            // serialisation on Postgres. The fall-through to
            // unlocked-eval is allowed only when we are
            // demonstrably running on a non-Postgres dialect that
            // does not implement pg_try_advisory_xact_lock (e.g. H2
            // in unit tests). On Postgres we re-throw as a
            // ConcurrentComplianceEvaluationException so the caller
            // (admin POST → 409, listener → silent skip) gets the
            // same "could not lock" treatment instead of proceeding
            // unserialised.
            if (resolveIsPostgresDialect()) {
                log.error("BE-023 advisory lock SQL failed on Postgres — fail-closed", ex);
                throw new ConcurrentComplianceEvaluationException(
                        "Advisory lock acquisition failed on Postgres; "
                                + "refusing to evaluate without serialisation.");
            }
            log.debug("BE-023 advisory lock unsupported on non-Postgres dialect — proceeding without lock", ex);
            return true;
        }
    }

    /**
     * Lazy single-call cached probe — Codex 019e6bdf iter-1 absorb.
     * Returns {@code true} only when the underlying datasource is a
     * genuine Postgres engine. H2 / Derby / unknown engines return
     * {@code false} so the advisory-lock SQL failure falls through to
     * the "unsupported dialect — proceed unlocked" branch.
     */
    private boolean resolveIsPostgresDialect() {
        Boolean cached = isPostgresDialect;
        if (cached != null) {
            return cached;
        }
        boolean detected = false;
        if (jdbcTemplate != null && jdbcTemplate.getDataSource() != null) {
            try (java.sql.Connection conn = jdbcTemplate.getDataSource().getConnection()) {
                String product = conn.getMetaData().getDatabaseProductName();
                if (product != null) {
                    detected = product.toLowerCase(Locale.ROOT).contains("postgres");
                }
            } catch (SQLException ex) {
                // If we cannot determine the dialect, default to "not Postgres"
                // so a metadata probe failure does not falsely classify a
                // non-Postgres engine as Postgres. This keeps the
                // fall-through unlocked-eval safe in unit tests.
                log.debug("BE-023 could not resolve database dialect — defaulting to non-Postgres", ex);
            }
        }
        isPostgresDialect = detected;
        return detected;
    }

    static long computeLockKey(UUID tenantId, UUID deviceId) {
        // Fold the two UUIDs into a stable bigint so the lock space is
        // shared across (tenant, device) pairs and isolated from
        // unrelated advisory-lock consumers in the schema.
        // Prefix with the BE-023 namespace bits.
        long t1 = tenantId.getMostSignificantBits();
        long t2 = tenantId.getLeastSignificantBits();
        long d1 = deviceId.getMostSignificantBits();
        long d2 = deviceId.getLeastSignificantBits();
        return 0xBE23000000000000L ^ (t1 * 31 + t2) ^ Long.rotateLeft(d1 * 31 + d2, 7);
    }

    private void upsertLatestPointer(
            UUID tenantId, UUID deviceId,
            EndpointComplianceEvaluation evaluation, Instant now) {
        EndpointDeviceComplianceState state = stateRepository
                .findByTenantIdAndDeviceId(tenantId, deviceId)
                .orElseGet(() -> {
                    EndpointDeviceComplianceState fresh = new EndpointDeviceComplianceState();
                    fresh.setTenantId(tenantId);
                    fresh.setDeviceId(deviceId);
                    return fresh;
                });
        state.setLatestEvaluationId(evaluation.getId());
        state.setDecision(evaluation.getDecision());
        state.setEvaluatedAt(evaluation.getEvaluatedAt());
        stateRepository.save(state);
    }

    // ────────────────────────────────────────────────────────────────
    // Hash + evidence projection

    /**
     * Compute the deterministic SHA-256 that backs the {@code policyDrift}
     * signal. It is a hash over a canonical-sorted projection of BOTH:
     *
     * <ol>
     *   <li>the tenant's REQUIRED/FORBIDDEN catalog-bound policy rows
     *       (the original BE-023 contract); and</li>
     *   <li>the tenant's enabled prohibited-software denylist rules
     *       (BE-025, Codex 019e763a REVISE #1) — so a denylist
     *       create/update/delete changes the hash and surfaces drift for
     *       a device whose verdict predates it.</li>
     * </ol>
     *
     * <p>Both sub-projections are sorted by their row id so the hash is
     * order-independent (it does not depend on repository result ordering),
     * and the two are wrapped under stable keys so the combined document is
     * itself canonical. The stored column / DTO field keeps the historical
     * name {@code catalogPolicyHash}; its meaning is widened to "the policy
     * set (catalog policies + prohibited rules) that produced this verdict".
     */
    private String computePolicyHash(
            List<EndpointSoftwareCompliancePolicyItem> policies,
            List<EndpointProhibitedSoftwareRule> prohibitedRules) {
        try {
            Map<String, Object> document = new TreeMap<>();
            document.put("catalogPolicies", catalogPolicyProjection(policies));
            document.put("prohibitedRules", prohibitedRuleProjection(prohibitedRules));
            String canonical = canonicalObjectMapper.writeValueAsString(document);
            return sha256Hex(canonical);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            // Fail-closed by hashing the cardinality and a stable sentinel.
            log.warn("BE-023 policyHash canonicalisation failed", ex);
            return "sha256:fallback-" + policies.size();
        }
    }

    /**
     * Canonical-sorted projection of the REQUIRED/FORBIDDEN catalog-bound
     * policy rows (the original BE-023 hash input, factored out so the BE-025
     * prohibited-rule projection can be folded in alongside it).
     */
    private static List<Map<String, Object>> catalogPolicyProjection(
            List<EndpointSoftwareCompliancePolicyItem> policies) {
        List<Map<String, Object>> projection = new ArrayList<>(policies.size());
        for (EndpointSoftwareCompliancePolicyItem policy : policies) {
            EndpointSoftwareCatalogItem catalog = policy.getCatalogItem();
            Map<String, Object> entry = new TreeMap<>();
            entry.put("policyItemId", policy.getId() == null ? null : policy.getId().toString());
            entry.put("policyRowVersion", policy.getVersion());
            entry.put("enforcementMode", policy.getEnforcementMode().name());
            entry.put("enabled", policy.isEnabled());
            if (catalog != null) {
                entry.put("catalogItemId", catalog.getId() == null ? null : catalog.getId().toString());
                entry.put("catalogRowVersion", catalog.getVersion());
                entry.put("catalogPackageId", catalog.getPackageId());
                entry.put("catalogDisplayName", catalog.getDisplayName());
                entry.put("catalogStatus", catalog.getStatus() == null ? null : catalog.getStatus().name());
                entry.put("versionPolicyType",
                        catalog.getVersionPolicyType() == null ? null : catalog.getVersionPolicyType().name());
                entry.put("versionPolicyValue", catalog.getVersionPolicyValue());
                entry.put("detectionRule", catalog.getDetectionRule() == null
                        ? null : new TreeMap<>(catalog.getDetectionRule()));
            }
            projection.add(entry);
        }
        projection.sort(Comparator.comparing(m -> Objects.toString(m.get("policyItemId"), "")));
        return projection;
    }

    /**
     * BE-025 (Codex 019e763a REVISE #1) — canonical, order-independent
     * projection of the tenant's enabled prohibited-software denylist rules
     * that feeds the drift hash. Each entry carries the stable identity an
     * operator could change ({@code ruleId}, {@code matchType},
     * {@code matchMode}) plus the NORMALIZED patterns ({@code lower(trim)} via
     * {@link ProhibitedSoftwareRuleValidator#normalize(String)}) so a pure
     * case/whitespace edit that the matcher treats as equivalent does NOT
     * register as drift, while a genuine pattern change does. Entries are
     * sorted by {@code ruleId} so the projection is independent of repository
     * ordering. An empty / absent denylist contributes an empty list (a hash
     * input distinct from a populated one).
     *
     * <p>BE-025 (Codex 019e763a REVISE #1): the {@code rules} list is
     * PRE-LOADED once per call site — the persisted path passes the same
     * snapshot the matching path consumed; {@code computeCurrentPolicyHash}
     * passes its own fresh read. This method no longer touches the repository,
     * so the persisted decision/evidence and stored hash can never derive from
     * two independent reads.
     */
    private List<Map<String, Object>> prohibitedRuleProjection(
            List<EndpointProhibitedSoftwareRule> rules) {
        List<Map<String, Object>> projection = new ArrayList<>(rules.size());
        for (EndpointProhibitedSoftwareRule rule : rules) {
            Map<String, Object> entry = new TreeMap<>();
            entry.put("ruleId", rule.getId() == null ? null : rule.getId().toString());
            entry.put("matchType", rule.getMatchType() == null ? null : rule.getMatchType().name());
            entry.put("matchMode", rule.getMatchMode() == null ? null : rule.getMatchMode().name());
            entry.put("namePattern", ProhibitedSoftwareRuleValidator.normalize(rule.getNamePattern()));
            entry.put("publisherPattern",
                    ProhibitedSoftwareRuleValidator.normalize(rule.getPublisherPattern()));
            projection.add(entry);
        }
        projection.sort(Comparator.comparing(m -> Objects.toString(m.get("ruleId"), "")));
        return projection;
    }

    private static String sha256Hex(String canonical) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private Map<String, Object> buildEvidence(
            EndpointSoftwareInventorySnapshot snapshot,
            List<EndpointSoftwareCompliancePolicyItem> policies,
            Map<UUID, MatchedRequired> matchedRequired,
            Map<UUID, MatchedRequired> outdatedRequired,
            Map<UUID, MatchedRequired> missingRequired,
            Map<UUID, MatchedForbidden> forbiddenInstalled,
            List<MatchedProhibited> prohibitedInstalled,
            Set<String> unsupportedDetectionPackageIds) {
        Map<String, Object> ev = buildEvidenceCore(snapshot, policies,
                matchedRequired, outdatedRequired, missingRequired,
                forbiddenInstalled, prohibitedInstalled, unsupportedDetectionPackageIds);
        // BE-021 (Codex 019e6dfb iter-3 P0-4): expose the install audit
        // fallback references as a top-level evidence projection so the
        // UI can render "satisfied via install audit X (reported_at Y)"
        // without having to walk matched.requiredOk[*].installAuditId.
        // This sits OUTSIDE catalogPolicyHash by construction —
        // computePolicyHash projects policy + prohibited-rule sets only.
        List<Map<String, Object>> installAuditRefs = new ArrayList<>();
        Map<String, String> dataSourceByCatalogItemId = new LinkedHashMap<>();
        appendInstallAuditRefs(matchedRequired.values(), installAuditRefs, dataSourceByCatalogItemId);
        appendInstallAuditRefs(outdatedRequired.values(), installAuditRefs, dataSourceByCatalogItemId);
        appendInstallAuditRefs(missingRequired.values(), installAuditRefs, dataSourceByCatalogItemId);
        ev.put("installAuditRefs", installAuditRefs);
        ev.put("dataSourceByCatalogItemId", dataSourceByCatalogItemId);
        ev.put("installAuditGraceWindowSeconds", installGraceWindow.toSeconds());
        return ev;
    }

    private static void appendInstallAuditRefs(
            Iterable<MatchedRequired> rows,
            List<Map<String, Object>> installAuditRefs,
            Map<String, String> dataSourceByCatalogItemId) {
        for (MatchedRequired m : rows) {
            if (m.catalogItemId() != null && m.dataSource() != null) {
                dataSourceByCatalogItemId.put(m.catalogItemId().toString(), m.dataSource());
            }
            if (m.installAuditId() == null) {
                continue;
            }
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("catalogItemId", m.catalogItemId() == null ? null : m.catalogItemId().toString());
            ref.put("installAuditId", m.installAuditId().toString());
            ref.put("installAuditReportedAt",
                    m.installAuditReportedAt() == null ? null : m.installAuditReportedAt().toString());
            ref.put("installAuditRowVersion", m.installAuditRowVersion());
            ref.put("outcome", m.outcome());
            ref.put("dataSource", m.dataSource());
            installAuditRefs.add(ref);
        }
    }

    private Map<String, Object> buildEvidenceCore(
            EndpointSoftwareInventorySnapshot snapshot,
            List<EndpointSoftwareCompliancePolicyItem> policies,
            Map<UUID, MatchedRequired> matchedRequired,
            Map<UUID, MatchedRequired> outdatedRequired,
            Map<UUID, MatchedRequired> missingRequired,
            Map<UUID, MatchedForbidden> forbiddenInstalled,
            List<MatchedProhibited> prohibitedInstalled,
            Set<String> unsupportedDetectionPackageIds) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("inventorySnapshotId", snapshot == null ? null
                : (snapshot.getId() == null ? null : snapshot.getId().toString()));
        ev.put("inventorySnapshotRowVersion", snapshot == null ? null : snapshot.getVersion());
        ev.put("inventoryUpdatedAt", snapshot == null || snapshot.getUpdatedAt() == null
                ? null : snapshot.getUpdatedAt().toString());
        ev.put("summaryCollectedAt", snapshot == null || snapshot.getSummaryCollectedAt() == null
                ? null : snapshot.getSummaryCollectedAt().toString());
        ev.put("appsCollectedAt", snapshot == null || snapshot.getAppsCollectedAt() == null
                ? null : snapshot.getAppsCollectedAt().toString());
        ev.put("wingetEgressCollectedAt",
                snapshot == null || snapshot.getWingetEgressCollectedAt() == null
                        ? null : snapshot.getWingetEgressCollectedAt().toString());
        ev.put("wingetEgressSchemaVersion",
                snapshot == null ? null : snapshot.getWingetEgressSchemaVersion());
        ev.put("latestSummaryCommandResultId",
                snapshot == null || snapshot.getLatestSummaryCommandResult() == null
                        ? null : snapshot.getLatestSummaryCommandResult().getId().toString());
        ev.put("latestFullCommandResultId",
                snapshot == null || snapshot.getLatestFullCommandResult() == null
                        ? null : snapshot.getLatestFullCommandResult().getId().toString());
        ev.put("latestWingetEgressCommandResultId",
                snapshot == null || snapshot.getLatestWingetEgressCommandResult() == null
                        ? null : snapshot.getLatestWingetEgressCommandResult().getId().toString());
        ev.put("catalogItemCount", policies.size());
        ev.put("policyItemCount", policies.size());

        Map<String, Object> matched = new LinkedHashMap<>();
        matched.put("requiredOk", matchedRequired.values().stream()
                .map(MatchedRequired::toEvidenceMap).collect(Collectors.toList()));
        matched.put("requiredOutdated", outdatedRequired.values().stream()
                .map(MatchedRequired::toEvidenceMap).collect(Collectors.toList()));
        matched.put("requiredMissing", missingRequired.values().stream()
                .map(MatchedRequired::toEvidenceMap).collect(Collectors.toList()));
        matched.put("forbiddenInstalled", forbiddenInstalled.values().stream()
                .map(MatchedForbidden::toEvidenceMap).collect(Collectors.toList()));
        // BE-025 — denylist findings, kept distinct from catalog
        // forbiddenInstalled so the UI / read surface can tell a
        // catalog-FORBIDDEN hit from a prohibited-denylist hit. Only the
        // rule id + redacted matched fields (name / publisher / version) —
        // never the rule's notes / created_by_subject.
        matched.put("prohibitedInstalled", prohibitedInstalled.stream()
                .map(MatchedProhibited::toEvidenceMap).collect(Collectors.toList()));
        matched.put("versionCompareUnsupportedPackageIds",
                new ArrayList<>(unsupportedDetectionPackageIds));
        ev.put("matchedItems", matched);
        return ev;
    }

    private ComplianceEvaluationOutcome.StalenessReport buildStalenessReport(
            EndpointSoftwareInventorySnapshot snapshot, Instant now) {
        if (snapshot == null) {
            return new ComplianceEvaluationOutcome.StalenessReport(
                    StalenessSeverity.UNAVAILABLE,
                    StalenessSeverity.UNAVAILABLE,
                    StalenessSeverity.UNAVAILABLE,
                    StalenessSeverity.UNAVAILABLE);
        }
        StalenessSeverity summary = StalenessSeverity.classify(snapshot.getSummaryCollectedAt(), now);
        StalenessSeverity apps = snapshot.isAppsAvailable()
                ? StalenessSeverity.classify(snapshot.getAppsCollectedAt(), now)
                : StalenessSeverity.UNAVAILABLE;
        StalenessSeverity wingetEgress = snapshot.getWingetEgress() == null
                ? StalenessSeverity.UNAVAILABLE
                : StalenessSeverity.classify(snapshot.getWingetEgressCollectedAt(), now);
        StalenessSeverity worst = StalenessSeverity.worstOf(summary, apps, wingetEgress);
        return new ComplianceEvaluationOutcome.StalenessReport(summary, apps, wingetEgress, worst);
    }

    // ────────────────────────────────────────────────────────────────
    // Inventory matching

    static EndpointSoftwareInventoryItem findInstalled(
            EndpointSoftwareInventorySnapshot snapshot,
            EndpointSoftwareCatalogItem catalog) {
        if (snapshot == null || catalog == null) {
            return null;
        }
        String pkg = catalog.getPackageId();
        String displayName = catalog.getDisplayName();
        for (EndpointSoftwareInventoryItem item : snapshot.getItems()) {
            if (matches(pkg, item) || matches(displayName, item)) {
                return item;
            }
        }
        return null;
    }

    private static boolean matches(String needle, EndpointSoftwareInventoryItem item) {
        if (needle == null || needle.isBlank()) {
            return false;
        }
        String n = needle.toLowerCase(Locale.ROOT);
        String name = item.getDisplayName() == null
                ? "" : item.getDisplayName().toLowerCase(Locale.ROOT);
        return !name.isEmpty() && name.contains(n);
    }

    // ────────────────────────────────────────────────────────────────
    // Internal aggregators

    /** Mutable per-evaluation accumulator for reasons (dedup'd). */
    static final class EvaluationContext {
        private final LinkedHashMap<ComplianceReason, ComplianceReason> reasons = new LinkedHashMap<>();

        void addReason(ComplianceReason reason) {
            reasons.put(reason, reason);
        }

        boolean hasReasonOfSeverity(ComplianceReason.Severity severity) {
            for (ComplianceReason r : reasons.keySet()) {
                if (r.severity() == severity) {
                    return true;
                }
            }
            return false;
        }

        List<String> allReasonCodes() {
            List<String> codes = new ArrayList<>(reasons.size());
            for (ComplianceReason r : reasons.keySet()) {
                codes.add(r.code());
            }
            return codes;
        }

        List<String> blockingReasonCodes() {
            List<String> codes = new ArrayList<>();
            for (ComplianceReason r : reasons.keySet()) {
                if (r.severity() == ComplianceReason.Severity.UNAUTHORIZED
                        || r.severity() == ComplianceReason.Severity.NON_COMPLIANT) {
                    codes.add(r.code());
                }
            }
            return codes;
        }

        List<String> warningReasonCodes() {
            List<String> codes = new ArrayList<>();
            for (ComplianceReason r : reasons.keySet()) {
                if (r.severity() == ComplianceReason.Severity.WARN
                        || r.severity() == ComplianceReason.Severity.UNKNOWN) {
                    codes.add(r.code());
                }
            }
            return codes;
        }
    }

    /**
     * Compact projection of a REQUIRED policy match for the evidence
     * block. Only carries what an admin / UI needs to confirm the
     * decision (catalog item id, installed display name + version,
     * optional install-audit reference when the inventory was missing
     * the package but a SATISFIED audit was selected).
     */
    record MatchedRequired(
            UUID catalogItemId,
            String catalogItemKey,
            String catalogDisplayName,
            String policyExpectedVersion,
            String installedDisplayName,
            String installedVersion,
            String outcome,
            String dataSource,
            UUID installAuditId,
            Instant installAuditReportedAt,
            Long installAuditRowVersion) {

        static MatchedRequired installed(EndpointSoftwareCatalogItem c, EndpointSoftwareInventoryItem i) {
            return new MatchedRequired(c.getId(), c.getCatalogItemId(), c.getDisplayName(),
                    c.getVersionPolicyValue(), i.getDisplayName(), i.getDisplayVersion(),
                    "INSTALLED", "INVENTORY", null, null, null);
        }

        static MatchedRequired outdated(EndpointSoftwareCatalogItem c, EndpointSoftwareInventoryItem i) {
            return new MatchedRequired(c.getId(), c.getCatalogItemId(), c.getDisplayName(),
                    c.getVersionPolicyValue(), i.getDisplayName(), i.getDisplayVersion(),
                    "OUTDATED", "INVENTORY", null, null, null);
        }

        static MatchedRequired missing(EndpointSoftwareCatalogItem c) {
            return new MatchedRequired(c.getId(), c.getCatalogItemId(), c.getDisplayName(),
                    c.getVersionPolicyValue(), null, null,
                    "MISSING", "INVENTORY", null, null, null);
        }

        static MatchedRequired installedByAudit(EndpointSoftwareCatalogItem c, EndpointInstallAudit a) {
            return new MatchedRequired(c.getId(), c.getCatalogItemId(), c.getDisplayName(),
                    c.getVersionPolicyValue(), c.getDisplayName(), a.getDetectedVersion(),
                    "INSTALLED", "INSTALL_AUDIT",
                    a.getId(), a.getReportedAt(), a.getRowVersion());
        }

        static MatchedRequired outdatedByAudit(EndpointSoftwareCatalogItem c, EndpointInstallAudit a) {
            return new MatchedRequired(c.getId(), c.getCatalogItemId(), c.getDisplayName(),
                    c.getVersionPolicyValue(), c.getDisplayName(), a.getDetectedVersion(),
                    "OUTDATED", "INSTALL_AUDIT",
                    a.getId(), a.getReportedAt(), a.getRowVersion());
        }

        static MatchedRequired missingByContradiction(EndpointSoftwareCatalogItem c, EndpointInstallAudit a) {
            return new MatchedRequired(c.getId(), c.getCatalogItemId(), c.getDisplayName(),
                    c.getVersionPolicyValue(), null, null,
                    "MISSING_CONTRADICTED", "INVENTORY",
                    a.getId(), a.getReportedAt(), a.getRowVersion());
        }

        Map<String, Object> toEvidenceMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("catalogItemId", catalogItemId == null ? null : catalogItemId.toString());
            m.put("catalogItemKey", catalogItemKey);
            m.put("catalogDisplayName", catalogDisplayName);
            m.put("policyExpectedVersion", policyExpectedVersion);
            m.put("installedDisplayName", installedDisplayName);
            m.put("installedVersion", installedVersion);
            m.put("outcome", outcome);
            m.put("dataSource", dataSource);
            if (installAuditId != null) {
                m.put("installAuditId", installAuditId.toString());
                m.put("installAuditReportedAt",
                        installAuditReportedAt == null ? null : installAuditReportedAt.toString());
                m.put("installAuditRowVersion", installAuditRowVersion);
            }
            return m;
        }
    }

    record MatchedForbidden(
            UUID catalogItemId,
            String catalogItemKey,
            String catalogDisplayName,
            String installedDisplayName,
            String installedVersion) {

        static MatchedForbidden of(EndpointSoftwareCatalogItem c, EndpointSoftwareInventoryItem i) {
            return new MatchedForbidden(c.getId(), c.getCatalogItemId(), c.getDisplayName(),
                    i.getDisplayName(), i.getDisplayVersion());
        }

        Map<String, Object> toEvidenceMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("catalogItemId", catalogItemId == null ? null : catalogItemId.toString());
            m.put("catalogItemKey", catalogItemKey);
            m.put("catalogDisplayName", catalogDisplayName);
            m.put("installedDisplayName", installedDisplayName);
            m.put("installedVersion", installedVersion);
            return m;
        }
    }

    /**
     * BE-025 — compact projection of one prohibited-software denylist match
     * for the evidence block. Carries the rule that fired (id + match
     * type/mode) and the redacted matched inventory fields (name / publisher
     * / version). Deliberately does NOT carry the rule's {@code notes} /
     * {@code createdBySubject} or any raw path / registry key. The evidence
     * key names ({@code ruleId} / {@code matchType} / {@code matchMode} /
     * {@code matchedName} / {@code matchedPublisher} / {@code matchedVersion})
     * are the wire contract the BE-025 read service parses back.
     */
    record MatchedProhibited(
            UUID ruleId,
            String matchType,
            String matchMode,
            String matchedName,
            String matchedPublisher,
            String matchedVersion) {

        static final String KEY_RULE_ID = "ruleId";
        static final String KEY_MATCH_TYPE = "matchType";
        static final String KEY_MATCH_MODE = "matchMode";
        static final String KEY_MATCHED_NAME = "matchedName";
        static final String KEY_MATCHED_PUBLISHER = "matchedPublisher";
        static final String KEY_MATCHED_VERSION = "matchedVersion";

        static MatchedProhibited of(
                EndpointProhibitedSoftwareRule rule, EndpointSoftwareInventoryItem item) {
            return new MatchedProhibited(
                    rule.getId(),
                    rule.getMatchType() == null ? null : rule.getMatchType().name(),
                    rule.getMatchMode() == null ? null : rule.getMatchMode().name(),
                    item.getDisplayName(),
                    item.getPublisher(),
                    item.getDisplayVersion());
        }

        Map<String, Object> toEvidenceMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(KEY_RULE_ID, ruleId == null ? null : ruleId.toString());
            m.put(KEY_MATCH_TYPE, matchType);
            m.put(KEY_MATCH_MODE, matchMode);
            m.put(KEY_MATCHED_NAME, matchedName);
            m.put(KEY_MATCHED_PUBLISHER, matchedPublisher);
            m.put(KEY_MATCHED_VERSION, matchedVersion);
            return m;
        }
    }

}
