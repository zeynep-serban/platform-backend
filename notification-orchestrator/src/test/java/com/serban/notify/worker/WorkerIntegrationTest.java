package com.serban.notify.worker;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.adapter.SlackWebhookAdapter;
import com.serban.notify.adapter.SmtpAdapter;
import com.serban.notify.adapter.WebhookEgressAdapter;
import com.serban.notify.domain.DeadLetter;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.repository.DeadLetterRepository;
import com.serban.notify.delivery.DeliveryPlanService;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.repository.NotificationTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Worker integration test (Codex 019dfa47 Q8 absorb).
 *
 * <p>Exercises full PR4 pipeline:
 * <ul>
 *   <li>OutboxPoller: PENDING claim + dispatch + COMPLETED transition</li>
 *   <li>OutboxPoller: expired intent terminalize</li>
 *   <li>OutboxPoller: lease recovery (stale PROCESSING → PENDING)</li>
 *   <li>RetryWorker: due RETRY claim + redispatch + DELIVERED</li>
 *   <li>RetryWorker: max-attempts → DLQ + intent FAILED</li>
 *   <li>SKIP LOCKED concurrency safety (sanity-level)</li>
 * </ul>
 *
 * <p>{@code notify.dispatch.enabled=true} aktif; @Scheduled tetik manuel
 * yapılır (worker.tick() veya worker.claimAtomic() doğrudan çağrılır) — gerçek
 * sleep ile beklemek yerine.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
@TestPropertySource(properties = {
    "notify.dispatch.enabled=true",
    // Codex iter-1 P2 absorb: scheduling-enabled=false disables @Scheduled tick;
    // tests call runCycle() manually for deterministic isolation.
    "notify.worker.scheduling-enabled=false",
    "notify.worker.poll-delay-ms=3600000",
    "notify.worker.lease-duration-ms=10000",
    "notify.retry.max-attempts=2",
    "notify.retry.backoff-initial-ms=1000",
    "notify.retry.jitter-ratio=0.0",
    "notify.adapters.slack.default-webhook-url=https://hooks.slack/test",
    "notify.adapters.webhook.default-target-url=https://api.test/notify"
})
class WorkerIntegrationTest extends AbstractPostgresTest {

    @Autowired OutboxPoller outboxPoller;
    @Autowired RetryWorker retryWorker;
    @Autowired DeliveryPlanService planService;
    @Autowired NotificationTemplateRepository templateRepo;
    @Autowired NotificationIntentRepository intentRepo;
    @Autowired NotificationDeliveryRepository deliveryRepo;
    @Autowired DeadLetterRepository dlqRepo;

    @MockBean SmtpAdapter smtpAdapter;
    @MockBean SlackWebhookAdapter slackAdapter;
    @MockBean WebhookEgressAdapter webhookAdapter;

    @BeforeEach
    void seed() {
        when(smtpAdapter.channelKey()).thenReturn("email");
        when(slackAdapter.channelKey()).thenReturn("slack");
        when(webhookAdapter.channelKey()).thenReturn("webhook");

        if (templateRepo.findByTemplateIdAndVersionAndLocale("worker-test", 1, "tr-TR").isPresent()) {
            return;
        }
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateId("worker-test");
        t.setVersion(1);
        t.setLocale("tr-TR");
        t.setSubject("Sub");
        t.setBodyText("Hello");
        t.setActive(true);
        t.setExternalAllowed(true);  // PR5 absorb: tests use external recipient
        t.setCreatedBy("test");
        templateRepo.save(t);
    }

    @Test
    void outboxPollerClaimsAndDispatchesPendingToCompleted() {
        NotificationIntent intent = saveIntent("email", "user@x.com");
        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.delivered("<msg-1>")
        );

