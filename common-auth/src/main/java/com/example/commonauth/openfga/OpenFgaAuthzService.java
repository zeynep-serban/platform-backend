package com.example.commonauth.openfga;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientCheckRequest;
import dev.openfga.sdk.api.client.model.ClientExpandRequest;
import dev.openfga.sdk.api.client.model.ClientListObjectsRequest;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckItem;
import dev.openfga.sdk.api.client.model.ClientBatchCheckRequest;
import dev.openfga.sdk.api.client.model.ClientBatchCheckResponse;
import dev.openfga.sdk.api.client.model.ClientBatchCheckSingleResponse;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wrapper around OpenFGA Java SDK.
 * When disabled (dev/permitAll mode), all checks return true and
 * listObjects returns the configured dev scope IDs.
 */
public class OpenFgaAuthzService {

    private static final Logger log = LoggerFactory.getLogger(OpenFgaAuthzService.class);

    private final OpenFgaClient client;
    private final OpenFgaProperties properties;
    private final boolean enabled;

    // SK-2: Check result cache — reduces OpenFGA API calls for repeated checks
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> checkCache;

    // C1: Circuit breaker — prevents cascade when OpenFGA is down
    private final OpenFgaCircuitBreaker circuitBreaker;

    // B3 (Rev 19): Authz decision counters — deny rate metric
    private final Counter allowCounter;
    private final Counter denyCounter;

    public OpenFgaAuthzService(OpenFgaClient client, OpenFgaProperties properties) {
        this(client, properties, null);
    }

    public OpenFgaAuthzService(OpenFgaClient client, OpenFgaProperties properties,
                               MeterRegistry meterRegistry) {
        this.client = client;
        this.properties = properties;
        this.enabled = properties.isEnabled() && client != null;
        int cacheTtlSeconds = properties.getCheckCacheTtlSeconds() > 0
                ? properties.getCheckCacheTtlSeconds() : 10;
        this.checkCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .expireAfterWrite(java.time.Duration.ofSeconds(cacheTtlSeconds))
                .maximumSize(1000)
                .build();
        this.circuitBreaker = new OpenFgaCircuitBreaker(); // 5 failures -> 30s open

        // B3 (Rev 19): Decision counters for deny rate metric
        if (meterRegistry != null) {
            this.allowCounter = Counter.builder("authz_decisions_total")
                    .tag("allowed", "true")
                    .description("Total authz decisions (allowed)")
                    .register(meterRegistry);
            this.denyCounter = Counter.builder("authz_decisions_total")
                    .tag("allowed", "false")
                    .description("Total authz decisions (denied)")
                    .register(meterRegistry);
            // B4 (Rev 19): Circuit breaker state gauge
            io.micrometer.core.instrument.Gauge.builder("openfga_circuit_breaker_state",
                    circuitBreaker, cb -> cb.getState().ordinal())
                    .description("OpenFGA circuit breaker state: 0=CLOSED, 1=OPEN, 2=HALF_OPEN")
                    .register(meterRegistry);
        } else {
            this.allowCounter = null;
            this.denyCounter = null;
        }

        if (!enabled) {
            log.warn("OpenFGA is DISABLED — all checks return true, scopes from dev config");
        }
    }

