package com.serban.notify.inbox;

import com.serban.notify.domain.NotificationInbox;
import com.serban.notify.exception.InvalidRequestException;
import com.serban.notify.repository.NotificationInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * In-app inbox service (Faz 23.3 PR-E.1).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>List subscriber's active inbox (paged, newest-first)</li>
 *   <li>Compute unread badge count</li>
 *   <li>State transitions: mark-as-read, archive</li>
 * </ul>
 *
 * <p>Tenancy invariant: every operation requires {@code orgId + subscriberId}
 * pair from caller (controller resolves from JWT subject claim). Repository
 * filters apply both — defense-in-depth for cross-tenant access.
 *
 * <p>Out of scope (this PR):
 * <ul>
 *   <li>Inbox row creation (intent fan-out hook) — PR-E.2</li>
 *   <li>WS/SSE real-time push — PR-E.2</li>
 *   <li>Bulk operations (mark-all-read, bulk-archive) — deferred</li>
 * </ul>
 */
@Service
public class InboxService {

    private static final Logger log = LoggerFactory.getLogger(InboxService.class);

    /** Pagination guardrail (page size cap) — prevent unbounded memory loads. */
    private static final int MAX_PAGE_SIZE = 100;

    /** Default page size for GET /inbox/me. */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final NotificationInboxRepository inboxRepository;

    public InboxService(NotificationInboxRepository inboxRepository) {
        this.inboxRepository = inboxRepository;
    }

    /**
     * List subscriber's active inbox (UNREAD + READ; ARCHIVED filtered out).
     *
     * @param orgId tenant scope
     * @param subscriberId from JWT subject claim
     * @param page 0-indexed page number
     * @param size requested page size (clamped to {@code [1, MAX_PAGE_SIZE]})
     * @return paged inbox entries newest-first
     */
    @Transactional(readOnly = true)
    public Page<NotificationInbox> listActive(String orgId, String subscriberId, int page, int size) {
        validateTenancy(orgId, subscriberId);
        int safeSize = Math.max(1, Math.min(size > 0 ? size : DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return inboxRepository.findActiveBySubscriber(orgId, subscriberId, pageable);
    }

    /**
     * Unread count for badge display (real-time refresh trigger PR-E.2 WS).
     */
    @Transactional(readOnly = true)
    public long unreadCount(String orgId, String subscriberId) {
        validateTenancy(orgId, subscriberId);
        return inboxRepository.countUnreadBySubscriber(orgId, subscriberId);
    }

    /**
     * Single inbox row lookup (tenancy-guarded).
     */
    @Transactional(readOnly = true)
    public Optional<NotificationInbox> findById(String orgId, Long id, String subscriberId) {
        validateTenancy(orgId, subscriberId);
        if (id == null) return Optional.empty();
        return inboxRepository.findByOrgIdAndIdAndSubscriberId(orgId, id, subscriberId);
    }

    /**
     * Mark inbox row as READ (idempotent).
     *
     * <p>State machine: UNREAD → READ. No-op if already READ or ARCHIVED.
     * Returns the post-mutation state via re-fetch.
     *
     * @return updated inbox row, empty if id not found OR cross-tenant access
     */
    @Transactional
    public Optional<NotificationInbox> markAsRead(String orgId, Long id, String subscriberId) {
        validateTenancy(orgId, subscriberId);
        if (id == null) return Optional.empty();
        // Tenancy probe first — avoids leaking 404 vs 403 distinction.
        Optional<NotificationInbox> current =
            inboxRepository.findByOrgIdAndIdAndSubscriberId(orgId, id, subscriberId);
        if (current.isEmpty()) return Optional.empty();
        int affected = inboxRepository.markAsRead(orgId, id, subscriberId, OffsetDateTime.now());
        log.info("inbox.mark_read: orgId={} subscriberId={} id={} affected={}",
            orgId, subscriberId, id, affected);
        // Re-fetch for post-state (trigger may have set read_at).
        return inboxRepository.findByOrgIdAndIdAndSubscriberId(orgId, id, subscriberId);
    }

    /**
     * Archive inbox row (idempotent terminal transition).
     *
     * <p>State machine: UNREAD/READ → ARCHIVED. No-op if already ARCHIVED.
     * KVKK erasure handles permanent deletion via existing erasure flow;
     * archive is soft-delete (audit trail preserved).
     */
    @Transactional
    public Optional<NotificationInbox> archive(String orgId, Long id, String subscriberId) {
        validateTenancy(orgId, subscriberId);
        if (id == null) return Optional.empty();
        Optional<NotificationInbox> current =
            inboxRepository.findByOrgIdAndIdAndSubscriberId(orgId, id, subscriberId);
        if (current.isEmpty()) return Optional.empty();
        int affected = inboxRepository.archive(orgId, id, subscriberId, OffsetDateTime.now());
        log.info("inbox.archive: orgId={} subscriberId={} id={} affected={}",
            orgId, subscriberId, id, affected);
        return inboxRepository.findByOrgIdAndIdAndSubscriberId(orgId, id, subscriberId);
    }

    private static void validateTenancy(String orgId, String subscriberId) {
        if (orgId == null || orgId.isBlank()) {
            throw new InvalidRequestException("orgId required");
        }
        if (subscriberId == null || subscriberId.isBlank()) {
            throw new InvalidRequestException("subscriberId required");
        }
    }
}
