package com.serban.notify.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.serban.notify.authz.DpoAuthzService;
import com.serban.notify.authz.DpoUserIdResolver;
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
import org.springframework.security.access.AccessDeniedException;
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
    private final NotifyOrgAccessGuard orgAccessGuard;
    private final DpoAuthzService dpoAuthzService;
    private final DpoUserIdResolver dpoUserIdResolver;

    public AdminErasureController(
        ErasureService erasureService,
        NotifyOrgAccessGuard orgAccessGuard,
        DpoAuthzService dpoAuthzService,
        DpoUserIdResolver dpoUserIdResolver
    ) {
        this.erasureService = erasureService;
        this.orgAccessGuard = orgAccessGuard;
        this.dpoAuthzService = dpoAuthzService;
        this.dpoUserIdResolver = dpoUserIdResolver;
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
        // Codex 019e4950 P1 #6 absorb: tenant-scoped DPO authz guard.
        // ROLE_PRIVACY_OFFICER yetkisi yetmiyor — DPO/legal sadece kendi
        // org'unun verisini silebilmeli. NotifyOrgAccessGuard JWT
        // org_id/tenant_id/allowed_orgs claim chain ile match check yapar;
        // cross-org çağrı 403 AccessDeniedException.
        orgAccessGuard.requireOrgAccessOrThrow(request.orgId());

        // Faz 23.2.B PR-K6 (Codex thread `019e59ea` iter-3 AGREE absorb):
        // tenant-scoped DPO authz least-privilege gate.
        //
        // The role+orgAccess stack above prevents cross-tenant access
        // ("you can only act on orgs in your trusted JWT set") but does
        // NOT enforce "this user is DPO for THIS specific org". When
        // `notify.kvkk.dpo-authz-enabled=true`, this guard adds an
        // OpenFGA `organization:<orgId>#can_erasure@user:<userId>`
        // tuple check on top.
        //
        // Default OFF (rollout pattern): existing controllers continue
        // with legacy role+orgAccess behavior until OpenFGA model is
        // extended with `organization#can_erasure` + per-org `dpo`
        // tuples are seeded + test overlay burn-in evidence captured.
        //
        // Fail-closed: missing userId claim (jwt.sub is NOT used — sub
        // is the Keycloak UUID, not the OpenFGA numeric user id) ⇒
        // DpoUserIdResolver returns null ⇒ DpoAuthzService denies when
        // flag is on.
        String dpoUserId = dpoUserIdResolver.resolveOrNull();
        if (!dpoAuthzService.canEraseForOrg(dpoUserId, request.orgId())) {
            log.info("KVKK erasure DPO authz denied: orgId={} userIdPresent={}",
                request.orgId(), dpoUserId != null);
            throw new AccessDeniedException(
                "user is not DPO for org " + request.orgId()
            );
        }

        // Codex 019e4950 P1 absorb: PII leakage guard. subscriber_id ve
        // free-form reason INFO log'da görünür → KVKK Madde 12 (data
        // minimization) ihlali. subscriber_id HMAC mask + reason short
        // enum label (free-form audit_event details'te kalır).
        log.info("KVKK erasure admin request: orgId={} subjectRef=<hmac-redacted> reasonClass={}",
            request.orgId(), classifyErasureReason(request.reason()));

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

        // Codex 019e4950 P0 #1 absorb: KVKK Madde 13.2 ledger response.
        // Caller'a request_id + due_at (30-gün) görünür yapılır → operator
        // SLA takibi + Slack #compliance kanalı korelasyon için kullanır.
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("intents_erased", result.intentsErased());
        body.put("deliveries_anonymized", result.deliveriesAnonymized());
        body.put("inbox_rows_deleted", result.inboxRowsDeleted());
        body.put("status", anyMutation ? "completed" : "no_op");
        if (result.ledgerRequestId() != null) {
            body.put("ledger_request_id", result.ledgerRequestId().toString());
        }
        if (result.dueAt() != null) {
            body.put("due_at", result.dueAt().toString());
        }
        return ResponseEntity.ok(body);
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

    /**
     * Codex {@code 019e4950} P1 absorb — log redaction whitelist.
     *
     * <p>Free-form {@code reason} field contains operator narrative
     * (e.g. "user requested via legal ticket #LK-2026-451"). This text
     * MUST NOT appear in INFO logs (Loki retention 7-14 gün +
     * developer dashboard surface). Audit trail retention is the
     * canonical record — {@code audit_event} table preserves the full
     * reason via PiiRedactor-controlled audit details. INFO log gets
     * a coarse classification enum instead.
     *
     * <p>Returns one of: SELF_SERVICE, LEGAL_REQUEST, COMPLIANCE_AUDIT,
     * ADMIN_INITIATED, UNKNOWN. Mapping is intentionally lossy.
     */
    static String classifyErasureReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        String lower = reason.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("self-service") || lower.contains("self_service")
            || lower.contains("kvkk-art-11")) {
            return "SELF_SERVICE";
        }
        if (lower.contains("legal") || lower.contains("ticket") || lower.contains("court")) {
            return "LEGAL_REQUEST";
        }
        if (lower.contains("compliance") || lower.contains("audit")
            || lower.contains("dpo") || lower.contains("kvkk")) {
            return "COMPLIANCE_AUDIT";
        }
        if (lower.contains("admin") || lower.contains("operator")) {
            return "ADMIN_INITIATED";
        }
        return "OTHER";
    }
}
