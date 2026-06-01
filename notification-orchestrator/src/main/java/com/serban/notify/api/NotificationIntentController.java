package com.serban.notify.api;

import com.serban.notify.api.dto.IntentStatusResponse;
import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.api.dto.SubmitIntentResponse;
import com.serban.notify.config.NotifyConfig;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.exception.CrossOrgAccessException;
import com.serban.notify.exception.ValidationErrorResponse;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.service.IntentSubmissionService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Notification intent REST controller (Faz 23.1 PR2 — ADR-0013 D38).
 *
 * <p>Codex 019df9ae absorb:
 * <ul>
 *   <li>Q1 — Idempotency advisory lock at service layer</li>
 *   <li>Q2 — Template resolve only (no render)</li>
 *   <li>Non-neg #1 — GET org-scoped (cross-tenant leak prevention via
 *       repository {@code findByIntentIdAndOrgId})</li>
 * </ul>
 *
 * <p>Org context: PR2 baseline takes {@code X-Org-Id} header for org-scoped
 * lookup. Faz 23.1 PR5 (Authz) replaces with JWT claim extraction
 * (OpenFGA + permission-service integration).
 */
@RestController
@RequestMapping("/api/v1/notify/intents")
@Validated
public class NotificationIntentController {

    private final IntentSubmissionService submissionService;
    private final NotificationIntentRepository intentRepository;
    private final NotifyConfig config;
    private final NotifyOrgAccessGuard orgGuard;

    public NotificationIntentController(
        IntentSubmissionService submissionService,
        NotificationIntentRepository intentRepository,
        NotifyConfig config,
        NotifyOrgAccessGuard orgGuard
    ) {
        this.submissionService = submissionService;
        this.intentRepository = intentRepository;
        this.config = config;
        this.orgGuard = orgGuard;
    }

    /**
     * Submit notification intent.
     *
     * <p>Response codes:
     * <ul>
     *   <li>202 ACCEPTED — new intent persisted</li>
     *   <li>202 + status=REPLAYED — duplicate idempotency_key within 24h window</li>
     *   <li>400 — validation error (missing/invalid field)</li>
     *   <li>403 — cross-org access denied (org_id mismatch)</li>
     *   <li>404 — template not found</li>
     *   <li>429 — abuse guard blocked (rate limit OR webhook fan-out cap; T1.6)</li>
     *   <li>503 — intake capacity exceeded (notify.intake.maxPending threshold)</li>
     * </ul>
     */
    @PostMapping
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Intent accepted (new or REPLAYED)"),
        @ApiResponse(responseCode = "400", description = "Validation error",
            content = @Content(schema = @Schema(implementation = ValidationErrorResponse.class))),
        @ApiResponse(responseCode = "403", description = "Cross-org access denied"),
        @ApiResponse(responseCode = "404", description = "Template not found"),
        @ApiResponse(responseCode = "429", description = "Abuse guard blocked (rate limit / webhook fan-out cap; T1.6 Faz 23.2.F)"),
        @ApiResponse(responseCode = "503", description = "Intake capacity exceeded")
    })
    public ResponseEntity<SubmitIntentResponse> submit(
        @Valid @RequestBody SubmitIntentRequest request,
        @RequestHeader(name = "X-Org-Id", required = false) String callerOrgIdHeader
    ) {
        // Faz 24 / PR-5.2 (Codex thread `019e0675` AGREE iter-1):
        //
        // org_id is now resolved from the JWT (canonical source) via
        // NotifyOrgAccessGuard. The body's `request.orgId()` selector
        // stays the authoritative target for the new intent row, but the
        // guard asserts the JWT principal actually has access to that
        // org (`org_id` claim → `tenant_id` alias → `allowed_orgs[]` →
        // configurable `defaultOrgId` fallback). This activates the
        // PR-5.1 `notify_org_access_match_total` cutover-gate metric on
        // the submit/status path so PR-5.4 has the observation it needs
        // before flipping `defaultOrgId=""`.
        //
        // Backward compat — `X-Org-Id` is now OPTIONAL but MUST equal
        // `request.orgId()` when supplied (legacy callers / smoke
        // scripts still send the header). Any mismatch keeps the
        // existing CrossOrgAccessException 403 path so multi-tenant
        // boundary tests stay green.
        if (callerOrgIdHeader != null && !callerOrgIdHeader.isBlank()
                && !callerOrgIdHeader.equals(request.orgId())) {
            throw new CrossOrgAccessException(
                "caller org_id (" + callerOrgIdHeader + ") does not match intent.org_id ("
                    + request.orgId() + ")"
            );
        }
        // JWT-backed authority check. Throws OrgAccessDeniedException → 403
        // when the JWT principal cannot reach `request.orgId()`. PR-5.2
        // ships this in canary mode (defaultOrgId fallback still open);
        // PR-5.4 closes the fallback once the metric gate is clean.
        orgGuard.requireOrgAccessOrThrow(request.orgId());
        SubmitIntentResponse response = submissionService.submit(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Get intent status — org-scoped lookup (Codex non-neg #1).
     *
     * <p>Cross-tenant leak prevention: caller's {@code X-Org-Id} header must match
     * intent's {@code org_id}. Repository {@code findByIntentIdAndOrgId} returns
     * empty for cross-org → 404 (not 403, to avoid existence disclosure).
     */
    @GetMapping("/{intentId}")
    public ResponseEntity<IntentStatusResponse> status(
        @PathVariable @NotBlank String intentId,
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId
    ) {
        // Faz 24 / PR-5.2 (Codex thread `019e0675` AGREE iter-1):
        //
        // status() reads `X-Org-Id` as the explicit selector (which
        // intent the caller wants) but the JWT must back the access
        // claim. orgGuard verifies the JWT principal has reach into
        // `callerOrgId`; the existing repository query then enforces
        // tenancy by selecting only rows whose `org_id` column matches
        // the same string (404 not 403 to preserve existence-disclosure
        // discipline for cross-tenant lookups — the JWT couldn't have
        // produced this id anyway).
        orgGuard.requireOrgAccessOrThrow(callerOrgId);
        NotificationIntent intent = intentRepository
            .findByIntentIdAndOrgId(intentId, callerOrgId)
            .orElseThrow(() -> new IntentNotFoundException(
                "intent not found or not accessible: " + intentId
            ));
        return ResponseEntity.ok(IntentStatusResponse.fromEntity(intent, config.dispatch().enabled()));
    }
}
