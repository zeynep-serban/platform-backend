package com.serban.notify.api;

import com.serban.notify.api.dto.TopicCatalogListResponse;
import com.serban.notify.preference.TopicCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the subscriber-facing topic catalog
 * (Faz 23.5 M5 G2).
 *
 * <p>Endpoint:
 * <ul>
 *   <li>{@code GET /api/v1/notify/topics/me} — returns the static
 *       catalog of known topic keys with their UI metadata (label,
 *       category, supported channels, critical-eligible flag,
 *       description, default frequency hint).</li>
 * </ul>
 *
 * <p>Authentication: requires an authenticated subscriber session.
 * The catalog itself is identical for every caller (org/subscriber-
 * agnostic), but the endpoint is protected by the same identity guard
 * chain as {@code /preferences/me} to maintain a uniform subscriber-
 * facing surface. Mismatched identity returns HTTP 403.
 *
 * <p>Caching: the catalog is static (loaded once at startup from
 * {@code application.yml}); clients should cache aggressively. No
 * {@code Cache-Control} headers are emitted by the controller; RTK
 * Query's default cache (60s) is sufficient and a future Cache-Control
 * header is non-breaking.
 */
@RestController
@RequestMapping("/api/v1/notify/topics")
@Tag(name = "notify-topic-catalog", description = "Subscriber-facing topic catalog (Faz 23.5 M5 G2)")
public class TopicCatalogController {

    private final TopicCatalogService topicCatalogService;

    public TopicCatalogController(TopicCatalogService topicCatalogService) {
        this.topicCatalogService = topicCatalogService;
    }

    /**
     * Returns the topic catalog. Empty list (HTTP 200 with empty
     * {@code items}) signals "catalog unavailable" — the frontend
     * falls back to free-text topic entry in that case.
     */
    @GetMapping("/me")
    @Operation(summary = "List the subscriber-facing topic catalog")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Topic catalog"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Identity mismatch")
    })
    public ResponseEntity<TopicCatalogListResponse> listMyCatalog() {
        return ResponseEntity.ok(topicCatalogService.listCatalog());
    }
}
