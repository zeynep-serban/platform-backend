package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class WorkcubeQueryExceptionHandlerTest {

    private final WorkcubeQueryExceptionHandler handler =
            new WorkcubeQueryExceptionHandler(new ObjectMapper());

    @Test
    void handle_workcubeSecurityException_returns403Body() {
        ResponseEntity<JsonNode> response = handler.handle(
                new WorkcubeQuerySecurityException("workcube-inv",
                        "Rendered SQL contains non-allowlisted table"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error").asText())
                .isEqualTo("workcube_query_security_violation");
        assertThat(response.getBody().get("reportKey").asText())
                .isEqualTo("workcube-inv");
        assertThat(response.getBody().get("message").asText())
                .contains("Rendered SQL contains non-allowlisted table");
    }

    @Test
    void handle_crossTenantSecurityException_returns403Body() {
        ResponseEntity<JsonNode> response = handler.handle(
                new WorkcubeQuerySecurityException("workcube-yearly",
                        "Rendered SQL spans multiple tenant ids [35, 99]"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("error").asText())
                .isEqualTo("workcube_query_security_violation");
        assertThat(response.getBody().get("reportKey").asText())
                .isEqualTo("workcube-yearly");
        assertThat(response.getBody().get("message").asText())
                .contains("multiple tenant ids");
    }
}
