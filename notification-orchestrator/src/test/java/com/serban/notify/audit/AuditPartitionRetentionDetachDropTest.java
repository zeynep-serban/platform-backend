package com.serban.notify.audit;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.AuditRetentionLog;
import com.serban.notify.repository.AuditRetentionLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuditPartitionRetentionService DETACH → DROP integration test
 * (Codex thread `019e090d` iter-1 P3 absorb + `019e0b9f` C.2 prep).
 *
 * <p>The companion test class {@link AuditPartitionV8IntegrationTest} sets
 * {@code retention-days=36500} (100 years) so its initial partitions are
 * NEVER eligible for detach. That choice was correct for that suite —
 * Testcontainers shared schema + DirtiesContext per-method order causes
 * detach state to bleed across tests if retention is real.
 *
 * <p>This separate class fills the C.2 (dry-run=false flip) prerequisite
 * gap: actually exercise the DETACH and DROP code paths against a
 * disposable manually-created old partition so a regression in either path
 * fails CI. Without this coverage, the audit_retention_log INSERT/UPDATE,
 * {@code ALTER TABLE ... DETACH PARTITION}, and {@code DROP TABLE} branches
 * had ZERO automated test coverage — the bean's destructive code paths
 * were "tested" only by live cron ticks in production.
 *
 * <p>Test setup:
 * <ul>
 *   <li>retention-days=30 (short window — disposable old partition becomes eligible)</li>
 *   <li>retention-grace-hours=0 (immediate DROP after DETACH; same cycle would
 *       drop in real flow but cron only runs once/day, so we run two cycles)</li>
 *   <li>retention-dry-run=false (actually execute DETACH + DROP)</li>
 *   <li>retention-scheduling-enabled=false (manual {@code runCycle()})</li>
 * </ul>
 *
 * <p>Disposable partition creation: {@code audit_event_v2_2024_01}. This
 * partition is well over 30 days old (range_end = 2024-02-01) so it
 * becomes a detach candidate on the first runCycle. After detach,
 * drop_after = now + 0h, so the second runCycle drops it.
 *
 * <p>Why a separate test class instead of adding methods to
 * {@code AuditPartitionV8IntegrationTest}: that class's class-level
 * {@code @TestPropertySource} sets retention-days=36500. JUnit 5 doesn't
 * support per-method @TestPropertySource overrides, and changing the
 * class-level value would break the existing tests' isolation guarantees
 * (Codex 019dfdec iter-3 absorb).
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
@TestPropertySource(properties = {
    "notify.audit.retention-enabled=true",
    "notify.audit.retention-scheduling-enabled=false",
    // Codex iter-2 absorb: with retention-days=30, V8 migration's initial
    // partitions (audit_event_v2_2026_02..07) ALSO become eligible for
    // detach because their range_end is < cutoff. This bleeds across to
    // AuditPartitionV8IntegrationTest's
    // v8MigrationCreatedPartitionedTableWithExpectedPartitions assertion.
    // Use retention-days=365 so cutoff = (now - 1y) ≈ 2025-05-09:
    //   - Our disposable 2024-01 partition (range_end 2024-02-01) IS eligible
    //   - V8 initial 2026-02..07 partitions (range_end 2026-03-01..2026-08-01)
    //     are NOT eligible
    // This isolates retention behavior to ONLY our disposable fixture.
    "notify.audit.retention-days=365",
    // Codex 019e0bb6 RED absorb: NotifyConfig.AuditConfig.retentionGraceHours
    // is `@Min(1)` validated; grace=0 fails @Validated bean instantiation
    // before Spring context loads. Use grace=1 and time-travel the
    // audit_retention_log.drop_after column manually for the DROP test.
    "notify.audit.retention-grace-hours=1",
    "notify.audit.retention-future-months=2",
    "notify.audit.retention-dry-run=false"
})
class AuditPartitionRetentionDetachDropTest extends AbstractPostgresTest {

