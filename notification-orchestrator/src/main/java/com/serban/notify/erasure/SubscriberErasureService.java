package com.serban.notify.erasure;

import com.serban.notify.api.dto.AuditHistoryItemResponse;
import com.serban.notify.api.dto.AuditHistoryListResponse;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.NotificationIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Pageable repository overload follow-up'a kadar service-side pagination kullanılır
// (Codex `019e0c28` non-blocking nit: Pageable construction kullanılmadığı için
// kaldırıldı; future Page<> repository overload eklendiğinde geri gelecek).
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * SubscriberErasureService — KVKK §11 self-service erasure +
 * §13 right-to-information facade.
 *
 * <p>Faz 23.2.B closure (M3 stale audit 2026-05-09 — Codex thread
 * {@code 019e0c28} strategic finding). Admin scope ({@link ErasureService})
 * mevcut; subscriber self-service path bu sınıfla expose edilir.
 *
 * <p>Pipeline:
 * <ul>
 *   <li>{@link #listMyAudit} — kendi (orgId, subscriberId) intent'leri
 *       paged döner; PII zaten retention policy + redaction'a tabi.</li>
 *   <li>{@link #eraseMyAudit} — {@link ErasureService#eraseSubscriber}
 *       reuse; evidence_ref="self-service-kvkk-art-11"; reason ve
 *       evidence_ref ikisi de sabit constant (Codex `019e0c28` P1
 *       absorb: free-form caller text accept etme; PII leakage riski).</li>
 * </ul>
 *
 * <p>Authorization: caller tarafından {@code SubscriberIdentityGuard}
 * (controller seviyesi) JWT subject claim ↔ X-Subscriber-Id match
 * doğrulanır. Service seviyesinde defense-in-depth orgId+subscriberId
 * filter zaten {@code NotificationIntentRepository.findIntentsBySubscriber}
 * tarafından zorunlu kılınır.
 *
 * <p>Cross-tenant leak: tenancy invariant (orgId, subscriberId)
 * filter; başka subscriber'ın audit history'sine erişim mümkün değil.
 *
 * <p>Idempotent erasure: {@link ErasureService} tarafında zaten
 * idempotent (intent.payload=null check + delivery.recipient_id=null
 * check); ikinci çağrı no-op.
 */
@Service
public class SubscriberErasureService {

    private static final Logger log = LoggerFactory.getLogger(SubscriberErasureService.class);

    /**
     * Self-service erasure evidence_ref constant — admin scope
     * "ROLE_PRIVACY_OFFICER" path-based call'larından ayırt etmek için.
     * Audit row'larda ayrı kategori sağlar (KVKK legal review gerekirse).
     */
    public static final String SELF_SERVICE_EVIDENCE_REF = "self-service-kvkk-art-11";

    /**
     * Page size clamp — InboxService pattern (charter: protect against
     * resource exhaustion via massive page query).
     */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MIN_PAGE_SIZE = 1;

    private final ErasureService erasureService;
    private final NotificationIntentRepository intentRepo;

    public SubscriberErasureService(
        ErasureService erasureService,
        NotificationIntentRepository intentRepo
    ) {
        this.erasureService = erasureService;
        this.intentRepo = intentRepo;
    }

    /**
     * KVKK §13 right-to-information: subscriber'ın kendi audit history.
     *
     * <p>Returns: paged intent list (metadata only; PII zaten redacted
     * per retention policy + PiiRedactor). Newest-first.
     *
     * <p>Tenancy invariant: only (orgId, subscriberId) match rows. Cross-
     * tenant leak imkansız çünkü {@code findIntentsBySubscriber}
     * native query orgId + subscriberId filter zorunlu.
     *
     * @param orgId caller org_id (X-Org-Id header)
     * @param subscriberId caller subscriber_id (X-Subscriber-Id header,
     *     verified against JWT sub claim by controller guard)
     * @param page 0-indexed
     * @param size requested page size (clamped {@code [1, 100]})
     * @return paged audit history list
     */
    @Transactional(readOnly = true)
    public AuditHistoryListResponse listMyAudit(
        String orgId, String subscriberId, int page, int size
    ) {
        // Page size clamp (InboxService pattern; protect against DoS via massive page)
        int clampedSize = Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE, size));

        // findIntentsBySubscriber currently returns List (full match);
        // service-side pagination ile sub-list. Future optimization:
        // repository tarafına Page<> overload (Codex iter follow-up).
        // Mutable copy — repository List.of() may be immutable; sort() needs mutable
        List<NotificationIntent> all = new ArrayList<>(intentRepo.findIntentsBySubscriber(orgId, subscriberId));
        long total = all.size();

        // Newest-first ordering (createdAt DESC)
        all.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        int fromIndex = Math.min(page * clampedSize, all.size());
        int toIndex = Math.min(fromIndex + clampedSize, all.size());
        List<NotificationIntent> pageItems = all.subList(fromIndex, toIndex);

        List<AuditHistoryItemResponse> items = pageItems.stream()
            .map(AuditHistoryItemResponse::fromEntity)
            .toList();

        log.debug("KVKK self-service audit history list: orgId={} subscriberId={} total={} returned={}",
            orgId, subscriberId, total, items.size());

        return new AuditHistoryListResponse(items, total, page, clampedSize);
    }

    /**
     * KVKK §11 self-service right-to-erasure: subscriber'ın kendi
     * audit verisinin PII purge.
     *
     * <p>Pipeline (admin {@link ErasureService} reuse with explicit event
     * type): payload + recipients_snapshot + metadata + preference_override
     * + channel_routing null'lanır; delivery.recipient_id null (subscriber
     * link severance); recipient_hash KORUNUR (operational analytics; KVKK
     * pseudonymous boundary). Audit append:
     * {@link ErasureService#EVENT_SELF_SERVICE_ERASURE} event type
     * (admin scope `SUBSCRIBER_ERASURE_REQUEST`'tan ayrı — Codex
     * `019e0c28` P2 absorb).
     *
     * <p>Idempotent: ikinci çağrı = no-op (intent.payload zaten null).
     *
     * <p><strong>PII boundary (Codex `019e0c28` P1 absorb)</strong>:
     * free-form `reason` kabul edilmez. Self-service path her zaman
     * sabit {@link #SELF_SERVICE_EVIDENCE_REF} ile çalışır; subscriber
     * tarafından sağlanan kullanıcı metni log + audit'e girmez (PII
     * leakage riski). Legal review gerekirse evidence_ref ayrı
     * follow-up'ta enum/whitelist yapılabilir.
     *
     * @param orgId caller org_id
     * @param subscriberId caller subscriber_id (JWT match enforced upstream)
     * @return EraseResult (intentsErased + deliveriesAnonymized + inboxRowsDeleted)
     */
    @Transactional
    public ErasureService.EraseResult eraseMyAudit(String orgId, String subscriberId) {
        // Free-form reason kabul edilmez (Codex P1 absorb): PII risk.
        // Reason ve evidence_ref ikisi de sabit constant.
        log.info("KVKK self-service erasure invoke: orgId={} subscriberId={}",
            orgId, subscriberId);

        return erasureService.eraseSubscriber(
            new ErasureService.EraseRequest(
                orgId,
                subscriberId,
                SELF_SERVICE_EVIDENCE_REF,
                SELF_SERVICE_EVIDENCE_REF
            ),
            ErasureService.EVENT_SELF_SERVICE_ERASURE
        );
    }
}
