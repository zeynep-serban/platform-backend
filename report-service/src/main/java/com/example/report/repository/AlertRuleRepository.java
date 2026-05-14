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
public class AlertRuleRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AlertRuleRepository(@Qualifier("pgJdbc") NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findByReportKey(String reportKey) {
        return jdbc.query(
                "SELECT * FROM alert_rules WHERE report_key = :reportKey ORDER BY created_at",
                new MapSqlParameterSource("reportKey", reportKey),
                this::mapRow
        );
    }

    public Map<String, Object> save(Map<String, Object> rule) {
        String sql = """
                INSERT INTO alert_rules (report_key, field, condition, threshold, channels, frequency, enabled, created_by)
                VALUES (:reportKey, :field, :condition, :threshold, :channels::text[], :frequency, :enabled, :createdBy)
                RETURNING *
                """;
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("reportKey", rule.get("reportKey"));
        params.addValue("field", rule.get("field"));
        params.addValue("condition", rule.get("condition"));
        params.addValue("threshold", rule.get("threshold"));
        params.addValue("frequency", rule.getOrDefault("frequency", "daily"));
        params.addValue("enabled", rule.getOrDefault("enabled", true));
        params.addValue("createdBy", rule.getOrDefault("createdBy", "system"));

        Object channels = rule.get("channels");
        if (channels instanceof List<?> list) {
            params.addValue("channels", "{" + String.join(",", list.stream().map(Object::toString).toList()) + "}");
        } else {
            params.addValue("channels", "{}");
        }

        List<Map<String, Object>> results = jdbc.query(sql, params, this::mapRow);
        return results.isEmpty() ? rule : results.get(0);
    }

    public boolean update(String ruleId, Map<String, Object> rule) {
        String sql = """
                UPDATE alert_rules SET
                    field = COALESCE(:field, field),
                    condition = COALESCE(:condition, condition),
                    threshold = COALESCE(:threshold, threshold),
                    enabled = COALESCE(:enabled, enabled)
                WHERE id = :id::uuid
                """;
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("id", ruleId);
        params.addValue("field", rule.get("field"));
        params.addValue("condition", rule.get("condition"));
        params.addValue("threshold", rule.get("threshold"));
        params.addValue("enabled", rule.get("enabled"));
        return jdbc.update(sql, params) > 0;
    }

    public boolean delete(String ruleId) {
        return jdbc.update(
                "DELETE FROM alert_rules WHERE id = :id::uuid",
                new MapSqlParameterSource("id", ruleId)
        ) > 0;
    }

    /**
     * Lookup the {@code report_key} owning a given alert rule (used by
     * {@code AlertController} authorization helpers to resolve the per-report
     * scope check when the URL has only the {@code ruleId}).
     */
    public Optional<String> findReportKeyById(String ruleId) {
        try {
            String result = jdbc.queryForObject(
                    "SELECT report_key FROM alert_rules WHERE id = :id::uuid",
                    new MapSqlParameterSource("id", ruleId),
                    String.class
            );
            return Optional.ofNullable(result);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("reportKey", rs.getString("report_key"));
        row.put("field", rs.getString("field"));
        row.put("condition", rs.getString("condition"));
        row.put("threshold", rs.getBigDecimal("threshold"));
        row.put("frequency", rs.getString("frequency"));
        row.put("enabled", rs.getBoolean("enabled"));
        row.put("createdBy", rs.getString("created_by"));
        row.put("createdAt", rs.getObject("created_at", OffsetDateTime.class));

        Array channelsArr = rs.getArray("channels");
        row.put("channels", channelsArr != null ? Arrays.asList((String[]) channelsArr.getArray()) : List.of());
        return row;
    }
}
