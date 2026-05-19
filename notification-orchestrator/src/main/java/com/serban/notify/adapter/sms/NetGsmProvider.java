package com.serban.notify.adapter.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * NetGSM SMS provider — Faz 23.3 multi-provider (Codex `019e3f82` AGREE).
 *
 * <p><b>Faz 23.3 PR-1 refactor</b>: bu sınıf eski {@code NetGsmSmsAdapter}
 * ({@code ChannelAdapter}) logic'inin birebir taşınmış halidir. Artık
 * {@code ChannelAdapter} DEĞİL — {@link SmsProvider}. SMS channelKey
 * orchestration {@link SmsAdapter} facade'inde; bu sınıf yalnızca
 * NetGSM-specific HTTP + response mapping yapar. Davranış behavior-neutral
 * korunmuştur (status semantiği eski adapter ile aynı).
 *
 * <p>NetGSM REST API: {@code https://api.netgsm.com.tr/sms/rest/v2/send}
 *
 * <p>Status semantiği (Faz 23.4 PR-F provider terminal authority via DLR):
 * <ul>
 *   <li>HTTP 2xx + code "00" + non-blank jobid → ACCEPTED (carrier queued)</li>
 *   <li>HTTP 2xx + code "00" + missing jobid → RETRY / {@code NO_CORRELATOR}</li>
 *   <li>HTTP 4xx / permanent code (20/30/40/50/70) → FAILED</li>
 *   <li>HTTP 5xx / timeout / IOException / code 60 / unknown → RETRY</li>
 * </ul>
 *
 * <p>NetGSM code → {@link SmsFailureClass}:
 * <ul>
 *   <li>20 (too long) → MESSAGE_TOO_LONG</li>
 *   <li>30 (bad credentials) / 40 (bad msgheader) → PROVIDER_CONFIG</li>
 *   <li>50 (bad phone) → INVALID_PHONE</li>
 *   <li>70 (IYS opt-out) → IYS_OPT_OUT</li>
 *   <li>60 (insufficient credit) → QUOTA_OR_CREDIT (RETRY)</li>
 *   <li>missing/unknown → UNKNOWN_TRANSIENT (RETRY)</li>
 * </ul>
 *
 * <p>DLR mode: {@link SmsProvider.SmsDlrMode#PUSH} — NetGSM webhook POST eder
 * {@code /api/v1/notify/dlr/netgsm} (DlrController).
 *
 * <p>Unicode: NetGSM UCS-2 destekler ({@code encoding=TR}) → supportsUnicode=true.
 */
@Component
public class NetGsmProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(NetGsmProvider.class);
    private static final int CONNECT_TIMEOUT_SEC = 5;
    private static final int RESPONSE_TIMEOUT_SEC = 15;
    static final String PROVIDER_KEY = "netgsm";

    private final ObjectMapper objectMapper;

    @Value("${notify.adapters.sms.netgsm.api-url:https://api.netgsm.com.tr/sms/rest/v2/send}")
    private String apiUrl;

    @Value("${notify.adapters.sms.netgsm.username:}")
    private String username;

    @Value("${notify.adapters.sms.netgsm.password:}")
    private String password;

    @Value("${notify.adapters.sms.netgsm.msgheader:Notify}")
    private String msgheader;

    public NetGsmProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerKey() {
        return PROVIDER_KEY;
    }

    @Override
    public SmsDlrMode dlrMode() {
        return SmsDlrMode.PUSH;
    }

    @Override
    public boolean supportsUnicode() {
        return true;  // NetGSM UCS-2 (encoding=TR)
    }

    /**
     * NetGSM REST v2 concat (çok-segment) SMS destekler — pratik üst sınır
     * 10 segment. UCS-2 (Türkçe) 67 char/segment → 670; GSM-7 153 char/segment
     * → 1530. Konservatif olarak UCS-2 tabanlı 670 döner (Codex `019e3fd9`
     * P1 absorb: MESSAGE_TOO_LONG capability route — JetSMS 160 fail →
     * NetGSM secondary'ye uzunluk-route).
     */
    @Override
    public int maxMessageLength() {
        return 670;
    }

    @Override
    public SmsSendResult send(String e164Phone, String text) {
        // 1. Config gate — username boşsa fail-closed (PROVIDER_CONFIG).
        if (username == null || username.isBlank()) {
            log.warn("netgsm config missing: username empty");
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.PROVIDER_CONFIG, "config");
        }
        // 2. Empty text guard (SmsAdapter zaten kontrol eder; defense-in-depth).
        if (text == null || text.isBlank()) {
            return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.EMPTY_MESSAGE, null);
        }

        // 3. Encoding + segment metadata (billing audit log).
        String encoding = SmsSegmentEncoder.encoding(text);
        int segments = SmsSegmentEncoder.segmentCount(text);
        log.info("netgsm send: encoding={} segments={} len={}", encoding, segments, text.length());

        // 4. NetGSM REST v2 payload — phone: + strip (NetGSM "no" param).
        String phone = (e164Phone != null && e164Phone.startsWith("+"))
            ? e164Phone.substring(1) : e164Phone;
        Map<String, Object> payload = new HashMap<>();
        payload.put("msgheader", msgheader);
        payload.put("encoding", "UCS-2".equals(encoding) ? "TR" : "");
        Map<String, Object> message1 = new HashMap<>();
        message1.put("msg", text);
        message1.put("no", phone);
        payload.put("messages", new Object[] { message1 });

        // 5. POST
        try (CloseableHttpClient client = newClient()) {
            HttpPost post = new HttpPost(apiUrl);
            post.setHeader("Content-Type", "application/json; charset=utf-8");
            post.setHeader("Authorization", basicAuth(username, password));
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(payload),
                StandardCharsets.UTF_8));

            return client.execute(post, response -> {
                int httpCode = response.getCode();
                String respBody = readEntityBody(response);

                // 5a. HTTP-level
                if (httpCode >= 500) {
                    log.warn("netgsm transient HTTP RETRY: code={} body_len={}",
                        httpCode, respBody.length());
                    return SmsSendResult.retry(PROVIDER_KEY, SmsFailureClass.HTTP_5XX,
                        "http" + httpCode);
                }
                if (httpCode == 401 || httpCode == 403) {
                    log.warn("netgsm auth HTTP FAIL: code={}", httpCode);
                    return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.PROVIDER_CONFIG,
                        "http" + httpCode);
                }
                if (httpCode >= 400) {
                    log.warn("netgsm permanent HTTP FAIL: code={} body_len={}",
                        httpCode, respBody.length());
                    return SmsSendResult.failed(PROVIDER_KEY, SmsFailureClass.PROVIDER_SYSTEM,
                        "http" + httpCode);
                }

                // 5b. Provider response code parse — drift safety RETRY.
                String code;
                String netgsmJobid;
                try {
                    if (respBody.isEmpty()) {
                        log.warn("netgsm 2xx empty body (RETRY): code={}", httpCode);
                        return SmsSendResult.retry(PROVIDER_KEY,
                            SmsFailureClass.UNKNOWN_TRANSIENT, "empty-body");
                    }
                    JsonNode node = objectMapper.readTree(respBody);
                    code = node.path("code").asText("");
                    netgsmJobid = node.path("jobid").asText("");
                } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
                    log.warn("netgsm 2xx malformed JSON (RETRY): http={} body_len={}",
                        httpCode, respBody.length());
                    return SmsSendResult.retry(PROVIDER_KEY,
                        SmsFailureClass.UNKNOWN_TRANSIENT, "malformed-body");
                }

                // 5c. code=00 → ACCEPTED (jobid zorunlu — DLR correlator).
                if ("00".equals(code)) {
                    if (netgsmJobid.isEmpty()) {
                        log.warn("netgsm code=00 jobid empty — RETRY (no DLR correlator)");
                        return SmsSendResult.retry(PROVIDER_KEY,
                            SmsFailureClass.NO_CORRELATOR, "00-no-jobid");
                    }
                    String correlator = PROVIDER_KEY + "-" + netgsmJobid;
                    log.info("netgsm ACCEPTED (awaits DLR): msg_id={}", correlator);
                    return SmsSendResult.accepted(PROVIDER_KEY, correlator);
                }

                // 5d. Provider error code → failureClass.
                SmsFailureClass permanent = permanentClass(code);
                if (permanent != null) {
                    log.warn("netgsm permanent provider FAIL: code={} class={}", code, permanent);
                    return SmsSendResult.failed(PROVIDER_KEY, permanent, code);
                }
                // 60 (credit) ve unknown → RETRY.
                SmsFailureClass transientClass = "60".equals(code)
                    ? SmsFailureClass.QUOTA_OR_CREDIT : SmsFailureClass.UNKNOWN_TRANSIENT;
                log.warn("netgsm provider code RETRY: code={} class={}",
                    code.isEmpty() ? "missing" : code, transientClass);
                return SmsSendResult.retry(PROVIDER_KEY, transientClass,
                    code.isEmpty() ? "missing" : code);
            });
        } catch (IOException e) {
            log.warn("netgsm IOException (RETRY): {}", e.getClass().getSimpleName());
            return SmsSendResult.retry(PROVIDER_KEY, SmsFailureClass.TIMEOUT,
                "io:" + e.getClass().getSimpleName());
        }
    }

    /**
     * NetGSM permanent error code → {@link SmsFailureClass}, transient ise null.
     */
    static SmsFailureClass permanentClass(String code) {
        if (code == null) return null;
        return switch (code) {
            case "20" -> SmsFailureClass.MESSAGE_TOO_LONG;   // message too long
            case "30", "40" -> SmsFailureClass.PROVIDER_CONFIG;  // bad credentials / msgheader
            case "50" -> SmsFailureClass.INVALID_PHONE;      // invalid phone format
            case "70" -> SmsFailureClass.IYS_OPT_OUT;        // IYS opt-out
            default -> null;  // 60 (credit) + unknowns RETRY
        };
    }

    private static String readEntityBody(org.apache.hc.core5.http.ClassicHttpResponse response)
            throws IOException {
        if (response.getEntity() == null) return "";
        try (var stream = response.getEntity().getContent()) {
            if (stream == null) return "";
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String basicAuth(String user, String pass) {
        String pair = user + ":" + pass;
        return "Basic " + java.util.Base64.getEncoder()
            .encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    private static CloseableHttpClient newClient() {
        RequestConfig config = RequestConfig.custom()
            .setConnectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .setResponseTimeout(RESPONSE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build();
        return HttpClients.custom().setDefaultRequestConfig(config).build();
    }
}
