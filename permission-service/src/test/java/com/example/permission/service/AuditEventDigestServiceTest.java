package com.example.permission.service;

import com.example.permission.audit.AuditReadScope;
import com.example.permission.dto.audit.AuditWeeklyDigestResponse;
import com.example.permission.repository.AuditEventDigestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-D2.5a (Codex 019e8708 AGREE) unit tests for {@link AuditEventDigestService}
 * validation + happy path orchestration.
 *
 * <p>SQL aggregation correctness validated in Testcontainers PG integration
 * test (separate; not in this unit suite).
 */
class AuditEventDigestServiceTest {

    private AuditEventDigestRepository repository;
    private AuditEventDigestService service;

    private static final Instant WEEK_1_START = OffsetDateTime
            .parse("2026-05-25T00:00:00Z").toInstant();   // Monday
    private static final Instant DATE_FROM = OffsetDateTime
            .parse("2026-05-26T00:00:00Z").toInstant();
    private static final Instant DATE_TO = OffsetDateTime
            .parse("2026-06-08T00:00:00Z").toInstant();   // 13 day range OK

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventDigestRepository.class);
        service = new AuditEventDigestService(repository);
    }

    @Test
    void rejectsNullDateFrom() {
        assertThatThrownBy(() -> service.aggregate(
                null, DATE_TO, null, null, null, null, null, null, AuditReadScope.GENERIC_AUDIT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("dateFrom required");
    }

    @Test
    void rejectsNullDateTo() {
        assertThatThrownBy(() -> service.aggregate(
                DATE_FROM, null, null, null, null, null, null, null, AuditReadScope.GENERIC_AUDIT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("dateTo required");
    }

    @Test
    void rejectsDateFromEqualsOrAfterDateTo() {
        Instant equal = DATE_FROM;
        assertThatThrownBy(() -> service.aggregate(
                DATE_FROM, equal, null, null, null, null, null, null, AuditReadScope.GENERIC_AUDIT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("strictly before");

        Instant earlier = DATE_FROM.minusSeconds(1);
        assertThatThrownBy(() -> service.aggregate(
                DATE_FROM, earlier, null, null, null, null, null, null, AuditReadScope.GENERIC_AUDIT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("strictly before");
    }

    @Test
    void rejectsRangeBeyondMaxDays() {
        Instant tooFar = DATE_FROM.plusSeconds(
                (AuditEventDigestService.MAX_RANGE_DAYS + 1) * 86_400L);
        assertThatThrownBy(() -> service.aggregate(
                DATE_FROM, tooFar, null, null, null, null, null, null, AuditReadScope.GENERIC_AUDIT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Date range exceeds max");
    }

    @Test
    void acceptsExactlyMaxRangeDays() {
        // Codex 019e8721 iter-2 P2 absorb: exact boundary tests.
        // 366 days EXACT must be accepted.
        Instant exactlyMax = DATE_FROM.plus(java.time.Duration.ofDays(
                AuditEventDigestService.MAX_RANGE_DAYS));
        when(repository.findWeeklyTotals(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findActionBreakdown(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findServiceBreakdown(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findTopUsersPerWeek(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        // Should NOT throw.
        AuditWeeklyDigestResponse response = service.aggregate(
                DATE_FROM, exactlyMax, null, null, null, null, null, null, AuditReadScope.GENERIC_AUDIT);
        assertThat(response.weeks()).isEmpty();
    }

    @Test
    void rejectsMaxRangeDaysPlusOneSecond() {
        // Codex 019e8721 iter-2 P2 absorb: 366d+1s MUST reject (previously
        // passed due to toDays() truncation; now uses Duration.compareTo).
        Instant tooFarByOneSecond = DATE_FROM.plus(java.time.Duration.ofDays(
                AuditEventDigestService.MAX_RANGE_DAYS)).plusSeconds(1);
        assertThatThrownBy(() -> service.aggregate(
                DATE_FROM, tooFarByOneSecond, null, null, null, null, null, null, AuditReadScope.GENERIC_AUDIT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Date range exceeds max");
    }

    @Test
    void rejectsTopKOutOfBoundsLow() {
        assertThatThrownBy(() -> service.aggregate(
                DATE_FROM, DATE_TO, null, null, null, null, null, 0, AuditReadScope.GENERIC_AUDIT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("topK out of bounds");
    }

    @Test
    void rejectsTopKOutOfBoundsHigh() {
        assertThatThrownBy(() -> service.aggregate(
                DATE_FROM, DATE_TO, null, null, null, null, null,
                AuditEventDigestService.MAX_TOP_K + 1, AuditReadScope.GENERIC_AUDIT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("topK out of bounds");
    }

    @Test
    void emptyResultReturns200WithEmptyWeeks() {
        when(repository.findWeeklyTotals(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findActionBreakdown(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findServiceBreakdown(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findTopUsersPerWeek(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        AuditWeeklyDigestResponse response = service.aggregate(
                DATE_FROM, DATE_TO, null, null, null, null, null, null, AuditReadScope.GENERIC_AUDIT);

        assertThat(response.weeks()).isEmpty();
        assertThat(response.computedAt()).isNotNull();
        assertThat(response.filterEcho())
                .containsEntry("dateFrom", DATE_FROM.toString())
                .containsEntry("dateTo", DATE_TO.toString())
                .containsEntry("topK", AuditEventDigestService.DEFAULT_TOP_K);
    }

    @Test
    void singleWeekHappyPath() {
        // Week start Monday 2026-05-25 UTC
        Timestamp weekStartTs = Timestamp.valueOf("2026-05-25 00:00:00");

        // findWeeklyTotals returns one row: [weekStart, isoYear, isoWeek, totalCount, distinctUserCount]
        when(repository.findWeeklyTotals(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{weekStartTs, 2026, 22, 1247L, 38L}));

        // Action breakdown: 2 actions in this week
        when(repository.findActionBreakdown(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{weekStartTs, "LOGIN", 800L},
                        new Object[]{weekStartTs, "LOGOUT", 447L}
                ));

        // Service breakdown: 1 service
        when(repository.findServiceBreakdown(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{weekStartTs, "auth-service", 1247L}
                ));

        // Top users: 2 users
        when(repository.findTopUsersPerWeek(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        new Object[]{weekStartTs, 1L, "admin@example.com", 412L},
                        new Object[]{weekStartTs, 2L, "user2@example.com", 200L}
                ));

        AuditWeeklyDigestResponse response = service.aggregate(
                DATE_FROM, DATE_TO, null, null, null, null, null, null, AuditReadScope.GENERIC_AUDIT);

        assertThat(response.weeks()).hasSize(1);
        var bucket = response.weeks().get(0);
        assertThat(bucket.isoYear()).isEqualTo(2026);
        assertThat(bucket.isoWeek()).isEqualTo(22);
        assertThat(bucket.totalEventCount()).isEqualTo(1247L);
        assertThat(bucket.distinctUserCount()).isEqualTo(38L);
        assertThat(bucket.actionBreakdown())
                .containsEntry("LOGIN", 800L)
                .containsEntry("LOGOUT", 447L);
        assertThat(bucket.serviceBreakdown())
                .containsEntry("auth-service", 1247L);
        assertThat(bucket.topUsers()).hasSize(2);
        assertThat(bucket.topUsers().get(0).userId()).isEqualTo(1L);
        assertThat(bucket.topUsers().get(0).eventCount()).isEqualTo(412L);
        // weekEnd = weekStart + 7 days - 1 second
        assertThat(bucket.weekEnd()).isEqualTo(bucket.weekStart().plusSeconds(7 * 86_400L - 1));
    }

    @Test
    void filterEchoIncludesOnlyProvidedFilters() {
        when(repository.findWeeklyTotals(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findActionBreakdown(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findServiceBreakdown(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(repository.findTopUsersPerWeek(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        AuditWeeklyDigestResponse response = service.aggregate(
                DATE_FROM, DATE_TO, "LOGIN", null, "INFO", null, "search-term", 10, AuditReadScope.GENERIC_AUDIT);

        assertThat(response.filterEcho())
                .containsEntry("action", "LOGIN")
                .containsEntry("level", "INFO")
                .containsEntry("search", "search-term")
                .containsEntry("topK", 10)
                .doesNotContainKey("service")
                .doesNotContainKey("userEmail");
    }
}
