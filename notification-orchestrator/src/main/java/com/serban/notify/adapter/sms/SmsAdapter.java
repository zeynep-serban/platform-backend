package com.serban.notify.adapter.sms;

import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.template.RenderedMessage;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SMS channel adapter — Faz 23.3 multi-provider failover facade
 * (Codex `019e3f82` AGREE).
 *
 * <p><b>Tek</b> {@code channelKey="sms"} {@link ChannelAdapter} — provider
 * çoğulluğu ({@link SmsProvider} listesi) bu facade'de orchestrate edilir.
 * {@code ChannelAdapterRegistry} her channelKey için tek adapter indekslediği
 * için JetSMS/NetGSM doğrudan {@code ChannelAdapter} olamaz.
 *
 * <p><b>Faz 23.3 PR-1 (behavior-neutral)</b>: source default
 * {@code primary-provider=netgsm}, {@code secondary-provider=} (boş). JetSMS
 * primary runtime flip'i GitOps ConfigMap ile gelir (PR-4 overlay).
 *
 * <h3>Failover matrisi (PR-2 — Codex `019e3f82` absorb)</h3>
 *
 * <ul>
 *   <li><b>failover-eligible</b> ({@link SmsFailureClass#failoverEligible()}
 *       {@code true}: TIMEOUT/HTTP_5XX/PROVIDER_SYSTEM/RATE_LIMIT/
 *       QUOTA_OR_CREDIT/NO_CORRELATOR/UNKNOWN_TRANSIENT) → secondary denenir</li>
 *   <li><b>failover-NOT-eligible kalıcı</b> (INVALID_PHONE/INVALID_TEXT/
 *       IYS_OPT_OUT/POLICY_BLOCK/EMPTY_MESSAGE) → secondary denenmez
 *       (secondary'de de aynı sonuç)</li>
 *   <li><b>UNSUPPORTED_CHARSET</b> → charset-capability pre-route: secondary
 *       {@link SmsProvider#supportsUnicode()} ise denenir (örn. JetSMS
 *       ISO-8859-9 fail → NetGSM UCS-2 secondary). Hiçbiri desteklemezse
 *       primary FAILED kalır. Silent transliteration YOK.</li>
 *   <li><b>MESSAGE_TOO_LONG</b> → uzunluk-capability route: secondary
 *       {@link SmsProvider#maxMessageLength()} mesajı kaldırabiliyorsa
 *       denenir (örn. JetSMS 160 fail → NetGSM concat 670 secondary).
 *       Secondary de kaldıramazsa primary FAILED kalır.</li>
 *   <li><b>PROVIDER_CONFIG</b> → high-severity alert metric
 *       ({@code notify_sms_provider_config_error_total}); default sessiz
 *       failover YOK. Operator opt-in
 *       {@code notify.adapters.sms.failover-on-provider-config-error=true}
 *       ile kritik topic'ler için açılabilir.</li>
 * </ul>
 */
@Component
public class SmsAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(SmsAdapter.class);
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

    private final Map<String, SmsProvider> providersByKey;
    private final String primaryKey;
    private final String secondaryKey;
    private final boolean failoverOnProviderConfigError;
    private final MeterRegistry meterRegistry;

    public SmsAdapter(
        List<SmsProvider> providers,
        @Value("${notify.adapters.sms.primary-provider:netgsm}") String primaryKey,
        @Value("${notify.adapters.sms.secondary-provider:}") String secondaryKey,
        @Value("${notify.adapters.sms.failover-on-provider-config-error:false}")
            boolean failoverOnProviderConfigError,
        MeterRegistry meterRegistry
    ) {
        // Codex `019e3f82` PR-1 review P2 absorb: duplicate providerKey
        // fail-fast (ChannelAdapterRegistry duplicate-channel disiplini ile
        // paralel). Aynı providerKey iki bean'den gelirse sessiz overwrite
        // yerine startup'ta IllegalStateException.
        Map<String, SmsProvider> index = new HashMap<>();
        for (SmsProvider p : providers) {
            SmsProvider prev = index.putIfAbsent(p.providerKey(), p);
            if (prev != null) {
                throw new IllegalStateException(
                    "Duplicate SmsProvider key '" + p.providerKey() + "': "
                        + prev.getClass().getName() + " vs " + p.getClass().getName());
            }
        }
        this.providersByKey = Map.copyOf(index);
        this.primaryKey = primaryKey == null ? "" : primaryKey.trim();
        this.secondaryKey = secondaryKey == null ? "" : secondaryKey.trim();
        this.failoverOnProviderConfigError = failoverOnProviderConfigError;
        this.meterRegistry = meterRegistry;
        log.info("SmsAdapter activated: primary={} secondary={} registered={} "
                + "failoverOnProviderConfigError={}",
            this.primaryKey,
            this.secondaryKey.isEmpty() ? "(none)" : this.secondaryKey,
            providersByKey.keySet(), failoverOnProviderConfigError);
    }

    @Override
    public String channelKey() {
        return "sms";
    }

    @Override
    public DeliveryAttemptResult send(DeliveryTarget target, RenderedMessage message) {
        // 1. Validation (provider-agnostic).
        String phone = target.targetRef();
        if (phone == null || !E164.matcher(phone).matches()) {
            log.warn("sms invalid phone format: hash={}", target.recipientHash());
            return DeliveryAttemptResult.failed("phone not E.164", null);
        }
        String text = (message.bodyText() != null && !message.bodyText().isBlank())
            ? message.bodyText()
            : message.subject();
        if (text == null || text.isBlank()) {
            return DeliveryAttemptResult.failed("empty message (no body_text or subject)", null);
        }

        // 2. Primary provider.
        SmsProvider primary = providersByKey.get(primaryKey);
        if (primary == null) {
            log.error("sms primary provider '{}' not registered (registered={})",
                primaryKey, providersByKey.keySet());
            return DeliveryAttemptResult.failed(
                "sms primary provider not registered: " + primaryKey, null);
        }

        // Faz 23.3.2 PR-A3.1 (Codex thread 019e4514): SMS context extract
        // from DeliveryTarget.routingMetadata. Primary + secondary aynı
        // context'i alır.
        SmsSendContext context = extractContext(target);

        SmsSendResult primaryResult = safeSend(primary, phone, text, context);
        log.info("sms primary={} result status={} class={} hash={}",
            primaryKey, primaryResult.status(), primaryResult.failureClass(),
            target.recipientHash());

        // 3. PROVIDER_CONFIG alert — Codex `019e3fd9` P1 absorb: metric emit
        //    failover kararından ve secondary varlığından BAĞIMSIZ. Credential/
        //    originator hatası secondary olmasa da high-severity alert üretir.
        maybeEmitProviderConfigAlert(primaryResult);

        // 4. Failover decision matrix (PR-2 — Codex absorb).
        if (primaryResult.status() != SmsSendResult.SmsSendStatus.ACCEPTED
            && shouldFailover(primaryResult, text.length())) {

            SmsProvider secondary = providersByKey.get(secondaryKey);
            if (secondary != null) {
                log.info("sms failover: primary={} class={} → secondary={}",
                    primaryKey, primaryResult.failureClass(), secondaryKey);
                SmsSendResult secondaryResult = safeSend(secondary, phone, text, context);
                log.info("sms secondary={} result status={} class={} hash={}",
                    secondaryKey, secondaryResult.status(), secondaryResult.failureClass(),
                    target.recipientHash());
                maybeEmitProviderConfigAlert(secondaryResult);
                return toAttemptResult(secondaryResult);
            }
            log.warn("sms failover skipped: secondary '{}' not registered", secondaryKey);
        }

        return toAttemptResult(primaryResult);
    }

    /**
     * DeliveryTarget routingMetadata Map'inden typed {@link SmsSendContext}
     * çıkar. Codex thread {@code 019e4514} PR-A3.1 absorb: defansif string
     * cast, blank guard, missing key tolerant.
     */
    private static SmsSendContext extractContext(DeliveryTarget target) {
        java.util.Map<String, Object> meta = target.routingMetadata();
        if (meta == null || meta.isEmpty()) {
            return SmsSendContext.empty();
        }
        return new SmsSendContext(
            stringMeta(meta, "severity"),
            stringMeta(meta, "topic_key"),
            stringMeta(meta, "template_id")
        );
    }

    /** Defansif string extract — non-String veya blank null döner. */
    private static String stringMeta(java.util.Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    /**
     * {@link SmsFailureClass#PROVIDER_CONFIG} sonucunda high-severity alert
     * metric emit eder ({@code notify_sms_provider_config_error_total}).
     *
     * <p>Codex `019e3fd9` P1 absorb: bu emit failover kararından ve secondary
     * varlığından bağımsızdır — source default {@code secondary=} boş olsa
     * bile primary credential/originator hatası alert'e düşmelidir.
     */
    private void maybeEmitProviderConfigAlert(SmsSendResult result) {
        if (result.failureClass() != SmsFailureClass.PROVIDER_CONFIG) {
            return;
        }
        String provider = result.actualProviderKey() != null
            ? result.actualProviderKey() : primaryKey;
        if (meterRegistry != null) {
            meterRegistry.counter("notify_sms_provider_config_error_total",
                "provider", provider,
                "code", result.providerCode() != null ? result.providerCode() : "unknown")
                .increment();
        }
        log.error("sms PROVIDER_CONFIG error: provider={} code={} — high-severity "
                + "alert emitted (notify_sms_provider_config_error_total)",
            provider, result.providerCode());
    }

    /**
     * Primary provider sonucundan sonra secondary denenmeli mi?
     *
     * <p>Failover matrisi (Codex `019e3f82` + `019e3fd9` absorb):
     * <ul>
     *   <li>secondary yok → her zaman false</li>
     *   <li>failover-eligible transient class → true</li>
     *   <li>{@code UNSUPPORTED_CHARSET} → secondary {@code supportsUnicode()}
     *       ise true (charset-capability pre-route)</li>
     *   <li>{@code MESSAGE_TOO_LONG} → secondary {@code maxMessageLength()}
     *       mesajı kaldırabiliyorsa true (uzunluk-capability route — JetSMS
     *       160 fail → NetGSM concat secondary)</li>
     *   <li>{@code PROVIDER_CONFIG} → opt-in flag'e bağlı (alert ayrı
     *       {@link #maybeEmitProviderConfigAlert} ile emit edilir)</li>
     *   <li>diğer kalıcı class → false</li>
     * </ul>
     *
     * @param textLength gönderilen mesaj uzunluğu (MESSAGE_TOO_LONG capability
     *                   route kararı için)
     */
    private boolean shouldFailover(SmsSendResult primaryResult, int textLength) {
        if (secondaryKey.isEmpty()) {
            return false;
        }
        SmsFailureClass fc = primaryResult.failureClass();

        if (fc.failoverEligible()) {
            return true;
        }

        if (fc == SmsFailureClass.UNSUPPORTED_CHARSET) {
            // Codex thread 019e4514 iter-2 P2 absorb (PR-A1.2 hardening):
            // charset-route'da secondary'nin Unicode desteklemesi yetmez;
            // mesaj uzunluğu secondary'nin maxMessageLength()'inden büyükse
            // dispatch yine fail eder (NetGSM provider-side code 20 ile
            // MESSAGE_TOO_LONG döner; client-side preflight'la yine route
            // değil). JetSMS multipart operational açıldığında uzun mesaj
            // (>NetGSM cap) emoji içerirse silent failover yerine no-route
            // dönülmeli.
            SmsProvider secondary = providersByKey.get(secondaryKey);
            boolean unicodeOk = secondary != null && secondary.supportsUnicode();
            boolean lengthOk = secondary != null && secondary.maxMessageLength() >= textLength;
            boolean route = unicodeOk && lengthOk;
            log.info("sms UNSUPPORTED_CHARSET: textLength={} secondary={} "
                    + "supportsUnicode={} maxLength={} → route={}",
                textLength, secondaryKey, unicodeOk,
                secondary != null ? secondary.maxMessageLength() : "(none)",
                route);
            return route;
        }

        if (fc == SmsFailureClass.MESSAGE_TOO_LONG) {
            SmsProvider secondary = providersByKey.get(secondaryKey);
            boolean route = secondary != null && secondary.maxMessageLength() >= textLength;
            log.info("sms MESSAGE_TOO_LONG: textLength={} secondary={} maxLength={} → route={}",
                textLength, secondaryKey,
                secondary != null ? secondary.maxMessageLength() : "(none)", route);
            return route;
        }

        if (fc == SmsFailureClass.PROVIDER_CONFIG) {
            // Alert maybeEmitProviderConfigAlert ile zaten emit edildi —
            // burada yalnız failover kararı (default sessiz failover YOK).
            return failoverOnProviderConfigError;
        }

        return false;  // kalıcı recipient/content hatası
    }

    /**
     * Provider.send'i sarmalar — beklenmedik exception RETRY'a çevrilir.
     * Faz 23.3.2 PR-A3.1 (Codex thread {@code 019e4514}): context-aware
     * 3-arg overload'u kullanır; context-agnostic provider'lar (NetGSM)
     * default impl üzerinden legacy 2-arg path'e düşer.
     */
    private SmsSendResult safeSend(SmsProvider provider, String phone, String text,
                                   SmsSendContext context) {
        try {
            return provider.send(phone, text, context != null ? context : SmsSendContext.empty());
        } catch (RuntimeException e) {
            log.warn("sms provider {} threw {} — RETRY",
                provider.providerKey(), e.getClass().getSimpleName());
            return SmsSendResult.retry(provider.providerKey(),
                SmsFailureClass.UNKNOWN_TRANSIENT, "exc:" + e.getClass().getSimpleName());
        }
    }

    /** {@link SmsSendResult} → {@link ChannelAdapter.DeliveryAttemptResult}. */
    private static DeliveryAttemptResult toAttemptResult(SmsSendResult r) {
        // Codex `019e3fc5` PR-1 review P2 absorb: providerCode numeric ise
        // structured providerResponseCode alanına da taşı (JetSMS Status -5,
        // NetGSM code 50 gibi). Non-numeric ("http503", "config", "io:...") →
        // null; bilgi failureReason string'inde korunur.
        Integer responseCode = parseNumericCode(r.providerCode());
        return switch (r.status()) {
            case ACCEPTED -> DeliveryAttemptResult.accepted(
                r.providerMsgId(), r.actualProviderKey(), smsMetadata(r));
            case FAILED -> DeliveryAttemptResult.failed(
                "sms " + r.actualProviderKey() + " " + r.failureClass()
                    + (r.providerCode() != null ? " (" + r.providerCode() + ")" : ""),
                responseCode, r.actualProviderKey());
            case RETRY -> DeliveryAttemptResult.retry(
                "sms " + r.actualProviderKey() + " " + r.failureClass()
                    + (r.providerCode() != null ? " (" + r.providerCode() + ")" : ""),
                responseCode, r.actualProviderKey());
        };
    }

    /**
     * SMS-specific provider metadata extract — Faz 23.3.2 PR-A1.1 (Codex
     * thread {@code 019e4514}). Yalnız ACCEPTED sonuçlardaki billed segment
     * sayısı + provider encoding taşınır.
     *
     * <p>FAILED/RETRY için metadata boş — "billed segment" anlamı vermemeli;
     * `MESSAGE_TOO_LONG` failure diagnostic zaten {@code providerCode}
     * içinde ({@code "segments7"}). İleride failure estimate gerekirse ayrı
     * key ({@code estimated_segment_count}), {@code segment_count} değil.
     */
    private static java.util.Map<String, Object> smsMetadata(SmsSendResult r) {
        java.util.Map<String, Object> metadata = new java.util.HashMap<>();
        if (r.segmentCount() > 0) {
            metadata.put("segment_count", r.segmentCount());
        }
        if (r.encoding() != null && !r.encoding().isBlank()) {
            metadata.put("encoding", r.encoding());
        }
        return metadata;
    }

    /** providerCode numeric ise Integer, değilse null. */
    private static Integer parseNumericCode(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) return null;
        try {
            return Integer.valueOf(providerCode.trim());
        } catch (NumberFormatException nfe) {
            return null;  // "http503", "config", "io:..." gibi non-numeric
        }
    }
}
