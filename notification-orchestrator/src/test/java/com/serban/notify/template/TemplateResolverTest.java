package com.serban.notify.template;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateResolverTest {

    @Test
    void localeFallbackChainTrTR() {
        List<String> chain = TemplateResolver.buildLocaleFallbackChain("tr-TR");
        assertThat(chain).containsExactly("tr-TR", "tr", "en-US", "en");
    }

    @Test
    void localeFallbackChainEnUS() {
        List<String> chain = TemplateResolver.buildLocaleFallbackChain("en-US");
        assertThat(chain).containsExactly("en-US", "en");
    }

    @Test
    void localeFallbackChainLanguageOnly() {
        List<String> chain = TemplateResolver.buildLocaleFallbackChain("fr");
        assertThat(chain).containsExactly("fr", "en-US", "en");
    }

    @Test
    void localeFallbackChainNullDefaults() {
        List<String> chain = TemplateResolver.buildLocaleFallbackChain(null);
        assertThat(chain).containsExactly("en-US", "en");
    }

    @Test
    void localeFallbackChainUnderscoreNormalized() {
        List<String> chain = TemplateResolver.buildLocaleFallbackChain("de_DE");
        assertThat(chain).containsExactly("de-DE", "de", "en-US", "en");
    }
}
