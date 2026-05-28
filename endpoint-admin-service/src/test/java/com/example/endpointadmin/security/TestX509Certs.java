package com.example.endpointadmin.security;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Faz 22.3 — Programmatic X.509 cert builder for unit / integration tests.
 *
 * <p>Generates RSA-2048 self-signed certs that look like AD CS machine certs
 * for the purposes of {@link MachineCertExtractor}: configurable validity
 * window, optional EKU clientAuth, optional SAN URI of the form
 * {@code adcomputer:{objectGUID}}, configurable subject/issuer DN.
 *
 * <p>Production runtime does NOT use BouncyCastle — only the standard JDK
 * {@link java.security.cert.X509Certificate} parser. BC is test-scope only.
 */
public final class TestX509Certs {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private TestX509Certs() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static X509Certificate validClientCert(UUID objectGuid) {
        return builder()
                .objectGuid(objectGuid)
                .clientAuth(true)
                .validForDays(30)
                .build();
    }

    public static final class Builder {
        private UUID objectGuid = UUID.randomUUID();
        private boolean clientAuth = true;
        private boolean includeSanUri = true;
        private String customSanUri;
        private String extraSanUri;
        private Instant notBefore = Instant.now().minusSeconds(60);
        private Instant notAfter = Instant.now().plusSeconds(24L * 60L * 60L * 30L);
        private String subjectDn = "CN=DESKTOP-TEST,DC=acik,DC=local";
        private String issuerDn = "CN=acik.local-CA,DC=acik,DC=local";

        public Builder objectGuid(UUID v) {
            this.objectGuid = v;
            return this;
        }

        public Builder clientAuth(boolean v) {
            this.clientAuth = v;
            return this;
        }

        public Builder includeSanUri(boolean v) {
            this.includeSanUri = v;
            return this;
        }

        public Builder customSanUri(String v) {
            this.customSanUri = v;
            return this;
        }

        /**
         * Adds a SECOND SAN URI (for testing the exactly-one assertion in
         * {@link MachineCertExtractor}). The primary URI is still derived
         * from {@link #objectGuid()} / {@link #customSanUri(String)}.
         */
        public Builder extraSanUri(String v) {
            this.extraSanUri = v;
            return this;
        }

        public Builder validFrom(Instant v) {
            this.notBefore = v;
            return this;
        }

        public Builder validUntil(Instant v) {
            this.notAfter = v;
            return this;
        }

        public Builder validForDays(int days) {
            Instant now = Instant.now();
            this.notBefore = now.minusSeconds(60);
            this.notAfter = now.plusSeconds((long) days * 24L * 60L * 60L);
            return this;
        }

        public Builder expiredDaysAgo(int days) {
            Instant now = Instant.now();
            this.notBefore = now.minusSeconds((long) (days + 30) * 24L * 60L * 60L);
            this.notAfter = now.minusSeconds((long) days * 24L * 60L * 60L);
            return this;
        }

        public Builder notYetValidDaysAhead(int days) {
            Instant now = Instant.now();
            this.notBefore = now.plusSeconds((long) days * 24L * 60L * 60L);
            this.notAfter = now.plusSeconds((long) (days + 30) * 24L * 60L * 60L);
            return this;
        }

        public Builder subjectDn(String v) {
            this.subjectDn = v;
            return this;
        }

        public Builder issuerDn(String v) {
            this.issuerDn = v;
            return this;
        }

        public X509Certificate build() {
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                KeyPair keyPair = kpg.generateKeyPair();

                X500Name subject = new X500Name(subjectDn);
                X500Name issuer = new X500Name(issuerDn);

                X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                        issuer,
                        BigInteger.valueOf(System.currentTimeMillis()),
                        Date.from(notBefore),
                        Date.from(notAfter),
                        subject,
                        keyPair.getPublic()
                );

                builder.addExtension(Extension.basicConstraints, true,
                        new BasicConstraints(false));

                if (clientAuth) {
                    builder.addExtension(Extension.extendedKeyUsage, false,
                            new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
                }

                if (includeSanUri) {
                    String sanUri = customSanUri != null
                            ? customSanUri
                            : "adcomputer:" + objectGuid.toString().toLowerCase();
                    GeneralName primary = new GeneralName(
                            GeneralName.uniformResourceIdentifier, sanUri);
                    GeneralNames names;
                    if (extraSanUri != null) {
                        GeneralName extra = new GeneralName(
                                GeneralName.uniformResourceIdentifier, extraSanUri);
                        names = new GeneralNames(new GeneralName[]{primary, extra});
                    } else {
                        names = new GeneralNames(primary);
                    }
                    builder.addExtension(Extension.subjectAlternativeName, false, names);
                }

                ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build(keyPair.getPrivate());

                return new JcaX509CertificateConverter()
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .getCertificate(builder.build(signer));
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to build test X509 cert", ex);
            }
        }
    }
}