    @Autowired AuditRetentionLogRepository logRepo;
    @Autowired AuditPartitionRetentionService retentionService;
    @Autowired JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void cleanRetentionState() {
        // Same isolation pattern as AuditPartitionV8IntegrationTest.
        jdbc.execute("DELETE FROM notify.audit_retention_log");
        // Drop any leftover disposable partitions from previous test method.
        // Idempotent — IF EXISTS swallows the not-attached case. Codex
        // 019e0bb6 RED absorb: also clean the 2099_12 fixture used by the
        // recentPartition test in case its cleanup didn't run (test failure).
        jdbc.execute("DROP TABLE IF EXISTS notify.audit_event_v2_2024_01");
        jdbc.execute("DROP TABLE IF EXISTS notify.audit_event_v2_2099_12");
    }

    @Test
    void detachOldPartitionInsertsLogRowAndRemovesFromInheritance() {
        // Setup: create a disposable old partition (range 2024-01-01 → 2024-02-01).
        // retention-days=30 means cutoff = today - 30d ≈ 2026-04-09;
        // partition range_end 2024-02-01 << cutoff → eligible for detach.
        createOldPartition("audit_event_v2_2024_01", "2024-01-01", "2024-02-01");

        // Confirm partition is attached
        Boolean attachedBefore = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_inherits inh "
                + "JOIN pg_class child ON child.oid = inh.inhrelid "
                + "JOIN pg_class parent ON parent.oid = inh.inhparent "
                + "JOIN pg_namespace n ON n.oid = parent.relnamespace "
                + "WHERE n.nspname = 'notify' AND parent.relname = 'audit_event_v2' "
                + "AND child.relname = 'audit_event_v2_2024_01')",
            Boolean.class);
        assertThat(attachedBefore).isTrue();

        // Run the cycle — should DETACH the partition.
        // grace-hours=1 means drop_after = now+1h, so the SAME cycle's
        // dropEligiblePartitions() phase finds drop_after > now → no drop.
        // Standalone table remains, log row stays in 'detached' status.
        retentionService.runCycle();

