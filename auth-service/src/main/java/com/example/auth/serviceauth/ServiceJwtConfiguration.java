package com.example.auth.serviceauth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

@Configuration
public class ServiceJwtConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ServiceJwtConfiguration.class);

    @Bean
    public RSAKey serviceRsaKey(ServiceJwtKeyProperties properties) {
        RSAPrivateKey privateKey;
        RSAPublicKey publicKey;

        if (!StringUtils.hasText(properties.getPrivateKey()) || !StringUtils.hasText(properties.getPublicKey())) {
            logger.info("Service JWT anahtarları tanımlanmadı, geçici anahtar çifti üretilecek (yalnızca geliştirme amaçlı)");
            KeyPair keyPair = generateKeyPair();
            privateKey = (RSAPrivateKey) keyPair.getPrivate();
            publicKey = (RSAPublicKey) keyPair.getPublic();
        } else {
            privateKey = parsePrivateKey(properties.getPrivateKey());
            publicKey = parsePublicKey(properties.getPublicKey());
        }

        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(properties.getKeyId())
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey rsaKey) {
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * Service-to-service JWT decoder. Sadece {@code local} ve {@code dev}
     * profillerinde devreye girer; {@code k8s} (test/prod) profilinde Spring
     * Boot OAuth2 Resource Server auto-config {@code jwk-set-uri} üzerinden
     * Keycloak JWKS decoder'ı üretir.
     *
     * <p><b>Codex iter-1 REVISE absorb (2026-05-10, thread 019e1183)</b>:
     * Önceki revizyon {@code @ConditionalOnProperty(matchIfMissing = false)}
     * kullanıyordu, ancak Spring Boot 3.5.6 {@code OnPropertyCondition$Spec.isMatch}
     * semantiği gereği "property tamamen yok" durumunu test eder.
     * {@code application.properties:32} ve {@code application-k8s.yml:119}
     * boş fallback ({@code ${AUTH_SERVICE_JWT_PRIVATE_KEY:}}) tanımladığı için
     * property test cluster'da <b>present (boş string)</b> olarak görünüyor
     * ve {@code havingValue} verilmediğinden condition match ederek
     * decoder bean instantiate ediliyordu — hot-fix etkisiz.
     *
     * <p>{@code @Profile({"local","dev"})} ile k8s/prod profilinde bean
     * hiçbir koşulda instantiate olmaz; Spring auto-config primary
     * {@code JwtDecoder} (jwk-set-uri based) Keycloak access tokenlarını
     * doğru public key ile decode eder. Local/dev'de mevcut
     * {@link com.example.auth.security.JwtTokenProvider} flow'u (legacy
     * service-to-service) korunur.
     *
     * <p>Prod'da service-to-service JWT decode gerekirse (mevcut akışta
     * yok), follow-up: resource server config'de explicit
     * {@code jwt(jwt -> jwt.decoder(keycloakJwtDecoder))} ile decoder
     * bağla, service decoder'ı {@code @Qualifier} ile sınırla.
     */
    @Bean
    @Profile({"local", "dev"})
    public JwtDecoder jwtDecoder(RSAKey rsaKey) {
        try {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        } catch (Exception ex) {
            throw new IllegalStateException("RSA public key parse edilemedi", ex);
        }
    }

    @Bean
    public JWKSet serviceJwkSet(RSAKey rsaKey) {
        return new JWKSet(rsaKey.toPublicJWK());
    }

    private RSAPrivateKey parsePrivateKey(String pem) {
        try {
            byte[] keyBytes = decodePem(pem, "PRIVATE KEY");
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) keyFactory.generatePrivate(keySpec);
        } catch (Exception ex) {
            throw new IllegalStateException("RSA private key parse edilemedi", ex);
        }
    }

    private RSAPublicKey parsePublicKey(String pem) {
        try {
            byte[] keyBytes = decodePem(pem, "PUBLIC KEY");
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) keyFactory.generatePublic(keySpec);
        } catch (Exception ex) {
            throw new IllegalStateException("RSA public key parse edilemedi", ex);
        }
    }

    private byte[] decodePem(String pem, String type) {
        String normalized = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("RSA anahtar çifti üretilemedi", ex);
        }
    }
}
