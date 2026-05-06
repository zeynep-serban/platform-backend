package com.serban.notify.provider;

import com.serban.notify.domain.WebhookHmacKey;
import com.serban.notify.repository.WebhookHmacKeyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WebhookHmacKeyRegistry unit test (Faz 23.2 PR-A — Codex 019dfae5 Q3).
 */
class WebhookHmacKeyRegistryTest {

    @Test
    void disabledRegistryFallsBackToLegacy() {
        WebhookHmacKeyRepository repo = mock(WebhookHmacKeyRepository.class);
        Environment env = new MockEnvironment();

        WebhookHmacKeyRegistry registry = new WebhookHmacKeyRegistry(
            repo, env, "legacy-secret-from-config", false
        );

        var active = registry.resolveActive();
        assertThat(active.kid()).isEqualTo("legacy");
        assertThat(active.secret()).isEqualTo("legacy-secret-from-config");
    }

    @Test
    void enabledRegistryWithActiveKeyResolves() {
        WebhookHmacKeyRepository repo = mock(WebhookHmacKeyRepository.class);
        WebhookHmacKey key = new WebhookHmacKey();
        key.setKid("k1");
        key.setStatus(WebhookHmacKey.Status.ACTIVE);
        key.setSecretRef("vault:kv/platform/notify/webhook/k1");
        when(repo.findByStatus(WebhookHmacKey.Status.ACTIVE)).thenReturn(Optional.of(key));

        MockEnvironment env = new MockEnvironment();
        env.setProperty("NOTIFY_WEBHOOK_HMAC_KEY_K1", "rotated-secret-k1");

        WebhookHmacKeyRegistry registry = new WebhookHmacKeyRegistry(
            repo, env, "legacy-secret", true
        );

        var active = registry.resolveActive();
        assertThat(active.kid()).isEqualTo("k1");
        assertThat(active.secret()).isEqualTo("rotated-secret-k1");
    }

    @Test
    void enabledRegistryWithoutActiveKeyFallsBackToLegacy() {
        WebhookHmacKeyRepository repo = mock(WebhookHmacKeyRepository.class);
        when(repo.findByStatus(WebhookHmacKey.Status.ACTIVE)).thenReturn(Optional.empty());

        WebhookHmacKeyRegistry registry = new WebhookHmacKeyRegistry(
            repo, new MockEnvironment(), "legacy-secret", true
        );

        var active = registry.resolveActive();
        assertThat(active.kid()).isEqualTo("legacy");
        assertThat(active.secret()).isEqualTo("legacy-secret");
    }

    @Test
    void enabledRegistryWithUnresolvableSecretFallsBackToLegacy() {
        WebhookHmacKeyRepository repo = mock(WebhookHmacKeyRepository.class);
        WebhookHmacKey key = new WebhookHmacKey();
        key.setKid("k1");
        key.setStatus(WebhookHmacKey.Status.ACTIVE);
        key.setSecretRef("vault:kv/platform/notify/webhook/k1");
        when(repo.findByStatus(WebhookHmacKey.Status.ACTIVE)).thenReturn(Optional.of(key));

        // No env var set for k1 → secret unresolvable
        MockEnvironment env = new MockEnvironment();

        WebhookHmacKeyRegistry registry = new WebhookHmacKeyRegistry(
            repo, env, "legacy-secret", true
        );

        var active = registry.resolveActive();
        assertThat(active.kid()).isEqualTo("legacy");
        assertThat(active.secret()).isEqualTo("legacy-secret");
    }

    @Test
    void resolveSecretForKidLooksUpRetiredKeys() {
        WebhookHmacKeyRepository repo = mock(WebhookHmacKeyRepository.class);
        WebhookHmacKey key = new WebhookHmacKey();
        key.setKid("k0-old");
        key.setStatus(WebhookHmacKey.Status.RETIRED);
        key.setSecretRef("vault:kv/platform/notify/webhook/k0-old");
        when(repo.findByKid("k0-old")).thenReturn(Optional.of(key));

        MockEnvironment env = new MockEnvironment();
        env.setProperty("NOTIFY_WEBHOOK_HMAC_KEY_K0_OLD", "old-secret");

        WebhookHmacKeyRegistry registry = new WebhookHmacKeyRegistry(
            repo, env, "legacy", true
        );

        var secret = registry.resolveSecretForKid("k0-old");
        assertThat(secret).contains("old-secret");
    }

    @Test
    void resolveSecretForKidReturnsEmptyForUnknownKid() {
        WebhookHmacKeyRepository repo = mock(WebhookHmacKeyRepository.class);
        when(repo.findByKid("unknown")).thenReturn(Optional.empty());

        WebhookHmacKeyRegistry registry = new WebhookHmacKeyRegistry(
            repo, new MockEnvironment(), "legacy", true
        );

        assertThat(registry.resolveSecretForKid("unknown")).isEmpty();
    }
}