    /** Expose circuit breaker state for health/monitoring endpoints. */
    public OpenFgaCircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Check if a user has a relation on an object.
     * Example: check("1", "viewer", "company", "5")
     */
    public boolean check(String userId, String relation, String objectType, String objectId) {
        if (!enabled) {
            return true;
        }
        // SK-2: Check cache — 10s TTL, avoids repeated OpenFGA calls for same check
        String cacheKey = userId + ":" + relation + ":" + objectType + ":" + objectId;
        Boolean cached = checkCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("OpenFGA check (cached): user:{} {} {}:{} → {}", userId, relation, objectType, objectId, cached);
            return cached;
        }
        // C1: Circuit breaker guard — short-circuit when OpenFGA is down
        if (!circuitBreaker.allowRequest()) {
            if (denyCounter != null) denyCounter.increment();
            log.warn("OpenFGA circuit OPEN — denying access: user:{} {} {}:{}",
                    userId, relation, objectType, objectId);
            return false;
        }
        try {
            var request = new ClientCheckRequest()
                    .user("user:" + userId)
                    .relation(relation)
                    ._object(objectType + ":" + objectId);

            var response = client.check(request).get();
            boolean allowed = Boolean.TRUE.equals(response.getAllowed());
            checkCache.put(cacheKey, allowed);
            circuitBreaker.recordSuccess();

            // B3 (Rev 19): Increment decision counter
            if (allowed && allowCounter != null) allowCounter.increment();
            if (!allowed && denyCounter != null) denyCounter.increment();

            log.debug("OpenFGA check: user:{} {} {}:{} -> {}",
                    userId, relation, objectType, objectId, allowed);
            return allowed;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            if (denyCounter != null) denyCounter.increment();
            log.error("OpenFGA check failed, denying access: user:{} {} {}:{}",
                    userId, relation, objectType, objectId, e);
            return false;
        }
    }

    /**
     * Check authorization with raw principal (Faz 23.1 PR5 — Codex 019dfaaa
     * lock-in #1 absorb).
     *
     * <p>Generic counterpart to {@link #check(String, String, String, String)}
     * which always prefixes user with {@code "user:"}. This method accepts
     * arbitrary principal type (e.g., {@code "subscriber:1204"}, {@code "external:hash"}).
     *
     * <p>Used by notification-orchestrator authz integration where the
     * principal is NOT the JWT-resolved user but the notification recipient
     * (subscriber id or external email hash).
     *
     * <p>Example:
     * <pre>
     *   checkPrincipal("subscriber:1204", "can_receive", "template", "auth-password-reset")
     * </pre>
     *
     * @param principalRef full OpenFGA user-ref including type prefix
     *                     (e.g., {@code "subscriber:1204"}, {@code "external:abc123"})
     * @param relation     OpenFGA relation (e.g., {@code "can_receive"})
     * @param objectType   OpenFGA object type
     * @param objectId     OpenFGA object id
     * @return {@code true} if tuple exists / relation holds; {@code false}
     *         on deny / circuit-open / exception (fail-closed)
     */
    public boolean checkPrincipal(String principalRef, String relation,
                                   String objectType, String objectId) {
        if (!enabled) {
            return true;
        }
        String cacheKey = principalRef + ":" + relation + ":" + objectType + ":" + objectId;
        Boolean cached = checkCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("OpenFGA checkPrincipal (cached): {} {} {}:{} → {}",
                principalRef, relation, objectType, objectId, cached);
            return cached;
        }
        if (!circuitBreaker.allowRequest()) {
            if (denyCounter != null) denyCounter.increment();
            log.warn("OpenFGA circuit OPEN — denying: {} {} {}:{}",
                principalRef, relation, objectType, objectId);
            return false;
        }
        try {
            var request = new ClientCheckRequest()
                    .user(principalRef)
                    .relation(relation)
                    ._object(objectType + ":" + objectId);
            var response = client.check(request).get();
            boolean allowed = Boolean.TRUE.equals(response.getAllowed());
            checkCache.put(cacheKey, allowed);
            circuitBreaker.recordSuccess();
            if (allowed && allowCounter != null) allowCounter.increment();
            if (!allowed && denyCounter != null) denyCounter.increment();
            log.debug("OpenFGA checkPrincipal: {} {} {}:{} -> {}",
                principalRef, relation, objectType, objectId, allowed);
            return allowed;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            if (denyCounter != null) denyCounter.increment();
            log.error("OpenFGA checkPrincipal failed, denying: {} {} {}:{}",
                principalRef, relation, objectType, objectId, e);
            return false;
        }
    }

    /**
     * List all objects of a type that a user has a relation on.
     * Example: listObjects("1", "viewer", "company") → ["1", "5"]
     * Returns object IDs (without type prefix).
     */
    public List<String> listObjects(String userId, String relation, String objectType) {
        if (!enabled) {
            return devFallbackIds(objectType);
        }
        if (!circuitBreaker.allowRequest()) {
            log.warn("OpenFGA circuit OPEN — returning empty for listObjects: user:{} {} {}",
                    userId, relation, objectType);
            return Collections.emptyList();
        }
        try {
            var request = new ClientListObjectsRequest()
                    .user("user:" + userId)
                    .relation(relation)
                    .type(objectType);

            var response = client.listObjects(request).get();
            List<String> objects = response.getObjects();
            if (objects == null) {
                circuitBreaker.recordSuccess();
                return Collections.emptyList();
            }

            String prefix = objectType + ":";
            List<String> ids = objects.stream()
                    .map(o -> o.startsWith(prefix) ? o.substring(prefix.length()) : o)
                    .collect(Collectors.toList());

            circuitBreaker.recordSuccess();
            log.debug("OpenFGA listObjects: user:{} {} {} → {}", userId, relation, objectType, ids);
            return ids;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.error("OpenFGA listObjects failed: user:{} {} {}", userId, relation, objectType, e);
            return Collections.emptyList();
        }
    }

    /**
     * List allowed object IDs as Long set (convenience for scope filtering).
     */
    public Set<Long> listObjectIds(String userId, String relation, String objectType) {
        if (!enabled) {
            return devFallbackLongIds(objectType);
        }
        return listObjects(userId, relation, objectType).stream()
                .map(id -> {
                    try {
                        return Long.parseLong(id);
                    } catch (NumberFormatException e) {
                        log.warn("Non-numeric object ID skipped: {}", id);
                        return null;
                    }
                })
                .filter(id -> id != null)
                .collect(Collectors.toSet());
    }

    /**
     * Write a relationship tuple.
     * Example: writeTuple("1", "admin", "company", "5")
     */
    public void writeTuple(String userId, String relation, String objectType, String objectId) {
        if (!enabled) {
            log.info("OpenFGA disabled — skipping writeTuple: user:{} {} {}:{}",
                    userId, relation, objectType, objectId);
            return;
        }
        try {
            var tuple = new ClientTupleKey()
                    .user("user:" + userId)
                    .relation(relation)
                    ._object(objectType + ":" + objectId);

            var request = new ClientWriteRequest().writes(List.of(tuple));
            client.write(request).get();

            evictCheckCache(userId);
            log.info("OpenFGA tuple written: user:{} {} {}:{}", userId, relation, objectType, objectId);
        } catch (Exception e) {
            if (isIdempotentWriteError(e)) {
                // 2026-04-18 OI-03 idempotency (Codex 019da431): OpenFGA SDK throws
                // validation_error when a tuple to write already exists. Under our
                // "refresh = delete-all + write-all" propagation model the tuple
                // likely IS already in the desired state, so swallow instead of
                // failing. Still evict cache to be safe. DEBUG-level to avoid
                // log spam during batch fallback loops (iter-2 REVISE).
                evictCheckCache(userId);
                log.debug("OpenFGA tuple already exists (idempotent write): user:{} {} {}:{}",
                        userId, relation, objectType, objectId);
                return;
            }
            log.error("OpenFGA writeTuple failed: user:{} {} {}:{}",
                    userId, relation, objectType, objectId, e);
            throw new RuntimeException("Failed to write authorization tuple", e);
        }
    }

    /**
     * Delete a relationship tuple.
     */
    public void deleteTuple(String userId, String relation, String objectType, String objectId) {
        if (!enabled) {
            log.info("OpenFGA disabled — skipping deleteTuple: user:{} {} {}:{}",
                    userId, relation, objectType, objectId);
            return;
        }
        try {
            var tuple = new ClientTupleKey()
                    .user("user:" + userId)
                    .relation(relation)
                    ._object(objectType + ":" + objectId);

            var request = new ClientWriteRequest().deletes(List.of(tuple));
            client.write(request).get();

            evictCheckCache(userId);
            log.info("OpenFGA tuple deleted: user:{} {} {}:{}", userId, relation, objectType, objectId);
        } catch (Exception e) {
            if (isIdempotentDeleteError(e)) {
                // 2026-04-18 OI-03 idempotency (Codex 019da431): SDK throws on
                // delete of non-existent tuple; treat as idempotent no-op (caller
                // intended: "ensure this tuple is absent"). DEBUG-level to avoid
                // log spam during batch fallback loops (iter-2 REVISE).
                log.debug("OpenFGA tuple already absent (idempotent delete): user:{} {} {}:{}",
                        userId, relation, objectType, objectId);
                return;
            }
            log.error("OpenFGA deleteTuple failed: user:{} {} {}:{}",
                    userId, relation, objectType, objectId, e);
            throw new RuntimeException("Failed to delete authorization tuple", e);
        }
    }

    /**
     * Batch write multiple tuples in a single API call.
     * Significantly faster than individual writeTuple calls for role propagation.
     */
    public void writeTuples(List<ClientTupleKey> tuples) {
        if (!enabled || tuples == null || tuples.isEmpty()) {
            return;
        }
        try {
            var request = new ClientWriteRequest().writes(tuples);
            client.write(request).get();
            // Evict cache for all affected users
            tuples.stream()
                    .map(ClientTupleKey::getUser)
                    .filter(u -> u != null && u.startsWith("user:"))
                    .map(u -> u.substring(5))
                    .distinct()
                    .forEach(this::evictCheckCache);
            log.info("OpenFGA batch write: {} tuples", tuples.size());
        } catch (Exception e) {
            if (isIdempotentWriteError(e)) {
                // 2026-04-18 OI-03 idempotency (Codex 019da431 iter-2 REVISE):
                // batch failed because at least one tuple already exists. Fall
                // back to per-tuple writes so valid new tuples still get
                // applied; collect non-idempotent failures and rethrow at the
                // end so callers (TupleSyncService.syncFeatureTuplesForUser)
                // correctly skip the version bump on partial failure.
                log.info("OpenFGA batch write hit idempotency constraint ({} tuples) — falling back to per-tuple", tuples.size());
                List<Throwable> nonIdempotentFailures = new java.util.ArrayList<>();
                for (ClientTupleKey t : tuples) {
                    String user = t.getUser();
                    String userId = user != null && user.startsWith("user:") ? user.substring(5) : user;
                    String rel = t.getRelation();
                    String obj = t.getObject();
                    String[] objParts = obj != null ? obj.split(":", 2) : new String[]{"", ""};
                    if (userId == null || rel == null || objParts.length < 2) continue;
                    try {
                        writeTuple(userId, rel, objParts[0], objParts[1]);
                    } catch (Exception per) {
                        nonIdempotentFailures.add(per);
                        log.warn("OpenFGA per-tuple write after batch fallback failed: {} {} {} — {}",
                                userId, rel, obj, per.getMessage());
                    }
                }
                if (!nonIdempotentFailures.isEmpty()) {
                    throw new RuntimeException(
                            "Batch write fallback hit " + nonIdempotentFailures.size()
                                    + " non-idempotent failures",
                            nonIdempotentFailures.get(0));
                }
                return;
            }
            log.error("OpenFGA batch writeTuples failed ({} tuples)", tuples.size(), e);
            throw new RuntimeException("Failed to batch write authorization tuples", e);
        }
    }

    /**
     * Batch delete multiple tuples in a single API call.
     */
    public void deleteTuples(List<ClientTupleKeyWithoutCondition> tuples) {
        if (!enabled || tuples == null || tuples.isEmpty()) {
            return;
        }
        try {
            var request = new ClientWriteRequest().deletes(tuples);
            client.write(request).get();
            // Evict cache for all affected users
            tuples.stream()
                    .map(ClientTupleKeyWithoutCondition::getUser)
                    .filter(u -> u != null && u.startsWith("user:"))
                    .map(u -> u.substring(5))
                    .distinct()
                    .forEach(this::evictCheckCache);
            log.info("OpenFGA batch delete: {} tuples", tuples.size());
        } catch (Exception e) {
            if (isIdempotentDeleteError(e)) {
                // 2026-04-18 OI-03 idempotency (Codex 019da431 iter-2 REVISE):
                // batch failed because at least one tuple does not exist. Fall
                // back to per-tuple deletes so existing tuples still get
                // removed; collect non-idempotent failures and rethrow so
                // callers can skip version bump on partial failure.
                log.info("OpenFGA batch delete hit idempotency constraint ({} tuples) — falling back to per-tuple", tuples.size());
                List<Throwable> nonIdempotentFailures = new java.util.ArrayList<>();
                for (ClientTupleKeyWithoutCondition t : tuples) {
                    String user = t.getUser();
                    String userId = user != null && user.startsWith("user:") ? user.substring(5) : user;
                    String rel = t.getRelation();
                    String obj = t.getObject();
                    String[] objParts = obj != null ? obj.split(":", 2) : new String[]{"", ""};
                    if (userId == null || rel == null || objParts.length < 2) continue;
                    try {
                        deleteTuple(userId, rel, objParts[0], objParts[1]);
                    } catch (Exception per) {
                        nonIdempotentFailures.add(per);
                        log.warn("OpenFGA per-tuple delete after batch fallback failed: {} {} {} — {}",
                                userId, rel, obj, per.getMessage());
                    }
                }
                if (!nonIdempotentFailures.isEmpty()) {
                    throw new RuntimeException(
                            "Batch delete fallback hit " + nonIdempotentFailures.size()
                                    + " non-idempotent failures",
                            nonIdempotentFailures.get(0));
                }
                return;
            }
            log.error("OpenFGA batch deleteTuples failed ({} tuples)", tuples.size(), e);
            throw new RuntimeException("Failed to batch delete authorization tuples", e);
        }
    }

    /**
     * Detects OpenFGA validation_error for writing a tuple that already exists.
     * OpenFGA message: "cannot write a tuple which already exists: user: ... relation: ... object: ...".
     * Used to treat duplicate writes as idempotent no-ops in our
     * "refresh = delete-all + write-all" propagation model.
     */
    static boolean isIdempotentWriteError(Throwable e) {
        return matchesIdempotencyMessage(e, "cannot write a tuple which already exists");
    }

    /**
     * Detects OpenFGA validation_error for deleting a tuple that does not exist.
     * OpenFGA message: "cannot delete a tuple which does not exist: user: ... relation: ... object: ...".
     * Used to treat missing-tuple deletes as idempotent no-ops.
     */
    static boolean isIdempotentDeleteError(Throwable e) {
        return matchesIdempotencyMessage(e, "cannot delete a tuple which does not exist");
    }

    private static boolean matchesIdempotencyMessage(Throwable e, String needle) {
        Throwable t = e;
        int depth = 0;
        while (t != null && depth < 10) {
            String msg = t.getMessage();
            if (msg != null && msg.contains(needle)) {
                return true;
            }
            t = t.getCause();
            depth++;
        }
        return false;
    }

    /**
     * Build a ClientTupleKey for use with batch write operations.
     */
    public static ClientTupleKey writeTupleKey(String userId, String relation, String objectType, String objectId) {
        return new ClientTupleKey()
                .user("user:" + userId)
                .relation(relation)
                ._object(objectType + ":" + objectId);
    }

    /**
     * Build a ClientTupleKeyWithoutCondition for use with batch delete operations.
     */
    public static ClientTupleKeyWithoutCondition deleteTupleKey(String userId, String relation, String objectType, String objectId) {
        return new ClientTupleKeyWithoutCondition()
                .user("user:" + userId)
                .relation(relation)
                ._object(objectType + ":" + objectId);
    }

    /**
     * Expand the relationship tree for an object and relation.
     * Returns the raw tree structure showing how access is derived.
     * Used for "explain why" features.
     */
    public Object expand(String objectType, String objectId, String relation) {
        if (!enabled) {
            return Map.of("allowed", true, "source", "dev-mode-bypass");
        }
        try {
            var request = new ClientExpandRequest()
                    .relation(relation)
                    ._object(objectType + ":" + objectId);

            var response = client.expand(request).get();
            log.debug("OpenFGA expand: {} {}:{} → tree returned", relation, objectType, objectId);
            return response.getTree();
        } catch (Exception e) {
            log.error("OpenFGA expand failed: {} {}:{}", relation, objectType, objectId, e);
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * Explain access: check + expand combined for a user.
     * Returns allowed flag + relationship chain.
     */
    public Map<String, Object> explainAccess(String userId, String relation, String objectType, String objectId) {
        boolean allowed = check(userId, relation, objectType, objectId);
        Object tree = expand(objectType, objectId, relation);
        return Map.of(
                "allowed", allowed,
                "userId", userId,
                "relation", relation,
                "objectType", objectType,
                "objectId", objectId,
                "tree", tree
        );
    }

    /**
     * Check with reason — distinguishes "blocked" from "no_relation".
     * Required for frontend AccessLevel mapping (disabled vs hidden).
     * CNS-20260411-005: Codex MODIFY — reason field mandatory for UI semantics.
     */
    public record CheckResult(boolean allowed, String reason) {}

    /**
     * 2026-04-18 OI-03 Bug 2 (Codex 019da431 Option A): object types that
     * define a `blocked` relation in the OpenFGA model. Probing `blocked`
     * on scope types (company / organization / project / warehouse / branch)
     * triggers HTTP 400 "relation 'X#blocked' not found" because those types
     * don't carry a blocked relation — scope denial semantics is NO_SCOPE,
     * not deny-wins. Scope deny-wins is out of scope for this service.
     */
    private static final Set<String> BLOCKED_SUPPORTED_TYPES = Set.of("module", "action", "report");

    public CheckResult checkWithReason(String userId, String relation, String objectType, String objectId) {
        if (!enabled) {
            return new CheckResult(true, "granted");
        }
        try {
            boolean allowed = check(userId, relation, objectType, objectId);
            String reason;
            if (allowed) {
                reason = "granted";
            } else if (BLOCKED_SUPPORTED_TYPES.contains(objectType)) {
                // Feature-level deny-wins: probe the explicit `blocked` relation
                // defined on module/action/report types.
                boolean isBlocked = check(userId, "blocked", objectType, objectId);
                reason = isBlocked ? "blocked" : "no_relation";
            } else {
                // Scope types (company/organization/project/warehouse/branch)
                // have no `blocked` relation in the model; denied means the
                // user simply lacks any positive scope relation.
                reason = "no_relation";
            }
            // SK-5: Per-decision audit log — every authorization decision recorded
            log.info("authz.decision user={} relation={} object={}:{} allowed={} reason={}",
                    userId, relation, objectType, objectId, allowed, reason);
            return new CheckResult(allowed, reason);
        } catch (Exception e) {
            log.error("OpenFGA checkWithReason failed: user:{} {} {}:{}", userId, relation, objectType, objectId, e);
            log.info("authz.decision user={} relation={} object={}:{} allowed=false reason=error",
                    userId, relation, objectType, objectId);
            return new CheckResult(false, "error");
        }
    }

    /**
     * Batch check with reason — bounded parallelism for UI component-level checks.
     * CNS-20260411-005: Codex REJECT (without batch) — batch endpoint mandatory.
     * Max 20 checks per call enforced at controller level.
     */
    public List<CheckResult> batchCheck(String userId, List<BatchCheckRequest> requests) {
        if (!enabled) {
            return requests.stream()
                    .map(r -> new CheckResult(true, "granted"))
                    .toList();
        }
        try {
            // Use OpenFGA native BatchCheck — single HTTP call for N checks (SK-11)
            List<ClientBatchCheckItem> items = requests.stream()
                    .map(r -> new ClientBatchCheckItem()
                            .user("user:" + userId)
                            .relation(r.relation())
                            ._object(r.objectType() + ":" + r.objectId()))
                    .toList();

            var batchRequest = ClientBatchCheckRequest.ofChecks(items);
            var response = client.batchCheck(batchRequest).get();

            var results = new java.util.ArrayList<CheckResult>();
            var singleResults = response.getResult();
            for (int i = 0; i < singleResults.size(); i++) {
                var single = singleResults.get(i);
                boolean allowed = single.isAllowed();
                String reason = allowed ? "granted" : "no_relation";
                // SK-5: Per-decision audit log — batch path
                var req = i < requests.size() ? requests.get(i) : null;
                if (req != null) {
                    log.info("authz.decision user={} relation={} object={}:{} allowed={} reason={} mode=batch",
                            userId, req.relation(), req.objectType(), req.objectId(), allowed, reason);
                }
                // CNS-20260415-004 (Codex): Native batch path'te authz_decisions_total
                // counter eksikti; synthetic canary NO_SIGNAL yanlis tespit yapiyordu.
                // Her batch sonucu icin allow/deny counter increment — single check
                // path ile ayni sayac semantigi. Fallback path checkWithReason icin
                // kendi counter'ini kullanir; double count riski yok.
                if (allowed) {
                    if (allowCounter != null) allowCounter.increment();
                } else {
                    if (denyCounter != null) denyCounter.increment();
                }
                results.add(new CheckResult(allowed, reason));
            }
            return results;
        } catch (Exception e) {
            log.warn("BatchCheck failed, falling back to parallel individual checks: {}", e.getMessage());
            // Fallback to parallel individual checks
            return requests.parallelStream()
                    .map(r -> checkWithReason(userId, r.relation(), r.objectType(), r.objectId()))
                    .toList();
        }
    }

    public record BatchCheckRequest(String relation, String objectType, String objectId) {}

    /**
     * Evict cached check results for a specific user.
     * Called after tuple write/delete to ensure immediate permission propagation.
     */
    public void evictCheckCache(String userId) {
        if (userId == null) return;
        String prefix = userId + ":";
        checkCache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
        log.debug("OpenFGA check cache evicted for user:{}", userId);
    }

    /**
     * Evict all cached check results.
     * Used when a broad permission change affects multiple users.
     */
    public void evictAllCheckCache() {
        checkCache.invalidateAll();
        log.debug("OpenFGA check cache fully invalidated");
    }

    public boolean isEnabled() {
        return enabled;
    }

    private List<String> devFallbackIds(String objectType) {
        OpenFgaProperties.DevScope dev = properties.getDevScope();
        return switch (objectType) {
            case "company" -> dev.getCompanyIds().stream().map(String::valueOf).collect(Collectors.toList());
            case "project" -> dev.getProjectIds().stream().map(String::valueOf).collect(Collectors.toList());
            case "warehouse" -> dev.getWarehouseIds().stream().map(String::valueOf).collect(Collectors.toList());
            default -> Collections.emptyList();
        };
    }

    private Set<Long> devFallbackLongIds(String objectType) {
        OpenFgaProperties.DevScope dev = properties.getDevScope();
        return switch (objectType) {
            case "company" -> dev.getCompanyIds();
            case "project" -> dev.getProjectIds();
            case "warehouse" -> dev.getWarehouseIds();
            default -> Collections.emptySet();
        };
    }
}
