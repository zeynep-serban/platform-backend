package com.serban.notify.preference;

import com.serban.notify.api.dto.TopicCatalogEntryResponse;
import com.serban.notify.api.dto.TopicCatalogListResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TopicCatalogService} (Faz 23.5 M5 G2).
 *
 * <p>Tests the DTO mapping layer in isolation; integration with
 * Spring config binding is covered by
 * {@code TopicCatalogControllerTest} via {@code @SpringBootTest}.
 */
class TopicCatalogServiceTest {

    @Test
    void emptyCatalogProducesEmptyResponse() {
        TopicCatalogProperties props = new TopicCatalogProperties(List.of());
        TopicCatalogService service = new TopicCatalogService(props);

        TopicCatalogListResponse response = service.listCatalog();

        assertThat(response).isNotNull();
        assertThat(response.items()).isEmpty();
    }

    @Test
    void nullCatalogTreatedAsEmpty() {
        TopicCatalogProperties props = new TopicCatalogProperties(null);
        TopicCatalogService service = new TopicCatalogService(props);

        TopicCatalogListResponse response = service.listCatalog();

        assertThat(response.items()).isEmpty();
    }

    @Test
    void singleEntryMapsAllFields() {
        TopicCatalogProperties.CatalogEntry entry = new TopicCatalogProperties.CatalogEntry(
            "auth.mfa-otp",
            "MFA OTP Kodu",
            "auth",
            List.of("SMS", "EMAIL"),
            true,
            "İki adımlı doğrulama kodu",
            null
        );
        TopicCatalogProperties props = new TopicCatalogProperties(List.of(entry));
        TopicCatalogService service = new TopicCatalogService(props);

        TopicCatalogListResponse response = service.listCatalog();

        assertThat(response.items()).hasSize(1);
        TopicCatalogEntryResponse item = response.items().get(0);
        assertThat(item.topicKey()).isEqualTo("auth.mfa-otp");
        assertThat(item.label()).isEqualTo("MFA OTP Kodu");
        assertThat(item.category()).isEqualTo("auth");
        assertThat(item.supportedChannels()).containsExactly("SMS", "EMAIL");
        assertThat(item.criticalEligible()).isTrue();
        assertThat(item.description()).isEqualTo("İki adımlı doğrulama kodu");
        assertThat(item.defaultFrequencyHint()).isNull();
    }

    @Test
    void multipleEntriesPreserveOrder() {
        TopicCatalogProperties props = new TopicCatalogProperties(List.of(
            new TopicCatalogProperties.CatalogEntry(
                "auth.mfa-otp", "OTP", "auth", List.of("SMS"), true, "kritik", null),
            new TopicCatalogProperties.CatalogEntry(
                "marketing.campaign", "Kampanya", "marketing", List.of("EMAIL", "SMS"), false, "ticari", 5),
            new TopicCatalogProperties.CatalogEntry(
                "audit.security", "Güvenlik", "audit", List.of("EMAIL", "SLACK", "IN_APP"), true, "audit", null)
        ));
        TopicCatalogService service = new TopicCatalogService(props);

        TopicCatalogListResponse response = service.listCatalog();

        assertThat(response.items())
            .extracting(TopicCatalogEntryResponse::topicKey)
            .containsExactly("auth.mfa-otp", "marketing.campaign", "audit.security");
    }

    @Test
    void defaultFrequencyHintRoundTrips() {
        TopicCatalogProperties props = new TopicCatalogProperties(List.of(
            new TopicCatalogProperties.CatalogEntry(
                "marketing.campaign", "Kampanya", "marketing", List.of("EMAIL"), false, "ticari", 5)
        ));
        TopicCatalogService service = new TopicCatalogService(props);

        TopicCatalogListResponse response = service.listCatalog();

        assertThat(response.items().get(0).defaultFrequencyHint()).isEqualTo(5);
    }

    @Test
    void criticalEligibleDefaultsFalseWhenNullProvided() {
        // CatalogEntry compact constructor normalizes null → false
        TopicCatalogProperties.CatalogEntry entry = new TopicCatalogProperties.CatalogEntry(
            "marketing.newsletter",
            "Bülten",
            "marketing",
            List.of("EMAIL"),
            null,  // null criticalEligible should normalize to false
            "ayda bir",
            2
        );

        assertThat(entry.criticalEligible()).isFalse();
    }

    @Test
    void supportedChannelsDefaultsEmptyWhenNullProvided() {
        TopicCatalogProperties.CatalogEntry entry = new TopicCatalogProperties.CatalogEntry(
            "system.notification",
            "Bildirim",
            "system",
            null,  // null supportedChannels should normalize to empty list
            false,
            "genel",
            null
        );

        assertThat(entry.supportedChannels()).isEmpty();
    }
}
