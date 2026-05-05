package com.serban.notify.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ChannelAdapterRegistry — Spring auto-discovery of {@link ChannelAdapter} beans.
 *
 * <p>Each implementation registered as bean ({@code @Component} on adapter class);
 * Spring constructor injection collects {@code List<ChannelAdapter>}, this class
 * indexes by {@link ChannelAdapter#channelKey()} on first access (lazy).
 *
 * <p>Codex 019df9ef CI absorb: index built lazily on first {@link #get},
 * {@link #supports}, or {@link #supportedChannels} call — NOT at construction
 * time. This avoids issues with Mockito mocks that return null from
 * {@link ChannelAdapter#channelKey()} until stubbed (in {@code @BeforeEach}).
 * Real {@code @Component} adapters' channelKey() is a constant method so
 * production behavior is unchanged.
 *
 * <p>Defensive: null channelKey adapters skipped with warn log; duplicate
 * non-identical adapters for same channel raise {@link IllegalStateException}.
 */
@Component
public class ChannelAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChannelAdapterRegistry.class);

    private final List<ChannelAdapter> adapters;
    private volatile Map<String, ChannelAdapter> byChannel;

    public ChannelAdapterRegistry(List<ChannelAdapter> adapters) {
        this.adapters = List.copyOf(adapters);
    }

    /** Build (or rebuild on first access) the channel index. */
    private Map<String, ChannelAdapter> index() {
        Map<String, ChannelAdapter> snapshot = byChannel;
        if (snapshot != null) return snapshot;
        synchronized (this) {
            if (byChannel != null) return byChannel;
            Map<String, ChannelAdapter> map = new HashMap<>(adapters.size());
            for (ChannelAdapter adapter : adapters) {
                String key = adapter.channelKey();
                if (key == null) {
                    log.warn("ChannelAdapter {} returned null channelKey — skipping registration",
                        adapter.getClass().getName());
                    continue;
                }
                ChannelAdapter prev = map.putIfAbsent(key, adapter);
                if (prev != null && prev != adapter) {
                    throw new IllegalStateException(
                        "duplicate ChannelAdapter for channel '" + key + "': "
                            + prev.getClass().getName() + " vs " + adapter.getClass().getName()
                    );
                }
            }
            byChannel = Map.copyOf(map);
            return byChannel;
        }
    }

    public Optional<ChannelAdapter> get(String channelKey) {
        return Optional.ofNullable(index().get(channelKey));
    }

    public boolean supports(String channelKey) {
        return index().containsKey(channelKey);
    }

    public java.util.Set<String> supportedChannels() {
        return index().keySet();
    }
}
