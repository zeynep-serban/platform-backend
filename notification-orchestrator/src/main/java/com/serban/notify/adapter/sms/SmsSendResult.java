package com.serban.notify.adapter.sms;

/**
 * SMS provider send sonucu — Faz 23.3 multi-provider (Codex `019e3f82`
 * iter-2 absorb).
 *
 * <p>{@link SmsProvider#send} bu record'u döner. {@link SmsAdapter} bunu
 * {@code ChannelAdapter.DeliveryAttemptResult}'a map eder, failover kararını
 * {@link #failureClass} üzerinden verir.
 *
 * <p>{@link #actualProviderKey} — sonucu üreten gerçek provider
 * ({@code "jetsms"} | {@code "netgsm"}). Failover sonrası secondary kabul
 * ederse bu alan secondary provider'ı taşır; {@code DeliveryDispatchService}
 * {@code notification_delivery.provider} kolonuna bu değeri yazar (plan-time
 * placeholder değil — Codex absorb #2).
 *
 * <p>{@link #providerMsgId} — DLR correlator. {@code ACCEPTED} sonuçta
 * non-blank zorunlu (yoksa caller {@code retry} dönmeli). Format:
 * {@code "<providerKey>-<rawId>"} (örn. {@code "jetsms-756464245"},
 * {@code "netgsm-4039201"}).
 *
 * <p><b>Faz 23.3.2 multipart metadata</b> (Codex thread {@code 019e4514}
 * PR-A1.1 absorb):
 * <ul>
 *   <li>{@link #segmentCount} — billed SMS segment sayısı; {@code ACCEPTED}
 *       sonuçta ≥1, {@code FAILED}/{@code RETRY} sonuçta 0 (mesaj
 *       gönderilmedi → billing yok).</li>
 *   <li>{@link #encoding} — provider-specific encoding label
 *       ({@code ISO-8859-9} JetSMS Latin-5, {@code UCS-2} NetGSM Unicode,
 *       {@code GSM-7} NetGSM ASCII). Null = encoding bilgisi yok (eski
 *       call path veya provider override).</li>
 *   <li>{@link #actualChannel} — Faz 23.3.2 PR-A3.1.1 (Codex P2 absorb):
 *       JetSMS SOAP outbound'ta gerçekten kullanılan kanal kodu
 *       ({@code VFO} / {@code VF}); routing kararının audit kanıtı.
 *       Null = legacy 2-arg send call veya channel-agnostic provider
 *       (örn. NetGSM tek-kanal). Sadece {@code ACCEPTED} sonuçta
 *       anlamlıdır; {@code FAILED}/{@code RETRY} için null olmalı.</li>
 * </ul>
 *
 * <p>{@code SmsAdapter} bu metadata'yı {@code DeliveryAttemptResult.providerMetadata}
 * generic map'ine taşır; {@code DeliveryDispatchService} ve {@code RetryWorker}
 * {@code DELIVERY_ACCEPTED} audit event details'ine merge eder
 * ({@code PiiRedactor} whitelist'i {@code segment_count} + {@code encoding} +
 * {@code actual_channel} key'lerini açar).
 */
