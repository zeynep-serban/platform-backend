package com.example.schema.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.schema.model.ChangeDataInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Phase B1-7 (capability M13 — Codex 019e32aa): {@code extractChangeData}
 * tests. The mocked {@code jdbc.query} is invoked twice — base (CDC / Change
 * Tracking / replication) then temporal enrichment — so the stub branches on
 * the SQL string. Locks: the feature-bearing filter, base ⋈ temporal merge,
 * Change Tracking version fields, and that a pre-2016 temporal failure leaves
 * the base CDC / Change Tracking / replication results intact.
 */
class SchemaExtractServiceChangeDataExtractionTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final SchemaExtractService service = new SchemaExtractService(jdbc);

    private String capturedBaseSql;
    private String capturedTemporalSql;

    private void stub(List<ResultSet> baseRows, List<ResultSet> temporalRows,
                       boolean temporalFails) {
        // jdbc.query is called twice; branch on the SQL string.
        doAnswer(inv -> {
            String sql = inv.getArgument(0);
            RowCallbackHandler handler = inv.getArgument(2);
            if (sql.contains("temporal_type_desc")) {
                capturedTemporalSql = sql;
                if (temporalFails) {
                    throw new RuntimeException("Invalid column name 'temporal_type_desc'");
                }
                for (ResultSet r : temporalRows) {
                    handler.processRow(r);
                }
            } else {
                capturedBaseSql = sql;
                for (ResultSet r : baseRows) {
                    handler.processRow(r);
                }
            }
            return null;
        }).when(jdbc).query(anyString(), anyMap(), any(RowCallbackHandler.class));
    }

    /** Builder for one base (CDC / Change Tracking / replication) row. */
    private static final class BaseRow {
        private String table = "ORDERS";
        private boolean cdc;
        private boolean ct;
        private boolean trackCols;
        private long minValid = 100L;
        private long beginVer = 50L;
        private long cleanupVer = 40L;
        private boolean replicated;
        private boolean mergePublished;
        private boolean replFilter;
        private boolean syncTranSub;

        BaseRow table(String v) { this.table = v; return this; }
        BaseRow cdc() { this.cdc = true; return this; }
        BaseRow changeTracking() { this.ct = true; return this; }
        BaseRow changeTracking(long min, long begin, long cleanup) {
            this.ct = true;
            this.minValid = min;
            this.beginVer = begin;
            this.cleanupVer = cleanup;
            return this;
        }
        BaseRow trackColumns() { this.ct = true; this.trackCols = true; return this; }
        BaseRow replicated() { this.replicated = true; return this; }
        BaseRow mergePublished() { this.mergePublished = true; return this; }

        ResultSet build() throws SQLException {
            ResultSet r = mock(ResultSet.class);
            when(r.getString("table_name")).thenReturn(table);
            when(r.getString("schema_name")).thenReturn("dbo");
            when(r.getBoolean("cdc_enabled")).thenReturn(cdc);
            when(r.getBoolean("ct_enabled")).thenReturn(ct);
            when(r.getBoolean("is_track_columns_updated_on")).thenReturn(trackCols);
            when(r.getBoolean("is_replicated")).thenReturn(replicated);
            when(r.getBoolean("is_merge_published")).thenReturn(mergePublished);
            when(r.getBoolean("has_replication_filter")).thenReturn(replFilter);
            when(r.getBoolean("is_sync_tran_subscribed")).thenReturn(syncTranSub);
            when(r.getLong("min_valid_version")).thenReturn(ct ? minValid : 0L);
            when(r.getLong("begin_version")).thenReturn(ct ? beginVer : 0L);
            when(r.getLong("cleanup_version")).thenReturn(ct ? cleanupVer : 0L);
            // CT rows carry real versions; non-CT rows → SQL NULL (wasNull true).
            when(r.wasNull()).thenReturn(!ct);
            return r;
        }
    }

    /** Builder for one temporal-enrichment row. */
    private static final class TemporalRow {
        private final String table;
        private String temporalType = "NON_TEMPORAL_TABLE";
        private String historySchema;
        private String historyTable;

        TemporalRow(String table) { this.table = table; }

        TemporalRow systemVersioned(String histSchema, String histTable) {
            this.temporalType = "SYSTEM_VERSIONED_TEMPORAL_TABLE";
            this.historySchema = histSchema;
            this.historyTable = histTable;
            return this;
        }

        ResultSet build() throws SQLException {
            ResultSet r = mock(ResultSet.class);
            when(r.getString("table_name")).thenReturn(table);
            when(r.getString("temporal_type")).thenReturn(temporalType);
            when(r.getString("history_schema")).thenReturn(historySchema);
            when(r.getString("history_table")).thenReturn(historyTable);
            return r;
        }
    }

    private List<ChangeDataInfo> extract(BaseRow... base) throws SQLException {
        return run(List.of(base), List.of(), false);
    }

    private List<ChangeDataInfo> run(List<BaseRow> base, List<TemporalRow> temporal,
                                     boolean temporalFails) throws SQLException {
        List<ResultSet> baseRs = new ArrayList<>();
        for (BaseRow b : base) {
            baseRs.add(b.build());
        }
        List<ResultSet> tempRs = new ArrayList<>();
        for (TemporalRow t : temporal) {
            tempRs.add(t.build());
        }
        stub(baseRs, tempRs, temporalFails);
        return service.extractChangeData("workcube_mikrolink");
    }

    // --- feature detection + filter ---

    @Test
    void cdcTable_appearsWithCdcFlag() throws SQLException {
        List<ChangeDataInfo> cd = extract(new BaseRow().table("ORDERS").cdc());

        assertThat(cd).hasSize(1);
        ChangeDataInfo c = cd.get(0);
        assertThat(c.table()).isEqualTo("ORDERS");
        assertThat(c.cdcEnabled()).isTrue();
        assertThat(c.changeTrackingEnabled()).isFalse();
        assertThat(c.temporalType()).isEqualTo("NON_TEMPORAL_TABLE");
    }

    @Test
    void changeTrackingTable_carriesVersionFields() throws SQLException {
        ChangeDataInfo c = extract(new BaseRow().table("INVOICE")
                .changeTracking(900L, 100L, 80L)).get(0);

        assertThat(c.changeTrackingEnabled()).isTrue();
        assertThat(c.ctMinValidVersion()).isEqualTo(900L);
        assertThat(c.ctBeginVersion()).isEqualTo(100L);
        assertThat(c.ctCleanupVersion()).isEqualTo(80L);
    }

    @Test
    void changeTrackingDisabled_versionFieldsNull() throws SQLException {
        ChangeDataInfo c = extract(new BaseRow().table("INVOICE").cdc()).get(0);

        // CDC-only table — no Change Tracking row → version fields are null.
        assertThat(c.changeTrackingEnabled()).isFalse();
        assertThat(c.ctMinValidVersion()).isNull();
        assertThat(c.ctBeginVersion()).isNull();
        assertThat(c.ctCleanupVersion()).isNull();
    }

    @Test
    void replicatedTable_carriesSpecificFlagAndDerivedConvenience() throws SQLException {
        ChangeDataInfo c = extract(new BaseRow().table("PRICE_LIST").replicated()).get(0);

        assertThat(c.transactionalReplicationEnabled()).isTrue();
        assertThat(c.mergePublished()).isFalse();
        assertThat(c.replicated()).isTrue();
    }

    @Test
    void nonFeatureTable_filteredOut() throws SQLException {
        // A plain table with no CDC / CT / temporal / replication is excluded.
        List<ChangeDataInfo> cd = extract(new BaseRow().table("PLAIN_TABLE"));

        assertThat(cd).isEmpty();
    }

    @Test
    void mixedTables_onlyFeatureBearingReturned() throws SQLException {
        List<ChangeDataInfo> cd = extract(
                new BaseRow().table("PLAIN_A"),
                new BaseRow().table("CDC_TABLE").cdc(),
                new BaseRow().table("PLAIN_B"),
                new BaseRow().table("CT_TABLE").changeTracking());

        assertThat(cd).extracting(ChangeDataInfo::table)
                .containsExactlyInAnyOrder("CDC_TABLE", "CT_TABLE");
    }

    @Test
    void emptySchema_yieldsNoChangeData() throws SQLException {
        stub(List.of(), List.of(), false);
        assertThat(service.extractChangeData("workcube_mikrolink")).isEmpty();
    }

    // --- temporal enrichment ---

    @Test
    void temporalTable_enrichedFromTemporalQuery() throws SQLException {
        // A table with no base feature becomes feature-bearing once the
        // temporal query marks it system-versioned.
        List<ChangeDataInfo> cd = run(
                List.of(new BaseRow().table("EMPLOYEE")),
                List.of(new TemporalRow("EMPLOYEE")
                        .systemVersioned("history", "EMPLOYEE_HISTORY")),
                false);

        assertThat(cd).hasSize(1);
        ChangeDataInfo c = cd.get(0);
        assertThat(c.temporalType()).isEqualTo("SYSTEM_VERSIONED_TEMPORAL_TABLE");
        assertThat(c.historySchema()).isEqualTo("history");
        assertThat(c.historyTable()).isEqualTo("EMPLOYEE_HISTORY");
    }

    @Test
    void temporalQueryFails_baseFeaturesPreserved() throws SQLException {
        // Pre-2016 SQL Server: the temporal query throws "Invalid column
        // name". CDC / Change Tracking / replication must survive.
        List<ChangeDataInfo> cd = run(
                List.of(new BaseRow().table("ORDERS").cdc()),
                List.of(),
                true);

        assertThat(cd).hasSize(1);
        assertThat(cd.get(0).cdcEnabled()).isTrue();
        assertThat(cd.get(0).temporalType()).isEqualTo("NON_TEMPORAL_TABLE");
    }

    // --- SQL shape ---

    @Test
    void extractChangeData_baseSqlUses2008SafeCatalog() throws SQLException {
        extract(new BaseRow().cdc());

        assertThat(capturedBaseSql)
                .contains("is_tracked_by_cdc")
                .contains("sys.change_tracking_tables")
                .contains("is_replicated")
                .doesNotContain("temporal_type_desc");
    }

    @Test
    void extractChangeData_temporalSqlIsolatesVersionGatedColumns() throws SQLException {
        extract(new BaseRow().cdc());

        assertThat(capturedTemporalSql)
                .contains("temporal_type_desc")
                .contains("history_table_id");
    }
}
