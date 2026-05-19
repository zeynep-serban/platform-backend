package com.serban.notify.api;

import com.serban.notify.api.dto.InboxHistoryListResponse;
import com.serban.notify.api.dto.InboxItemResponse;
import com.serban.notify.api.dto.InboxListResponse;
import com.serban.notify.domain.NotificationInbox;
import com.serban.notify.inbox.InboxService;

import java.time.OffsetDateTime;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * In-app inbox REST controller (Faz 23.3 PR-E.1 — charter scope:
 * {@code GET /inbox/me}, {@code POST /inbox/{id}/read}, {@code POST /inbox/{id}/archive}).
 *
 * <p>Identity model: caller passes {@code X-Org-Id} + {@code X-Subscriber-Id}
 * headers (PR-E.1 baseline matches PR2 NotificationIntentController pattern).
 * PR-D-future replaces with JWT subject claim extraction (OpenFGA
 * + permission-service S2S path already wired in Faz 23.2 PR-D.3.x).
 *
 * <p>Tenancy invariant: every endpoint filters by (orgId, subscriberId);
 * the service repository enforces both — defense-in-depth for cross-tenant
 * access.
 *
 * <p>Cross-tenant leak prevention: 404 (not 403) returned for missing or
 * cross-tenant rows — avoids existence disclosure (matches
 * NotificationIntentController.status pattern).
 *
 * <p>Faz 23.4 PR-E.5 (Codex thread {@code 019e01ba} iter-2 absorb):
 * {@link SubscriberIdentityGuard} validates the {@code X-Subscriber-Id}
 * header against the JWT principal's {@code sub} claim before any
 * service call. Without this guard an authenticated caller could send
 * any other subscriber's id in the header and read their inbox. The
 * guard returns 403 (Forbidden) on mismatch — distinct from the 404
 * cross-tenant existence-disclosure pattern, because identity mismatch
 * is an authorization failure, not a missing-row case.
 */
@RestController
@RequestMapping("/api/v1/notify/inbox")
@Validated
public class InboxController {

    private final InboxService inboxService;
    private final SubscriberIdentityGuard subscriberIdentityGuard;
    private final NotifyOrgAccessGuard notifyOrgAccessGuard;

    public InboxController(
        InboxService inboxService,
        SubscriberIdentityGuard subscriberIdentityGuard,
        NotifyOrgAccessGuard notifyOrgAccessGuard
    ) {
        this.inboxService = inboxService;
        this.subscriberIdentityGuard = subscriberIdentityGuard;
        this.notifyOrgAccessGuard = notifyOrgAccessGuard;
    }

