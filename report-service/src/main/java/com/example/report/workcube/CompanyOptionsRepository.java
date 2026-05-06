package com.example.report.workcube;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only catalog of Workcube tenants (companies) for the muavin /
 * dynamic-report CompanyPicker dropdown.
 *
 * <p><b>Data source</b>: {@code [workcube_mikrolink].[OUR_COMPANY]} —
 * a single canonical table that lists every tenant. The original PR #68
 * design assumed the catalog had to be reconstructed by UNION-ing each
 * per-tenant {@code workcube_mikrolink_<id>} schema, but the live
 * schema (verified against the canonical snapshot in
 * {@code docs/migration/workcube-schema.json}) has only one OUR_COMPANY
 * table — in the {@code workcube_mikrolink} master schema. The
 * per-tenant schemas hold parametric/transactional data, not the tenant
 * registry. The PR #68 query failed with
 * {@code Invalid object name 'workcube_mikrolink_1.OUR_COMPANY'} for
 * exactly this reason.
 *
 * <p><b>Columns</b> (from the live schema; differ slightly from what
 * PR #68 guessed):
 * <ul>
 *   <li>{@code COMP_ID} — primary key (numeric).</li>
 *   <li>{@code NICK_NAME} — short code (the underscore matters; the
 *       PR #68 code used {@code NICKNAME} which doesn't exist).</li>
 *   <li>{@code COMPANY_NAME} — legal name.</li>
 * </ul>
 *
 * <p><b>Caching</b> (Codex 019dfb15 iter-2 absorb #1, preserved):
 * {@link Cacheable} on {@link #findAll()} so Spring's proxy actually
 * intercepts the call (proxy-based caching skips self-invocation).
 * Cache name {@code companyOptions} is registered in
 * {@code CacheConfig} with a 5-minute Caffeine TTL.
 *
 * <p><b>Degraded mode</b> (Codex 019dfb15 iter-2 absorb #3, preserved):
 * {@link org.springframework.dao.DataAccessResourceFailureException}
 * propagates to the controller (surfaced as 503), so an MSSQL outage
 * does not poison the cache for the full TTL.
 *
 * <p><b>JdbcTemplate</b> (Codex 019dfb15 iter-2 absorb #4, preserved):
 * we inject {@code workcubeMssqlPlainJdbc}, which has the 30-second
 * query timeout and the 10000-row guard from
 * {@link com.example.report.config.WorkcubeMssqlConfig}.
 *
 * <p>Activation: same conditional as the rest of the workcube package —
 * feature flag {@code report.mssql.enabled=true} → {@code
 * workcubeMssqlDataSource} bean → this repository auto-registers.
 */
@Repository
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class CompanyOptionsRepository {

    private static final Logger log = LoggerFactory.getLogger(CompanyOptionsRepository.class);

    /**
     * Single read-only query against the master tenant registry. Read-only
     * by Hikari pool config; no parameters; no risk of injection because
     * the entire SQL string is a constant.
     */
    private static final String SELECT_ALL_COMPANIES =
            "SELECT COMP_ID AS id, NICK_NAME AS nickname, COMPANY_NAME AS name "
                    + "FROM [workcube_mikrolink].[OUR_COMPANY] "
                    + "ORDER BY COMP_ID";

    private final JdbcTemplate jdbc;

    public CompanyOptionsRepository(
            @Qualifier("workcubeMssqlPlainJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns every Workcube tenant the cluster knows about. Filtering by
     * caller authorization (super-admin vs. company-scoped user) is the
     * service layer's responsibility — this method intentionally returns
     * the full catalog so the cache stays per-cluster and cheap.
     *
     * <p>Cache: {@code companyOptions} (5 min TTL via {@code CacheConfig}).
     * Cache misses propagate
     * {@link org.springframework.dao.DataAccessResourceFailureException}
     * upward so the controller can surface 503 — see class javadoc on
     * degraded mode.
     */
    @Cacheable(cacheNames = "companyOptions", sync = true)
    public List<CompanyOption> findAll() {
        // ADR-0005 degraded mode: do NOT catch DataAccessResourceFailureException
        // here. Letting it bubble up means (a) the controller surfaces 503 like
        // every other Workcube endpoint and (b) the @Cacheable proxy refuses to
        // cache the failure, so the next request retries instead of being stuck
        // on stale empty data for the full TTL.
        List<CompanyOption> rows = jdbc.query(SELECT_ALL_COMPANIES,
                (rs, rowNum) -> new CompanyOption(
                        rs.getInt("id"),
                        rs.getString("nickname"),
                        rs.getString("name")));
        log.debug("CompanyOptionsRepository: loaded {} tenants from OUR_COMPANY", rows.size());
        return rows;
    }

    /** Plain DTO — id (numeric), nickname (short code), name (legal). */
    public record CompanyOption(int id, String nickname, String name) {}
}
