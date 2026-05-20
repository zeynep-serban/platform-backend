package com.serban.notify.adapter.sms;

import java.util.List;

/**
 * SMS provider abstraction — Faz 23.3 multi-provider (Codex `019e3f82`
 * iter-2 AGREE).
 *
 * <p>Tek bir {@code ChannelAdapter} ({@link SmsAdapter}, {@code channelKey="sms"})
 * birden fazla SMS provider'ı bu interface üzerinden orchestrate eder.
 * {@code ChannelAdapterRegistry} her channelKey için TEK adapter indeksler;
 * provider çoğulluğu bu katmanda çözülür.
 *
 * <p>Implementasyonlar:
 * <ul>
 *   <li>{@code NetGsmProvider} — NetGSM REST v2, DLR webhook PUSH</li>
 *   <li>{@code JetSmsProvider} — JetSMS HTTP API, DLR polling PULL (Faz 23.3 PR-2)</li>
 * </ul>
 *
 * <p>Her implementasyon {@code @Component} bean'dir; {@link SmsAdapter}
 * constructor injection ile {@code List<SmsProvider>} toplar ve
 * {@link #providerKey()} ile indeksler.
 */
public interface SmsProvider {

    /** Provider DLR (Delivery Receipt) toplama modeli. */
    enum SmsDlrMode {
        /** Provider DLR'yi webhook ile POST eder (NetGSM). */
        PUSH,
        /** Backend provider'ı periyodik poll eder (JetSMS HttpSmsReport). */
        POLL
    }

    /**
     * Provider tanımlayıcısı — {@code notification_delivery.provider} kolonu
     * + {@code providerMsgId} prefix. Lowercase, tek kelime.
     * Örnek: {@code "netgsm"}, {@code "jetsms"}.
     */
    String providerKey();

    /**
     * SMS gönder (legacy 2-arg).
     *
     * @param e164Phone E.164 telefon ({@code +905321234567}); provider kendi
     *                  formatına dönüştürür (örn. JetSMS {@code +} strip)
     * @param text mesaj metni (render edilmiş, plain text)
     * @return send sonucu — {@link SmsSendResult#actualProviderKey()} bu
     *         provider'ın {@link #providerKey()} değerine eşit olmalı
     */
    SmsSendResult send(String e164Phone, String text);

    /**
     * SMS gönder (context-aware) — Faz 23.3.2 PR-A3.1 (Codex thread
     * {@code 019e4514}).
     *
     * <p>Provider implementasyonu context'ten routing kararı verebilir
     * (örn. JetSMS topic/template allowlist → VFO channel). Default 2-arg
     * legacy path'e düşer (context yokmuş gibi davranır) — NetGSM gibi
     * context-aware olmayan provider'lar override etmez.
     *
     * @param e164Phone E.164 telefon
     * @param text rendered text
     * @param context routing context (severity audit hint, topic/template
     *                channel select); {@code null} OK ama prefer
     *                {@link SmsSendContext#empty()}
     * @return send sonucu
     */
    default SmsSendResult send(String e164Phone, String text, SmsSendContext context) {
        return send(e164Phone, text);
    }

    /** Bu provider DLR'yi nasıl bildiriyor (PUSH webhook / POLL). */
    SmsDlrMode dlrMode();

    /**
     * Provider Unicode (UCS-2 / emoji / CJK) destekliyor mu?
     *
     * <p>{@code false} dönen provider'lar (örn. JetSMS ISO-8859-9) charset
     * dışı karakter içeren mesajı {@link SmsFailureClass#UNSUPPORTED_CHARSET}
     * ile reddeder; {@link SmsAdapter} Unicode destekleyen secondary'ye
     * pre-route eder (Codex `019e3f82` absorb — silent transliteration YOK).
     */
    boolean supportsUnicode();

    /**
     * Provider'ın kabul ettiği maksimum mesaj uzunluğu (karakter).
     *
     * <p>Codex `019e3fd9` PR-2 review P1 absorb: provider-capability route.
     * JetSMS tek segment 160 char hard limit; NetGSM concat SMS (çok-segment)
     * desteklediği için pratikte yüksek limit. {@link SmsAdapter}
     * {@link SmsFailureClass#MESSAGE_TOO_LONG} sonucunda secondary
     * {@code maxMessageLength()} mesajı kaldırabiliyorsa charset-route'a
     * benzer uzunluk-capability route uygular.
     *
     * <p>Default 160 (konservatif tek-segment); concat destekleyen provider
     * (NetGSM) override eder.
     */
    default int maxMessageLength() {
        return 160;
    }

    /**
     * DLR durumlarını poll et (yalnızca {@link SmsDlrMode#POLL} provider'lar).
     *
     * <p>PUSH-mode provider'lar bunu implement etmez (default
     * {@link UnsupportedOperationException}). POLL-mode provider'lar
     * (JetSMS) {@code HttpSmsReport}-benzeri batch sorgu yapar.
     *
     * @param providerMsgIds raw provider message ID listesi ({@code providerKey}
     *                       prefix'i ÇIKARILMIŞ — örn. {@code "756464245"})
     * @return her ID için DLR durumu (sıralama input ile aynı olmayabilir;
     *         caller {@link SmsDlrPollResult#rawProviderMsgId()} ile eşler)
     */
    default List<SmsDlrPollResult> pollDelivery(List<String> providerMsgIds) {
        throw new UnsupportedOperationException(
            providerKey() + " POLL-mode provider değil (dlrMode=" + dlrMode() + ")");
    }
}
