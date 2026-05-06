package com.example.report.workcube;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import com.example.commonauth.scope.ScopeContext;

/**
 * Authorization-aware wrapper around {@link CompanyOptionsRepository}.
 *
 * <p>Caching: handled inside the repository (Codex 019dfb15 iter-2 absorb #1).
 * The {@code @Cacheable} annotation lives on
 * {@link CompanyOptionsRepository#findAll()} so Spring's proxy actually
 * intercepts the call (proxy-based caching skips self-invocation, so a
 * service-method-calling-its-own-cached-method would silently bypass the
 * cache). This service simply applies the per-request authorization filter
 * on top of the cached catalog.
 *
 * <p>Authorization model:
 * <ul>
 *   <li>Super-admin: returns the full catalog.</li>
 *   <li>Scoped user: filters down to {@code ScopeContext.allowedCompanyIds()}.</li>
 *   <li>Anonymous / no scope context: returns empty list (controller surfaces
 *       this as 401 if it slipped past Spring Security).</li>
 * </ul>
 *
 * <p>Activation: tied to the {@code workcubeMssqlDataSource} bean directly
 * (Codex 019dfb15 iter-2 absorb #2 — chained {@code @ConditionalOnBean} on
 * a sibling component-scanned bean is brittle because condition evaluation
 * order isn't guaranteed; the existing Workcube pattern in the codebase
 * pins each layer directly to the datasource bean instead).
 */
@Service
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class CompanyOptionsService {

    private static final Logger log = LoggerFactory.getLogger(CompanyOptionsService.class);

    private final CompanyOptionsRepository repository;

    public CompanyOptionsService(CompanyOptionsRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the catalog filtered by the caller's scope.
     *
     * <p>The repository call is cached ({@code companyOptions}, 5 min TTL).
     * If MSSQL is unreachable the underlying
     * {@link org.springframework.dao.DataAccessResourceFailureException}
     * propagates so the controller can return 503 (ADR-0005 degraded mode).
     *
     * @param scope authorization context — must not be null in production
     *              (Spring Security guarantees this on /api/* paths via
     *              {@code ScopeContextFilter}).
     */
    public List<CompanyOptionsRepository.CompanyOption> findAuthorized(ScopeContext scope) {
        if (scope == null) {
            log.warn("CompanyOptionsService: ScopeContext null — returning empty");
            return Collections.emptyList();
        }
        List<CompanyOptionsRepository.CompanyOption> all = repository.findAll();
        if (scope.superAdmin()) {
            return all;
        }
        Set<Long> allowedIds = scope.allowedCompanyIds();
        if (allowedIds == null || allowedIds.isEmpty()) {
            return Collections.emptyList();
        }
        return all.stream()
                .filter(opt -> allowedIds.contains((long) opt.id()))
                .toList();
    }
}
