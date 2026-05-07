package com.serban.notify.repository;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.SubscriberPreference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class SubscriberPreferenceRepositoryTest extends AbstractPostgresTest {

    @Autowired
    SubscriberPreferenceRepository repo;

    @Test
    void persistAndLookupBySubscriber() {
        SubscriberPreference p1 = new SubscriberPreference();
        p1.setSubscriberId("1204");
        p1.setOrgId("default");
        p1.setTopicKey("auth.password-reset");
        p1.setChannel("email");
        p1.setEnabled(true);
        repo.save(p1);

        SubscriberPreference p2 = new SubscriberPreference();
        p2.setSubscriberId("1204");
        p2.setOrgId("default");
        p2.setTopicKey("system.update");
        p2.setChannel("email");
        p2.setEnabled(false);
        repo.save(p2);

        List<SubscriberPreference> prefs = repo.findBySubscriberIdAndOrgId("1204", "default");
        assertThat(prefs).hasSize(2);

        SubscriberPreference resetPref = prefs.stream()
            .filter(p -> "auth.password-reset".equals(p.getTopicKey()))
            .findFirst()
            .orElseThrow();
        assertThat(resetPref.isEnabled()).isTrue();
        assertThat(resetPref.isBypassForCritical()).isTrue();

        SubscriberPreference updatePref = prefs.stream()
            .filter(p -> "system.update".equals(p.getTopicKey()))
            .findFirst()
            .orElseThrow();
        assertThat(updatePref.isEnabled()).isFalse();
    }

    /**
     * Codex 019df86f post-impl bulgu #3 absorb: cross-tenant collision testi.
     * Aynı subscriber_id farklı org'larda aynı topic+channel preference yazınca
     * UNIQUE constraint çakışmamalı (D41 multi-tenant boundary).
     */
    @Test
    void crossOrgSameSubscriberSameTopicChannelAllowed() {
        SubscriberPreference orgA = new SubscriberPreference();
        orgA.setSubscriberId("1204");
        orgA.setOrgId("org-a");
        orgA.setTopicKey("auth.password-reset");
        orgA.setChannel("email");
        orgA.setEnabled(true);
        repo.save(orgA);

        SubscriberPreference orgB = new SubscriberPreference();
        orgB.setSubscriberId("1204");
        orgB.setOrgId("org-b");
        orgB.setTopicKey("auth.password-reset");
        orgB.setChannel("email");
        orgB.setEnabled(false);

        // Should NOT throw — UNIQUE index includes org_id (Codex fix)
        assertThat(repo.save(orgB).getId()).isNotNull();

        List<SubscriberPreference> orgAList = repo.findBySubscriberIdAndOrgId("1204", "org-a");
        List<SubscriberPreference> orgBList = repo.findBySubscriberIdAndOrgId("1204", "org-b");
        assertThat(orgAList).hasSize(1);
        assertThat(orgBList).hasSize(1);
        assertThat(orgAList.get(0).isEnabled()).isTrue();
        assertThat(orgBList.get(0).isEnabled()).isFalse();
    }

    // ── Faz 23.6 PR-A1 — restore-defaults ─────────────────────────────────

    @Test
    @Transactional
    void deleteAllByOrgIdAndSubscriberId_clearsCallerRows() {
        // Use a unique (org, subscriber) tuple so we never collide with rows
        // persisted by other tests in the same Postgres container — the
        // unique constraint uq_pref_subscriber_org_topic_channel would
        // otherwise fail on shared schema state across @DirtiesContext
        // recreations (CI Testcontainers reuses the database file).
        SubscriberPreference row1 = newPref("restore-org", "rest-1204", "auth.password-reset", "email", true);
        SubscriberPreference row2 = newPref("restore-org", "rest-1204", "report.export.ready", "email", false);
        SubscriberPreference row3 = newPref("restore-org", "rest-1204", null, "sms", false);
        repo.save(row1);
        repo.save(row2);
        repo.save(row3);
        assertThat(repo.findBySubscriberIdAndOrgId("rest-1204", "restore-org")).hasSize(3);

        int deleted = repo.deleteAllByOrgIdAndSubscriberId("restore-org", "rest-1204");

        assertThat(deleted).isEqualTo(3);
        assertThat(repo.findBySubscriberIdAndOrgId("rest-1204", "restore-org")).isEmpty();
    }

    @Test
    @Transactional
    void deleteAllByOrgIdAndSubscriberId_isIdempotent_returnsZeroOnEmpty() {
        int deleted = repo.deleteAllByOrgIdAndSubscriberId("restore-org", "ghost-subscriber");
        assertThat(deleted).isEqualTo(0);
    }

    @Test
    @Transactional
    void deleteAllByOrgIdAndSubscriberId_onlyTouchesCallerRows() {
        SubscriberPreference orgA = newPref("restore-iso-a", "iso-1204", "auth.password-reset", "email", true);
        SubscriberPreference orgB = newPref("restore-iso-b", "iso-1204", "auth.password-reset", "email", true);
        repo.save(orgA);
        repo.save(orgB);

        int deleted = repo.deleteAllByOrgIdAndSubscriberId("restore-iso-a", "iso-1204");

        assertThat(deleted).isEqualTo(1);
        assertThat(repo.findBySubscriberIdAndOrgId("iso-1204", "restore-iso-a")).isEmpty();
        assertThat(repo.findBySubscriberIdAndOrgId("iso-1204", "restore-iso-b")).hasSize(1);
    }

    // ── Faz 23.6 PR-A2 — same-channel exact override delete ──────────────

    @Test
    @Transactional
    void deleteSameChannelExactOverrides_removesExactsButPreservesWildcardAndOtherChannels() {
        // The mute-channel action writes a (topic=null, channel=email)
        // wildcard deny rule and then drops same-channel exact overrides
        // so the wildcard wins resolver precedence. This test pins:
        //  - same-channel exact overrides ARE deleted
        //  - the same-channel wildcard row is preserved
        //  - other-channel rows are untouched
        SubscriberPreference wildcardEmail =
            newPref("mute-org", "mute-1204", null, "email", false);
        SubscriberPreference exactEmail1 =
            newPref("mute-org", "mute-1204", "report.export.ready", "email", true);
        SubscriberPreference exactEmail2 =
            newPref("mute-org", "mute-1204", "auth.password-reset", "email", false);
        SubscriberPreference exactSms =
            newPref("mute-org", "mute-1204", "auth.password-reset", "sms", true);
        repo.save(wildcardEmail);
        repo.save(exactEmail1);
        repo.save(exactEmail2);
        repo.save(exactSms);

        int deleted = repo.deleteSameChannelExactOverrides("mute-org", "mute-1204", "email");

        assertThat(deleted).isEqualTo(2);
        java.util.List<SubscriberPreference> remaining =
            repo.findBySubscriberIdAndOrgId("mute-1204", "mute-org");
        assertThat(remaining).hasSize(2);
        assertThat(remaining).extracting(SubscriberPreference::getTopicKey)
            .containsExactlyInAnyOrder(null, "auth.password-reset");
        assertThat(remaining).extracting(SubscriberPreference::getChannel)
            .containsExactlyInAnyOrder("email", "sms");
    }

    @Test
    @Transactional
    void deleteSameChannelExactOverrides_isIdempotent_returnsZeroOnEmpty() {
        int deleted = repo.deleteSameChannelExactOverrides("mute-org", "mute-ghost", "email");
        assertThat(deleted).isEqualTo(0);
    }

    @Test
    @Transactional
    void findTopicWideAllowRows_returnsTopicWithChannelNullAndEnabled() {
        // Faz 23.6 PR-A2 P1 absorb: muteChannel needs to enumerate the
        // topic-wide allow rows (topic IS NOT NULL, channel IS NULL,
        // enabled=true) so it can shadow them with a channel-specific
        // exact deny.
        SubscriberPreference topicWideAllow1 = newPref(
            "shadow-org", "shadow-1204", "auth.password-reset", null, true
        );
        SubscriberPreference topicWideAllow2 = newPref(
            "shadow-org", "shadow-1204", "report.export.ready", null, true
        );
        // Disabled topic-wide row — should NOT be returned (we only
        // shadow allow rows; deny rows already match the resolver).
        SubscriberPreference topicWideDeny = newPref(
            "shadow-org", "shadow-1204", "marketing.weekly", null, false
        );
        // Channel-wildcard row — should NOT be returned.
        SubscriberPreference channelWildcard = newPref(
            "shadow-org", "shadow-1204", null, "email", false
        );
        // Exact row — should NOT be returned.
        SubscriberPreference exact = newPref(
            "shadow-org", "shadow-1204", "billing.alert", "email", true
        );
        repo.save(topicWideAllow1);
        repo.save(topicWideAllow2);
        repo.save(topicWideDeny);
        repo.save(channelWildcard);
        repo.save(exact);

        java.util.List<SubscriberPreference> rows =
            repo.findTopicWideAllowRows("shadow-org", "shadow-1204");

        assertThat(rows).extracting(SubscriberPreference::getTopicKey)
            .containsExactlyInAnyOrder("auth.password-reset", "report.export.ready");
    }

    @Test
    @Transactional
    void findTopicWideAllowRows_returnsEmpty_whenSubscriberHasNoTopicWideAllows() {
        java.util.List<SubscriberPreference> rows =
            repo.findTopicWideAllowRows("shadow-org", "no-rules-1204");
        assertThat(rows).isEmpty();
    }

    @Test
    @Transactional
    void deleteSameChannelExactOverrides_preservesOtherSubscribersAndOrgs() {
        SubscriberPreference selfExact =
            newPref("mute-iso-a", "mute-1204", "auth.password-reset", "email", true);
        SubscriberPreference otherSubExact =
            newPref("mute-iso-a", "mute-other", "auth.password-reset", "email", true);
        SubscriberPreference otherOrgExact =
            newPref("mute-iso-b", "mute-1204", "auth.password-reset", "email", true);
        repo.save(selfExact);
        repo.save(otherSubExact);
        repo.save(otherOrgExact);

        int deleted = repo.deleteSameChannelExactOverrides("mute-iso-a", "mute-1204", "email");

        assertThat(deleted).isEqualTo(1);
        assertThat(repo.findBySubscriberIdAndOrgId("mute-1204", "mute-iso-a")).isEmpty();
        assertThat(repo.findBySubscriberIdAndOrgId("mute-other", "mute-iso-a")).hasSize(1);
        assertThat(repo.findBySubscriberIdAndOrgId("mute-1204", "mute-iso-b")).hasSize(1);
    }

    private SubscriberPreference newPref(
        String orgId, String subscriberId, String topicKey, String channel, boolean enabled
    ) {
        SubscriberPreference p = new SubscriberPreference();
        p.setOrgId(orgId);
        p.setSubscriberId(subscriberId);
        p.setTopicKey(topicKey);
        p.setChannel(channel);
        p.setEnabled(enabled);
        return p;
    }
}
