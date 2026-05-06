package com.serban.notify.api;

import com.serban.notify.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OpenAPI contract test — Codex 019df9ae Q6 AGREE.
 *
 * <p>Springdoc auto-gen /v3/api-docs JSON. Dar JSONPath assertion (golden-file
 * snapshot yerine) — kırılgan değil; sadece API surface kontratı.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class OpenApiContractTest extends AbstractPostgresTest {

    @Autowired MockMvc mockMvc;

    @Test
    void openApiContainsIntentEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths./api/v1/notify/intents").exists())
            .andExpect(jsonPath("$.paths./api/v1/notify/intents.post").exists())
            .andExpect(jsonPath("$.paths./api/v1/notify/intents/{intentId}").exists())
            .andExpect(jsonPath("$.paths./api/v1/notify/intents/{intentId}.get").exists());
    }

    @Test
    void openApiSubmitResponseContains202() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths./api/v1/notify/intents.post.responses.202").exists());
    }

    @Test
    void openApiContainsInboxEndpoints() throws Exception {
        // Faz 23.3 PR-E.1 (Codex iter-1 P4 absorb) — inbox surface contract assertion
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths./api/v1/notify/inbox/me").exists())
            .andExpect(jsonPath("$.paths./api/v1/notify/inbox/me.get").exists())
            .andExpect(jsonPath("$.paths./api/v1/notify/inbox/me/unread-count").exists())
            .andExpect(jsonPath("$.paths./api/v1/notify/inbox/me/unread-count.get").exists())
            .andExpect(jsonPath("$.paths./api/v1/notify/inbox/{id}/read").exists())
            .andExpect(jsonPath("$.paths./api/v1/notify/inbox/{id}/read.post").exists())
            .andExpect(jsonPath("$.paths./api/v1/notify/inbox/{id}/archive").exists())
            .andExpect(jsonPath("$.paths./api/v1/notify/inbox/{id}/archive.post").exists());
    }
}
