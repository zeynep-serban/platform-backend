package com.serban.notify.api;

import com.serban.notify.api.dto.AuditHistoryListResponse;
import com.serban.notify.erasure.ErasureService;
import com.serban.notify.erasure.SubscriberErasureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * SubscriberErasureController — KVKK §11 (right-to-erasure) +
 * §13 (right-to-information) self-service endpoints.
 *
 * <p>Faz 23.2.B closure (M3 stale audit 2026-05-09 — Codex thread
 * {@code 019e0c28} strategic finding): admin scope erasure
 * ({@code AdminErasureController}) production'da source-ready/live;
 * subscriber self-service path Codex iter-2 verdict ile <strong>gerçek
 * pending</strong> olarak işaretlendi (PR #447 audit doc). Bu
 * controller subscriber'ın kendi audit history'sini görüntüleme +
 * silme yetkisini sağlar.
 *
 * <p>Identity model: caller passes {@code X-Org-Id} + {@code
 * X-Subscriber-Id} headers; subject claim (JWT) {@code sub} =
 * subscriberId match {@code SubscriberIdentityGuard.requireMatchOrThrow}
 * tarafından doğrulanır (403 on mismatch). Org access
 * {@code NotifyOrgAccessGuard.requireOrgAccessOrThrow}.
 *
 * <p>Cross-tenant prevention: tenancy invariant `(orgId, subscriberId)`
 * filter; başka subscriber'ın audit history'sine erişim mümkün değil.
 * GET empty list döner; DELETE no-op counter döner. Identity mismatch
 * (X-Subscriber-Id != JWT sub claim) {@code SubscriberIdentityGuard}
 * tarafından 403'e dönüştürülür.
 *
 * <p>Pipeline:
 * <ul>
 *   <li>{@code DELETE /api/v1/notify/audit/me} → admin {@link ErasureService}
 *       reuse with explicit event type {@link ErasureService#EVENT_SELF_SERVICE_ERASURE}:
 *       payload + recipients_snapshot + metadata + preference_override
 *       null'lanır; recipient_hash KORUNUR (operational analytics).
 *       Audit append: {@code SUBSCRIBER_SELF_ERASURE_REQUEST} event type
 *       (admin scope `SUBSCRIBER_ERASURE_REQUEST`'tan ayrı — audit
 *       reporting netliği için).</li>
 *   <li>{@code GET /api/v1/notify/audit/me} → kendi audit row'larını
 *       paged döner (KVKK §13 right-to-information).</li>
 * </ul>
 *
 * <p><strong>PII boundary (Codex `019e0c28` P1 absorb)</strong>:
 * Free-form `reason` kabul edilmez. Self-service path her zaman
 * sabit {@code self-service-kvkk-art-11} ile çalışır; user-provided
 * metin log/audit'e girmez (PII leakage riski). Legal review gerekirse
 * evidence_ref ayrı follow-up'ta enum/whitelist yapılabilir.
 *
 * <p>Idempotent: ikinci DELETE çağrısı = no-op (already erased).
 *
 * <p>R2 KVKK legal review: admin scope için ETA 2026-05-25; self-service
 * scope aynı veri modeli ile aynı legal scope kapsamı (per-subscriber
 * own data; legal review extension follow-up).
 */
@RestController
@RequestMapping("/api/v1/notify/audit")
@Validated
@Tag(name = "Subscriber — KVKK Self-Service",
     description = "Subscriber self-service KVKK §11 erasure + §13 right-to-information")
public class SubscriberErasureController {

    private static final Logger log = LoggerFactory.getLogger(SubscriberErasureController.class);

    private final SubscriberErasureService subscriberErasureService;
    private final SubscriberIdentityGuard subscriberIdentityGuard;
    private final NotifyOrgAccessGuard notifyOrgAccessGuard;

    public SubscriberErasureController(
        SubscriberErasureService subscriberErasureService,
        SubscriberIdentityGuard subscriberIdentityGuard,
        NotifyOrgAccessGuard notifyOrgAccessGuard
    ) {
        this.subscriberErasureService = subscriberErasureService;
        this.subscriberIdentityGuard = subscriberIdentityGuard;
        this.notifyOrgAccessGuard = notifyOrgAccessGuard;
    }

    /**
     * GET /api/v1/notify/audit/me — kendi audit history (KVKK §13).
     *
     * <p>Filtre: orgId + subscriberId scope. Cross-tenant leak yok;
     * sadece (orgId, subscriberId) match audit row'ları döner.
     *
     * <p>Newest-first ordering (created_at DESC). Default page size 20,
     * service-side clamped {@code [1, 100]}.
     *
     * @param size requested page size
     */
    @GetMapping("/me")
    @Operation(
        summary = "List my notification audit history",
        description = "KVKK §13 right-to-information: subscriber's own audit "
            + "trail (intent + delivery row metadata; PII redacted per "
            + "retention policy)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paged audit history"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Authentication required (JWT missing/invalid)"),
        @ApiResponse(responseCode = "403", description = "Identity mismatch (X-Subscriber-Id != JWT sub claim)")
    })
    public ResponseEntity<AuditHistoryListResponse> listMyAudit(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId,
        @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
        @RequestParam(name = "size", defaultValue = "20") @Min(1) int size
    ) {
        // Defense-in-depth: org access guard önce; subscriber identity guard
        // sonra (mevcut InboxController pattern matches PR-5.2.1 Codex
        // 019e0675 AGREE iter-5).
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);

        AuditHistoryListResponse response = subscriberErasureService.listMyAudit(
            callerOrgId, subscriberId, page, size
        );
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/v1/notify/audit/me — kendi audit history erase (KVKK §11).
     *
     * <p>Pipeline (admin {@link ErasureService} reuse):
     * <ol>
     *   <li>Find all intents for (orgId, subscriberId)</li>
     *   <li>For each intent: payload + recipients_snapshot + metadata +
     *       preference_override null'lanır (PII surface)</li>
     *   <li>For each delivery: recipient_id null (subscriber link severance);
     *       recipient_hash KORUNUR (operational analytics; KVKK pseudonymous)</li>
     *   <li>Audit append: SUBSCRIBER_SELF_ERASURE_REQUEST event
     *       (append-only RULE — silinmez)</li>
     * </ol>
     *
     * <p>Idempotent: ikinci çağrı = no-op (already erased; status="no_op").
     *
     * <p>Free-form `reason` field accept edilmez; reason ve evidence_ref
     * ikisi de sabit {@code "self-service-kvkk-art-11"} (Codex
     * `019e0c28` P1 absorb: PII leakage riski).
     */
    @DeleteMapping("/me")
    @Operation(
        summary = "Erase my notification audit data",
        description = "KVKK §11 self-service right-to-erasure: subscriber's "
            + "own intents/deliveries PII null'lanır; recipient_hash "
            + "KORUNUR. Idempotent."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Erasure complete (idempotent)"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Identity mismatch")
    })
    public ResponseEntity<Map<String, Object>> eraseMyAudit(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId
    ) {
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);

        // Codex `019e0c28` P1 absorb: free-form `reason` kabul edilmez (PII
        // leakage riski). Reason ve evidence_ref sabit
        // `self-service-kvkk-art-11`; log surface minimal.
        log.info("KVKK self-service erasure request: orgId={} subscriberId={}",
            callerOrgId, subscriberId);

        ErasureService.EraseResult result = subscriberErasureService.eraseMyAudit(
            callerOrgId, subscriberId
        );

        boolean anyMutation = result.intentsErased() > 0
            || result.deliveriesAnonymized() > 0
            || result.inboxRowsDeleted() > 0;
        return ResponseEntity.ok(Map.of(
            "intents_erased", result.intentsErased(),
            "deliveries_anonymized", result.deliveriesAnonymized(),
            "inbox_rows_deleted", result.inboxRowsDeleted(),
            "status", anyMutation ? "completed" : "no_op",
            "evidence_ref", "self-service-kvkk-art-11"
        ));
    }
}
