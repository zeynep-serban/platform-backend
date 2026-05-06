package com.serban.notify.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.serban.notify.erasure.ErasureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AdminErasureController — KVKK §11 / GDPR Art 17 right-to-erasure admin
 * endpoint (Faz 23.2 PR-B — Codex 019dfae5 Q2 absorb).
 *
 * <p>Codex Q2 absorb:
 * <ul>
 *   <li>Sync admin endpoint (small data; async job follow-up for bulk)</li>
 *   <li>Auth: api-gateway path-based ROLE_PRIVACY_OFFICER allowlist
 *       (Spring Security in-app + JWT decoder + role converter + test
 *       infrastructure follow-up; Codex iter-1 P0 #2 absorb deferred to PR-C)</li>
 *   <li>Idempotent: ikinci çağrı = no-op</li>
 *   <li>Audit append: SUBSCRIBER_ERASURE_REQUEST event (silinmez)</li>
 * </ul>
 *
 * <p>Operator runbook: docs/runbooks/RB-faz-23-2-kvkk-erasure.md (PR-C scope).
 *
 * <p>Future scope (follow-up):
 * <ul>
 *   <li>Async job for bulk erasure (subscriber list)</li>
 *   <li>permission-service can_erasure relation (FGA tuple)</li>
 *   <li>External recipient erasure (email_hash based)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/notify/erasure")
@Tag(name = "Admin — KVKK Erasure",
     description = "Subscriber right-to-erasure (KVKK §11 / GDPR Art 17)")
public class AdminErasureController {

    private static final Logger log = LoggerFactory.getLogger(AdminErasureController.class);

    private final ErasureService erasureService;

    public AdminErasureController(ErasureService erasureService) {
        this.erasureService = erasureService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_PRIVACY_OFFICER')")
    @Operation(
        summary = "Erase subscriber PII",
        description = "Sync KVKK erasure: payload + recipients_snapshot + metadata "
            + "+ preference_override null'lanan; delivery.recipient_id null. "
            + "recipient_hash KORUNUR (operational analytics). Audit trail append-only. "
            + "Faz 23.2 PR-D.3.x: ROLE_PRIVACY_OFFICER role gate enforced."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "Erasure complete (idempotent)"),
        @ApiResponse(responseCode = "401",
            description = "Authentication required (JWT missing/invalid)"),
        @ApiResponse(responseCode = "403",
            description = "Authorization failed (ROLE_PRIVACY_OFFICER missing)"),
        @ApiResponse(responseCode = "400",
            description = "Validation failed")
    })
    public ResponseEntity<Map<String, Object>> erase(@Valid @RequestBody EraseRequest request) {
        log.info("KVKK erasure admin request: orgId={} subscriberId={} reason={}",
            request.orgId(), request.subscriberId(), request.reason());

        ErasureService.EraseResult result = erasureService.eraseSubscriber(
            new ErasureService.EraseRequest(
                request.orgId(),
                request.subscriberId(),
                request.reason(),
                request.evidenceRef()
            )
        );

        // Codex iter-2 P3 absorb: status reflects ALL counters; inbox-only
        // erasure (no intent match) was previously misreported as "no_op".
        boolean anyMutation = result.intentsErased() > 0
            || result.deliveriesAnonymized() > 0
            || result.inboxRowsDeleted() > 0;
        return ResponseEntity.ok(Map.of(
            "intents_erased", result.intentsErased(),
            "deliveries_anonymized", result.deliveriesAnonymized(),
            "inbox_rows_deleted", result.inboxRowsDeleted(),
            "status", anyMutation ? "completed" : "no_op"
        ));
    }

    /**
     * KVKK erasure request DTO. snake_case JSON binding via @JsonProperty
     * (consistent with InternalAuthorizationController pattern).
     */
    public record EraseRequest(
        @JsonProperty("org_id")
        @NotBlank
        @Size(max = 64)
        String orgId,

        @JsonProperty("subscriber_id")
        @NotBlank
        @Size(max = 128)
        String subscriberId,

        @JsonProperty("reason")
        @NotBlank
        @Size(max = 128)
        String reason,

        @JsonProperty("evidence_ref")
        @Size(max = 255)
        String evidenceRef
    ) {}
}
