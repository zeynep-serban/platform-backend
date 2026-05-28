package com.example.endpointadmin.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faz 22.3 — Parse an AD CS machine cert into the fields we persist into
 * {@code endpoint_machine_certs}. Codex plan-time consult
 * 019e692b-f023-75a1-a2e9-a915f9cd58ee.
 *
 * <p>The cert MUST carry:
 * <ul>
 *   <li>EKU {@code clientAuth} (OID 1.3.6.1.5.5.7.3.2);</li>
 *   <li>a Subject Alternative Name URI of the form
 *       {@code adcomputer:{objectGUID}} where {@code objectGUID} is a lowercase
 *       canonical UUID — this is the PRIMARY device identity;</li>
 *   <li>{@code notBefore < notAfter} and {@code now in [notBefore, notAfter]}.</li>
 * </ul>
 */
public final class MachineCertExtractor {

    private static final Logger log = LoggerFactory.getLogger(MachineCertExtractor.class);

    /** RFC 5280 GeneralName type tag for URI. */
    private static final int SAN_TYPE_URI = 6;

    /** EKU OID for TLS Web Client Authentication. */
    public static final String EKU_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";

    /**
     * SAN URI format pin (ADR-0029): {@code adcomputer:} followed by a
     * canonical lowercase UUID (8-4-4-4-12 hex). We deliberately reject
     * uppercase forms so that the persisted {@code san_uri} string is exactly
     * the canonical lookup key; the controller / agent MUST send lowercase.
     */
    public static final Pattern SAN_URI_PATTERN =
            Pattern.compile("^adcomputer:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

    private static final HexFormat HEX = HexFormat.of();

    private MachineCertExtractor() {
    }

    /**
     * Extract the {@link ParsedCert} or throw {@link MachineCertExtractionException}
     * with a stable {@code errorCode} suitable for HTTP 400/401 mapping.
     */
    public static ParsedCert extract(X509Certificate cert, Instant now) {
        Objects.requireNonNull(cert, "cert");
        Objects.requireNonNull(now, "now");

        // 1. Validity window (not-before / not-after).
        Instant notBefore = cert.getNotBefore().toInstant();
        Instant notAfter = cert.getNotAfter().toInstant();
        if (!notBefore.isBefore(notAfter)) {
            throw new MachineCertExtractionException(
                    "CERT_INVALID_VALIDITY",
                    "Cert validity window invalid (notBefore >= notAfter).");
        }
        if (now.isBefore(notBefore)) {
            throw new MachineCertExtractionException(
                    "CERT_NOT_YET_VALID",
                    "Cert is not yet valid.");
        }
        if (!now.isBefore(notAfter)) {
            throw new MachineCertExtractionException(
                    "CERT_EXPIRED",
                    "Cert is expired.");
        }

        // 2. Extended Key Usage MUST contain clientAuth.
        List<String> ekus;
        try {
            ekus = cert.getExtendedKeyUsage();
        } catch (CertificateParsingException ex) {
            throw new MachineCertExtractionException(
                    "CERT_EKU_PARSE_FAILED",
                    "Failed to parse cert EKU extension.",
                    ex);
        }
        if (ekus == null || !ekus.contains(EKU_CLIENT_AUTH)) {
            throw new MachineCertExtractionException(
                    "CERT_EKU_MISSING_CLIENT_AUTH",
                    "Cert EKU does not include clientAuth (1.3.6.1.5.5.7.3.2).");
        }

        // 3. SAN URI matching adcomputer:{objectGUID}.
        String sanUri;
        UUID objectGuid;
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            Optional<String> match = findAdcomputerSan(sans);
            if (match.isEmpty()) {
                throw new MachineCertExtractionException(
                        "CERT_SAN_URI_MISSING",
                        "Cert has no SAN URI matching adcomputer:{objectGUID}.");
            }
            sanUri = match.get();
            Matcher m = SAN_URI_PATTERN.matcher(sanUri);
            if (!m.matches()) {
                throw new MachineCertExtractionException(
                        "CERT_SAN_URI_FORMAT_INVALID",
                        "SAN URI does not match adcomputer:{objectGUID} format.");
            }
            try {
                objectGuid = UUID.fromString(m.group(1));
            } catch (IllegalArgumentException ex) {
                throw new MachineCertExtractionException(
                        "CERT_OBJECT_GUID_INVALID",
                        "SAN URI objectGUID is not a valid UUID.",
                        ex);
            }
        } catch (CertificateParsingException ex) {
            throw new MachineCertExtractionException(
                    "CERT_SAN_PARSE_FAILED",
                    "Failed to parse cert SAN extension.",
                    ex);
        }

        // 4. Derived fields: SHA-256 thumbprint over DER-encoded cert.
        String thumbprint;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            thumbprint = HEX.formatHex(md.digest(cert.getEncoded()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        } catch (java.security.cert.CertificateEncodingException ex) {
            throw new MachineCertExtractionException(
                    "CERT_ENCODING_FAILED",
                    "Failed to DER-encode cert for thumbprint.",
                    ex);
        }

        String serial = cert.getSerialNumber().toString(16);
        String issuer = cert.getIssuerX500Principal().getName();
        String subject = cert.getSubjectX500Principal().getName();

        return new ParsedCert(
                sanUri,
                objectGuid,
                serial,
                thumbprint,
                issuer,
                subject,
                notBefore,
                notAfter
        );
    }

    /**
     * Find the single {@code adcomputer:{objectGUID}} SAN URI in the cert.
     *
     * <p>Codex 019e6dc9 P1-5 absorb: cert MUST carry EXACTLY ONE matching
     * SAN URI. A misissued cert (CA template bug or adversarial issuance)
     * with two {@code adcomputer:} SANs would otherwise allow first-match-wins
     * ambiguity — a single cert could enroll under two distinct objectGUID
     * identities depending on iteration order. The strict count check
     * propagates {@code CERT_SAN_URI_AMBIGUOUS} via
     * {@link MachineCertExtractionException}.
     */
    private static Optional<String> findAdcomputerSan(Collection<List<?>> sans) {
        if (sans == null) {
            return Optional.empty();
        }
        String firstMatch = null;
        int matchCount = 0;
        for (List<?> entry : sans) {
            if (entry.size() < 2) {
                continue;
            }
            Object typeTag = entry.get(0);
            Object value = entry.get(1);
            if (!(typeTag instanceof Integer type) || type != SAN_TYPE_URI) {
                continue;
            }
            if (!(value instanceof String uri)) {
                continue;
            }
            if (SAN_URI_PATTERN.matcher(uri).matches()) {
                if (firstMatch == null) {
                    firstMatch = uri;
                }
                matchCount++;
            } else {
                log.debug("Ignoring SAN URI not matching adcomputer pattern (length={})",
                        uri.length());
            }
        }
        if (matchCount > 1) {
            throw new MachineCertExtractionException(
                    "CERT_SAN_URI_AMBIGUOUS",
                    "Cert has " + matchCount + " adcomputer:{objectGUID} SAN URIs; "
                            + "exactly one is required.");
        }
        return Optional.ofNullable(firstMatch);
    }

    /**
     * Bounded R24 grace window (ADR-0029): an active cert is treated as
     * usable until {@code notAfter + min(24h, lastGoodCrlCheck-age)}. We do
     * NOT apply this at extraction time — extraction is strict
     * ({@code now <= notAfter}); the grace logic belongs in the service layer
     * which knows about CRL freshness.
     */
    public static Instant computeR24EffectiveNotAfter(Instant certNotAfter,
                                                      Instant lastGoodCrlCheck,
                                                      Instant now) {
        Objects.requireNonNull(certNotAfter, "certNotAfter");
        Objects.requireNonNull(now, "now");
        if (lastGoodCrlCheck == null) {
            return certNotAfter;
        }
        long crlAgeSeconds = Math.max(0L, now.getEpochSecond() - lastGoodCrlCheck.getEpochSecond());
        long graceSeconds = Math.min(24L * 60L * 60L, crlAgeSeconds);
        return certNotAfter.plusSeconds(graceSeconds);
    }

    public static String sha256HexUtf8(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    public record ParsedCert(
            String sanUri,
            UUID objectGuid,
            String serial,
            String thumbprint,
            String issuer,
            String subject,
            Instant notBefore,
            Instant notAfter
    ) {
    }
}
