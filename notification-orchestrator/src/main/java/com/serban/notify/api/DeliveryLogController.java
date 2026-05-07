package com.serban.notify.api;

import com.serban.notify.api.dto.DeliveryLogListResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Intent-scoped delivery log endpoint (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE — operator / audit surface for
 * inspecting per-channel delivery attempts of a single intent.
 *
 * <h3>Auth contract</h3>
 *
 * <ul>
 *   <li>{@code @PreAuthorize("hasAnyAuthority('audit-read','ROLE_OPERATOR','ROLE_ADMIN')")}
 *       — canonical permission is {@code audit-read}; ROLE fallbacks support
 *       JWTs that only carry realm roles.</li>
 *   <li>{@code X-Org-Id} header is mandatory — used as the
 *       <i>selector</i>; {@link NotifyOrgAccessGuard} verifies the JWT can
 *       claim that org.</li>
 *   <li>Cross-org intent lookup returns 404 (info-leak safe), never 403.</li>
 * </ul>
 *
 * <h3>Response shape</h3>
 *
 * <p>{@link DeliveryLogListResponse} with redaction policy {@code v1}: raw
 * recipient ids, raw provider message ids, raw failure reasons, claim
 * tokens and lease deadlines are stripped. See
 * {@link com.serban.notify.redaction.DeliveryLogRedactor}.
 */
@RestController
@RequestMapping("/api/v1/notify/intents")
@Tag(name = "Delivery Log — Intent",
     description = "Per-intent delivery attempts (operator/audit surface, redacted)")
public class DeliveryLogController {

    private static final Logger log = LoggerFactory.getLogger(DeliveryLogController.class);

    private final DeliveryLogService service;
    private final NotifyOrgAccessGuard orgGuard;

    public DeliveryLogController(DeliveryLogService service, NotifyOrgAccessGuard orgGuard) {
        this.service = service;
        this.orgGuard = orgGuard;
    }

    @GetMapping("/{intentId}/deliveries")
    @PreAuthorize("hasAnyAuthority('audit-read','ROLE_OPERATOR','ROLE_ADMIN')")
    @Operation(
        summary = "List delivery attempts for an intent",
        description = "Returns redacted delivery rows for the given intent. "
            + "Requires X-Org-Id header that the caller can claim via JWT "
            + "(org_id | tenant_id | allowed_orgs | configured default-org). "
            + "Cross-org intent lookups return 404 to avoid existence leak."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Delivery log page"),
        @ApiResponse(responseCode = "400", description = "Validation failed (page/size)"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Org boundary mismatch"),
        @ApiResponse(responseCode = "404", description = "Intent not found in this org")
    })
    public ResponseEntity<DeliveryLogListResponse> listDeliveries(
        @PathVariable("intentId") String intentId,
        @Parameter(description = "Org boundary selector — must match a JWT-trusted org claim",
            required = true)
        @RequestHeader("X-Org-Id") String orgId,
        @Parameter(description = "Zero-based page index (default 0)")
        @RequestParam(name = "page", defaultValue = "0") int page,
        @Parameter(description = "Page size (default 20, max 100)")
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        DeliveryLogQueryValidator.validatePage(page);
        DeliveryLogQueryValidator.validateSize(size);

        orgGuard.requireOrgAccessOrThrow(orgId);

        DeliveryLogListResponse body = service.listForIntent(intentId, orgId, page, size)
            .orElseThrow(() -> new IntentNotFoundException(
                "intent " + intentId + " not found in org " + orgId
            ));

        log.debug("delivery-log: intent={} org={} page={} size={} total={}",
            intentId, orgId, page, size, body.totalElements());
        return ResponseEntity.ok(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidation(IllegalArgumentException ex) {
        return Map.of("error", "validation", "message", ex.getMessage());
    }
}
