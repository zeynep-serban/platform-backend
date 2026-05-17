package com.example.permission.service;

import com.example.permission.dto.v1.PermissionCatalogDto.ModuleCatalogItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the module catalog contract for the {@code SUGGESTIONS} and
 * {@code ETHIC} modules.
 *
 * <p>The Öneriler / Etik remote MFEs are permission-gated features; their
 * module keys must appear in the catalog so {@code /api/v1/authz/me}
 * surfaces them and the frontend nav + route guards can enforce them.
 * Every module exposes the standard {@code VIEW} / {@code MANAGE} levels.
 */
class PermissionCatalogServiceModulesTest {

    private final PermissionCatalogService service = new PermissionCatalogService();

    private Optional<ModuleCatalogItem> module(String key) {
        return service.getCatalog().modules().stream()
                .filter(m -> key.equals(m.key()))
                .findFirst();
    }

    @Test
    void catalogExposesSuggestionsModule() {
        ModuleCatalogItem suggestions = module("SUGGESTIONS")
                .orElseThrow(() -> new AssertionError("SUGGESTIONS module missing from catalog"));
        assertThat(suggestions.label()).isEqualTo("Öneri ve Fikir");
        assertThat(suggestions.levels()).containsExactly("VIEW", "MANAGE");
    }

    @Test
    void catalogExposesEthicModule() {
        ModuleCatalogItem ethic = module("ETHIC")
                .orElseThrow(() -> new AssertionError("ETHIC module missing from catalog"));
        assertThat(ethic.label()).isEqualTo("Etik Raporlama");
        assertThat(ethic.levels()).containsExactly("VIEW", "MANAGE");
    }

    @Test
    void moduleKeysIncludeSuggestionsAndEthic() {
        assertThat(service.getModuleKeys())
                .as("getModuleKeys() feeds the admin JWT fallback module set")
                .contains("SUGGESTIONS", "ETHIC");
    }

    @Test
    void moduleKeysHaveNoDuplicates() {
        List<String> keys = service.getModuleKeys();
        assertThat(keys).doesNotHaveDuplicates();
    }
}
