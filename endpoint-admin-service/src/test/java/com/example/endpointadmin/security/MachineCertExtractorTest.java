package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.3 — Unit tests for {@link MachineCertExtractor}. Codex plan-time
 * consult 019e692b-f023-75a1-a2e9-a915f9cd58ee.
 */
class MachineCertExtractorTest {

    private static final Instant NOW = Instant.now();

    @Test
    void extractsSanUriAndObjectGuidFromValidCert() {
        UUID guid = UUID.fromString("a1b2c3d4-1111-2222-3333-444455556666");
        X509Certificate cert = TestX509Certs.validClientCert(guid);

        MachineCertExtractor.ParsedCert parsed = MachineCertExtractor.extract(cert, NOW);

        assertThat(parsed.sanUri()).isEqualTo("adcomputer:" + guid.toString().toLowerCase());
        assertThat(parsed.objectGuid()).isEqualTo(guid);
        assertThat(parsed.thumbprint()).hasSize(64);
        assertThat(parsed.thumbprint()).matches("[0-9a-f]{64}");
        assertThat(parsed.notBefore()).isBefore(parsed.notAfter());
    }

    @Test
    void rejectsCertWithoutClientAuthEku() {
        X509Certificate cert = TestX509Certs.builder()
                .clientAuth(false)
                .validForDays(30)
                .build();

        assertThatThrownBy(() -> MachineCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .extracting(ex -> ((MachineCertExtractionException) ex).getErrorCode())
                .isEqualTo("CERT_EKU_MISSING_CLIENT_AUTH");
    }

    @Test
    void rejectsCertWithoutSanUri() {
        X509Certificate cert = TestX509Certs.builder()
                .includeSanUri(false)
                .validForDays(30)
                .build();

        assertThatThrownBy(() -> MachineCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .extracting(ex -> ((MachineCertExtractionException) ex).getErrorCode())
                .isEqualTo("CERT_SAN_URI_MISSING");
    }

    @Test
    void rejectsCertWithMalformedSanUri() {
        X509Certificate cert = TestX509Certs.builder()
                .customSanUri("urn:notadcomputer:foo-bar")
                .validForDays(30)
                .build();

        // SAN URI is present but does NOT match adcomputer pattern → treated as
        // "no matching SAN URI" (other URIs in the cert are ignored).
        assertThatThrownBy(() -> MachineCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .extracting(ex -> ((MachineCertExtractionException) ex).getErrorCode())
                .isEqualTo("CERT_SAN_URI_MISSING");
    }

    @Test
    void rejectsCertWithUppercaseObjectGuid() {
        X509Certificate uppercaseCert = TestX509Certs.builder()
                .customSanUri("adcomputer:A1B2C3D4-1111-2222-3333-444455556666")
                .clientAuth(true)
                .validForDays(30)
                .build();

        assertThatThrownBy(() -> MachineCertExtractor.extract(uppercaseCert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .extracting(ex -> ((MachineCertExtractionException) ex).getErrorCode())
                .isEqualTo("CERT_SAN_URI_MISSING");
    }

    @Test
    void rejectsCertWithAmbiguousMultipleAdcomputerSans() {
        // Codex 019e6dc9 P1-5 absorb: a misissued cert with two matching
        // adcomputer:{objectGUID} SAN URIs must be rejected explicitly, not
        // resolved by first-match.
        UUID guid1 = UUID.randomUUID();
        UUID guid2 = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.builder()
                .objectGuid(guid1)
                .extraSanUri("adcomputer:" + guid2.toString().toLowerCase())
                .clientAuth(true)
                .validForDays(30)
                .build();

        assertThatThrownBy(() -> MachineCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .extracting(ex -> ((MachineCertExtractionException) ex).getErrorCode())
                .isEqualTo("CERT_SAN_URI_AMBIGUOUS");
    }

    @Test
    void rejectsExpiredCert() {
        X509Certificate cert = TestX509Certs.builder()
                .expiredDaysAgo(5)
                .build();

        assertThatThrownBy(() -> MachineCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .extracting(ex -> ((MachineCertExtractionException) ex).getErrorCode())
                .isEqualTo("CERT_EXPIRED");
    }

    @Test
    void rejectsNotYetValidCert() {
        X509Certificate cert = TestX509Certs.builder()
                .notYetValidDaysAhead(5)
                .build();

        assertThatThrownBy(() -> MachineCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .extracting(ex -> ((MachineCertExtractionException) ex).getErrorCode())
                .isEqualTo("CERT_NOT_YET_VALID");
    }

    @Test
    void thumbprintIsDeterministicForSameCert() {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);

        String t1 = MachineCertExtractor.extract(cert, NOW).thumbprint();
        String t2 = MachineCertExtractor.extract(cert, NOW).thumbprint();

        assertThat(t1).isEqualTo(t2);
    }

    @Test
    void r24GraceWindowBoundedAt24h() {
        Instant certNotAfter = Instant.parse("2026-01-01T00:00:00Z");
        Instant lastCrl = Instant.parse("2025-12-15T00:00:00Z");
        Instant now = Instant.parse("2026-01-02T00:00:00Z");

        Instant effective = MachineCertExtractor.computeR24EffectiveNotAfter(
                certNotAfter, lastCrl, now);

        assertThat(effective).isEqualTo(certNotAfter.plusSeconds(24L * 60L * 60L));
    }

    @Test
    void r24GraceWindowZeroWhenNoCrlData() {
        Instant certNotAfter = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = Instant.parse("2026-01-02T00:00:00Z");

        Instant effective = MachineCertExtractor.computeR24EffectiveNotAfter(
                certNotAfter, null, now);

        assertThat(effective).isEqualTo(certNotAfter);
    }

    @Test
    void r24GraceWindowEqualsCrlAgeWhenLessThan24h() {
        Instant certNotAfter = Instant.parse("2026-01-01T00:00:00Z");
        Instant lastCrl = Instant.parse("2026-01-01T08:00:00Z");
        Instant now = Instant.parse("2026-01-01T12:00:00Z");

        Instant effective = MachineCertExtractor.computeR24EffectiveNotAfter(
                certNotAfter, lastCrl, now);

        assertThat(effective).isEqualTo(certNotAfter.plusSeconds(4L * 60L * 60L));
    }
}
