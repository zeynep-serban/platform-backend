package com.serban.notify.api;

import com.serban.notify.api.dto.PreferenceResponse;
import com.serban.notify.api.dto.PreferenceUpsertRequest;
import com.serban.notify.exception.InvalidRequestException;
import com.serban.notify.preference.SubscriberPreferenceService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Subscriber preference REST controller (Faz 23.5 PR2 — charter §188
 * "Preference UI v1").
 *
 * <p>Lets a subscriber inspect, set, and clear preference rows that
 * gate notification delivery for their account. The shape mirrors the
 * existing inbox surface: {@code /me} routes are scoped to the caller
 * via {@code X-Org-Id} + {@code X-Subscriber-Id} headers, and the
 * {@link SubscriberIdentityGuard} enforces the JWT trusted-claim-set
 * match so a caller cannot read or mutate another subscriber's rows.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/v1/notify/preferences/me} — list rows
 *       newest-first.</li>
 *   <li>{@code PUT /api/v1/notify/preferences/me} — upsert by composite
 *       key {@code (orgId, subscriberId, topicKey, channel)}; nullable
 *       fields are wildcards.</li>
 *   <li>{@code DELETE /api/v1/notify/preferences/me/{id}} — remove a
 *       specific row (revert to default-allow for the tuple).</li>
 * </ul>
 *
 * <h3>Feature gate</h3>
 *
 * The {@code notify.preferences.enabled} property gates the entire
 * controller. When false (test / staging-only), every endpoint
 * returns 503 so the client knows preference behavior is non-active
 * — same shape as the eligibility-service guard
 * ({@link com.serban.notify.eligibility.DeliveryEligibilityService})
 * uses on the dispatch path.
 */
@RestController
@RequestMapping("/api/v1/notify/preferences")
@Validated
public class PreferenceController {

    private final SubscriberPreferenceService preferenceService;
    private final SubscriberIdentityGuard subscriberIdentityGuard;
    private final NotifyOrgAccessGuard notifyOrgAccessGuard;
    private final boolean preferencesEnabled;

    public PreferenceController(
        SubscriberPreferenceService preferenceService,
        SubscriberIdentityGuard subscriberIdentityGuard,
        NotifyOrgAccessGuard notifyOrgAccessGuard,
        @Value("${notify.preferences.enabled:true}") boolean preferencesEnabled
    ) {
        this.preferenceService = preferenceService;
        this.subscriberIdentityGuard = subscriberIdentityGuard;
        this.notifyOrgAccessGuard = notifyOrgAccessGuard;
        this.preferencesEnabled = preferencesEnabled;
    }

