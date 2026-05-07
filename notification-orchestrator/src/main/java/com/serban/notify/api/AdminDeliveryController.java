package com.serban.notify.api;

import com.serban.notify.api.dto.DeliveryLogListResponse;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.service.DeliveryLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Admin-wide delivery log search (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE — operator dashboard surface
 * for fleet-wide delivery health (e.g. "show me failed SMS in the last 24h
 * for org X"). Sits alongside {@link AdminErasureController} under the
 * {@code /api/v1/admin/notify/**} path which {@link com.serban.notify.config.SecurityConfig}
 * already pins to authenticated callers.
 *
 * <h3>Auth contract</h3>
 *
 * <ul>
 *   <li>{@code @PreAuthorize("hasAnyAuthority('audit-read','ROLE_ADMIN')")}
 *       — strictly tighter than the intent endpoint; {@code ROLE_OPERATOR}
 *       does not unlock fleet-wide search.</li>
 *   <li>{@code X-Org-Id} header is mandatory and is reconciled against
 *       JWT-trusted org claims via {@link NotifyOrgAccessGuard}.</li>
 *   <li>Cross-org search is closed in v1 — a future
 *       {@code notify-deliveries-cross-org} permission could open it.</li>
 * </ul>
 *
 * <h3>Time window</h3>
 *
 * <p>Window applies to the {@code activityAt} axis (geç-DLR safe). Caller
 * may omit both endpoints; defaults to the last 24h. Range is capped at
 * {@code MAX_WINDOW = 7 days}; future skew tolerance is 1 day. See
 * {@link DeliveryLogQueryValidator}.
 */
@RestController
@RequestMapping("/api/v1/admin/notify")
@Tag(name = "Admin — Delivery Log",
     description = "Org-scoped delivery log search (audit surface, redacted)")
public class AdminDeliveryController {

    private static final Logger log = LoggerFactory.getLogger(AdminDeliveryController.class);

    private final DeliveryLogService service;
    private final NotifyOrgAccessGuard orgGuard;

    public AdminDeliveryController(DeliveryLogService service, NotifyOrgAccessGuard orgGuard) {
        this.service = service;
        this.orgGuard = orgGuard;
    }

    @GetMapping("/deliveries")
    @PreAuthorize("hasAnyAuthority('audit-read','ROLE_ADMIN')")
    @Operation(
        summary = "Search delivery log within an org",
        description = "Filters: status, channel, provider, time window. Window "
            + "applies to activityAt = COALESCE(permanentFailureAt, deliveredAt, "
            + "lastAttemptAt, updatedAt, createdAt) — late DLR safe. Default "
            + "window = last 24h; max range = 7d. Redaction policy v1: raw "
            + "recipient/provider/failure fields stripped."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Search results page"),
        @ApiResponse(responseCode = "400", description = "Validation failed (size, window, range)"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Missing audit-read / ROLE_ADMIN or org mismatch")
    })
    public ResponseEntity<DeliveryLogListResponse> searchDeliveries(
        @Parameter(description = "Org boundary selector — must match a JWT-trusted org claim",
            required = true)
        @RequestHeader("X-Org-Id") String orgId,
        @Parameter(description = "Filter by delivery status (optional)")
        @RequestParam(name = "status", required = false) NotificationDelivery.Status status,
        @Parameter(description = "Filter by channel (email, sms, slack, webhook, in-app)")
        @RequestParam(name = "channel", required = false) String channel,
        @Parameter(description = "Filter by provider key (smtp, netgsm, slack-webhook, ...)")
        @RequestParam(name = "provider", required = false) String provider,
        @Parameter(description = "Activity window start (ISO-8601). Default: now - 24h")
        @RequestParam(name = "from", required = false) OffsetDateTime from,
        @Parameter(description = "Activity window end (ISO-8601). Default: now")
        @RequestParam(name = "to", required = false) OffsetDateTime to,
        @Parameter(description = "Zero-based page index (default 0)")
        @RequestParam(name = "page", defaultValue = "0") int page,
        @Parameter(description = "Page size (default 20, max 100)")
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        DeliveryLogQueryValidator.validatePage(page);
        DeliveryLogQueryValidator.validateSize(size);

        orgGuard.requireOrgAccessOrThrow(orgId);

        OffsetDateTime[] window = DeliveryLogQueryValidator.resolveAdminWindow(
            from, to, OffsetDateTime.now()
        );

        DeliveryLogListResponse body = service.searchAdmin(
            orgId, status, channel, provider, window[0], window[1], page, size
        );

        log.info("admin delivery-search: org={} status={} channel={} provider={} from={} to={} total={}",
            orgId, status, channel, provider, window[0], window[1], body.totalElements());
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidation(IllegalArgumentException ex) {
        return Map.of("error", "validation", "message", ex.getMessage());
    }
}
