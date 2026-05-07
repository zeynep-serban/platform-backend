package com.serban.notify.redaction;

import com.serban.notify.api.dto.DeliveryLogResponse;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.repository.projection.DeliveryLogRow;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DeliveryLogRedactor} (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE absorb: redactor is the
 * only path raw fields can leak through; these tests pin the contract.
 */
class DeliveryLogRedactorTest {

    private final DeliveryLogRedactor redactor = new DeliveryLogRedactor();

    @Test
    void maskProviderMsgId_keepsPrefixAndLast4_whenDashSeparated() {
        assertThat(redactor.maskProviderMsgId("netgsm-12345001234"))
            .isEqualTo("netgsm-***1234");
    }

    @Test
    void maskProviderMsgId_returnsTripleStar_whenShorterThanFive() {
        assertThat(redactor.maskProviderMsgId("abcd")).isEqualTo("***");
        assertThat(redactor.maskProviderMsgId("a")).isEqualTo("***");
    }

    @Test
    void maskProviderMsgId_returnsNull_forBlankInput() {
        assertThat(redactor.maskProviderMsgId(null)).isNull();
        assertThat(redactor.maskProviderMsgId("")).isNull();
        assertThat(redactor.maskProviderMsgId("   ")).isNull();
    }

    @Test
    void maskProviderMsgId_keepsFirstTwoCharsAndLast4_whenNoDash() {
        assertThat(redactor.maskProviderMsgId("smtpmsgid20260507"))
            .isEqualTo("sm***0507");
    }

    @Test
    void classify_recognizesQuotaIdiom() {
        assertThat(redactor.classify("Quota exceeded for SMS account"))
            .isEqualTo("PROVIDER_QUOTA");
        assertThat(redactor.classify("Rate limit reached"))
            .isEqualTo("PROVIDER_QUOTA");
    }

    @Test
    void classify_recognizesRecipientRejected() {
        assertThat(redactor.classify("550 user not found"))
            .isEqualTo("RECIPIENT_REJECTED");
        assertThat(redactor.classify("Recipient refused"))
            .isEqualTo("RECIPIENT_REJECTED");
    }

    @Test
    void classify_recognizesRecipientBlocked() {
        assertThat(redactor.classify("Recipient is on the blacklist"))
            .isEqualTo("RECIPIENT_BLOCKED");
        assertThat(redactor.classify("opt-out registered"))
            .isEqualTo("RECIPIENT_BLOCKED");
    }

    @Test
    void classify_recognizesInvalidTarget() {
        assertThat(redactor.classify("Invalid phone number format"))
            .isEqualTo("INVALID_TARGET");
        assertThat(redactor.classify("No such address"))
            .isEqualTo("INVALID_TARGET");
    }

    @Test
    void classify_recognizesTransientNetwork() {
        assertThat(redactor.classify("Connection timed out"))
            .isEqualTo("TRANSIENT_NETWORK");
        assertThat(redactor.classify("DNS resolution failed"))
            .isEqualTo("TRANSIENT_NETWORK");
    }

    @Test
    void classify_recognizesAuthFailure() {
        assertThat(redactor.classify("HTTP 401 Unauthorized"))
            .isEqualTo("AUTH_FAILURE");
        assertThat(redactor.classify("invalid token: expired"))
            .isEqualTo("AUTH_FAILURE");
    }

    @Test
    void classify_unknownIdiomCollapsesToUnknown() {
        assertThat(redactor.classify("Internal Server Error xyz"))
            .isEqualTo("UNKNOWN");
        assertThat(redactor.classify(null)).isEqualTo("UNKNOWN");
        assertThat(redactor.classify("")).isEqualTo("UNKNOWN");
    }

    @Test
    void classify_netgsmDlrCode_recipientRejectedFamily() {
        // Codex thread 019e036c absorb: canonical deterministic mapping
        // ahead of the keyword heuristics. Codes 04/05/16 land in
        // RECIPIENT_REJECTED — see DlrIngestService.NETGSM_PERMANENT_FAILURE_CODES.
        assertThat(redactor.classify("dlr netgsm code=04"))
            .isEqualTo("RECIPIENT_REJECTED");
        assertThat(redactor.classify("dlr netgsm code=05"))
            .isEqualTo("RECIPIENT_REJECTED");
        assertThat(redactor.classify("dlr netgsm code=16"))
            .isEqualTo("RECIPIENT_REJECTED");
    }

    @Test
    void classify_netgsmDlrCode_recipientBlockedFamily() {
        // KVKK / IYS opt-out — operator must back off; map to BLOCKED.
        assertThat(redactor.classify("dlr netgsm code=17"))
            .isEqualTo("RECIPIENT_BLOCKED");
        assertThat(redactor.classify("dlr netgsm code=70"))
            .isEqualTo("RECIPIENT_BLOCKED");
    }

    @Test
    void classify_netgsmDlrCode_unknownCodeStaysUnknown() {
        // Unrecognised carrier code does not leak through as a guessed
        // category; UI surfaces PROVIDER_FAILURE_REDACTED via the summary
        // key fallback.
        assertThat(redactor.classify("dlr netgsm code=99"))
            .isEqualTo("UNKNOWN");
    }

    @Test
    void classify_netgsmDlrCode_caseInsensitive_andSpaceTolerant() {
        // The regex was deliberately made tolerant so we don't depend on
        // the exact whitespace / casing emitted by DlrIngestService.
        assertThat(redactor.classify("DLR NETGSM CODE=17"))
            .isEqualTo("RECIPIENT_BLOCKED");
        assertThat(redactor.classify("dlr  netgsm  code = 04"))
            .isEqualTo("RECIPIENT_REJECTED");
    }

    @Test
    void classify_keywordHeuristics_stillWorkForNonCarrierIdioms() {
        // Regression: explicit-text idioms continue to flow through the
        // keyword heuristics that ship with the redactor today.
        assertThat(redactor.classify("Recipient is on the blacklist"))
            .isEqualTo("RECIPIENT_BLOCKED");
    }

    @Test
    void toResponse_netgsmDlrCode_emitsDeterministicSummaryKeys() {
        // toResponse round-trip pin: classify outcome -> summary key map.
        OffsetDateTime now = OffsetDateTime.parse("2026-05-07T08:00:00Z");
        DeliveryLogResponse rejected = redactor.toResponse(sampleRow(
            now, "netgsm-12345", "1204", "dlr netgsm code=04"
        ));
        assertThat(rejected.failureCategory()).isEqualTo("RECIPIENT_REJECTED");
        assertThat(rejected.failureSummaryRedacted())
            .isEqualTo("provider.failure.recipient_rejected");

        DeliveryLogResponse blocked = redactor.toResponse(sampleRow(
            now, "netgsm-12345", "1204", "dlr netgsm code=17"
        ));
        assertThat(blocked.failureCategory()).isEqualTo("RECIPIENT_BLOCKED");
        assertThat(blocked.failureSummaryRedacted())
            .isEqualTo("provider.failure.recipient_blocked");

        DeliveryLogResponse unknown = redactor.toResponse(sampleRow(
            now, "netgsm-12345", "1204", "dlr netgsm code=99"
        ));
        assertThat(unknown.failureCategory()).isEqualTo("UNKNOWN");
        assertThat(unknown.failureSummaryRedacted())
            .isEqualTo("PROVIDER_FAILURE_REDACTED");
    }

    @Test
    void toResponse_dropsRawFieldsAndAppliesMaskAndCategory() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-07T08:00:00Z");
        DeliveryLogRow row = sampleRow(now,
            "netgsm-99887766", "1204+90555*****", "Recipient rejected by carrier"
        );

        DeliveryLogResponse response = redactor.toResponse(row);

        assertThat(response.deliveryId()).isEqualTo(42L);
        assertThat(response.intentId()).isEqualTo("intent-uuid");
        assertThat(response.topicKey()).isEqualTo("auth.password-reset");
        assertThat(response.recipientHash()).isEqualTo("hash-abc");
        assertThat(response.providerMsgIdMasked()).isEqualTo("netgsm-***7766");
        assertThat(response.failureCategory()).isEqualTo("RECIPIENT_REJECTED");
        assertThat(response.failureSummaryRedacted())
            .isEqualTo("provider.failure.recipient_rejected");
        assertThat(response.activityAt()).isEqualTo(now);

        // Raw / sensitive fields must not be reachable through the DTO.
        // (Compile-time guarantee: DeliveryLogResponse simply has no
        // recipientId / claimToken / processingLeaseUntil / failureReason
        // accessor; this assertion keeps the regression test obvious.)
        assertThat(response.toString())
            .doesNotContain("1204+90555")  // raw recipient id
            .doesNotContain("Recipient rejected by carrier")  // raw failure
            .doesNotContain("99887766");   // raw provider msg id middle
    }

    @Test
    void toResponse_unknownFailureCategoryEmitsRedactedSentinel() {
        DeliveryLogRow row = sampleRow(
            OffsetDateTime.parse("2026-05-07T08:00:00Z"),
            null, null, "Mysterious provider error 0x9001"
        );

        DeliveryLogResponse response = redactor.toResponse(row);

        assertThat(response.failureCategory()).isEqualTo("UNKNOWN");
        assertThat(response.failureSummaryRedacted())
            .isEqualTo("PROVIDER_FAILURE_REDACTED");
        assertThat(response.providerMsgIdMasked()).isNull();
    }

    @Test
    void toResponse_throwsOnNullRow() {
        assertThatThrownBy(() -> redactor.toResponse(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redactionPolicy_returnsStableV1Identifier() {
        assertThat(redactor.redactionPolicy()).isEqualTo("v1");
    }

    private DeliveryLogRow sampleRow(
        OffsetDateTime now, String providerMsgId, String recipientId, String failureReason
    ) {
        return new DeliveryLogRow(
            42L,
            "intent-uuid",
            "default",
            "auth.password-reset",
            "corr-1",
            "sms",
            NotificationDelivery.RecipientType.EXTERNAL,
            "hash-abc",
            recipientId,
            "netgsm",
            providerMsgId,
            NotificationDelivery.Status.FAILED,
            3,
            failureReason,
            "claim-token-uuid",
            now.plusMinutes(5),
            now.minusMinutes(1),
            null,
            now,
            null,
            now.minusMinutes(10),
            now,
            now
        );
    }
}
