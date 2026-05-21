package com.serban.notify.worker;

import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Intent terminal status resolver (Codex 019dfa47 Q4 REVISE absorb).
 *
 * <p>Intent terminal state computation rules:
 * <ul>
 *   <li>Tüm delivery {@code DELIVERED} → {@link NotificationIntent.Status#COMPLETED}</li>
 *   <li>En az 1 {@code DELIVERED} + en az 1 terminal failure (FAILED/BOUNCED) →
 *       {@link NotificationIntent.Status#PARTIALLY_FAILED}</li>
 *   <li>Hiç {@code DELIVERED} yok, tümü terminal non-delivered →
 *       {@link NotificationIntent.Status#FAILED}</li>
 *   <li>Hâlâ {@code RETRY} delivery var → no terminal transition (intent
 *       PROCESSING kalır; PR4 worker re-attempt edecek)</li>
 *   <li>Hâlâ {@code PENDING} delivery var → no terminal transition</li>
 * </ul>
 *
 * <p>{@link NotificationIntent.Status#EXPIRED} ayrı zaman politikası ile set
 * edilir (provider failure DEĞİL); bu resolver dokunmaz.
 */
@Component
public class IntentStatusResolver {

    /**
     * Resolve terminal status from a list of deliveries.
     *
     * @return target Status if terminal transition possible; {@code null} if
     *         intent must remain PROCESSING (RETRY/PENDING outstanding)
     */
    public NotificationIntent.Status resolve(List<NotificationDelivery> deliveries) {
        if (deliveries == null || deliveries.isEmpty()) {
            return null;  // no deliveries yet — intent in early PROCESSING
        }

        boolean anyOutstanding = false;
        boolean anyDelivered = false;
        boolean anyTerminalFailure = false;

        for (NotificationDelivery d : deliveries) {
            switch (d.getStatus()) {
                case DELIVERED -> anyDelivered = true;
                // Faz 23.7 M7 T4.2 PR-W2.5+W2.6 (Codex 019e4a3d iter-2 P1
                // absorb): BLOCKED_NO_PUSH_ENDPOINT terminal-failure set'e
                // eklendi — yoksa push-only + 0 endpoint zombie düzelse de
                // multi-channel (email DELIVERED + push BLOCKED_NO_PUSH_ENDPOINT)
                // yanlışlıkla COMPLETED dönüyordu (beklenen PARTIALLY_FAILED).
                // BLOCKED_BY_SUPPRESSION da aynı eksiklik vardı (Faz 23.8
                // M7 T4.3.b scope), aynı satırda eklendi.
                case FAILED, BOUNCED, BLOCKED_BY_PREFERENCE,
                     BLOCKED_BY_AUTHZ, BLOCKED_BY_IDEMPOTENCY,
                     BLOCKED_EXTERNAL_NOT_ALLOWED,
                     BLOCKED_BY_SUPPRESSION,
                     BLOCKED_NO_PUSH_ENDPOINT -> anyTerminalFailure = true;
                // Faz 23.4 PR-F: ACCEPTED is provider-queued waiting for DLR
                // terminal verdict; treated as outstanding (intent stays PROCESSING).
                case RETRY, PENDING, ACCEPTED -> anyOutstanding = true;
            }
        }

        if (anyOutstanding) {
            return null;  // RETRY or PENDING — keep PROCESSING
        }
        if (anyDelivered && anyTerminalFailure) {
            return NotificationIntent.Status.PARTIALLY_FAILED;
        }
        if (anyDelivered) {
            return NotificationIntent.Status.COMPLETED;
        }
        // No DELIVERED + no outstanding → FAILED
        return NotificationIntent.Status.FAILED;
    }
}
