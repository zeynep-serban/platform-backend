package com.serban.notify.adapter.sms;

/**
 * SMS send context — Faz 23.3.2 PR-A3.1 (Codex thread {@code 019e4514}).
 *
 * <p>Typed routing context — {@link SmsProvider#send(String, String, SmsSendContext)}
 * context-aware overload'una geçirilir. {@code DeliveryTarget.routingMetadata}
 * generic Map'inden {@link SmsAdapter} tarafından extract edilir.
 *
 * <p><b>JetSMS channel routing</b> (PR-A3.1):
 * <ul>
 *   <li>{@link #topicKey} OTP allowlist'inde → channel = {@code VFO} (OTP)</li>
 *   <li>{@link #templateId} OTP allowlist'inde → channel = {@code VFO}</li>
 *   <li>Aksi halde → channel = config default ({@code VF} BULK)</li>
 * </ul>
 *
 * <p>{@link #severity} BU SÜRÜMDE channel routing'inde KULLANILMAZ. Codex
 * thread {@code 019e4514} absorb: VFO OTP-only kanaldır; kritik+uzun mesaj
 * VFO'ya otomatik route edilmez (operator carrier kısa OTP-style bekler).
 * Severity audit/metric için context'te taşınır ama channel kararı vermez.
 *
 * <p><b>Backward-compat</b>: tüm field'lar nullable. {@link #empty()} factory
 * routing context bilinmeyen path'lerden çağrılırsa kullanılır;
 * {@link SmsProvider#send(String, String)} legacy 2-arg method default
 * empty context'i bypass eder.
 */
public record SmsSendContext(
    String severity,
    String topicKey,
    String templateId
) {

    /** Empty context — routing metadata unavailable. */
    public static SmsSendContext empty() {
        return new SmsSendContext(null, null, null);
    }

    /** {@code topicKey} non-blank mı? */
    public boolean hasTopicKey() {
        return topicKey != null && !topicKey.isBlank();
    }

    /** {@code templateId} non-blank mı? */
    public boolean hasTemplateId() {
        return templateId != null && !templateId.isBlank();
    }

    /** {@code severity} non-blank mı? (audit hint, channel kararı vermez). */
    public boolean hasSeverity() {
        return severity != null && !severity.isBlank();
    }
}
