package com.example.schema.service.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Phase 1 portability (Codex 019e2d7d AGREE — quick win 3+4/5):
 * loads FK-heuristic dictionaries (alias map, common-FK map) from JSON.
 *
 * <p>Each map is a flat {@code {"COLUMN":"TABLE"}} JSON object. Keys and
 * values are normalized to trimmed UPPER-CASE — the discovery engine
 * compares against upper-case schema identifiers. Blank or malformed
 * entries are skipped with a warning, never fatal.
 *
 * <p><strong>Fallback contract:</strong>
 * <ul>
 *   <li>configured path resolves + parses → that map is used. A valid
 *       empty {@code {}} is honoured as an intentionally empty map
 *       (that heuristic is switched off), not a failure;</li>
 *   <li>a non-default (external) path that is missing or unparseable →
 *       WARN + fall back to the classpath default;</li>
 *   <li>the classpath default itself missing/unparseable → fail-fast
 *       ({@link IllegalStateException}); that is a packaging bug and
 *       must not be masked.</li>
 * </ul>
 */
@Component
public class FkHeuristicMapLoader {

    private static final Logger log = LoggerFactory.getLogger(FkHeuristicMapLoader.class);
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    public FkHeuristicMapLoader(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
    }

    /**
     * Load an FK-heuristic map, applying the fallback contract above.
     *
     * @param configuredPath   path from config (blank → classpath default)
     * @param classpathDefault the packaged classpath default path
     * @param label            short name for logging (e.g. {@code "alias"})
     * @return immutable normalized map (may be empty if the source is {@code {}})
     */
    public Map<String, String> load(String configuredPath, String classpathDefault, String label) {
        String normalizedConfigured = configuredPath == null ? "" : configuredPath.trim();
        // Default mode: blank config, or a path equal to the classpath
        // default. Any other (typically file:) path is external/custom.
        boolean defaultMode = normalizedConfigured.isEmpty()
                || normalizedConfigured.equals(classpathDefault.trim());
        String effectivePath = defaultMode ? classpathDefault : normalizedConfigured;

        try {
            return parse(effectivePath, label);
        } catch (Exception e) {
            if (defaultMode) {
                throw new IllegalStateException(
                        "FK-heuristic '" + label + "' default resource '" + effectivePath
                                + "' could not be loaded: " + e.getMessage(), e);
            }
            log.warn("FK-heuristic '{}' external resource '{}' failed ({}); "
                            + "falling back to classpath default '{}'.",
                    label, effectivePath, e.getMessage(), classpathDefault);
            try {
                return parse(classpathDefault, label);
            } catch (Exception fe) {
                throw new IllegalStateException(
                        "FK-heuristic '" + label + "' fallback default '" + classpathDefault
                                + "' could not be loaded: " + fe.getMessage(), fe);
            }
        }
    }

    private Map<String, String> parse(String path, String label) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        if (!resource.exists()) {
            throw new IOException("resource not found: " + path);
        }
        Map<String, String> raw;
        try (InputStream in = resource.getInputStream()) {
            raw = objectMapper.readValue(in, MAP_TYPE);
        }
        if (raw == null) {
            throw new IOException("resource parsed to null (JSON 'null' literal): " + path);
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        int skipped = 0;
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim().toUpperCase(Locale.ROOT);
            String val = e.getValue() == null ? "" : e.getValue().trim().toUpperCase(Locale.ROOT);
            if (key.isEmpty() || val.isEmpty()) {
                skipped++;
                continue;
            }
            normalized.put(key, val);
        }

        if (skipped > 0) {
            log.warn("FK-heuristic '{}' from '{}': {} malformed entr{} skipped "
                            + "(blank/null key or value).",
                    label, path, skipped, skipped == 1 ? "y" : "ies");
        }
        if (normalized.isEmpty() && !raw.isEmpty()) {
            // Every entry was malformed — distinct from an intentional {}.
            log.warn("FK-heuristic '{}' from '{}': all {} entr{} malformed; "
                            + "resulting map is empty — check the source file.",
                    label, path, raw.size(), raw.size() == 1 ? "y" : "ies");
        } else {
            log.info("FK-heuristic '{}' loaded from '{}': {} entr{} ({} skipped).",
                    label, path, normalized.size(),
                    normalized.size() == 1 ? "y" : "ies", skipped);
        }
        return Map.copyOf(normalized);
    }
}
