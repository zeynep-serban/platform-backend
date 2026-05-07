package com.serban.notify.repository;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.projection.DeliveryLogRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration tests for delivery log search queries
 * (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE: real DB run is mandatory
 * to validate the JOIN org boundary, the {@code activityAt = COALESCE(...)}
 * expression, and the index plan. JPQL constructor projection alias
 * brittleness only surfaces against Postgres.
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class NotificationDeliveryRepositoryAdminSearchIT extends AbstractPostgresTest {

    @Autowired NotificationDeliveryRepository deliveryRepo;
    @Autowired NotificationIntentRepository intentRepo;

    @Test
    void searchAdmin_isolatesByOrg() {
        OffsetDateTime now = OffsetDateTime.now();
        NotificationIntent orgA = persistIntent("orgA");
        NotificationIntent orgB = persistIntent("orgB");
        persistDelivery(orgA, "email", "smtp", NotificationDelivery.Status.DELIVERED, now);
        persistDelivery(orgB, "email", "smtp", NotificationDelivery.Status.FAILED, now);

        Page<DeliveryLogRow> resultsA = deliveryRepo.searchAdminDeliveryLog(
            "orgA", null, null, null, now.minusHours(1), now.plusHours(1),
            PageRequest.of(0, 20)
        );
        assertThat(resultsA.getContent()).hasSize(1);
        assertThat(resultsA.getContent().get(0).orgId()).isEqualTo("orgA");
    }

    @Test
    void searchAdmin_filtersByStatus() {
        OffsetDateTime now = OffsetDateTime.now();
        NotificationIntent intent = persistIntent("orgC");
        persistDelivery(intent, "email", "smtp", NotificationDelivery.Status.DELIVERED, now);
        NotificationDelivery failed = persistDelivery(
            intent, "sms", "netgsm", NotificationDelivery.Status.FAILED, now
        );
        // Force activityAt via permanentFailureAt
        failed.setPermanentFailureAt(now);
        deliveryRepo.save(failed);

        Page<DeliveryLogRow> failedRows = deliveryRepo.searchAdminDeliveryLog(
            "orgC", NotificationDelivery.Status.FAILED, null, null,
            now.minusHours(1), now.plusHours(1), PageRequest.of(0, 20)
        );
        assertThat(failedRows.getContent()).hasSize(1);
        assertThat(failedRows.getContent().get(0).status())
            .isEqualTo(NotificationDelivery.Status.FAILED);
    }

    @Test
    void searchAdmin_filtersByChannelAndProvider() {
        OffsetDateTime now = OffsetDateTime.now();
        NotificationIntent intent = persistIntent("orgD");
        persistDelivery(intent, "email", "smtp", NotificationDelivery.Status.DELIVERED, now);
        persistDelivery(intent, "sms", "netgsm", NotificationDelivery.Status.DELIVERED, now);

        Page<DeliveryLogRow> smsRows = deliveryRepo.searchAdminDeliveryLog(
            "orgD", null, "sms", null,
            now.minusHours(1), now.plusHours(1), PageRequest.of(0, 20)
        );
        assertThat(smsRows.getContent()).hasSize(1);
        assertThat(smsRows.getContent().get(0).channel()).isEqualTo("sms");

        Page<DeliveryLogRow> netgsmRows = deliveryRepo.searchAdminDeliveryLog(
            "orgD", null, null, "netgsm",
            now.minusHours(1), now.plusHours(1), PageRequest.of(0, 20)
        );
        assertThat(netgsmRows.getContent()).hasSize(1);
        assertThat(netgsmRows.getContent().get(0).provider()).isEqualTo("netgsm");
    }

    @Test
    void searchAdmin_orderByActivityAtDesc_lateDlrSurfaceFirst() {
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(5);
        NotificationIntent intent = persistIntent("orgE");

        // Earlier created_at, but DLR terminalised it later → activityAt LATER
        NotificationDelivery older = persistDelivery(
            intent, "sms", "netgsm", NotificationDelivery.Status.FAILED, t0
        );
        older.setPermanentFailureAt(t0.plusHours(2));
        deliveryRepo.save(older);

        // Later created_at, terminalised immediately
        NotificationDelivery newer = persistDelivery(
            intent, "email", "smtp", NotificationDelivery.Status.DELIVERED, t0.plusHours(1)
        );
        newer.setDeliveredAt(t0.plusHours(1));
        deliveryRepo.save(newer);

        // The search window is wide enough to cover both rows.
        Page<DeliveryLogRow> rows = deliveryRepo.searchAdminDeliveryLog(
            "orgE", null, null, null,
            t0.minusHours(1), t0.plusHours(3), PageRequest.of(0, 20)
        );
        assertThat(rows.getContent()).hasSize(2);
        // The OLDER row has the LATER activityAt because of permanentFailureAt;
        // it should come first under activityAt DESC.
        assertThat(rows.getContent().get(0).deliveryId()).isEqualTo(older.getId());
        assertThat(rows.getContent().get(1).deliveryId()).isEqualTo(newer.getId());
    }

    @Test
    void findDeliveryLogByIntentIdAndOrgId_isolatesByOrgEvenWhenIntentIdMatches() {
        OffsetDateTime now = OffsetDateTime.now();
        // Two intents from different orgs; their intent_ids are unique by
        // construction (UUID), so the actual cross-org leak protection here
        // is the JOIN org_id predicate. We assert the predicate is enforced.
        NotificationIntent orgA = persistIntent("orgF");
        NotificationIntent orgB = persistIntent("orgG");
        persistDelivery(orgA, "email", "smtp", NotificationDelivery.Status.DELIVERED, now);

        // Querying orgA's intent under orgB's name returns empty.
        Page<DeliveryLogRow> wrongOrg = deliveryRepo.findDeliveryLogByIntentIdAndOrgId(
            orgA.getIntentId(), "orgG", PageRequest.of(0, 20)
        );
        assertThat(wrongOrg.getContent()).isEmpty();

        Page<DeliveryLogRow> correctOrg = deliveryRepo.findDeliveryLogByIntentIdAndOrgId(
            orgA.getIntentId(), "orgF", PageRequest.of(0, 20)
        );
        assertThat(correctOrg.getContent()).hasSize(1);

        // Sanity: orgB intent didn't bleed in either.
        Page<DeliveryLogRow> orgBLookup = deliveryRepo.findDeliveryLogByIntentIdAndOrgId(
            orgB.getIntentId(), "orgG", PageRequest.of(0, 20)
        );
        assertThat(orgBLookup.getContent()).isEmpty();
    }

    private NotificationIntent persistIntent(String orgId) {
        NotificationIntent intent = new NotificationIntent();
        intent.setIntentId(UUID.randomUUID().toString());
        intent.setOrgId(orgId);
        intent.setTopicKey("test.topic");
        intent.setSeverity(NotificationIntent.Severity.info);
        intent.setDataClassification(NotificationIntent.DataClassification.transactional);
        intent.setPayload(Map.of("k", "v"));
        intent.setTemplateId("test-template");
        intent.setLocale("tr-TR");
        intent.setChannels(new String[]{"email"});
        return intentRepo.save(intent);
    }

    private NotificationDelivery persistDelivery(
        NotificationIntent intent,
        String channel,
        String provider,
        NotificationDelivery.Status status,
        OffsetDateTime createdAt
    ) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setIntentId(intent.getIntentId());
        delivery.setChannel(channel);
        delivery.setRecipientType(NotificationDelivery.RecipientType.SUBSCRIBER);
        delivery.setRecipientId("recipient-" + UUID.randomUUID());
        delivery.setRecipientHash("hash-" + UUID.randomUUID().toString().substring(0, 12));
        delivery.setProvider(provider);
        delivery.setProviderMsgId(provider + "-" + UUID.randomUUID().toString().substring(0, 8));
        delivery.setStatus(status);
        return deliveryRepo.save(delivery);
    }
}