public record SmsSendResult(
    SmsSendStatus status,
    SmsFailureClass failureClass,
    String actualProviderKey,
    String providerMsgId,
    String providerCode,
    int segmentCount,
    String encoding,
    String actualChannel
) {

    /** Send sonuç durumu. DELIVERED yok — SMS her zaman async DLR ile terminal olur. */
    public enum SmsSendStatus {
        /** Provider mesajı carrier'a kabul etti; terminal durum DLR ile gelir. */
        ACCEPTED,
        /** Kalıcı hata — retry yok. */
        FAILED,
        /** Transient hata — RetryWorker tekrar dener. */
        RETRY
    }

    public SmsSendResult {
        if (status == null) {
            throw new IllegalArgumentException("SmsSendResult.status null olamaz");
        }
        if (failureClass == null) {
            throw new IllegalArgumentException("SmsSendResult.failureClass null olamaz");
        }
        if (status == SmsSendStatus.ACCEPTED
            && (providerMsgId == null || providerMsgId.isBlank())) {
            throw new IllegalArgumentException(
                "ACCEPTED sonuç DLR correlation için non-blank providerMsgId gerektirir; "
                    + "provider correlator dönmediyse retry() kullan");
        }
        if (segmentCount < 0) {
            throw new IllegalArgumentException(
                "SmsSendResult.segmentCount negatif olamaz (got " + segmentCount + ")");
        }
        if (status == SmsSendStatus.ACCEPTED && segmentCount < 1) {
            throw new IllegalArgumentException(
                "ACCEPTED sonuç billed segment ≥1 gerektirir (got " + segmentCount + ")");
        }
        if (status != SmsSendStatus.ACCEPTED && segmentCount > 0) {
            throw new IllegalArgumentException(
                "Sadece ACCEPTED sonuç segmentCount > 0 olabilir; FAILED/RETRY "
                    + "için segmentCount=0 (mesaj gönderilmedi → billing yok)");
        }
        if (status != SmsSendStatus.ACCEPTED && actualChannel != null) {
            throw new IllegalArgumentException(
                "Sadece ACCEPTED sonuç actualChannel taşıyabilir; FAILED/RETRY "
                    + "için null (mesaj gönderilmedi → kanal bilgisi anlamsız)");
        }
    }

    /**
     * Backward-compatible: carrier kabul etti — DLR bekleniyor.
     * Default {@code segmentCount=1} + {@code encoding=null} + {@code actualChannel=null}
     * (legacy callers).
     *
     * @param actualProviderKey gerçek dispatch eden provider
     * @param providerMsgId DLR correlator ({@code "<providerKey>-<rawId>"}), non-blank
     */
    public static SmsSendResult accepted(String actualProviderKey, String providerMsgId) {
        return accepted(actualProviderKey, providerMsgId, 1, null, null);
    }

    /**
     * Backward-compatible (Faz 23.3.2 PR-A1.1): segment + encoding metadata,
     * {@code actualChannel=null}.
     *
     * @param actualProviderKey gerçek dispatch eden provider
     * @param providerMsgId DLR correlator, non-blank
     * @param segmentCount billed segment sayısı (≥1)
     * @param encoding provider encoding label (ISO-8859-9, UCS-2, GSM-7, vb.); null OK
     */
    public static SmsSendResult accepted(String actualProviderKey, String providerMsgId,
                                         int segmentCount, String encoding) {
        return accepted(actualProviderKey, providerMsgId, segmentCount, encoding, null);
    }

    /**
     * Carrier kabul etti — DLR bekleniyor + multipart metadata + actual channel
     * (Faz 23.3.2 PR-A3.1.1 Codex P2 absorb).
     *
     * @param actualProviderKey gerçek dispatch eden provider
     * @param providerMsgId DLR correlator, non-blank
     * @param segmentCount billed segment sayısı (≥1)
     * @param encoding provider encoding label (ISO-8859-9, UCS-2, GSM-7, vb.); null OK
     * @param actualChannel SOAP outbound'ta kullanılan kanal kodu
     *                      ({@code VFO} / {@code VF}); null = channel-agnostic
     */
    public static SmsSendResult accepted(String actualProviderKey, String providerMsgId,
                                         int segmentCount, String encoding,
                                         String actualChannel) {
        return new SmsSendResult(
            SmsSendStatus.ACCEPTED, SmsFailureClass.NONE,
            actualProviderKey, providerMsgId, null, segmentCount, encoding, actualChannel);
    }

    /**
     * Kalıcı hata — secondary failover {@code failureClass.failoverEligible()}
     * değerine göre {@link SmsAdapter} tarafından karar verilir.
     * {@code segmentCount=0} (mesaj gönderilmedi).
     */
    public static SmsSendResult failed(String actualProviderKey,
                                       SmsFailureClass failureClass,
                                       String providerCode) {
        return new SmsSendResult(
            SmsSendStatus.FAILED, failureClass, actualProviderKey, null, providerCode, 0, null, null);
    }

    /**
     * Transient hata — RetryWorker tekrar dener.
     * {@code segmentCount=0} (mesaj gönderilmedi).
     */
    public static SmsSendResult retry(String actualProviderKey,
                                      SmsFailureClass failureClass,
                                      String providerCode) {
        return new SmsSendResult(
            SmsSendStatus.RETRY, failureClass, actualProviderKey, null, providerCode, 0, null, null);
    }
}
