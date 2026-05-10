package com.example.auth.serviceauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * PR-B retrospective regression guard: {@link ServiceJwtConfiguration#jwtDecoder}
 * eskiden default bean adıyla ({@code jwtDecoder}) tanımlanıyor ve OAuth2
 * Resource Server auto-config'in Keycloak JWKS decoder'ını override ederek
 * KC tarafından imzalanmış access tokenları "Invalid signature" ile reject
 * etmesine yol açıyordu (R-2 LIVE smoke 2026-05-10).
 *
 * <p><b>Codex iter-1 REVISE absorb (2026-05-10, thread 019e1183)</b>:
 * Önceki test versiyonu sadece {@code hasSingleBean(JwtDecoder.class)} ve
 * {@code instanceof NimbusJwtDecoder} kontrol ediyordu. Bu iki assertion
 * hem auto-config decoder hem ServiceJwt decoder için PASS olabiliyordu
 * (ikisi de {@link NimbusJwtDecoder}); manuel override yokluğu
 * kanıtlanmıyordu. Boot 3.5.6 auto-config bean adı
 * {@code jwtDecoderByJwkKeySetUri} (factory method), ServiceJwt manuel
 * bean adı {@code jwtDecoder} (manuel {@code @Bean} default'u method
 * adı). Bu sürüm factory method + declaring class assertion ile gerçek
 * discriminating test yapar:
 * <ul>
 *   <li>Empty/k8s case: factory method {@code jwtDecoderByJwkKeySetUri}
 *       (Spring Boot {@code OAuth2ResourceServerJwtConfiguration$JwtDecoderConfiguration})
 *       — auto-config decoder.</li>
 *   <li>Set/local case: factory method {@code jwtDecoder} (declaring class
 *       {@link ServiceJwtConfiguration}) — manuel decoder.</li>
 * </ul>
 *
 * <p><b>Codex iter-2 REVISE absorb (2026-05-10, thread 019e1183, P1
 * gitleaks blocker)</b>: Test fixture'ı eskiden static {@code String}
 * literal RSA PKCS#8 base64 anahtarlarını içeriyordu. Gitleaks bunu
 * "real-looking secret" olarak yakaladı (CI fail). Bu sürüm static
 * initializer'da {@link KeyPairGenerator} ile **her test koşumunda
 * runtime'da yeni 2048-bit RSA çifti** üretir; literal değer repo
 * history'sine girmez. Test davranışı aynı (key bean wiring'i
 * etkilemez, sadece string formatına ihtiyaç var).
 *
 * <p>Plus iki ana profil koşulu test edilir:
 * <ul>
 *   <li>{@code k8s} profil (test/prod default): ServiceJwt decoder bean
 *       {@code @Profile({"local","dev"})} sebebiyle instantiate olmamalı,
 *       Spring Boot OAuth2 auto-config'in JWKS decoder'ı tek
 *       {@link JwtDecoder} bean olarak kalmalı. Property-set koşulundan
 *       bağımsız ({@code AUTH_SERVICE_JWT_PRIVATE_KEY} dolu olsa bile).</li>
 *   <li>{@code local} profil (dev/local + JwtTokenProvider legacy flow):
 *       ServiceJwt decoder bean'i devreye girer ve auto-config decoder'ı
 *       override eder.</li>
 * </ul>
 */
class JwtDecoderBeanWiringTest {

    /**
     * Test-only RSA keypair, runtime'da generate edilir (static initializer
     * via {@link KeyPairGenerator}). Repo'ya hiçbir literal RSA PKCS#8
     * base64 string'i yazılmaz — gitleaks-safe (Codex iter-2 P1 absorb).
     *
     * <p>Anahtarın amacı yalnızca {@code security.service-jwt.private-key}
     * / {@code public-key} property'lerine non-empty, geçerli formatta
     * base64 değer sağlamak; bean wiring için kriptografik geçerlilik
     * gerek değil ama {@link ServiceJwtConfiguration} parsing yapıyor
     * olabilir, gerçek RSA çifti güvenli tercih.
     */
    private static final KeyPair TEST_KEY_PAIR;

