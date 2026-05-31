package com.example.commonexport;

import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * Generic, service-agnostic export query: a named-parameter SQL string
 * plus its bound parameters. This is the decoupling seam that lets the
 * streaming exporters live in {@code common-export} without depending on
 * any single service's query builder.
 *
 * <p>report-service adapts its {@code SqlBuilder.BuiltQuery} into this via
 * {@code new ExportQuery(builtQuery.sql(), builtQuery.params())};
 * endpoint-admin-service builds one directly from its
 * {@code DeviceGridQueryBuilder}. Both feed the SAME exporters, so the
 * CSV byte output and Excel worksheet content are identical by
 * construction (board #1154, Codex thread 019e7e35).
 *
 * <p>{@code params} defaults to {@link EmptySqlParameterSource#INSTANCE}
 * when {@code null} so a caller with no bind variables never trips a NPE
 * inside {@code NamedParameterJdbcTemplate.query(...)}. {@code sql} is
 * required and must be non-blank — an export with no query is a
 * programming error, surfaced eagerly rather than as an empty file.
 */
public record ExportQuery(String sql, SqlParameterSource params) {

    public ExportQuery {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("ExportQuery.sql must be non-blank");
        }
        if (params == null) {
            params = EmptySqlParameterSource.INSTANCE;
        }
    }

    /** Convenience factory for a parameter-less query. */
    public static ExportQuery of(String sql) {
        return new ExportQuery(sql, EmptySqlParameterSource.INSTANCE);
    }
}
