package com.serban.notify.api.dto;

import java.util.List;

/**
 * Single entry in the subscriber-facing topic catalog (Faz 23.5 M5 G2).
 *
 * <p>Topic catalog surfaces the universe of dotted-notation topic keys
 * that a subscriber can opt into / out of. Without a catalog, the
 * frontend preference editor has no canonical source of truth for which
 * {@code topicKey} values are valid, leading to typo'd preferences and
 * a UX where the user cannot discover what topics they could
 * potentially receive.
 *
 * <p>This catalog is <b>config-driven</b> (not DB-driven) — the set of
 * known topics is part of the platform contract and lives in
 * {@code application.yml} under {@code notify.topics.catalog}. Topics
 * not in the catalog can still be sent to (intent submission does not
 * validate against this list) but they will not appear in the catalog
 * UI; this preserves operator flexibility while giving subscribers a
 * concrete "known topics" menu.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code topicKey}: dotted-notation identifier
 *       (e.g. {@code auth.mfa-otp}, {@code marketing.campaign}).</li>
 *   <li>{@code label}: human-readable Turkish label shown in the UI.</li>
 *   <li>{@code category}: high-level group
 *       ({@code auth}, {@code audit}, {@code marketing}, {@code system}).</li>
 *   <li>{@code supportedChannels}: which channels this topic is
 *       eligible to deliver on (subset of
 *       {@code [EMAIL, SLACK, WEBHOOK, IN_APP, SMS]}).</li>
 *   <li>{@code criticalEligible}: whether {@code bypassForCritical}
 *       can be set for this topic; for example {@code auth.mfa-otp} is
 *       critical-eligible because OTP delivery is security-critical and
 *       cannot be opted out by user preference. {@code
 *       marketing.campaign} is not.</li>
 *   <li>{@code description}: short Turkish description shown as
 *       tooltip / drawer subtitle in the UI.</li>
 *   <li>{@code defaultFrequencyHint}: optional hint for the frequency
 *       limit slider default (e.g. {@code 5} per day for
 *       marketing.campaign, {@code null} for transactional topics).</li>
 * </ul>
 */
public record TopicCatalogEntryResponse(
    String topicKey,
    String label,
    String category,
    List<String> supportedChannels,
    boolean criticalEligible,
    String description,
    Integer defaultFrequencyHint
) {}
