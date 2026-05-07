package com.serban.notify.service;

import com.serban.notify.api.dto.DeliveryLogListResponse;
import com.serban.notify.api.dto.DeliveryLogResponse;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.redaction.DeliveryLogRedactor;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.repository.projection.DeliveryLogRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Orchestration service for delivery log queries (Faz 23.5 PR6).
 *
 * <p>Codex thread {@code 019e0289} iter-3 AGREE: the controller never sees
 * raw {@link DeliveryLogRow} — this service applies
 * {@link DeliveryLogRedactor} and returns the public DTO only.
 *
 * <p>{@code listForIntent} returns {@link Optional#empty()} when the intent
 * does not exist <i>or</i> is not owned by the requested org. The caller is
 * expected to translate that into a 404 (info-leak safe — see
 * {@link com.serban.notify.api.NotifyOrgAccessGuard} for the broader
 * boundary contract).
 */
@Service
public class DeliveryLogService {

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationIntentRepository intentRepository;
    private final DeliveryLogRedactor redactor;

    public DeliveryLogService(
        NotificationDeliveryRepository deliveryRepository,
        NotificationIntentRepository intentRepository,
        DeliveryLogRedactor redactor
    ) {
        this.deliveryRepository = deliveryRepository;
        this.intentRepository = intentRepository;
        this.redactor = redactor;
    }

    /**
     * Intent-scoped delivery log.
     *
     * @return {@link Optional#empty()} when the intent does not exist or
     *         belongs to a different org; populated list response otherwise
     */
    @Transactional(readOnly = true)
    public Optional<DeliveryLogListResponse> listForIntent(
        String intentId,
        String orgId,
        int page,
        int size
    ) {
        Optional<NotificationIntent> intent =
            intentRepository.findByIntentIdAndOrgId(intentId, orgId);
        if (intent.isEmpty()) {
            return Optional.empty();
        }
        Page<DeliveryLogRow> rows = deliveryRepository.findDeliveryLogByIntentIdAndOrgId(
            intentId, orgId, PageRequest.of(page, size)
        );
        List<DeliveryLogResponse> items = rows.getContent().stream()
            .map(redactor::toResponse)
            .toList();
        return Optional.of(new DeliveryLogListResponse(
            items,
            rows.getNumber(),
            rows.getSize(),
            rows.getTotalElements(),
            rows.getTotalPages(),
            null,
            null,
            redactor.redactionPolicy()
        ));
    }

    /**
     * Admin-wide delivery log search.
     *
     * <p>The controller resolves the time window and validates the
     * pagination contract; this service only executes the query and applies
     * redaction.
     */
    @Transactional(readOnly = true)
    public DeliveryLogListResponse searchAdmin(
        String orgId,
        NotificationDelivery.Status status,
        String channel,
        String provider,
        OffsetDateTime fromTs,
        OffsetDateTime toTs,
        int page,
        int size
    ) {
        Page<DeliveryLogRow> rows = deliveryRepository.searchAdminDeliveryLog(
            orgId, status, channel, provider, fromTs, toTs,
            PageRequest.of(page, size)
        );
        List<DeliveryLogResponse> items = rows.getContent().stream()
            .map(redactor::toResponse)
            .toList();
        return new DeliveryLogListResponse(
            items,
            rows.getNumber(),
            rows.getSize(),
            rows.getTotalElements(),
            rows.getTotalPages(),
            fromTs,
            toTs,
            redactor.redactionPolicy()
        );
    }
}
