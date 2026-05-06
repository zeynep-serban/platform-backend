package com.serban.notify.provider;

import com.serban.notify.domain.ProviderConfig;
import com.serban.notify.repository.ProviderConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProviderConfigService unit test (Faz 23.2 PR-A — Codex 019dfae5 Q4).
 */
class ProviderConfigServiceTest {

    @Test
    void findPrimaryReturnsFirstByPriority() {
        ProviderConfigRepository repo = mock(ProviderConfigRepository.class);
        ProviderConfig p1 = config(1L, "smtp-primary", 100);
        ProviderConfig p2 = config(2L, "smtp-fallback", 200);
        when(repo.findActiveByOrgChannelOrderByPriority("acme", "email", "test"))
            .thenReturn(List.of(p1, p2));

        ProviderConfigService svc = new ProviderConfigService(repo, 30);
        var primary = svc.findPrimary("acme", "email", "test");

        assertThat(primary).isPresent();
        assertThat(primary.get().getProviderKey()).isEqualTo("smtp-primary");
    }

    @Test
    void findPrimaryReturnsEmptyWhenNoActive() {
        ProviderConfigRepository repo = mock(ProviderConfigRepository.class);
        when(repo.findActiveByOrgChannelOrderByPriority(anyString(), anyString(), anyString()))
            .thenReturn(List.of());

        ProviderConfigService svc = new ProviderConfigService(repo, 30);
        assertThat(svc.findPrimary("acme", "email", "test")).isEmpty();
    }

    @Test
    void findNextFailoverReturnsNextByPriority() {
        ProviderConfigRepository repo = mock(ProviderConfigRepository.class);
        ProviderConfig p1 = config(1L, "primary", 100);
        ProviderConfig p2 = config(2L, "fallback", 200);
        when(repo.findActiveByOrgChannelOrderByPriority("acme", "email", "test"))
            .thenReturn(List.of(p1, p2));

        ProviderConfigService svc = new ProviderConfigService(repo, 30);
        var next = svc.findNextFailover("acme", "email", "test", p1);

        assertThat(next).isPresent();
        assertThat(next.get().getProviderKey()).isEqualTo("fallback");
    }

    @Test
    void findNextFailoverReturnsEmptyWhenNoFallback() {
        ProviderConfigRepository repo = mock(ProviderConfigRepository.class);
        ProviderConfig p1 = config(1L, "primary", 100);
        when(repo.findActiveByOrgChannelOrderByPriority(anyString(), anyString(), anyString()))
            .thenReturn(List.of(p1));

        ProviderConfigService svc = new ProviderConfigService(repo, 30);
        var next = svc.findNextFailover("acme", "email", "test", p1);

        assertThat(next).isEmpty();
    }

    @Test
    void cacheHitsAvoidRepoCall() {
        ProviderConfigRepository repo = mock(ProviderConfigRepository.class);
        ProviderConfig p = config(1L, "primary", 100);
        when(repo.findActiveByOrgChannelOrderByPriority("acme", "email", "test"))
            .thenReturn(List.of(p));

        ProviderConfigService svc = new ProviderConfigService(repo, 30);

        // 3 lookups within TTL → 1 repo call
        svc.findPrimary("acme", "email", "test");
        svc.findPrimary("acme", "email", "test");
        svc.findPrimary("acme", "email", "test");

        verify(repo, times(1)).findActiveByOrgChannelOrderByPriority(anyString(), anyString(), anyString());
    }

    @Test
    void invalidateCacheForcesRefresh() {
        ProviderConfigRepository repo = mock(ProviderConfigRepository.class);
        ProviderConfig p = config(1L, "primary", 100);
        when(repo.findActiveByOrgChannelOrderByPriority(anyString(), anyString(), anyString()))
            .thenReturn(List.of(p));

        ProviderConfigService svc = new ProviderConfigService(repo, 30);
        svc.findPrimary("acme", "email", "test");
        svc.invalidateCache();
        svc.findPrimary("acme", "email", "test");

        verify(repo, times(2)).findActiveByOrgChannelOrderByPriority(anyString(), anyString(), anyString());
    }

    private ProviderConfig config(long id, String key, int priority) {
        ProviderConfig p = new ProviderConfig();
        p.setId(id);
        p.setOrgId("acme");
        p.setProviderKey(key);
        p.setChannel("email");
        p.setEnvironment("test");
        p.setVersion(1);
        p.setActive(true);
        p.setPriority(priority);
        return p;
    }
}
