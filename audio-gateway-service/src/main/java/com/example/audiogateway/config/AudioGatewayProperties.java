package com.example.audiogateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code audio.gateway.*} properties.
 *
 * <p>Codex {@code 019e879c} + {@code 019e8c26} iter-2 AGREE: bounds + JWT claims +
 * idempotency policy configurable; hard-coded YASAK. Defaults reflect ADR-0031 PoC
 * scope (256 KB / 60 dk / 1000 active sessions / 4096 replay cache).
 *
 * <p>Registered via {@code @ConfigurationPropertiesScan} on {@code AudioGatewayApplication}.
 */
@ConfigurationProperties(prefix = "audio.gateway")
public class AudioGatewayProperties {

    private final Contract contract = new Contract();
    private final Bounds bounds = new Bounds();
    private final Stt stt = new Stt();
    private final Jwt jwt = new Jwt();
    private final Idempotency idempotency = new Idempotency();

    public Contract getContract() {
        return contract;
    }

    public Bounds getBounds() {
        return bounds;
    }

    public Stt getStt() {
        return stt;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public static class Contract {
        private String version = "1.0";

        public String getVersion() {
            return version;
        }

        public void setVersion(final String version) {
            this.version = version;
        }
    }

    public static class Bounds {
        private long maxChunkBytes = 262_144L;
        private int maxBufferedSeconds = 30;
        private int maxSessionMinutes = 60;
        private int admissionQueueCapacity = 1_000;
        private int maxActiveSessions = 1_000;

        public long getMaxChunkBytes() {
            return maxChunkBytes;
        }

        public void setMaxChunkBytes(final long maxChunkBytes) {
            this.maxChunkBytes = maxChunkBytes;
        }

        public int getMaxBufferedSeconds() {
            return maxBufferedSeconds;
        }

        public void setMaxBufferedSeconds(final int maxBufferedSeconds) {
            this.maxBufferedSeconds = maxBufferedSeconds;
        }

        public int getMaxSessionMinutes() {
            return maxSessionMinutes;
        }

        public void setMaxSessionMinutes(final int maxSessionMinutes) {
            this.maxSessionMinutes = maxSessionMinutes;
        }

        public int getAdmissionQueueCapacity() {
            return admissionQueueCapacity;
        }

        public void setAdmissionQueueCapacity(final int admissionQueueCapacity) {
            this.admissionQueueCapacity = admissionQueueCapacity;
        }

        public int getMaxActiveSessions() {
            return maxActiveSessions;
        }

        public void setMaxActiveSessions(final int maxActiveSessions) {
            this.maxActiveSessions = maxActiveSessions;
        }
    }

    public static class Stt {
        private String dispatchMode = "noop";

        public String getDispatchMode() {
            return dispatchMode;
        }

        public void setDispatchMode(final String dispatchMode) {
            this.dispatchMode = dispatchMode;
        }
    }

    public static class Jwt {
        private String tenantClaim = "companyId";
        private String userClaim = "userId";

        public String getTenantClaim() {
            return tenantClaim;
        }

        public void setTenantClaim(final String tenantClaim) {
            this.tenantClaim = tenantClaim;
        }

        public String getUserClaim() {
            return userClaim;
        }

        public void setUserClaim(final String userClaim) {
            this.userClaim = userClaim;
        }
    }

    public static class Idempotency {
        private String headerName = "Idempotency-Key";
        private int minLength = 16;
        private int maxLength = 128;
        private int replayCacheSize = 4_096;

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(final String headerName) {
            this.headerName = headerName;
        }

        public int getMinLength() {
            return minLength;
        }

        public void setMinLength(final int minLength) {
            this.minLength = minLength;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(final int maxLength) {
            this.maxLength = maxLength;
        }

        public int getReplayCacheSize() {
            return replayCacheSize;
        }

        public void setReplayCacheSize(final int replayCacheSize) {
            this.replayCacheSize = replayCacheSize;
        }
    }
}
