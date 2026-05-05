package com.serban.notify.template;

import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.exception.TemplateNotFoundException;
import com.serban.notify.repository.NotificationTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * TemplateResolver — resolve only, NO render (Codex 019df9ae Q2 AGREE).
 *
 * <p>PR2 scope: validate template_id + locale + optional version,
 * return resolved (template_id, version, locale, external_allowed) metadata.
 * Render PR3'te (channel adapter call'da Thymeleaf engine + variable substitution).
 *
 * <p>Locale fallback algorithm:
 * <ol>
 *   <li>Exact requested locale (e.g., "tr-TR")</li>
 *   <li>Language-only fallback (e.g., "tr")</li>
 *   <li>Default fallback "en-US"</li>
 *   <li>Final fallback "en"</li>
 * </ol>
 *
 * <p>Version semantics:
 * <ul>
 *   <li>{@code version == null}: locale fallback chain → first hit's latest active version</li>
 *   <li>{@code version != null}: locale fallback chain → exact version match required;
 *       no version-fallback (immutable contract)</li>
 * </ul>
 */
@Component
public class TemplateResolver {

    private static final Logger log = LoggerFactory.getLogger(TemplateResolver.class);
    private static final String DEFAULT_FALLBACK = "en-US";
    private static final String FINAL_FALLBACK = "en";

    private final NotificationTemplateRepository repository;

    public TemplateResolver(NotificationTemplateRepository repository) {
        this.repository = repository;
    }

    /**
     * Resolve template metadata.
     *
     * @param templateId template_id requested by intent
     * @param locale requested locale (e.g., "tr-TR")
     * @param version optional explicit version (null → latest active)
     * @return resolved template entity (metadata only — body not rendered)
     * @throws TemplateNotFoundException if no active version found via fallback chain
     */
    @Transactional(readOnly = true)
    public NotificationTemplate resolve(String templateId, String locale, Integer version) {
        List<String> chain = buildLocaleFallbackChain(locale);
        log.debug("template resolve: templateId={} locale={} version={} chain={}",
            templateId, locale, version, chain);

        for (String candidate : chain) {
            Optional<NotificationTemplate> result;
            if (version != null) {
                result = repository.findByTemplateIdAndVersionAndLocale(templateId, version, candidate);
            } else {
                result = repository.findActiveByTemplateIdAndLocale(templateId, candidate);
            }
            if (result.isPresent() && result.get().isActive()) {
                NotificationTemplate t = result.get();
                log.debug("template resolved: templateId={} version={} locale={} (requested={})",
                    t.getTemplateId(), t.getVersion(), t.getLocale(), locale);
                return t;
            }
        }
        throw new TemplateNotFoundException(
            "no active template found: templateId=" + templateId
                + " locale=" + locale + " version=" + version
        );
    }

    /**
     * Build locale fallback chain: requested → language-only → default → final.
     *
     * <p>Examples:
     * <ul>
     *   <li>"tr-TR" → ["tr-TR", "tr", "en-US", "en"]</li>
     *   <li>"en-US" → ["en-US", "en"]</li>
     *   <li>"de-DE" → ["de-DE", "de", "en-US", "en"]</li>
     *   <li>"fr" → ["fr", "en-US", "en"]</li>
     * </ul>
     */
    static List<String> buildLocaleFallbackChain(String requested) {
        if (requested == null || requested.isBlank()) {
            return List.of(DEFAULT_FALLBACK, FINAL_FALLBACK);
        }
        String norm = requested.replace('_', '-');
        java.util.LinkedHashSet<String> chain = new java.util.LinkedHashSet<>();
        chain.add(norm);
        int dashIdx = norm.indexOf('-');
        if (dashIdx > 0) {
            chain.add(norm.substring(0, dashIdx));
        }
        chain.add(DEFAULT_FALLBACK);
        chain.add(FINAL_FALLBACK);
        return List.copyOf(chain);
    }
}
