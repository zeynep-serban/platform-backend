package com.example.permission.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InternalAuthorizationController MockMvc test (Codex 019dfaaa PR5 P0 #2 absorb).
 *
 * <p>Verifies snake_case JSON binding (principal_type, principal_id, object_type,
 * object_id) — global Jackson PROPERTY_NAMING_STRATEGY=SNAKE_CASE yok, bu yüzden
 * record components @JsonProperty ile annotate edilmiş olmalı.
 */
@WebMvcTest(controllers = InternalAuthorizationController.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(roles = "INTERNAL")
class InternalAuthorizationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // ImpersonationContextFilter (@Component) is auto-picked up by the
    // @WebMvcTest slice and transitively requires ImpersonationContextExtractor;
    // mock it so the slice ApplicationContext loads.
    @MockitoBean
    private com.example.permission.security.ImpersonationContextExtractor impersonationContextExtractor;

    @MockitoBean private OpenFgaAuthzService authzService;

    @Test
    void allowedPathReturnsTupleMatch() throws Exception {
        when(authzService.checkPrincipal(
            "subscriber:1204", "can_receive", "template", "auth-password-reset"
        )).thenReturn(true);

        String body = objectMapper.writeValueAsString(Map.of(
            "principal_type", "subscriber",
            "principal_id", "1204",
            "relation", "can_receive",
            "object_type", "template",
            "object_id", "auth-password-reset"
        ));

        mockMvc.perform(post("/api/v1/internal/authz/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(true))
            .andExpect(jsonPath("$.reason").value("tuple_match"));
    }

    @Test
    void deniedPathReturnsNoTuple() throws Exception {
        when(authzService.checkPrincipal(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(false);

        String body = objectMapper.writeValueAsString(Map.of(
            "principal_type", "subscriber",
            "principal_id", "9999",
            "relation", "can_receive",
            "object_type", "template",
            "object_id", "secret-template"
        ));

        mockMvc.perform(post("/api/v1/internal/authz/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(false))
            .andExpect(jsonPath("$.reason").value("no_tuple"));
    }

    @Test
    void invalidPrincipalTypeRejected() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
            "principal_type", "invalid_type",  // not 'subscriber' or 'external'
            "principal_id", "1204",
            "relation", "can_receive",
            "object_type", "template",
            "object_id", "auth-password-reset"
        ));

        mockMvc.perform(post("/api/v1/internal/authz/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void principalIdWithColonRejected() throws Exception {
        // Codex Lock-in #3: external principal id colon yasak (OpenFGA user-ref ambiguity)
        String body = objectMapper.writeValueAsString(Map.of(
            "principal_type", "external",
            "principal_id", "email_hash:abc123",  // colon in id
            "relation", "can_receive",
            "object_type", "template",
            "object_id", "auth-password-reset"
        ));

        mockMvc.perform(post("/api/v1/internal/authz/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void externalPrincipalAccepted() throws Exception {
        when(authzService.checkPrincipal(
            "external:abc123def", "can_receive", "template", "marketing-promo"
        )).thenReturn(true);

        String body = objectMapper.writeValueAsString(Map.of(
            "principal_type", "external",
            "principal_id", "abc123def",
            "relation", "can_receive",
            "object_type", "template",
            "object_id", "marketing-promo"
        ));

        mockMvc.perform(post("/api/v1/internal/authz/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    void camelCaseBodyRejected() throws Exception {
        // Verify camelCase is NOT accepted (global Jackson does NOT use snake_case;
        // @JsonProperty mapping is the only contract).
        String body = objectMapper.writeValueAsString(Map.of(
            "principalType", "subscriber",  // camelCase
            "principalId", "1204",
            "relation", "can_receive",
            "objectType", "template",
            "objectId", "auth-password-reset"
        ));

        mockMvc.perform(post("/api/v1/internal/authz/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }
}