        // Partition no longer in inheritance graph (DETACHED)
        Boolean attachedAfter = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_inherits inh "
                + "JOIN pg_class child ON child.oid = inh.inhrelid "
                + "JOIN pg_class parent ON parent.oid = inh.inhparent "
                + "JOIN pg_namespace n ON n.oid = parent.relnamespace "
                + "WHERE n.nspname = 'notify' AND parent.relname = 'audit_event_v2' "
                + "AND child.relname = 'audit_event_v2_2024_01')",
            Boolean.class);
        assertThat(attachedAfter).isFalse();

        // But standalone table still exists (DROP gate not crossed yet — drop_after = now+1h)
        Boolean tableExists = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = 'notify' AND c.relname = 'audit_event_v2_2024_01')",
            Boolean.class);
        assertThat(tableExists).isTrue();

        // audit_retention_log row inserted with status=detached
        Optional<AuditRetentionLog> logEntry = logRepo.findByPartitionName("audit_event_v2_2024_01");
        assertThat(logEntry).isPresent();
        AuditRetentionLog row = logEntry.get();
        assertThat(row.getStatus()).isEqualTo(AuditRetentionLog.Status.detached);
        assertThat(row.getDetachedAt()).isNotNull();
        assertThat(row.getDroppedAt()).isNull();
        assertThat(row.getDropAfter()).isNotNull();
        // grace-hours=1 → drop_after ≈ detached_at + 1h
        assertThat(row.getDropAfter()).isAfter(row.getDetachedAt());
    }

    @Test
    void dropEligiblePartitionRemovesTableAndUpdatesLog() {
        // Setup: same disposable partition + run first cycle to DETACH (drop_after = now+1h)
        createOldPartition("audit_event_v2_2024_01", "2024-01-01", "2024-02-01");
        retentionService.runCycle();

        // Confirm DETACHED state
        AuditRetentionLog detachedRow = logRepo.findByPartitionName("audit_event_v2_2024_01")
            .orElseThrow(() -> new AssertionError("Detach phase didn't insert log row"));
        assertThat(detachedRow.getStatus()).isEqualTo(AuditRetentionLog.Status.detached);

        // Codex 019e0bb6 RED absorb: rather than wait 1h grace window, manually
        // time-travel drop_after into the past so the next runCycle's DROP phase
        // finds it eligible. This exercises the same dropEligiblePartitions code
        // path the production cron would on day N+1 with grace=24h.
        jdbc.update(
            "UPDATE notify.audit_retention_log "
                + "SET drop_after = NOW() - INTERVAL '1 hour' "
                + "WHERE partition_name = ?",
            "audit_event_v2_2024_01");

        // Run second cycle — should DROP the detached partition (drop_after passed)
        retentionService.runCycle();

        // Standalone table no longer exists
        Boolean tableExistsAfter = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = 'notify' AND c.relname = 'audit_event_v2_2024_01')",
            Boolean.class);
        assertThat(tableExistsAfter).isFalse();

        // audit_retention_log row updated to status=dropped
        AuditRetentionLog droppedRow = logRepo.findByPartitionName("audit_event_v2_2024_01")
            .orElseThrow(() -> new AssertionError("Log row disappeared"));
        assertThat(droppedRow.getStatus()).isEqualTo(AuditRetentionLog.Status.dropped);
        assertThat(droppedRow.getDroppedAt()).isNotNull();
        assertThat(droppedRow.getDetachedAt()).isNotNull();
        // dropped_at must be after detached_at
        assertThat(droppedRow.getDroppedAt()).isAfterOrEqualTo(droppedRow.getDetachedAt());
    }

    @Test
    void recentPartitionNotEligibleForDetach() {
        // Disposable partition with range_end inside the 30-day retention window
        // (today is well after 2024 but we use a recent date).
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String currentMonth = String.format("%04d_%02d",
            now.getYear(), now.getMonthValue());
        String partitionName = "audit_event_v2_2099_12";  // far future, also not eligible

        // Create partition with future range — not eligible for detach
        createOldPartition(partitionName, "2099-12-01", "2100-01-01");

        retentionService.runCycle();

        // Should still be attached (not detached)
        Boolean attachedAfter = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM pg_inherits inh "
                + "JOIN pg_class child ON child.oid = inh.inhrelid "
                + "JOIN pg_class parent ON parent.oid = inh.inhparent "
                + "JOIN pg_namespace n ON n.oid = parent.relnamespace "
                + "WHERE n.nspname = 'notify' AND parent.relname = 'audit_event_v2' "
                + "AND child.relname = ?)",
            Boolean.class, partitionName);
        assertThat(attachedAfter).isTrue();

        // No log row inserted for this partition
        Optional<AuditRetentionLog> logEntry = logRepo.findByPartitionName(partitionName);
        assertThat(logEntry).isEmpty();

        // Cleanup (BeforeEach won't drop _2099_12 — Codex 019e0bb6 RED absorb).
        // Use IF EXISTS to swallow already-detached/dropped state if assertion
        // failed and detach happened anyway; safe for next test method.
        try {
            jdbc.execute("ALTER TABLE notify.audit_event_v2 DETACH PARTITION notify." + partitionName);
        } catch (RuntimeException ignored) {
            // Already detached or doesn't exist
        }
        jdbc.execute("DROP TABLE IF EXISTS notify." + partitionName);
    }

    @Test
    void detachIsIdempotent() {
        // Setup: detach a disposable partition once.
        createOldPartition("audit_event_v2_2024_01", "2024-01-01", "2024-02-01");
        retentionService.runCycle();

        long logCountAfterFirst = countLog();
        assertThat(logCountAfterFirst).isEqualTo(1L);

        // Run the cycle a second time — should NOT create another log row.
        // The standalone partition still exists (drop hasn't happened in this
        // test setup since we only ran once), but it's no longer attached
        // so detach phase iterates over partition list and skips this one
        // (already in audit_retention_log).
        retentionService.runCycle();

        long logCountAfterSecond = countLog();
        // detachOldPartitions() guards with `findByPartitionName` early-skip
        // so log row count stays at 1; dropEligible(...) might increment
        // status to dropped which keeps row count the same.
        assertThat(logCountAfterSecond).isEqualTo(1L);
    }

    /** Create + ATTACH a disposable partition for testing. */
    private void createOldPartition(String partitionName, String rangeStart, String rangeEnd) {
        jdbc.execute(String.format(
            "CREATE TABLE notify.%s PARTITION OF notify.audit_event_v2 "
                + "FOR VALUES FROM ('%s 00:00:00+00') TO ('%s 00:00:00+00')",
            partitionName, rangeStart, rangeEnd));
    }

    private long countLog() {
        Long c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notify.audit_retention_log", Long.class);
        return c == null ? 0L : c;
    }
}