        outboxPoller.runCycle();

        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.COMPLETED);
        assertThat(reloaded.getTerminatedAt()).isNotNull();

        List<NotificationDelivery> ds = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(ds).hasSize(1);
        assertThat(ds.get(0).getStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
    }

    @Test
    void outboxPollerExpiresPastDueIntents() {
        NotificationIntent intent = saveIntent("email", "user@x.com");
        intent.setExpireAt(OffsetDateTime.now().minus(Duration.ofMinutes(10)));
        intentRepo.save(intent);

        outboxPoller.runCycle();

        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.EXPIRED);
        assertThat(reloaded.getTerminatedAt()).isNotNull();
    }

    @Test
    void outboxPollerLeaseRecoveryRevertsStalePROCESSING() {
        NotificationIntent intent = saveIntent("email", "user@x.com");
        intent.setStatus(NotificationIntent.Status.PROCESSING);
        intent.setProcessingStartedAt(OffsetDateTime.now().minus(Duration.ofMinutes(5)));
        intent.setProcessingLeaseUntil(OffsetDateTime.now().minus(Duration.ofMinutes(2)));
        intent.setProcessingOwner("dead-pod-1");
        intentRepo.save(intent);

        // smtpAdapter to deliver on poll (after recovery → claim again)
        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.delivered("<msg-recover>")
        );

        outboxPoller.runCycle();

        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        // recovered to PENDING then re-claimed and dispatched to COMPLETED
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.COMPLETED);
        assertThat(reloaded.getProcessingOwner()).isNull();  // cleared on terminal
    }

    @Test
    void retryWorkerSucceedsTransitionsIntentToCompleted() {
        // Codex iter-2 P0 absorb: RETRY → DELIVERED → intent COMPLETED
        // Setup: intent PROCESSING+lease=NULL, delivery RETRY due (next_retry_at past)
        // Codex iter-3 fix: use plan-derived recipient_hash (PiiRedactor),
        // not hardcoded — RetryWorker re-plans + matches by (channel, hash).
        NotificationIntent intent = saveIntent("email", "user@x.com");
        intent.setStatus(NotificationIntent.Status.PROCESSING);
        intentRepo.save(intent);

        DeliveryTarget plannedTarget = planService.plan(intent, null).get(0);

        NotificationDelivery delivery = createRetryDelivery(intent, plannedTarget,
            1, "503", OffsetDateTime.now().minus(Duration.ofSeconds(10)));

        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.delivered("<msg-recovered>")
        );

        retryWorker.runCycle();

        NotificationDelivery reloadedDelivery = deliveryRepo.findById(delivery.getId()).orElseThrow();
        assertThat(reloadedDelivery.getStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
        assertThat(reloadedDelivery.getDeliveredAt()).isNotNull();
        assertThat(reloadedDelivery.getAttemptCount()).isEqualTo(2);

        NotificationIntent reloadedIntent = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloadedIntent.getStatus()).isEqualTo(NotificationIntent.Status.COMPLETED);
        assertThat(reloadedIntent.getTerminatedAt()).isNotNull();
    }

    @Test
    void retryWorkerPermanentFailureTransitionsIntentToFailed() {
        // Codex iter-2 P0 absorb: RETRY → FAILED (permanent on retry) → intent FAILED
        NotificationIntent intent = saveIntent("email", "user@x.com");
        intent.setStatus(NotificationIntent.Status.PROCESSING);
        intentRepo.save(intent);

        DeliveryTarget plannedTarget = planService.plan(intent, null).get(0);

        NotificationDelivery delivery = createRetryDelivery(intent, plannedTarget,
            1, "503-prev", OffsetDateTime.now().minus(Duration.ofSeconds(10)));

        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.failed("550 hard bounce", 500)
        );

        retryWorker.runCycle();

        NotificationDelivery reloadedDelivery = deliveryRepo.findById(delivery.getId()).orElseThrow();
        assertThat(reloadedDelivery.getStatus()).isEqualTo(NotificationDelivery.Status.FAILED);
        assertThat(reloadedDelivery.getPermanentFailureAt()).isNotNull();

        NotificationIntent reloadedIntent = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloadedIntent.getStatus()).isEqualTo(NotificationIntent.Status.FAILED);
    }

    /**
     * Helper: build a RETRY delivery whose recipient_hash matches the planned
     * target's hash (Codex iter-3 fix — PiiRedactor pepper makes hashes
     * non-trivial; using hardcoded values caused DLQ target_reconstruction
     * mismatch).
     */
    private NotificationDelivery createRetryDelivery(
        NotificationIntent intent, DeliveryTarget target,
        int attemptCount, String failureReason, OffsetDateTime nextRetryAt
    ) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setIntentId(intent.getIntentId());
        delivery.setChannel(target.channel());
        delivery.setRecipientType(NotificationDelivery.RecipientType.valueOf(
            target.recipientType().equals("subscriber") ? "SUBSCRIBER"
                : target.recipientType().equals("external") ? "EXTERNAL" : "CHANNEL"
        ));
        delivery.setRecipientId(target.recipientId());
        delivery.setRecipientHash(target.recipientHash());
        delivery.setProvider(target.providerKey());
        delivery.setStatus(NotificationDelivery.Status.RETRY);
        delivery.setAttemptCount(attemptCount);
        delivery.setLastAttemptAt(OffsetDateTime.now().minus(Duration.ofMinutes(1)));
        delivery.setNextRetryAt(nextRetryAt);
        delivery.setFailureReason(failureReason);
        return deliveryRepo.save(delivery);
    }

    @Test
    void retryWorkerExhaustsAfterMaxAttemptsAndMovesToDlq() {
        // Setup: intent in PROCESSING with one RETRY delivery already at attempt_count=2
        // (max-attempts=2 in this test). RetryWorker should pre-check and DLQ immediately.
        NotificationIntent intent = saveIntent("email", "user@x.com");
        intent.setStatus(NotificationIntent.Status.PROCESSING);
        intent.setProcessingStartedAt(OffsetDateTime.now());
        intent.setProcessingLeaseUntil(OffsetDateTime.now().plus(Duration.ofMinutes(10)));
        intentRepo.save(intent);

        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setIntentId(intent.getIntentId());
        delivery.setChannel("email");
        delivery.setRecipientType(NotificationDelivery.RecipientType.EXTERNAL);
        delivery.setRecipientHash("rh-exhausted");
        delivery.setRecipientId(null);
        delivery.setProvider("smtp-default");
        delivery.setStatus(NotificationDelivery.Status.RETRY);
        delivery.setAttemptCount(2);  // == max-attempts
        delivery.setLastAttemptAt(OffsetDateTime.now().minus(Duration.ofMinutes(5)));
        delivery.setNextRetryAt(OffsetDateTime.now().minus(Duration.ofSeconds(10)));
        delivery.setFailureReason("503");
        deliveryRepo.save(delivery);

        retryWorker.runCycle();

        NotificationDelivery reloaded = deliveryRepo.findById(delivery.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationDelivery.Status.FAILED);
        assertThat(reloaded.getPermanentFailureAt()).isNotNull();

        // DLQ row created — filter by THIS delivery's id (other tests may
        // populate DLQ; @DirtiesContext recreates Spring context but PG
        // container is shared and persists rows across test methods).
        List<DeadLetter> allDlq = dlqRepo.findUnreplayed(
            org.springframework.data.domain.PageRequest.of(0, 50));
        List<DeadLetter> thisDlq = allDlq.stream()
            .filter(dl -> dl.getDeliveryId().equals(delivery.getId()))
            .toList();
        assertThat(thisDlq).hasSize(1);
        assertThat(thisDlq.get(0).getAttemptCount()).isEqualTo(2);

        // Intent transitioned to FAILED (only 1 delivery, all FAILED)
        NotificationIntent reloadedIntent = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloadedIntent.getStatus()).isEqualTo(NotificationIntent.Status.FAILED);
        assertThat(reloadedIntent.getTerminatedAt()).isNotNull();
    }

    @Test
    void claimAtomicPreventsDoubleClaimSameOwner() {
        // Sanity: SKIP LOCKED prevents the same row being claimed twice in one cycle
        // (we test single-pod behavior; multi-pod requires concurrent transactions).
        for (int i = 0; i < 3; i++) {
            saveIntent("email", "u" + i + "@x.com");
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime leaseUntil = now.plus(Duration.ofMinutes(1));

        int firstClaim = outboxPoller.claimAtomic(now, leaseUntil, UUID.randomUUID().toString());
        int secondClaim = outboxPoller.claimAtomic(now, leaseUntil, UUID.randomUUID().toString());

        // First claim grabs all 3; second cycle finds 0 (all PROCESSING)
        assertThat(firstClaim).isEqualTo(3);
        assertThat(secondClaim).isEqualTo(0);
    }

    private NotificationIntent saveIntent(String channel, String externalEmail) {
        NotificationIntent intent = new NotificationIntent();
        intent.setIntentId(UUID.randomUUID().toString());
        intent.setCorrelationId("trace-" + UUID.randomUUID().toString().substring(0, 8));
        intent.setOrgId("default");
        intent.setTopicKey("worker.test");
        intent.setSeverity(NotificationIntent.Severity.info);
        intent.setDataClassification(NotificationIntent.DataClassification.transactional);
        intent.setPayload(Map.of("user_name", "Halil"));
        intent.setTemplateId("worker-test");
        intent.setTemplateVersion(1);
        intent.setLocale("tr-TR");
        intent.setChannels(new String[] { channel });
        intent.setStatus(NotificationIntent.Status.PENDING);
        intent.setRecipientsSnapshot(List.of(
            new java.util.LinkedHashMap<>(Map.of(
                "type", "external", "email", externalEmail, "locale", "tr-TR"
            ))
        ));
        return intentRepo.save(intent);
    }
}