    /**
     * GET /api/v1/notify/preferences/me — list the caller's preference
     * rows. Newest-first by {@code updated_at} (service-side sort) so
     * the most recently-touched rule appears at the top.
     */
    @GetMapping("/me")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Preference list"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "Subscriber identity mismatch"),
        @ApiResponse(responseCode = "503", description = "Preference feature disabled")
    })
    public ResponseEntity<List<PreferenceResponse>> listMine(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId
    ) {
        requireFeatureEnabled();
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        List<PreferenceResponse> body = preferenceService.listForSubscriber(callerOrgId, subscriberId)
            .stream()
            .map(PreferenceResponse::fromEntity)
            .toList();
        return ResponseEntity.ok(body);
    }

    /**
     * PUT /api/v1/notify/preferences/me — upsert by composite key.
     * Idempotent: a follow-up PUT for the same tuple returns the
     * post-mutation row again.
     */
    @PutMapping("/me")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Preference upserted"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "403", description = "Subscriber identity mismatch"),
        @ApiResponse(responseCode = "503", description = "Preference feature disabled")
    })
    public ResponseEntity<PreferenceResponse> upsertMine(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId,
        @Valid @RequestBody PreferenceUpsertRequest request
    ) {
        requireFeatureEnabled();
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        if (request == null) {
            throw new InvalidRequestException("request body required");
        }
        PreferenceResponse response = PreferenceResponse.fromEntity(
            preferenceService.upsert(
                callerOrgId,
                subscriberId,
                request.topicKey(),
                request.channel(),
                // @NotNull on the DTO field guarantees a value reached
                // here; unboxing is safe.
                request.enabled(),
                request.quietHours(),
                request.frequencyLimitPerDay(),
                request.bypassForCritical()
            )
        );
        return ResponseEntity.ok(response);
    }

    /**
     * DELETE /api/v1/notify/preferences/me/{id} — remove a preference
     * row owned by the caller. 404 if the row does not exist or belongs
     * to a different subscriber/org (matches inbox cross-tenant
     * existence-disclosure pattern).
     */
    @DeleteMapping("/me/{id}")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Preference removed"),
        @ApiResponse(responseCode = "403", description = "Subscriber identity mismatch"),
        @ApiResponse(responseCode = "404", description = "Preference not found or not accessible"),
        @ApiResponse(responseCode = "503", description = "Preference feature disabled")
    })
    public ResponseEntity<Void> deleteMine(
        @PathVariable Long id,
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId
    ) {
        requireFeatureEnabled();
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        boolean removed = preferenceService.delete(callerOrgId, subscriberId, id);
        if (!removed) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /api/v1/notify/preferences/me — restore-defaults: hard delete
     * every preference row owned by the caller in a single transaction
     * (Faz 23.6 PR-A1 — Codex thread {@code 019e0376} iter-2 AGREE absorb).
     *
     * <p>Idempotent: a follow-up call returns {@code 200 OK} with
     * {@code deletedCount: 0}. Each invocation appends a
     * {@code PREFERENCE_RESTORE_DEFAULTS} audit event so the operator
     * action is observable even when no rows were deleted.
     *
     * <p>Once the rows are gone the dispatcher's preference resolver
     * falls through to the default-allow behaviour (ADR-0013 D46 #8) —
     * "no preference set" maps to ALLOW.
     */
    @DeleteMapping("/me")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Preference rows cleared"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Org or subscriber identity mismatch"),
        @ApiResponse(responseCode = "503", description = "Preference feature disabled")
    })
    public ResponseEntity<com.serban.notify.api.dto.PreferenceRestoreDefaultsResponse> restoreDefaults(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId
    ) {
        requireFeatureEnabled();
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        int deleted = preferenceService.restoreDefaults(callerOrgId, subscriberId);
        return ResponseEntity.ok(
            new com.serban.notify.api.dto.PreferenceRestoreDefaultsResponse(deleted)
        );
    }

    /**
     * POST /api/v1/notify/preferences/me/mute-channel — atomic
     * channel-level mute (Faz 23.6 PR-A2 — Codex thread {@code 019e0387}
     * `N` decision).
     *
     * <p>Writes a channel-wildcard deny rule
     * ({@code topicKey IS NULL, channel=:channel, enabled=false,
     * bypassForCritical=true}) and atomically deletes every same-channel
     * exact override so the wildcard actually wins the dispatch
     * resolver precedence (resolver order: exact &gt; channel-wildcard
     * &gt; topic-wildcard &gt; both-null).
     *
     * <p>Critical bypass stays on by default — the operator can flip it
     * with a per-row PUT after the mute lands. Returns the number of
     * exact override rows removed for audit / UX feedback.
     */
    @org.springframework.web.bind.annotation.PostMapping("/me/mute-channel")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Channel muted; wildcard deny in place"),
        @ApiResponse(responseCode = "400", description = "Validation error (channel missing or unknown)"),
        @ApiResponse(responseCode = "401", description = "Authentication required"),
        @ApiResponse(responseCode = "403", description = "Org or subscriber identity mismatch"),
        @ApiResponse(responseCode = "503", description = "Preference feature disabled")
    })
    public ResponseEntity<com.serban.notify.api.dto.PreferenceMuteChannelResponse> muteChannel(
        @RequestHeader(name = "X-Org-Id", required = true) @NotBlank String callerOrgId,
        @RequestHeader(name = "X-Subscriber-Id", required = true) @NotBlank String subscriberId,
        @Valid @RequestBody com.serban.notify.api.dto.PreferenceMuteChannelRequest request
    ) {
        requireFeatureEnabled();
        notifyOrgAccessGuard.requireOrgAccessOrThrow(callerOrgId);
        subscriberIdentityGuard.requireMatchOrThrow(subscriberId);
        if (request == null) {
            throw new InvalidRequestException("request body required");
        }
        com.serban.notify.preference.SubscriberPreferenceService.MuteChannelResult result =
            preferenceService.muteChannel(callerOrgId, subscriberId, request.channel());
        return ResponseEntity.ok(new com.serban.notify.api.dto.PreferenceMuteChannelResponse(
            request.channel(), true, result.deletedOverrideCount(), result.shadowDenyCount()
        ));
    }

    private void requireFeatureEnabled() {
        if (!preferencesEnabled) {
            throw new PreferencesDisabledException();
        }
    }

    /**
     * Thrown when {@code notify.preferences.enabled=false}. Mapped to
     * 503 by the local exception handler — clients can treat it as
     * "preference-aware UX is off; default-allow rules are in effect".
     */
    public static class PreferencesDisabledException extends RuntimeException {
        PreferencesDisabledException() {
            super("notify.preferences.enabled=false");
        }
    }

    @ExceptionHandler(PreferencesDisabledException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handlePreferencesDisabled(PreferencesDisabledException ex) {
        return Map.of(
            "error", "preferences_disabled",
            "message", "preference feature is disabled in this environment"
        );
    }

    @ExceptionHandler(InvalidRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleInvalidRequest(InvalidRequestException ex) {
        return Map.of("error", "validation", "message", ex.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleConstraintViolation(ConstraintViolationException ex) {
        return Map.of("error", "validation", "message", ex.getMessage());
    }
}
