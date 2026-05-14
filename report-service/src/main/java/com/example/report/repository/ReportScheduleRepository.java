package com.example.report.repository;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

@Repository
public class ReportScheduleRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ReportScheduleRepository(@Qualifier("pgJdbc") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Map<String, Object>> findByReportKey(String reportKey) {
        List<Map<String, Object>> results = jdbc.query(
                "SELECT * FROM report_schedules WHERE report_key = :reportKey ORDER BY created_at DESC LIMIT 1",
                new MapSqlParameterSource("reportKey", reportKey),
                this::mapRow
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Map<String, Object> save(Map<String, Object> schedule) {
        String sql = """
                INSERT INTO report_schedules (report_key, cron, timezone, recipients, format, enabled, created_by)
                VALUES (:reportKey, :cron, :timezone, :recipients::text[], :format, :enabled, :createdBy)
                RETURNING *
                """;
        MapSqlParameterSource params = buildParams(schedule);
        List<Map<String, Object>> results = jdbc.query(sql, params, this::mapRow);
        return results.isEmpty() ? schedule : results.get(0);
    }

    public boolean update(String scheduleId, Map<String, Object> schedule) {
        String sql = """
                UPDATE report_schedules SET
                    cron = COALESCE(:cron, cron),
                    timezone = COALESCE(:timezone, timezone),
                    recipients = COALESCE(:recipients::text[], recipients),
                    format = COALESCE(:format, format),
                    enabled = COALESCE(:enabled, enabled)
                WHERE id = :id::uuid
                """;
        MapSqlParameterSource params = buildParams(schedule);
        params.addValue("id", scheduleId);
        return jdbc.update(sql, params) > 0;
    }

    public boolean delete(String scheduleId) {
        return jdbc.update(
                "DELETE FROM report_schedules WHERE id = :id::uuid",
                new MapSqlParameterSource("id", scheduleId)
        ) > 0;
    }

    /**
     * Lookup the {@code report_key} owning a given schedule (used by
     * {@code ScheduleController} authorization helpers to resolve the per-report
     * scope check when the URL has only the {@code scheduleId}).
     */
    public Optional<String> findReportKeyById(String scheduleId) {
        try {
            String result = jdbc.queryForObject(
                    "SELECT report_key FROM report_schedules WHERE id = :id::uuid",
                    new MapSqlParameterSource("id", scheduleId),
                    String.class
            );
            return Optional.ofNullable(result);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private MapSqlParameterSource buildParams(Map<String, Object> schedule) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("reportKey", schedule.get("reportKey"));
        params.addValue("cron", schedule.get("cron"));
        params.addValue("timezone", schedule.getOrDefault("timezone", "Europe/Istanbul"));
        params.addValue("format", schedule.getOrDefault("format", "excel"));
        params.addValue("enabled", schedule.getOrDefault("enabled", true));
        params.addValue("createdBy", schedule.getOrDefault("createdBy", "system"));

        Object recipients = schedule.get("recipients");
        if (recipients instanceof List<?> list) {
            params.addValue("recipients", "{" + String.join(",", list.stream().map(Object::toString).toList()) + "}");
        } else {
            params.addValue("recipients", "{}");
        }
        return params;
    }

    private Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("reportKey", rs.getString("report_key"));
        row.put("cron", rs.getString("cron"));
        row.put("timezone", rs.getString("timezone"));
        row.put("format", rs.getString("format"));
        row.put("enabled", rs.getBoolean("enabled"));
        row.put("createdBy", rs.getString("created_by"));
        row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));

        Array recipientsArr = rs.getArray("recipients");
        row.put("recipients", recipientsArr != null ? Arrays.asList((String[]) recipientsArr.getArray()) : List.of());
        return row;
    }
}
