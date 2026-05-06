package com.serban.notify.inbox;

import com.serban.notify.domain.NotificationInbox;
import com.serban.notify.exception.InvalidRequestException;
import com.serban.notify.repository.NotificationInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * InboxService unit tests (Faz 23.3 PR-E.1).
 *
 * <p>Test scope:
 * <ul>
 *   <li>Tenancy guard (orgId/subscriberId required)</li>
 *   <li>Pagination clamp ({@code [1, MAX_PAGE_SIZE]})</li>
 *   <li>State transition idempotency (markAsRead / archive — no-op when
 *       row already in target state)</li>
 *   <li>Cross-tenant defense (returns empty when row not found via
 *       org-scoped lookup)</li>
 * </ul>
 */
class InboxServiceUnitTest {

    private NotificationInboxRepository repository;
    private InboxService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationInboxRepository.class);
        service = new InboxService(repository);
    }

    // ─── Tenancy guard ───────────────────────────────────────────────────

    @Test
    void listActiveRequiresOrgId() {
        assertThatThrownBy(() -> service.listActive(null, "sub-1", 0, 20))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("orgId");
        assertThatThrownBy(() -> service.listActive("", "sub-1", 0, 20))
            .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void listActiveRequiresSubscriberId() {
        assertThatThrownBy(() -> service.listActive("default", null, 0, 20))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("subscriberId");
    }

    @Test
    void unreadCountRequiresOrgIdAndSubscriberId() {
        assertThatThrownBy(() -> service.unreadCount(null, "sub-1"))
            .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.unreadCount("default", ""))
            .isInstanceOf(InvalidRequestException.class);
    }

    // ─── Pagination clamp ────────────────────────────────────────────────

    @Test
    void listActiveClampsPageSizeTo100() {
        when(repository.findActiveBySubscriber(eq("default"), eq("sub-1"), any()))
            .thenReturn(Page.empty());

        service.listActive("default", "sub-1", 0, 1000);

        // Verify Pageable was clamped to MAX_PAGE_SIZE=100
        verify(repository).findActiveBySubscriber(eq("default"), eq("sub-1"),
            org.mockito.ArgumentMatchers.argThat(p -> p.getPageSize() == 100));
    }

    @Test
    void listActiveAppliesDefaultSizeWhenZeroOrNegative() {
        when(repository.findActiveBySubscriber(eq("default"), eq("sub-1"), any()))
            .thenReturn(Page.empty());

        service.listActive("default", "sub-1", 0, 0);

        verify(repository).findActiveBySubscriber(eq("default"), eq("sub-1"),
            org.mockito.ArgumentMatchers.argThat(p -> p.getPageSize() == 20));
    }

    @Test
    void listActiveClampsNegativePageToZero() {
        when(repository.findActiveBySubscriber(eq("default"), eq("sub-1"), any()))
            .thenReturn(Page.empty());

        service.listActive("default", "sub-1", -5, 20);

        verify(repository).findActiveBySubscriber(eq("default"), eq("sub-1"),
            org.mockito.ArgumentMatchers.argThat(p -> p.getPageNumber() == 0));
    }

    @Test
    void listActiveReturnsRepositoryPage() {
        NotificationInbox row = stubRow();
        Page<NotificationInbox> page = new PageImpl<>(List.of(row), Pageable.ofSize(20), 1);
        when(repository.findActiveBySubscriber(eq("default"), eq("sub-1"), any()))
            .thenReturn(page);

        Page<NotificationInbox> result = service.listActive("default", "sub-1", 0, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(42L);
    }

    @Test
    void unreadCountReturnsRepositoryValue() {
        when(repository.countUnreadBySubscriber("default", "sub-1")).thenReturn(7L);

        long count = service.unreadCount("default", "sub-1");

        assertThat(count).isEqualTo(7L);
    }

    // ─── findById ────────────────────────────────────────────────────────

    @Test
    void findByIdNullReturnsEmpty() {
        Optional<NotificationInbox> result = service.findById("default", null, "sub-1");
        assertThat(result).isEmpty();
    }

    @Test
    void findByIdQueriesRepoWithTenancyFilter() {
        NotificationInbox row = stubRow();
        when(repository.findByOrgIdAndIdAndSubscriberId("default", 42L, "sub-1"))
            .thenReturn(Optional.of(row));

        Optional<NotificationInbox> result = service.findById("default", 42L, "sub-1");

        assertThat(result).isPresent();
    }

    @Test
    void findByIdCrossTenantReturnsEmpty() {
        // Row exists for other org/subscriber; tenancy filter returns empty
        when(repository.findByOrgIdAndIdAndSubscriberId("default", 42L, "wrong-sub"))
            .thenReturn(Optional.empty());

        Optional<NotificationInbox> result = service.findById("default", 42L, "wrong-sub");

        assertThat(result).isEmpty();
    }

    // ─── markAsRead idempotency ──────────────────────────────────────────

    @Test
    void markAsReadHappyPath() {
        NotificationInbox row = stubRow();
        when(repository.findByOrgIdAndIdAndSubscriberId("default", 42L, "sub-1"))
            .thenReturn(Optional.of(row));
        when(repository.markAsRead(eq("default"), eq(42L), eq("sub-1"), any()))
            .thenReturn(1);

        Optional<NotificationInbox> result = service.markAsRead("default", 42L, "sub-1");

        assertThat(result).isPresent();
        verify(repository).markAsRead(eq("default"), eq(42L), eq("sub-1"), any());
    }

    @Test
    void markAsReadIdempotentWhenAlreadyRead() {
        // Pre-state: already READ; markAsRead returns 0 (no-op due to WHERE state=UNREAD)
        NotificationInbox row = stubRow();
        row.setState(NotificationInbox.State.READ);
        when(repository.findByOrgIdAndIdAndSubscriberId("default", 42L, "sub-1"))
            .thenReturn(Optional.of(row));
        when(repository.markAsRead(eq("default"), eq(42L), eq("sub-1"), any()))
            .thenReturn(0);

        Optional<NotificationInbox> result = service.markAsRead("default", 42L, "sub-1");

        // Re-fetch returns row (still READ). Operation idempotent — no exception.
        assertThat(result).isPresent();
    }

    @Test
    void markAsReadCrossTenantReturnsEmpty() {
        when(repository.findByOrgIdAndIdAndSubscriberId("default", 42L, "wrong-sub"))
            .thenReturn(Optional.empty());

        Optional<NotificationInbox> result = service.markAsRead("default", 42L, "wrong-sub");

        assertThat(result).isEmpty();
        // markAsRead never invoked for cross-tenant id
        verify(repository, never()).markAsRead(any(), any(), any(), any());
    }

    @Test
    void markAsReadNullIdReturnsEmpty() {
        Optional<NotificationInbox> result = service.markAsRead("default", null, "sub-1");
        assertThat(result).isEmpty();
    }

    // ─── archive idempotency ─────────────────────────────────────────────

    @Test
    void archiveHappyPath() {
        NotificationInbox row = stubRow();
        when(repository.findByOrgIdAndIdAndSubscriberId("default", 42L, "sub-1"))
            .thenReturn(Optional.of(row));
        when(repository.archive(eq("default"), eq(42L), eq("sub-1"), any()))
            .thenReturn(1);

        Optional<NotificationInbox> result = service.archive("default", 42L, "sub-1");

        assertThat(result).isPresent();
        verify(repository).archive(eq("default"), eq(42L), eq("sub-1"), any());
    }

    @Test
    void archiveIdempotentWhenAlreadyArchived() {
        NotificationInbox row = stubRow();
        row.setState(NotificationInbox.State.ARCHIVED);
        when(repository.findByOrgIdAndIdAndSubscriberId("default", 42L, "sub-1"))
            .thenReturn(Optional.of(row));
        when(repository.archive(eq("default"), eq(42L), eq("sub-1"), any()))
            .thenReturn(0);

        Optional<NotificationInbox> result = service.archive("default", 42L, "sub-1");

        assertThat(result).isPresent();  // re-fetch still returns ARCHIVED row
    }

    @Test
    void archiveCrossTenantReturnsEmpty() {
        when(repository.findByOrgIdAndIdAndSubscriberId("default", 42L, "wrong-sub"))
            .thenReturn(Optional.empty());

        Optional<NotificationInbox> result = service.archive("default", 42L, "wrong-sub");

        assertThat(result).isEmpty();
        verify(repository, never()).archive(any(), any(), any(), any());
    }

    private static NotificationInbox stubRow() {
        NotificationInbox row = new NotificationInbox();
        row.setId(42L);
        row.setIntentId("intent-x");
        row.setOrgId("default");
        row.setSubscriberId("sub-1");
        row.setSubject("Hello");
        row.setBodyText("body");
        row.setLocale("tr-TR");
        row.setTopicKey("auth.password-reset");
        row.setSeverity("info");
        row.setState(NotificationInbox.State.UNREAD);
        row.setCreatedAt(OffsetDateTime.now());
        return row;
    }
}
