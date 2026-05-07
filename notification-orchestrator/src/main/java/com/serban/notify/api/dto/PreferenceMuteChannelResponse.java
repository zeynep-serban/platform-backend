package com.serban.notify.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response body for {@code POST /api/v1/notify/preferences/me/mute-channel}
 * (Faz 23.6 PR-A2).
 *
 * <p>Codex thread {@code 019e0387} `N` decision: a channel-level mute
 * writes one wildcard deny rule and removes every same-channel exact
 * override so the wildcard actually fires. The response surfaces both
 * sides of the action for audit / UX feedback:
 *
 * <ul>
 *   <li>{@code channel} — echo of the requested channel.</li>
 *   <li>{@code muted} — {@code true} when the channel-wildcard deny rule
 *       is in place after the call (always {@code true} on success).</li>
 *   <li>{@code deletedOverrideCount} — number of exact override rows the
 *       endpoint removed; 0 on a "fresh" mute that found no existing
 *       overrides.</li>
 * </ul>
 */
public record PreferenceMuteChannelResponse(
    @JsonProperty("channel") String channel,
    @JsonProperty("muted") boolean muted,
    @JsonProperty("deletedOverrideCount") int deletedOverrideCount,
    /**
     * Codex thread {@code 019e0387} P1 absorb: shadow exact-deny rows
     * the action wrote for topic-wide allow rules so the resolver
     * actually mutes the channel. Surface this so the UI can be honest
     * ("3 same-channel kuralı silindi, 2 topic-wide kural email için
     * gölgelendi").
     */
    @JsonProperty("shadowDenyCount") int shadowDenyCount
) {}
