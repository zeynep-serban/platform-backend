package com.serban.notify.api;

import com.serban.notify.api.dto.InboxItemResponse;
import com.serban.notify.api.dto.InboxListResponse;
import com.serban.notify.domain.NotificationInbox;
import com.serban.notify.inbox.InboxService;
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

    public InboxController(
        InboxService inboxService,
        SubscriberIdentityGuard subscriberIdentityGuard
    ) {
        this.inboxService = inboxService;
        this.subscriberIdentityGuard = subscriberIdentityGuard;
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
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        Page<NotificationInbox> pageResult = inboxService.listActive(
            callerOrgId, subscriberId, page, size
        );
        long unreadCount = inboxService.unreadCount(callerOrgId, subscriberId);
        return ResponseEntity.ok(InboxListResponse.from(pageResult, unreadCount));
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
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        return inboxService.archive(callerOrgId, id, subscriberId)
            .map(InboxItemResponse::fromEntity)
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new InboxNotFoundException(
                "inbox row not found or not accessible: " + id
            ));
    }

    /** Lightweight DTO for unread count endpoint. */
    public record UnreadCountResponse(long unreadCount) {}

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
