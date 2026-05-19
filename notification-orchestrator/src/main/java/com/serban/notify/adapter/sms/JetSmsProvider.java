package com.serban.notify.adapter.sms;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * JetSMS SMS provider — Faz 23.3 multi-provider (Codex `019e3f82` AGREE, PR-2).
 *
 * <p>JetSMS HTTP API: {@code https://api.jetsms.com.tr/SMS-Web/HttpSmsSend}
 * (POST {@code application/x-www-form-urlencoded}).
 *
 * <h3>Encoding — ISO-8859-9 (Türkçe Latin-5)</h3>
 *
 * <p>JetSMS dokümanı: body bytes {@code ISO-8859-9} ile encode edilir,
 * standart {@code URLEncoder} (UTF-8) <b>kullanılmaz</b>. Yalnızca
 * {@code !*'();:@&=+$,/?%#[]} reserved karakterleri {@code %XX} hex'e
 * çevrilir; Türkçe karakterler raw bırakılır (byte-level ISO-8859-9).
 *
 * <p><b>Charset preflight</b> (Codex `019e3f82` absorb): ISO-8859-9 dışı
 * karakter (emoji, CJK, smart quote) içeren mesaj
 * {@link SmsFailureClass#UNSUPPORTED_CHARSET} ile reddedilir — silent
 * transliteration YAPILMAZ. {@link SmsAdapter} Unicode destekleyen
 * secondary'ye (NetGSM) charset-capability pre-route eder.
 *
 * <h3>Response — plain text key=value</h3>
 *
 * <p>{@code Status=0 MessageIDs=756464245}. Status 0 başarı; negatif hata.
 * MessageID positive geçerli, {@code -1} invalid msisdn, {@code -2} invalid
 * text. ACCEPTED correlator: {@code "jetsms-<messageId>"}.
 *
 * <h3>DLR — POLL</h3>
 *
 * <p>JetSMS DLR webhook GÖNDERMEZ; backend {@code HttpSmsReport} endpoint'ini
 * poll eder ({@link #dlrMode()} = {@link SmsProvider.SmsDlrMode#POLL}).
 * Poll impl PR-3 ({@code JetSmsDlrPollingWorker}); PR-2'de
 * {@link #pollDelivery} default UnsupportedOperationException kalır —
 * PR-3'te override edilir.
 */
@Component
public class JetSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(JetSmsProvider.class);
    private static final int CONNECT_TIMEOUT_SEC = 5;
    private static final int RESPONSE_TIMEOUT_SEC = 15;
    public static final String PROVIDER_KEY = "jetsms";

    /** JetSMS ek yardım dosyası: bu karakterler %XX hex'e çevrilir, diğerleri raw. */
    private static final String RESERVED_CHARS = "!*'();:@&=+$,/?%#[]";
    /** JetSMS body charset — Türkçe Latin-5. */
    static final Charset ISO_8859_9 = Charset.forName("ISO-8859-9");
    /** JetSMS tek mesaj uzunluk limiti (dokümandan). */
    private static final int MAX_MESSAGE_LENGTH = 160;

    @Value("${notify.adapters.sms.jetsms.api-url:https://api.jetsms.com.tr/SMS-Web/HttpSmsSend}")
    private String apiUrl;

    @Value("${notify.adapters.sms.jetsms.report-url:https://api.jetsms.com.tr/SMS-Web/HttpSmsReport}")
    private String reportUrl;

    @Value("${notify.adapters.sms.jetsms.username:}")
    private String username;

    @Value("${notify.adapters.sms.jetsms.password:}")
    private String password;

    @Value("${notify.adapters.sms.jetsms.originator:}")
    private String originator;

    @Override
    public String providerKey() {
        return PROVIDER_KEY;
    }

    @Override
    public SmsDlrMode dlrMode() {
        return SmsDlrMode.POLL;
    }

    @Override
    public boolean supportsUnicode() {
        return false;  // JetSMS ISO-8859-9 — Unicode (emoji/CJK) desteklemez
    }

    @Override
    public SmsSendResult send(String e164Phone, String text) {
        // 1. Config gate — username/originator boşsa fail-closed (PROVIDER_CONFIG).
        if (username == null || username.isBlank()
            || originator == null || originator.isBlank()) {
            log.warn("jetsms config missing: username/originator empty");
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.PROVIDER_CONFIG, "config");
        }
        // 2. Empty text guard.
        if (text == null || text.isBlank()) {
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.EMPTY_MESSAGE, null);
        }
        // 3. Message length guard (JetSMS 160 char limit).
        if (text.length() > MAX_MESSAGE_LENGTH) {
            log.warn("jetsms message too long: len={} max={}", text.length(), MAX_MESSAGE_LENGTH);
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.MESSAGE_TOO_LONG,
                "len" + text.length());
        }
        // 4. Charset preflight — ISO-8859-9 encode edilemiyorsa UNSUPPORTED_CHARSET
        //    (Codex absorb: silent transliteration YOK; SmsAdapter Unicode
        //    destekleyen secondary'ye pre-route eder).
        if (!ISO_8859_9.newEncoder().canEncode(text)) {
            log.warn("jetsms text not ISO-8859-9 encodable — UNSUPPORTED_CHARSET");
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.UNSUPPORTED_CHARSET,
                "charset");
        }

        // 5. Phone format — JetSMS "90..." (+ strip, no leading +).
        String phone = (e164Phone != null && e164Phone.startsWith("+"))
            ? e164Phone.substring(1) : e164Phone;

        // 6. form-urlencoded body — custom encode (JetSMS reserved-set), tüm
        //    body ISO-8859-9 byte'a StringEntity charset ile çevrilir.
        String body = "Username=" + jetEncode(username)
            + "&Password=" + jetEncode(password)
            + "&Msisdns=" + jetEncode(phone)
            + "&Messages=" + jetEncode(text)
            + "&Originator=" + jetEncode(originator);

        // 7. POST
        try (CloseableHttpClient client = newClient()) {
            HttpPost post = new HttpPost(apiUrl);
            post.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=ISO-8859-9");
            post.setEntity(new StringEntity(body, ISO_8859_9));

            return client.execute(post, response -> {
                int httpCode = response.getCode();
                String respBody = readEntityBody(response);

                // 7a. HTTP-level dispatch.
                if (httpCode >= 500) {
                    log.warn("jetsms transient HTTP RETRY: code={}", httpCode);
                    return SmsSendResult.retry(PROVIDER_KEY, SmsFailureClass.HTTP_5XX,
                        "http" + httpCode);
                }
                if (httpCode == 401 || httpCode == 403) {
                    log.warn("jetsms auth HTTP FAIL: code={}", httpCode);
                    return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.PROVIDER_CONFIG,
                        "http" + httpCode);
                }
                if (httpCode >= 400) {
                    log.warn("jetsms permanent HTTP FAIL: code={}", httpCode);
                    return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.PROVIDER_SYSTEM,
                        "http" + httpCode);
                }

                // 7b. Plain-text response parse: "Status=X MessageIDs=Y".
                if (respBody.isBlank()) {
                    log.warn("jetsms 2xx empty body (RETRY)");
                    return SmsSendResult.retry(PROVIDER_KEY,
                        SmsFailureClass.UNKNOWN_TRANSIENT, "empty-body");
                }
                return parseResponse(respBody);
            });
        } catch (IOException e) {
            log.warn("jetsms IOException (RETRY): {}", e.getClass().getSimpleName());
            return SmsSendResult.retry(PROVIDER_KEY, SmsFailureClass.TIMEOUT,
                "io:" + e.getClass().getSimpleName());
        }
    }

    /**
     * JetSMS DLR poll — {@code HttpSmsReport} (Faz 23.3 PR-3).
     *
     * <p>JetSMS DLR webhook GÖNDERMEZ; backend bu method ile periyodik poll
     * eder ({@code JetSmsDlrPollingWorker}). POST form-urlencoded:
     * {@code Username/Password/Originator/MessageIDs} ({@code |} ayraçlı).
     * Response: {@code Status=0 MessageStates=2|2}.
     *
     * <p>MessageState → {@link SmsDlrPollResult.DeliveryStatus}:
     * {@code 1}→DELIVERED; {@code 3/4/9}→FAILED; {@code 0/2/6/7/8}→PENDING
     * (yeniden poll); {@code -1}→FAILED (yanlış MessageID — correlator
     * güvenilemez, terminal FAILED).
     *
     * @param providerMsgIds raw JetSMS message ID listesi ({@code "jetsms-"}
     *                       prefix ÇIKARILMIŞ)
     * @return her input ID için DLR durumu (sıra input ile aynı; eşleşmeyen
     *         ID PENDING-fallback)
     */
    @Override
    public java.util.List<SmsDlrPollResult> pollDelivery(java.util.List<String> providerMsgIds) {
        if (providerMsgIds == null || providerMsgIds.isEmpty()) {
            return java.util.List.of();
        }
        if (username == null || username.isBlank()
            || originator == null || originator.isBlank()) {
            log.warn("jetsms DLR poll config missing — pending fallback");
            return providerMsgIds.stream()
                .map(id -> SmsDlrPollResult.pending(id, "config"))
                .toList();
        }

        String joined = String.join("|", providerMsgIds);
        String body = "Username=" + jetEncode(username)
            + "&Password=" + jetEncode(password)
            + "&Originator=" + jetEncode(originator)
            + "&MessageIDs=" + jetEncode(joined);

        try (CloseableHttpClient client = newClient()) {
            HttpPost post = new HttpPost(reportUrl);
            post.setHeader("Content-Type", "application/x-www-form-urlencoded; charset=ISO-8859-9");
            post.setEntity(new StringEntity(body, ISO_8859_9));

            return client.execute(post, response -> {
                int httpCode = response.getCode();
                String respBody = readEntityBody(response);
                if (httpCode >= 400 || respBody.isBlank()) {
                    log.warn("jetsms DLR poll HTTP {} / empty — pending fallback", httpCode);
                    return providerMsgIds.stream()
                        .map(id -> SmsDlrPollResult.pending(id, "http" + httpCode))
                        .toList();
                }
                return parseReportResponse(providerMsgIds, respBody);
            });
        } catch (IOException e) {
            log.warn("jetsms DLR poll IOException ({}) — pending fallback",
                e.getClass().getSimpleName());
            return providerMsgIds.stream()
                .map(id -> SmsDlrPollResult.pending(id, "io"))
                .toList();
        }
    }

    /**
     * {@code HttpSmsReport} response parse: {@code Status=0 MessageStates=2|2}.
     * MessageStates input MessageIDs ile aynı sırada; index-eşleme.
     */
    static java.util.List<SmsDlrPollResult> parseReportResponse(
            java.util.List<String> providerMsgIds, String body) {
        String statusRaw = extractField(body, "Status");
        int status;
        try {
            status = Integer.parseInt(statusRaw.trim());
        } catch (NumberFormatException nfe) {
            log.warn("jetsms DLR poll unparseable Status — pending fallback");
            return providerMsgIds.stream()
                .map(id -> SmsDlrPollResult.pending(id, "bad-status"))
                .toList();
        }
        if (status != 0) {
            // Status -3 (bad MessageID format) / -5 (login) / -15 / -99 →
            // bu batch poll edilemedi; tümü pending, sonraki cycle tekrar.
            log.warn("jetsms DLR poll Status={} — pending fallback (batch retry)", status);
            return providerMsgIds.stream()
                .map(id -> SmsDlrPollResult.pending(id, String.valueOf(status)))
                .toList();
        }

        String statesRaw = extractField(body, "MessageStates");
        String[] states = statesRaw.isEmpty()
            ? new String[0] : statesRaw.split("\\|", -1);

        java.util.List<SmsDlrPollResult> results = new java.util.ArrayList<>(providerMsgIds.size());
        for (int i = 0; i < providerMsgIds.size(); i++) {
            String rawId = providerMsgIds.get(i);
            if (i >= states.length) {
                // Response state count input'tan az — eşleşmeyen ID pending.
                results.add(SmsDlrPollResult.pending(rawId, "no-state"));
                continue;
            }
            results.add(mapMessageState(rawId, states[i].trim()));
        }
        return results;
    }

    /**
     * JetSMS MessageState kodu → {@link SmsDlrPollResult}.
     *
     * <p>{@code 1}→DELIVERED; {@code 3/4/9}→FAILED; {@code 0/2/6/7/8}→PENDING;
     * {@code -1}→FAILED (yanlış MessageID); unparseable→PENDING.
     */
    static SmsDlrPollResult mapMessageState(String rawId, String stateRaw) {
        int state;
        try {
            state = Integer.parseInt(stateRaw);
        } catch (NumberFormatException nfe) {
            return SmsDlrPollResult.pending(rawId, "bad-state");
        }
        return switch (state) {
            case 1 -> SmsDlrPollResult.delivered(rawId, "1");
            case 3, 4, 9 -> SmsDlrPollResult.failed(rawId, String.valueOf(state));
            case -1 -> SmsDlrPollResult.failed(rawId, "-1");  // wrong MessageID
            case 0, 2, 6, 7, 8 -> SmsDlrPollResult.pending(rawId, String.valueOf(state));
            default -> SmsDlrPollResult.pending(rawId, String.valueOf(state));
        };
    }

    /**
     * JetSMS plain-text response parse: {@code Status=<int> MessageIDs=<id>}.
     *
     * <p>Status 0 + MessageID positive → ACCEPTED. Status/MessageID negatif →
     * {@link SmsFailureClass} mapping.
     */
    static SmsSendResult parseResponse(String body) {
        String statusRaw = extractField(body, "Status");
        String messageIdRaw = extractField(body, "MessageIDs");

        int status;
        try {
            status = Integer.parseInt(statusRaw.trim());
        } catch (NumberFormatException nfe) {
            log.warn("jetsms unparseable Status (RETRY): raw_len={}", statusRaw.length());
            return SmsSendResult.retry(PROVIDER_KEY,
                SmsFailureClass.UNKNOWN_TRANSIENT, "bad-status");
        }

        // Status != 0 — send-level hata.
        if (status != 0) {
            SmsFailureClass cls = statusFailureClass(status);
            if (cls.failoverEligible()) {
                log.warn("jetsms send Status={} → RETRY/{}", status, cls);
                return SmsSendResult.retry(PROVIDER_KEY, cls, String.valueOf(status));
            }
            log.warn("jetsms send Status={} → FAILED/{}", status, cls);
            return SmsSendResult.failed(PROVIDER_KEY, cls, String.valueOf(status));
        }

        // Status == 0 — MessageID kontrol (-1 invalid msisdn, -2 invalid text,
        // çoklu gönderimde "|" ayraçlı; biz tek msisdn → ilk segment).
        String firstId = messageIdRaw.contains("|")
            ? messageIdRaw.substring(0, messageIdRaw.indexOf('|')) : messageIdRaw;
        firstId = firstId.trim();
        int messageId;
        try {
            messageId = Integer.parseInt(firstId);
        } catch (NumberFormatException nfe) {
            log.warn("jetsms Status=0 but unparseable MessageID (RETRY)");
            return SmsSendResult.retry(PROVIDER_KEY,
                SmsFailureClass.NO_CORRELATOR, "bad-msgid");
        }
        if (messageId == -1) {
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.INVALID_PHONE, "msgid-1");
        }
        if (messageId == -2) {
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.INVALID_TEXT, "msgid-2");
        }
        if (messageId <= 0) {
            log.warn("jetsms Status=0 non-positive MessageID={} (RETRY)", messageId);
            return SmsSendResult.retry(PROVIDER_KEY,
                SmsFailureClass.NO_CORRELATOR, "msgid" + messageId);
        }
        // Geçerli MessageID → ACCEPTED (terminal durum DLR polling ile gelir).
        String correlator = PROVIDER_KEY + "-" + messageId;
        log.info("jetsms ACCEPTED (awaits DLR poll): msg_id={}", correlator);
        return SmsSendResult.accepted(PROVIDER_KEY, correlator);
    }

    /**
     * JetSMS send Status kodu → {@link SmsFailureClass}.
     *
     * <p>-5 login / -6 data / -7 senddate / -8 msisdn / -9 message → kalıcı;
     * -15 system / -99 unknown → transient.
     */
    static SmsFailureClass statusFailureClass(int status) {
        return switch (status) {
            case -5 -> SmsFailureClass.PROVIDER_CONFIG;   // login error (username/password/originator)
            case -6 -> SmsFailureClass.PROVIDER_SYSTEM;   // data error
            case -7 -> SmsFailureClass.INVALID_TEXT;      // senddate error
            case -8 -> SmsFailureClass.INVALID_PHONE;     // msisdn missing
            case -9 -> SmsFailureClass.EMPTY_MESSAGE;     // message missing
            case -15 -> SmsFailureClass.PROVIDER_SYSTEM;  // system fault → transient
            default -> SmsFailureClass.UNKNOWN_TRANSIENT; // -99 ve bilinmeyen
        };
    }

    /**
     * JetSMS field extract — plain-text {@code Key=Value} (boşluk/newline ayraçlı).
     * Yoksa boş string.
     */
    static String extractField(String body, String key) {
        int idx = body.indexOf(key + "=");
        if (idx < 0) return "";
        int start = idx + key.length() + 1;
        int end = start;
        while (end < body.length()
            && !Character.isWhitespace(body.charAt(end))) {
            end++;
        }
        return body.substring(start, end);
    }

    /**
     * JetSMS custom URL-encode (ek yardım dosyası): yalnız
     * {@link #RESERVED_CHARS} {@code %XX} hex'e çevrilir; diğerleri (Türkçe
     * dahil) raw bırakılır. Standart {@code URLEncoder} (UTF-8 + boşluk→+)
     * KULLANILMAZ.
     */
    static String jetEncode(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (RESERVED_CHARS.indexOf(c) >= 0) {
                sb.append('%').append(String.format("%02X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String readEntityBody(org.apache.hc.core5.http.ClassicHttpResponse response)
            throws IOException {
        if (response.getEntity() == null) return "";
        try (var stream = response.getEntity().getContent()) {
            if (stream == null) return "";
            return new String(stream.readAllBytes(), ISO_8859_9);
        }
    }

    private static CloseableHttpClient newClient() {
        RequestConfig config = RequestConfig.custom()
            .setConnectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .setResponseTimeout(RESPONSE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build();
        return HttpClients.custom().setDefaultRequestConfig(config).build();
    }
}
