package com.serban.notify.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/notify/preferences/me/mute-channel}
 * (Faz 23.6 PR-A2).
 *
 * <p>Codex thread {@code 019e0387} `N` decision: muting a channel is a
 * single user-facing intent, not two separate writes. The backend takes
 * the channel name, writes a wildcard deny rule
 * ({@code topic_key IS NULL, channel=:channel, enabled=false,
 * bypassForCritical=true}), and atomically deletes every same-channel
 * exact override so the wildcard actually wins the dispatch resolver
 * precedence.
 *
 * <p>Channel pattern follows the same whitelist the eligibility service
 * recognises ({@code email}, {@code sms}, {@code slack}, {@code webhook},
 * {@code in-app}); a 400 surfaces unknown channel names instead of letting
 * them write a rule that never fires.
 */
public record PreferenceMuteChannelRequest(
    @JsonProperty("channel")
    @NotBlank(message = "channel is required")
    @Size(max = 32)
    @Pattern(
        regexp = "email|sms|slack|webhook|in-app",
        message = "channel must be one of email, sms, slack, webhook, in-app"
    )
    String channel
) {}
