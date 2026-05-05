package com.serban.notify.api;

import com.serban.notify.api.dto.IntentStatusResponse;
import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.api.dto.SubmitIntentResponse;
import com.serban.notify.config.NotifyConfig;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.exception.CrossOrgAccessException;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.service.IntentSubmissionService;
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

    public NotificationIntentController(
        IntentSubmissionService submissionService,
        NotificationIntentRepository intentRepository,
        NotifyConfig config
    ) {
        this.submissionService = submissionService;
        this.intentRepository = intentRepository;
        this.config = config;
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
     *   <li>503 — intake capacity exceeded (notify.intake.maxPending threshold)</li>
     * </ul>
     */
    @PostMapping
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Intent accepted (new or REPLAYED)"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "Cross-org access denied"),
        @ApiResponse(responseCode = "404", description = "Template not found"),
        @ApiResponse(responseCode = "503", description = "Intake capacity exceeded")
    })
    public ResponseEntity<SubmitIntentResponse> submit(
        @Valid @RequestBody SubmitIntentRequest request,
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId
    ) {
        // Codex non-neg #1: caller org must match intent org (D41 multi-tenant boundary)
        if (!callerOrgId.equals(request.orgId())) {
            throw new CrossOrgAccessException(
                "caller org_id (" + callerOrgId + ") does not match intent.org_id ("
                    + request.orgId() + ")"
            );
        }
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
        NotificationIntent intent = intentRepository
            .findByIntentIdAndOrgId(intentId, callerOrgId)
            .orElseThrow(() -> new IntentNotFoundException(
                "intent not found or not accessible: " + intentId
            ));
        return ResponseEntity.ok(IntentStatusResponse.fromEntity(intent, config.dispatch().enabled()));
    }
}
