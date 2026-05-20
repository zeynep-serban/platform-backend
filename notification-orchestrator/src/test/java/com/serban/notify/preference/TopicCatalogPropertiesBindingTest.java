package com.serban.notify.preference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that verifies {@link TopicCatalogProperties}
 * binding works against the canonical {@code application.yml} catalog
 * (Faz 23.5 M5 G2).
 *
 * <p>Loads only the {@code TopicCatalogProperties} bean (slice
 * approach) and asserts that the default catalog has the expected
 * entries with correct field values. This protects against:
 * <ul>
 *   <li>YAML typos in {@code application.yml} silently dropping
 *       entries</li>
 *   <li>Field rename/removal in {@code CatalogEntry} breaking the
 *       binding without surfacing a runtime error</li>
 *   <li>Default-value normalization regressions
 *       ({@code criticalEligible=null} → false)</li>
 * </ul>
 */
@SpringBootTest(
    classes = TopicCatalogPropertiesBindingTest.Config.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "notify.topics.catalog[0].topicKey=auth.mfa-otp",
    "notify.topics.catalog[0].label=MFA OTP Kodu",
    "notify.topics.catalog[0].category=auth",
    "notify.topics.catalog[0].supportedChannels[0]=SMS",
    "notify.topics.catalog[0].supportedChannels[1]=EMAIL",
    "notify.topics.catalog[0].criticalEligible=true",
    "notify.topics.catalog[0].description=İki adımlı doğrulama",
    "notify.topics.catalog[1].topicKey=marketing.campaign",
    "notify.topics.catalog[1].label=Kampanya",
    "notify.topics.catalog[1].category=marketing",
    "notify.topics.catalog[1].supportedChannels[0]=EMAIL",
    "notify.topics.catalog[1].criticalEligible=false",
    "notify.topics.catalog[1].defaultFrequencyHint=5"
})
class TopicCatalogPropertiesBindingTest {

    @EnableConfigurationProperties(TopicCatalogProperties.class)
    static class Config {}

    @Autowired
    TopicCatalogProperties properties;

    @Test
    void bindsCatalogFromYamlProperties() {
        assertThat(properties).isNotNull();
        assertThat(properties.catalog()).hasSize(2);

        TopicCatalogProperties.CatalogEntry first = properties.catalog().get(0);
        assertThat(first.topicKey()).isEqualTo("auth.mfa-otp");
        assertThat(first.label()).isEqualTo("MFA OTP Kodu");
        assertThat(first.category()).isEqualTo("auth");
        assertThat(first.supportedChannels()).containsExactly("SMS", "EMAIL");
        assertThat(first.criticalEligible()).isTrue();
        assertThat(first.description()).isEqualTo("İki adımlı doğrulama");
        assertThat(first.defaultFrequencyHint()).isNull();

        TopicCatalogProperties.CatalogEntry second = properties.catalog().get(1);
        assertThat(second.topicKey()).isEqualTo("marketing.campaign");
        assertThat(second.supportedChannels()).containsExactly("EMAIL");
        assertThat(second.criticalEligible()).isFalse();
        assertThat(second.defaultFrequencyHint()).isEqualTo(5);
    }
}
