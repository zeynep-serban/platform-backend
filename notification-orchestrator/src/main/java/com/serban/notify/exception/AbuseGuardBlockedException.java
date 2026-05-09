package com.serban.notify.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Map;

/**
 * Abuse guard blocked — Faz 23.2.F T1.6 (Codex thread `019e0c28`).
 *
 * <p>{@code AbuseGuardService.check()} returns {@code Decision.allowed=false}
 * when:
 * <ul>
 *   <li>Rate limit per (orgId, topicKey) sliding window exceeded</li>
 *   <li>Webhook fan-out cap exceeded (channels.count("webhook") &gt; cap)</li>
 * </ul>
 *
 * <p>HTTP 429 Too Many Requests response; {@code reason} + {@code auditDetails}
 * caller'a döner (Retry-After header gelecek iter follow-up).
 *
 * <p>Critical bypass: severity=critical OR data_classification=security
 * intent'ler bu exception'ı tetiklemez ({@code AbuseGuardService} pre-check
 * tarafından bypass edilir).
 */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class AbuseGuardBlockedException extends RuntimeException {

    private final String reason;
    private final String auditEventType;
    private final Map<String, Object> auditDetails;

    public AbuseGuardBlockedException(String reason, String auditEventType, Map<String, Object> auditDetails) {
        super("AbuseGuard blocked: " + reason + " (event=" + auditEventType + ")");
        this.reason = reason;
        this.auditEventType = auditEventType;
        this.auditDetails = auditDetails;
    }

    public String getReason() {
        return reason;
    }

    public String getAuditEventType() {
        return auditEventType;
    }

    public Map<String, Object> getAuditDetails() {
        return auditDetails;
    }
}
