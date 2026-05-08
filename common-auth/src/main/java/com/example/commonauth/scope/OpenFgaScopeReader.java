package com.example.commonauth.scope;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Codex thread 019e0891 iter-2 AGREE absorb (PR-BE-10 Phase 3):
 * reusable OpenFGA scope-fetch helper extracted from
 * {@link ScopeContextFilter#fetchScopeFromOpenFga(String)} so
 * permission-service controllers (which do NOT register
 * ScopeContextFilter in their request path) can read the same
 * authoritative scope view without re-implementing the parallel
 * fetch + cache + relation map. Used by:
 *
 * <ul>
 *   <li>{@link ScopeContextFilter} — request-scope binding (existing
 *       caller, now delegates here)</li>
 *   <li>{@code UserScopeService.listUserScopes(userId)} — admin
 *       read-path migration (Phase 4)</li>
 *   <li>{@code AuthorizationQueryService.getUserScopeSummary(userId)} —
 *       /authz/me read-path migration (Phase 4)</li>
 * </ul>
 *
 * <p>Source-of-truth correctness: with PR-BE-9 (Phase 1+2) aligning
 * WRITE relations on {@code viewer} for all scope object types, this
 * reader's READ relations match exactly what
 * {@link com.example.permission.service.TupleSyncService#syncScopeTuples(
 * String, java.util.List, java.util.List, java.util.List, java.util.List, boolean)}
 * writes — closing the persistence-store split that surfaced as the
 * "şirket yetkileri kayıt olmuyor" production bug.
 *
 * <p>Cache reuse: when {@link ScopeContextCache} and
 * {@link AuthzVersionProvider} are wired, this reader uses the same
 * Caffeine cache as {@link ScopeContextFilter} (key:
 * {@code userId:v{authzVersion}:s{storeId}:m{modelId}}), so admin reads
 * for user X benefit from a recent request-scope cache hit for the same
 * user X. Cache is invalidated on tuple writes via
 * {@link ScopeContextCache#evictUser(String)}.
 *
 * <p>Failure semantics — two contracts on the same fetch path:
 * <ul>
 *   <li>{@link #readScopeContext} (strict): propagates the underlying
 *       OpenFGA exception. Used by {@link ScopeContextFilter} which
 *       wraps the throw and substitutes its legacy "production OpenFGA
 *       fail → dev scope" fallback (preserves pre-PR-BE-10 request-
 *       binding behavior).</li>
 *   <li>{@link #readScopeSummarySafe} (admin): catches the exception
 *       and returns an empty map. Used by {@code UserScopeService}
 *       and {@code AuthorizationQueryService} where the UI prefers a
 *       deterministic "no scopes" view over a 5xx; the empty list has
 *       the same shape as a legitimately scope-less user.</li>
 * </ul>
 */
public class OpenFgaScopeReader {

    private static final Logger log = LoggerFactory.getLogger(OpenFgaScopeReader.class);

    private final OpenFgaAuthzService authzService;
    private final OpenFgaProperties properties;
    @Nullable private final ScopeContextCache scopeContextCache;
    @Nullable private final AuthzVersionProvider versionProvider;

    public OpenFgaScopeReader(OpenFgaAuthzService authzService, OpenFgaProperties properties) {
        this(authzService, properties, null, null);
    }

    public OpenFgaScopeReader(OpenFgaAuthzService authzService,
                              OpenFgaProperties properties,
                              @Nullable ScopeContextCache scopeContextCache,
                              @Nullable AuthzVersionProvider versionProvider) {
        this.authzService = authzService;
        this.properties = properties;
        this.scopeContextCache = scopeContextCache;
        this.versionProvider = versionProvider;
    }

    /**
     * Reads a complete {@link ScopeContext} for the given user from
     * OpenFGA. Throws on OpenFGA outage so callers can choose how to
     * degrade: {@link ScopeContextFilter} maps the throw to its
     * "production OpenFGA fail → dev scope" legacy fallback;
     * admin-side callers (UserScopeService, AuthorizationQueryService)
     * use {@link #readScopeSummarySafe} which catches and returns empty.
     *
     * <p>Cache integration: if both {@link ScopeContextCache} and
     * {@link AuthzVersionProvider} are wired, reads consult the
     * Caffeine cache first (key includes userId + authz version + store
     * + model). Cache miss triggers parallel fetch + put.
     *
     * @param userId numeric DB user id (string form, e.g. "1204")
     * @return ScopeContext with allowed scope IDs and superAdmin flag
     * @throws RuntimeException on OpenFGA outage / fetch failure
     */
    public ScopeContext readScopeContext(String userId) {
        if (userId == null || userId.isBlank()) {
            return ScopeContext.empty(userId);
        }
        if (!properties.isEnabled()) {
            // openfga.enabled=false → caller should not call this reader.
            // Return empty rather than throw so existing callers get a
            // safe default if they're misconfigured.
            log.debug("OpenFgaScopeReader called but properties.enabled=false; returning empty scope");
            return ScopeContext.empty(userId);
        }

        ScopeContextCache cache = scopeContextCache;
        AuthzVersionProvider vp = versionProvider;
        if (cache != null && cache.isEnabled() && vp != null) {
            long version = vp.getCurrentVersion();
            String cacheKey = ScopeContextCache.cacheKey(
                    userId, version, properties.getStoreId(), properties.getModelId());
            ScopeContext cached = cache.get(cacheKey);
            if (cached != null) {
                log.debug("OpenFgaScopeReader cache HIT for user:{}", userId);
                return cached;
            }
            ScopeContext fresh = fetchFromOpenFga(userId);
            cache.put(cacheKey, fresh);
            return fresh;
        }

        return fetchFromOpenFga(userId);
    }

    /**
     * Safe variant for admin-side callers: returns empty scope summary
     * on OpenFGA outage rather than propagating the exception. Use this
     * from {@code UserScopeService.listUserScopes} and
     * {@code AuthorizationQueryService.getUserScopeSummary} where the
     * UI prefers a deterministic "no scopes" view to a 5xx.
     */
    public Map<String, Set<Long>> readScopeSummarySafe(String userId) {
        try {
            return readScopeSummary(userId);
        } catch (Exception e) {
            log.warn("OpenFgaScopeReader summary fetch failed for user:{}; returning empty. cause={}",
                    userId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Reads a permission-free scope summary keyed by scope type
     * ({@code COMPANY}, {@code PROJECT}, {@code WAREHOUSE},
     * {@code BRANCH}). Used by {@code AuthorizationQueryService} for
     * the {@code /authz/me} response and by admin endpoints that need
     * per-type lists without the superAdmin flag.
     *
     * <p>SuperAdmin-as-scope semantics: when the user is the
     * organization admin, this method returns an EMPTY map (not a
     * "wildcard" set), matching the legacy DB-backed behavior of
     * {@code AuthorizationQueryService.getUserScopeSummary} where
     * superAdmin had no per-resource scope rows. Callers that need to
     * detect superAdmin should use {@link #readScopeContext} which
     * carries the boolean flag.
     */
    public Map<String, Set<Long>> readScopeSummary(String userId) {
        ScopeContext ctx = readScopeContext(userId);
        if (ctx.superAdmin()) {
            return Collections.emptyMap();
        }
        Map<String, Set<Long>> result = new LinkedHashMap<>();
        if (!ctx.allowedCompanyIds().isEmpty()) {
            result.put("COMPANY", new LinkedHashSet<>(ctx.allowedCompanyIds()));
        }
        if (!ctx.allowedProjectIds().isEmpty()) {
            result.put("PROJECT", new LinkedHashSet<>(ctx.allowedProjectIds()));
        }
        if (!ctx.allowedWarehouseIds().isEmpty()) {
            result.put("WAREHOUSE", new LinkedHashSet<>(ctx.allowedWarehouseIds()));
        }
        if (!ctx.allowedBranchIds().isEmpty()) {
            result.put("BRANCH", new LinkedHashSet<>(ctx.allowedBranchIds()));
        }
        return result;
    }

    /**
     * Parallel OpenFGA fetch — 4 listObjectIds + 1 admin check, joined
     * via CompletableFuture.allOf so latency = max(individual call)
     * rather than sum.
     *
     * <p>Relation map (PR-BE-9 aligned, all object types use
     * {@code viewer}; superAdmin uses {@code admin/organization/default}):
     * <ul>
     *   <li>company → viewer</li>
     *   <li>project → viewer</li>
     *   <li>warehouse → viewer</li>
     *   <li>branch → viewer</li>
     * </ul>
     */
    private ScopeContext fetchFromOpenFga(String userId) {
        var companyFuture = CompletableFuture.supplyAsync(
                () -> authzService.listObjectIds(userId, "viewer", "company"));
        var projectFuture = CompletableFuture.supplyAsync(
                () -> authzService.listObjectIds(userId, "viewer", "project"));
        var warehouseFuture = CompletableFuture.supplyAsync(
                () -> authzService.listObjectIds(userId, "viewer", "warehouse"));
        var branchFuture = CompletableFuture.supplyAsync(
                () -> authzService.listObjectIds(userId, "viewer", "branch"));
        var adminFuture = CompletableFuture.supplyAsync(
                () -> authzService.check(userId, "admin", "organization", "default"));

        CompletableFuture.allOf(companyFuture, projectFuture, warehouseFuture, branchFuture, adminFuture).join();

        boolean isSuperAdmin = adminFuture.join();
        if (isSuperAdmin) {
            return ScopeContext.superAdmin(userId);
        }
        return new ScopeContext(userId,
                companyFuture.join(),
                projectFuture.join(),
                warehouseFuture.join(),
                branchFuture.join(),
                false);
    }
}
