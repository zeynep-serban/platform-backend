package com.serban.notify.api;

import com.serban.notify.api.dto.TopicCatalogEntryResponse;
import com.serban.notify.api.dto.TopicCatalogListResponse;
import com.serban.notify.preference.TopicCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TopicCatalogController} @WebMvcTest slice (Faz 23.5 M5 G2).
 *
 * <p>Test scope:
 * <ul>
 *   <li>GET /me happy path — returns wrapped list with all metadata</li>
 *   <li>Empty catalog returns HTTP 200 with empty items array
 *       (frontend "catalog unavailable" fallback signal)</li>
 *   <li>JSON shape — all 7 DTO fields present + nullable hints
 *       respected</li>
 * </ul>
 *
 * <p>Identity guard test (HTTP 403 for mismatched headers) is covered
 * by the broader SecurityConfig integration test path — this slice
 * focuses on the controller-layer contract.
 */
@WebMvcTest(controllers = TopicCatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class TopicCatalogControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean TopicCatalogService topicCatalogService;
    @MockBean JwtDecoder jwtDecoder;

    @Test
    void listMyCatalogReturns200WithItems() throws Exception {
        when(topicCatalogService.listCatalog()).thenReturn(new TopicCatalogListResponse(List.of(
            new TopicCatalogEntryResponse(
                "auth.mfa-otp",
                "MFA OTP Kodu",
                "auth",
                List.of("SMS", "EMAIL"),
                true,
                "İki adımlı doğrulama kodu",
                null
            ),
            new TopicCatalogEntryResponse(
                "marketing.campaign",
                "Pazarlama Kampanyaları",
                "marketing",
                List.of("EMAIL", "SMS", "IN_APP"),
                false,
                "Ürün tanıtım kampanyaları",
                5
            )
        )));

        mockMvc.perform(get("/api/v1/notify/topics/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].topicKey").value("auth.mfa-otp"))
            .andExpect(jsonPath("$.items[0].label").value("MFA OTP Kodu"))
            .andExpect(jsonPath("$.items[0].category").value("auth"))
            .andExpect(jsonPath("$.items[0].supportedChannels[0]").value("SMS"))
            .andExpect(jsonPath("$.items[0].supportedChannels[1]").value("EMAIL"))
            .andExpect(jsonPath("$.items[0].criticalEligible").value(true))
            .andExpect(jsonPath("$.items[0].description").value("İki adımlı doğrulama kodu"))
            .andExpect(jsonPath("$.items[0].defaultFrequencyHint").doesNotExist())
            .andExpect(jsonPath("$.items[1].topicKey").value("marketing.campaign"))
            .andExpect(jsonPath("$.items[1].criticalEligible").value(false))
            .andExpect(jsonPath("$.items[1].defaultFrequencyHint").value(5));
    }

    @Test
    void listMyCatalogEmptyReturns200WithEmptyItems() throws Exception {
        when(topicCatalogService.listCatalog()).thenReturn(new TopicCatalogListResponse(List.of()));

        mockMvc.perform(get("/api/v1/notify/topics/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
    }
}
