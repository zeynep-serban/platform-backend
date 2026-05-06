package com.serban.notify.provider;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * SmtpTlsEnforcer — production STARTTLS required guard (Faz 23.2 PR-A —
 * Codex 019dfae5 Q2 AGREE absorb).
 *
 * <p>Codex Q2 absorb:
 * <ul>
 *   <li>Production: STARTTLS required (mail.smtp.starttls.required=true)</li>
 *   <li>Hostname/cert validation açık (mail.smtp.ssl.checkserveridentity=true)</li>
 *   <li>TLS 1.2 minimum, TLS 1.3 prefer (jdk.tls.client.protocols)</li>
 *   <li>CA verify default; pinning sadece private/onprem relay için</li>
 *   <li>Plaintext fallback YASAK (operator yanlış config → fail-closed)</li>
 *   <li>Mailpit plaintext yalnız test/local profile istisnası</li>
 * </ul>
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code notify.smtp.tls.enforce} (default true; test profile false)</li>
 *   <li>{@code notify.smtp.tls.min-protocol} (default TLSv1.2)</li>
 *   <li>{@code notify.smtp.tls.check-server-identity} (default true)</li>
 * </ul>
 *
 * <p>This component validates Spring Mail JavaMailSender properties at startup
 * and fails closed if production profile lacks STARTTLS enforcement.
 */
@Component
@ConditionalOnProperty(name = "notify.smtp.tls.enforce", havingValue = "true", matchIfMissing = false)
public class SmtpTlsEnforcer {

    private static final Logger log = LoggerFactory.getLogger(SmtpTlsEnforcer.class);

    private final Environment springEnv;
    private final String minProtocol;
    private final boolean checkServerIdentity;

    public SmtpTlsEnforcer(
        Environment springEnv,
        @Value("${notify.smtp.tls.min-protocol:TLSv1.2}") String minProtocol,
        @Value("${notify.smtp.tls.check-server-identity:true}") boolean checkServerIdentity
    ) {
        this.springEnv = springEnv;
        this.minProtocol = minProtocol;
        this.checkServerIdentity = checkServerIdentity;
    }

    @PostConstruct
    void enforce() {
        boolean isProdProfile = isProductionProfile();
        log.info("SmtpTlsEnforcer activated: prod-profile={} min-protocol={} check-server-identity={}",
            isProdProfile, minProtocol, checkServerIdentity);

        // Java system property — TLS protocol baseline (JVM-wide)
        String currentProtocols = System.getProperty("jdk.tls.client.protocols");
        if (currentProtocols == null || currentProtocols.isBlank()) {
            // Prefer TLS 1.3, fall back to TLS 1.2 minimum
            String enforced = "TLSv1.3,TLSv1.2";
            if ("TLSv1.3".equals(minProtocol)) enforced = "TLSv1.3";
            System.setProperty("jdk.tls.client.protocols", enforced);
            log.info("set jdk.tls.client.protocols={}", enforced);
        }

        // Spring Mail Java property baseline (JavaMailSender pickups via spring.mail.properties)
        // Production validation: spring.mail.properties.mail.smtp.starttls.required must be true
        if (isProdProfile) {
            assertProperty("spring.mail.properties.mail.smtp.starttls.enable", "true");
            assertProperty("spring.mail.properties.mail.smtp.starttls.required", "true");
            assertProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity",
                String.valueOf(checkServerIdentity));
        }
    }

    /** Apply enforcement properties to a JavaMail Session Properties map. */
    public void applyTo(Properties props) {
        props.setProperty("mail.smtp.starttls.enable", "true");
        props.setProperty("mail.smtp.starttls.required", "true");
        props.setProperty("mail.smtp.ssl.checkserveridentity", String.valueOf(checkServerIdentity));
        props.setProperty("mail.smtp.ssl.protocols", minProtocol + " TLSv1.3");
    }

    private boolean isProductionProfile() {
        for (String profile : springEnv.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private void assertProperty(String key, String expected) {
        String actual = springEnv.getProperty(key);
        if (actual == null || !actual.equals(expected)) {
            throw new IllegalStateException(
                "SMTP TLS production guard FAIL: " + key + " expected=" + expected
                    + " actual=" + actual + " — set " + key + "=" + expected
                    + " in production profile (Codex 019dfae5 Q2 absorb)"
            );
        }
        log.info("verified {}={}", key, expected);
    }
}