    /**
     * GET /api/v1/notify/inbox/me — paged active inbox + unread count.
     *
     * <p>Active = state IN (UNREAD, READ); ARCHIVED filtered out.
     * Newest-first ordering (created_at DESC).
     *
     * @param size requested page size, clamped service-side to {@code [1, 100]}
     */
    @GetMapping("/me")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paged active inbox"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<InboxListResponse> listMine(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId,
        @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
        @RequestParam(name = "size", defaultValue = "20") @Min(1) int size
    ) {
        // Faz 24 / PR-5.2.1 (Codex thread `019e0675` AGREE iter-5):
        // org guard runs BEFORE the subscriber identity guard so the
        // PR-5.1 `notify_org_access_match_total` cutover-gate metric
        // captures every browser inbox call. This is the authoritative
        // signal PR-5.4 needs to flip `defaultOrgId=""` safely.
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        Page<NotificationInbox> pageResult = inboxService.listActive(
            callerOrgId, subscriberId, page, size
        );
        long unreadCount = inboxService.unreadCount(callerOrgId, subscriberId);
        return ResponseEntity.ok(InboxListResponse.from(pageResult, unreadCount));
    }

    /**
     * GET /api/v1/notify/inbox/me/history — paged notification history.
     *
     * <p>Faz 23.4 M6a (Codex thread {@code 019e40ec} AGREE iter-2).
     * Unlike {@link #listMine} (active surface — UNREAD + READ only),
     * the history view returns rows in EVERY state (UNREAD + READ +
     * ARCHIVED) created within a server-enforced rolling window (default
     * 30 days; property {@code notify.inbox.history-window-days}). It is
     * a read-only review surface — no mutation endpoint accepts a
     * history-scoped row, and there is no client {@code since} param
     * (the window floor is server policy, sourced from the DB clock).
     *
     * <p>The response carries {@code windowStart} + {@code windowDays}
     * so the client can label the view without re-deriving the boundary.
     *
     * @param size requested page size, clamped service-side to {@code [1, 100]}
     */
    @GetMapping("/me/history")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paged 30-day inbox history (all states)"),
        @ApiResponse(responseCode = "400", description = "Validation error")
    })
    public ResponseEntity<InboxHistoryListResponse> historyMine(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId,
        @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
        @RequestParam(name = "size", defaultValue = "20") @Min(1) int size
    ) {
        // Same guard order as listMine (Codex thread `019e0675` iter-5):
        // org guard before the subscriber identity guard so the
        // cutover-gate metric captures every browser inbox call.
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        InboxService.HistoryResult result = inboxService.listHistory(
            callerOrgId, subscriberId, page, size
        );
        return ResponseEntity.ok(InboxHistoryListResponse.from(
            result.page(), result.windowStart(), result.windowDays()
        ));
    }

    /**
     * GET /api/v1/notify/inbox/me/unread-count — unread badge count.
     *
     * <p>Lightweight endpoint for client polling (PR-E.2 will add WS push to
     * eliminate polling).
     */
    @GetMapping("/me/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId
    ) {
        // Faz 24 / PR-5.2.1 (Codex thread `019e0675` AGREE iter-5):
        // org guard runs BEFORE the subscriber identity guard so the
        // PR-5.1 `notify_org_access_match_total` cutover-gate metric
        // captures every browser inbox call. This is the authoritative
        // signal PR-5.4 needs to flip `defaultOrgId=""` safely.
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        long count = inboxService.unreadCount(callerOrgId, subscriberId);
        return ResponseEntity.ok(new UnreadCountResponse(count));
    }

    /**
     * POST /api/v1/notify/inbox/{id}/read — mark as READ (idempotent).
     *
     * <p>Returns the post-mutation row. 404 if id not found OR cross-tenant
     * (no existence disclosure).
     */
    @PostMapping("/{id}/read")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Inbox row state mutated or already READ"),
        @ApiResponse(responseCode = "404", description = "Not found or not accessible")
    })
    public ResponseEntity<InboxItemResponse> markAsRead(
        @PathVariable Long id,
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId
    ) {
        // Faz 24 / PR-5.2.1 (Codex thread `019e0675` AGREE iter-5):
        // org guard runs BEFORE the subscriber identity guard so the
        // PR-5.1 `notify_org_access_match_total` cutover-gate metric
        // captures every browser inbox call. This is the authoritative
        // signal PR-5.4 needs to flip `defaultOrgId=""` safely.
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        return inboxService.markAsRead(callerOrgId, id, subscriberId)
            .map(InboxItemResponse::fromEntity)
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new InboxNotFoundException(
                "inbox row not found or not accessible: " + id
            ));
    }

    /**
     * POST /api/v1/notify/inbox/{id}/archive — soft-delete (idempotent).
     *
     * <p>State machine: UNREAD/READ → ARCHIVED. KVKK erasure handles
     * permanent deletion via existing AdminErasureController (separate
     * compliance flow); archive is a user-initiated soft-delete that
     * preserves audit trail.
     */
    @PostMapping("/{id}/archive")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Inbox row archived (or already ARCHIVED)"),
        @ApiResponse(responseCode = "404", description = "Not found or not accessible")
    })
    public ResponseEntity<InboxItemResponse> archive(
        @PathVariable Long id,
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId
    ) {
        // Faz 24 / PR-5.2.1 (Codex thread `019e0675` AGREE iter-5):
        // org guard runs BEFORE the subscriber identity guard so the
        // PR-5.1 `notify_org_access_match_total` cutover-gate metric
        // captures every browser inbox call. This is the authoritative
        // signal PR-5.4 needs to flip `defaultOrgId=""` safely.
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        return inboxService.archive(callerOrgId, id, subscriberId)
            .map(InboxItemResponse::fromEntity)
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new InboxNotFoundException(
                "inbox row not found or not accessible: " + id
            ));
    }

    /**
     * POST /api/v1/notify/inbox/me/mark-all-read — Faz 23.5 PR1
     * + Faz 23.5 hardening (Codex thread {@code 019e03b5} AGREE
     * iter-1; Codex thread {@code 019e03c9} REVISE iter-2 doc absorb).
     *
     * <p>Replaces the v1 UI's N+1 mark-read loop with a single SQL
     * UPDATE. The cutoff is fully owned by the database — the
     * service issues a native UPDATE with {@code created_at <= NOW()}
     * in the WHERE clause and the {@link InboxService} reads back
     * the same DB clock via {@link NotificationInboxRepository#currentDatabaseTimestamp()}
     * (transaction-start timestamp). The controller therefore does
     * not capture {@link OffsetDateTime#now()} or any other JVM
     * timestamp; the JVM clock has zero authority over either side
     * of the cutoff comparison, which keeps the action race-safe
     * across pods regardless of NTP drift. Idempotent: a re-call
     * with no UNREAD rows returns {@code updatedCount: 0}.
     *
     * <p>Identity: same {@link SubscriberIdentityGuard} contract as
     * the other "me" endpoints — caller-supplied {@code X-Subscriber-Id}
     * must match a trusted JWT identity claim.
     *
     * @return {@link BulkMarkAllReadResponse} echoing the affected
     *         row count and the DB-sourced cutoff that was applied
     *         (audit affordance; wire shape unchanged from PR1)
     */
    @PostMapping("/me/mark-all-read")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Bulk mark-all-read result"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "Subscriber identity mismatch")
    })
    public ResponseEntity<BulkMarkAllReadResponse> markAllAsRead(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId
    ) {
        // Faz 23.5 hardening (Codex thread `019e03b5`): the cutoff is
        // now captured by the database via CURRENT_TIMESTAMP inside the
        // same SQL statement that does the bulk UPDATE. The handler
        // therefore no longer reads OffsetDateTime.now() — the JVM
        // clock has zero authority on the read boundary, which makes
        // the action race-safe across pods regardless of NTP drift.
        // The response still echoes the cutoff timestamp for audit
        // affordance, but it is now the DB clock value.
        // Faz 24 / PR-5.2.1 (Codex thread `019e0675` AGREE iter-5):
        // org guard runs BEFORE the subscriber identity guard so the
        // PR-5.1 `notify_org_access_match_total` cutover-gate metric
        // captures every browser inbox call. This is the authoritative
        // signal PR-5.4 needs to flip `defaultOrgId=""` safely.
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        InboxService.BulkMarkAllReadResult result =
            inboxService.markAllAsRead(callerOrgId, subscriberId);
        return ResponseEntity.ok(BulkMarkAllReadResponse.from(result));
    }

    /** Lightweight DTO for unread count endpoint. */
    public record UnreadCountResponse(long unreadCount) {}

    /**
     * Response DTO for the bulk mark-all-read endpoint.
     *
     * <p>{@code updatedCount} is the number of rows that flipped
     * UNREAD → READ; {@code cutoff} is the server-side timestamp the
     * service applied to the WHERE clause so the caller can audit /
     * display "marked X notifications as read up to {time}".
     */
    public record BulkMarkAllReadResponse(int updatedCount, OffsetDateTime cutoff) {
        static BulkMarkAllReadResponse from(InboxService.BulkMarkAllReadResult result) {
            return new BulkMarkAllReadResponse(result.updatedCount(), result.cutoff());
        }
    }

    /**
     * Local exception handler — controller-level @Validated constraint
     * violations (e.g. @Min on query params) translate to 400 instead of
     * the Spring default 500. Scoped to this controller; broader
     * @ControllerAdvice can replace this in PR-D-future.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleConstraintViolation(ConstraintViolationException ex) {
        return Map.of("error", "validation", "message", ex.getMessage());
    }
}