    static {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            TEST_KEY_PAIR = kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Test RSA keypair generation failed", e);
        }
    }

    private static String testPrivateKeyBase64() {
        return Base64.getEncoder().encodeToString(TEST_KEY_PAIR.getPrivate().getEncoded());
    }

    private static String testPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(TEST_KEY_PAIR.getPublic().getEncoded());
    }

    /**
     * Spring Boot 3.5.6 OAuth2 auto-config'in JWKS decoder factory method
     * adı: {@code OAuth2ResourceServerJwtConfiguration$JwtDecoderConfiguration#jwtDecoderByJwkKeySetUri}.
     * Bytecode'dan doğrulandı (Codex iter-1 + Claude javap).
     */
    private static final String AUTO_CONFIG_FACTORY_METHOD = "jwtDecoderByJwkKeySetUri";

    /**
     * ServiceJwtConfiguration'ın manuel decoder factory method adı.
     */
    private static final String SERVICE_JWT_FACTORY_METHOD = "jwtDecoder";

    /**
     * {@link OAuth2ResourceServerAutoConfiguration} {@code @ConditionalOnWebApplication(Type.SERVLET)}
     * koşulu olduğundan {@link WebApplicationContextRunner} kullanılır;
     * normal {@code ApplicationContextRunner} (non-web) ile JwtDecoder
     * auto-config tetiklenmiyor.
     */
    private WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PropertyPlaceholderAutoConfiguration.class,
                        SecurityAutoConfiguration.class,
                        OAuth2ResourceServerAutoConfiguration.class))
                .withUserConfiguration(TestServiceJwtConfig.class)
                .withPropertyValues(
                        // Spring Boot OAuth2 Resource Server auto-config bunu görünce
                        // NimbusJwtDecoder.withJwkSetUri(...) ile decoder bean üretir.
                        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri="
                                + "http://localhost:8081/realms/serban/protocol/openid-connect/certs",
                        "security.service-jwt.key-id=test-key");
    }

    /**
     * Profile aktivasyonunu environment'a doğrudan inject eder.
     * {@code withPropertyValues("spring.profiles.active=...")} runtime'da
     * profile set etmiyor çünkü {@code @Profile} condition resolve'u bean
     * registration sırasında oluyor; environment'in profilleri bean
     * registration'dan önce set olmalı. {@code ApplicationContextInitializer}
     * tam bunu yapar.
     */
    private static ApplicationContextInitializer<ConfigurableApplicationContext> withProfile(String profile) {
        return ctx -> ctx.getEnvironment().setActiveProfiles(profile);
    }

    /**
     * k8s profil + boş key property (ESO/Vault populate öncesi ya da hiçbir
     * zaman): ServiceJwt decoder {@code @Profile({"local","dev"})} sebebiyle
     * instantiate olmaz; auto-config decoder primary kalır.
     *
     * <p>Bu test eski {@code @ConditionalOnProperty(matchIfMissing=false)}
     * çözümünün neden başarısız olduğunu da yakalar: property
     * {@code application-k8s.yml:119} sebebiyle present (boş string) idi,
     * eski annotation match ediyordu, decoder yanlış instantiate oluyordu.
     * Profile-based gating bu probleme immune.
     */
    @Test
    void k8sProfile_emptyKey_useSpringAutoConfigJwtDecoder_serviceDecoderNotInstantiated() {
        runner()
                .withInitializer(withProfile("k8s"))
                .withPropertyValues(
                        "security.service-jwt.private-key=",
                        "security.service-jwt.public-key=")
                .run(context -> {
                    assertThat(context).hasSingleBean(JwtDecoder.class);
                    JwtDecoder decoder = context.getBean(JwtDecoder.class);
                    assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);

                    // Spring Boot 3.5.6 auto-config bean adı factory method
                    // adıyla aynı: "jwtDecoderByJwkKeySetUri". Manuel
                    // ServiceJwt decoder bean adı "jwtDecoder" olur (Spring
                    // @Bean default'u method adı). İkisinin namespace çakışması
                    // yok; manuel override sadece bean type üzerinden olur.
                    String[] beanNames = context.getBeanNamesForType(JwtDecoder.class);
                    assertThat(beanNames).containsExactly(AUTO_CONFIG_FACTORY_METHOD);

                    // Discriminating assertion (Codex iter-1 absorb):
                    // factory method auto-config'in olmalı, ServiceJwt
                    // decoder factory'si değil. ServiceJwt decoder
                    // {@code @Profile({"local","dev"})} sebebiyle aktif
                    // değil — bean definition source class farklı.
                    RootBeanDefinition def = (RootBeanDefinition)
                            context.getBeanFactory().getMergedBeanDefinition(AUTO_CONFIG_FACTORY_METHOD);
                    assertThat(def.getFactoryMethodName()).isEqualTo(AUTO_CONFIG_FACTORY_METHOD);
                    assertThat(def.getResolvedFactoryMethod().getDeclaringClass().getName())
                            .contains("OAuth2ResourceServerJwtConfiguration");
                });
    }

    /**
     * k8s profil + property set (Vault populate sonrası senaryo):
     * ServiceJwt decoder yine instantiate olmaz çünkü profile gate
     * property-bağımsız. Auto-config decoder primary kalır.
     *
     * <p>Bu eski {@code @ConditionalOnProperty} çözümünün non-empty key
     * verildiğinde tekrar bug'lanmasını engelleyen guard.
     */
    @Test
    void k8sProfile_keySet_serviceDecoderStillNotInstantiated_profileGateOverridesProperty() {
        runner()
                .withInitializer(withProfile("k8s"))
                .withPropertyValues(
                        "security.service-jwt.private-key=" + testPrivateKeyBase64(),
                        "security.service-jwt.public-key=" + testPublicKeyBase64())
                .run(context -> {
                    assertThat(context).hasSingleBean(JwtDecoder.class);
                    JwtDecoder decoder = context.getBean(JwtDecoder.class);
                    assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);

                    String[] beanNames = context.getBeanNamesForType(JwtDecoder.class);
                    assertThat(beanNames).containsExactly(AUTO_CONFIG_FACTORY_METHOD);

                    RootBeanDefinition def = (RootBeanDefinition)
                            context.getBeanFactory().getMergedBeanDefinition(AUTO_CONFIG_FACTORY_METHOD);
                    assertThat(def.getFactoryMethodName()).isEqualTo(AUTO_CONFIG_FACTORY_METHOD);
                    assertThat(def.getResolvedFactoryMethod().getDeclaringClass().getName())
                            .contains("OAuth2ResourceServerJwtConfiguration");
                });
    }

    /**
     * local profil + property set (dev/local default):
     * ServiceJwt decoder devreye girer; auto-config decoder ile çakışırsa
     * Spring "consider renaming" hatası verir, override beklenen davranış
     * (legacy {@link com.example.auth.security.JwtTokenProvider} flow için).
     */
    @Test
    void localProfile_keySet_serviceJwtDecoderInstantiated_overridesAutoConfig() {
        runner()
                .withInitializer(withProfile("local"))
                .withPropertyValues(
                        "security.service-jwt.private-key=" + testPrivateKeyBase64(),
                        "security.service-jwt.public-key=" + testPublicKeyBase64())
                .run(context -> {
                    assertThat(context).hasSingleBean(JwtDecoder.class);
                    JwtDecoder decoder = context.getBean(JwtDecoder.class);
                    assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);

                    // Local profilde ServiceJwt decoder aktif → auto-config
                    // {@code @ConditionalOnMissingBean(JwtDecoder.class)} type-based
                    // back off eder; tek decoder = ServiceJwt, bean adı "jwtDecoder"
                    // (manuel @Bean default factory method adı).
                    String[] beanNames = context.getBeanNamesForType(JwtDecoder.class);
                    assertThat(beanNames).containsExactly(SERVICE_JWT_FACTORY_METHOD);

                    RootBeanDefinition def = (RootBeanDefinition)
                            context.getBeanFactory().getMergedBeanDefinition(SERVICE_JWT_FACTORY_METHOD);
                    assertThat(def.getFactoryMethodName()).isEqualTo(SERVICE_JWT_FACTORY_METHOD);
                    assertThat(def.getResolvedFactoryMethod().getDeclaringClass())
                            .isEqualTo(ServiceJwtConfiguration.class);
                });
    }

    /**
     * dev profil + property set: aynı local davranışı — ServiceJwt decoder
     * aktif. {@code @Profile({"local","dev"})} eşitliği test eder.
     */
    @Test
    void devProfile_keySet_serviceJwtDecoderInstantiated() {
        runner()
                .withInitializer(withProfile("dev"))
                .withPropertyValues(
                        "security.service-jwt.private-key=" + testPrivateKeyBase64(),
                        "security.service-jwt.public-key=" + testPublicKeyBase64())
                .run(context -> {
                    assertThat(context).hasSingleBean(JwtDecoder.class);

                    String[] beanNames = context.getBeanNamesForType(JwtDecoder.class);
                    assertThat(beanNames).containsExactly(SERVICE_JWT_FACTORY_METHOD);

                    RootBeanDefinition def = (RootBeanDefinition)
                            context.getBeanFactory().getMergedBeanDefinition(SERVICE_JWT_FACTORY_METHOD);
                    assertThat(def.getFactoryMethodName()).isEqualTo(SERVICE_JWT_FACTORY_METHOD);
                    assertThat(def.getResolvedFactoryMethod().getDeclaringClass())
                            .isEqualTo(ServiceJwtConfiguration.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServiceJwtKeyProperties.class)
    @org.springframework.context.annotation.Import(ServiceJwtConfiguration.class)
    static class TestServiceJwtConfig {
        // ServiceJwtConfiguration tüm bean'leri tanımlar; sadece
        // ServiceJwtKeyProperties'in @ConfigurationProperties binding'i
        // burada açılıyor (ana app context dışında @Component pickup yok).
    }
}
