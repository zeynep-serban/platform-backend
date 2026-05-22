package com.serban.notify.fbl;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.EmailSuppression;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.repository.EmailBounceEventRepository;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.projection.FblDeliveryResolution;
import com.serban.notify.suppression.EmailSuppressionService;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FblService} — ARF spam-complaint ingestion flow
 * (Faz 23.8 M7 T4.3.5 FBL). Mockito-based; no Spring context, no DB.
 */
class FblServiceTest {

    private ArfReportParser parser;
    private NotificationDeliveryRepository deliveryRepository;
    private EmailBounceEventRepository bounceEventRepository;
    private EmailSuppressionService suppressionService;
    private AuditEventPublisher audit;
    private FblMetrics metrics;
    private FblService service;

    private final MimeMessage rawArf = mock(MimeMessage.class);

    @BeforeEach
    void setUp() {
        parser = mock(ArfReportParser.class);
        deliveryRepository = mock(NotificationDeliveryRepository.class);
        bounceEventRepository = mock(EmailBounceEventRepository.class);
        suppressionService = mock(EmailSuppressionService.class);
        audit = mock(AuditEventPublisher.class);
        metrics = mock(FblMetrics.class);
        service = new FblService(parser, deliveryRepository, bounceEventRepository,
            suppressionService, audit, metrics);
    }

    private static ArfReport abuseReport() {
        return new ArfReport(
            "Microsoft-Office365-FBL/1.0",
            "abuse",
            "<arf-report-001@office365.com>",
            "<orig-msg-456@acik.com>",
            "notify-corr-789",
            List.of("notify-corr-789", "<orig-msg-456@acik.com>", "orig-msg-456@acik.com"),
            "complainer@example.com",
            "Thu, 22 May 2026 10:00:00 +0000",
            OffsetDateTime.parse("2026-05-22T12:00:00Z"),
            "feedback_type=abuse; reporter=Microsoft-Office365-FBL/1.0"
        );
    }

    /**
     * Plain interface implementation — NOT a Mockito mock. Building a mock
     * here would nest {@code when()...thenReturn()} stubbing inside the
     * {@code when(deliveryRepository...)} stubbing argument and trigger
     * Mockito UnfinishedStubbing.
     */
    private static FblDeliveryResolution resolution() {
        return resolutionWithChannel("email");
    }

    private static FblDeliveryResolution resolutionWithChannel(String channel) {
        return new FblDeliveryResolution() {
            @Override public String getOrgId() { return "org-1"; }
            @Override public String getRecipientHash() { return "recip-hash-abc"; }
            @Override public NotificationDelivery.RecipientType getRecipientType() {
                return NotificationDelivery.RecipientType.EXTERNAL;
            }
            @Override public String getIntentId() { return "intent-1"; }
            @Override public String getChannel() { return channel; }
        };
    }

    @Test
    void parseErrorIsCountedAndSkipped() throws Exception {
        when(parser.parse(any())).thenThrow(new ArfParseException("bad MIME"));

        FblService.FblOutcome outcome = service.ingest(rawArf);

        assertThat(outcome).isEqualTo(FblService.FblOutcome.PARSE_ERROR);
        verify(metrics).received(FblMetrics.OUTCOME_PARSE_ERROR);
        verify(suppressionService, never()).upsert(any());
        verify(bounceEventRepository, never())
            .insertIfAbsent(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void nonAbuseFeedbackTypeIsIgnored() throws Exception {
        ArfReport fraud = new ArfReport("rep", "fraud", "<r@x>", "<o@y>", null,
            List.of("<o@y>"), null, null, OffsetDateTime.now(), "feedback_type=fraud");
        when(parser.parse(any())).thenReturn(fraud);

        FblService.FblOutcome outcome = service.ingest(rawArf);

        assertThat(outcome).isEqualTo(FblService.FblOutcome.IGNORED_UNSUPPORTED);
        verify(metrics).received(FblMetrics.OUTCOME_IGNORED_UNSUPPORTED);
        verify(suppressionService, never()).upsert(any());
    }

    @Test
    void unresolvedDeliveryIsCountedAndSkipped() throws Exception {
        when(parser.parse(any())).thenReturn(abuseReport());
        when(deliveryRepository.resolveFblByProviderMsgId(anyString()))
            .thenReturn(Optional.empty());

        FblService.FblOutcome outcome = service.ingest(rawArf);

        assertThat(outcome).isEqualTo(FblService.FblOutcome.UNRESOLVED);
        verify(metrics).received(FblMetrics.OUTCOME_UNRESOLVED);
        verify(suppressionService, never()).upsert(any());
        verify(bounceEventRepository, never())
            .insertIfAbsent(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void duplicateEventIsMetricOnlyNoSuppression() throws Exception {
        when(parser.parse(any())).thenReturn(abuseReport());
        when(deliveryRepository.resolveFblByProviderMsgId(anyString()))
            .thenReturn(Optional.of(resolution()));
        // insertIfAbsent rowcount 0 → duplicate
        when(bounceEventRepository.insertIfAbsent(anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), any(), anyString()))
            .thenReturn(0);

        FblService.FblOutcome outcome = service.ingest(rawArf);

        assertThat(outcome).isEqualTo(FblService.FblOutcome.DUPLICATE);
        verify(metrics).received(FblMetrics.OUTCOME_DUPLICATE);
        verify(suppressionService, never()).upsert(any());
        verify(audit, never()).publishStandalone(anyString(), anyString(), anyString(), any());
    }

    @Test
    void freshComplaintSuppressesAndAudits() throws Exception {
        when(parser.parse(any())).thenReturn(abuseReport());
        when(deliveryRepository.resolveFblByProviderMsgId(anyString()))
            .thenReturn(Optional.of(resolution()));
        // insertIfAbsent rowcount 1 → fresh event
        when(bounceEventRepository.insertIfAbsent(anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), any(), anyString()))
            .thenReturn(1);

        FblService.FblOutcome outcome = service.ingest(rawArf);

        assertThat(outcome).isEqualTo(FblService.FblOutcome.SUPPRESSED);
        verify(metrics).received(FblMetrics.OUTCOME_SUPPRESSED);
        verify(metrics).suppressed("org-1");

        ArgumentCaptor<EmailSuppressionService.UpsertInput> upsertCaptor =
            ArgumentCaptor.forClass(EmailSuppressionService.UpsertInput.class);
        verify(suppressionService).upsert(upsertCaptor.capture());
        EmailSuppressionService.UpsertInput input = upsertCaptor.getValue();
        assertThat(input.orgId).isEqualTo("org-1");
        assertThat(input.recipientHash).isEqualTo("recip-hash-abc");
        assertThat(input.reason).isEqualTo(EmailSuppression.Reason.SPAM_COMPLAINT);
        assertThat(input.source).isEqualTo(EmailSuppression.Source.ARF_MAILBOX);
        assertThat(input.recipientType).isEqualTo(EmailSuppression.RecipientType.EXTERNAL);

        ArgumentCaptor<Map<String, Object>> detailsCaptor =
            ArgumentCaptor.forClass(Map.class);
        verify(audit).publishStandalone(
            eq("EMAIL_SPAM_COMPLAINT"), eq("org-1"), eq("recip-hash-abc"),
            detailsCaptor.capture());
        assertThat(detailsCaptor.getValue())
            .containsEntry("reason", "SPAM_COMPLAINT")
            .containsEntry("feedback_type", "abuse")
            .containsKey("event_fingerprint");
    }

    @Test
    void nonEmailChannelResolutionIsSkipped() throws Exception {
        // Codex 019e4fc6 iter-2 HIGH #1: a non-email correlated delivery must
        // never drive an email-channel suppression (defense-in-depth guard).
        when(parser.parse(any())).thenReturn(abuseReport());
        when(deliveryRepository.resolveFblByProviderMsgId(anyString()))
            .thenReturn(Optional.of(resolutionWithChannel("sms")));

        FblService.FblOutcome outcome = service.ingest(rawArf);

        assertThat(outcome).isEqualTo(FblService.FblOutcome.UNRESOLVED);
        verify(metrics).received(FblMetrics.OUTCOME_UNRESOLVED);
        verify(suppressionService, never()).upsert(any());
    }

    @Test
    void originalMessageIdAbsentStillSuppressesViaCorrelatorFallback() throws Exception {
        // Codex 019e4fc6 iter-2 MEDIUM #3: no original Message-ID, but an
        // X-Notify-Message-ID correlator is present — fingerprint must still
        // be computed (fallback) and the complaint suppressed.
        ArfReport noOrigMsgId = new ArfReport(
            "Microsoft-Office365-FBL/1.0", "abuse", "<arf-report-002@office365.com>",
            null, "notify-corr-999", List.of("notify-corr-999"),
            "complainer@example.com", null,
            OffsetDateTime.parse("2026-05-22T12:00:00Z"),
            "feedback_type=abuse; reporter=Microsoft-Office365-FBL/1.0");
        when(parser.parse(any())).thenReturn(noOrigMsgId);
        when(deliveryRepository.resolveFblByProviderMsgId(anyString()))
            .thenReturn(Optional.of(resolution()));
        when(bounceEventRepository.insertIfAbsent(anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), any(), anyString()))
            .thenReturn(1);

        FblService.FblOutcome outcome = service.ingest(rawArf);

        assertThat(outcome).isEqualTo(FblService.FblOutcome.SUPPRESSED);
        verify(suppressionService).upsert(any());
    }

    @Test
    void recipientHashComesFromDeliveryNotArfAddress() throws Exception {
        // Codex 019e4fc6 critical rule: suppression recipient_hash MUST be the
        // delivery row's hash, never recomputed from the ARF originalRecipient.
        when(parser.parse(any())).thenReturn(abuseReport());
        when(deliveryRepository.resolveFblByProviderMsgId(anyString()))
            .thenReturn(Optional.of(resolution()));
        when(bounceEventRepository.insertIfAbsent(anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), any(), anyString()))
            .thenReturn(1);

        service.ingest(rawArf);

        ArgumentCaptor<EmailSuppressionService.UpsertInput> captor =
            ArgumentCaptor.forClass(EmailSuppressionService.UpsertInput.class);
        verify(suppressionService).upsert(captor.capture());
        // delivery hash, not a hash of "complainer@example.com"
        assertThat(captor.getValue().recipientHash).isEqualTo("recip-hash-abc");
    }
}
