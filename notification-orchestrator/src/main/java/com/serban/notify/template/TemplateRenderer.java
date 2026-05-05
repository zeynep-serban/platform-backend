package com.serban.notify.template;

import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.exception.InvalidRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Template renderer (Faz 23.1 PR3 — Codex 019df9ae Q3 REVISE absorb).
 *
 * <p>Codex Q3 mandate'leri:
 * <ul>
 *   <li>StringTemplateResolver (DB-backed body strings)</li>
 *   <li>Payload {@code vars} namespace (NOT root): {@code ${vars.user_name}}</li>
 *   <li>Payload key regex limit: {@code [a-zA-Z0-9_.-]{1,64}}</li>
 *   <li>HTML mode escaped only — {@code th:utext} / inline raw {@code [(...)]}
 *       reject (lint-time)</li>
 *   <li>SSTI prevention: {@code T(...)}, {@code @bean}, {@code #ctx},
 *       {@code #request}, {@code getClass}, {@code class}, {@code new } reject</li>
 *   <li>Subject CRLF normalize (header injection guard)</li>
 *   <li>Spring beans/request/session expose etmez — Context root sadece
 *       {@code vars} Map</li>
 * </ul>
 */
@Component
public class TemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(TemplateRenderer.class);

    private static final Pattern PAYLOAD_KEY = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");

    /**
     * Reject patterns — template body lint, SSTI/XSS prevention.
     *
     * <p>Codex 019df9ef P1 absorb (review): patterns hardened to catch bypass
     * vectors (preprocessing, lowercase package types, generic instantiation,
     * any bean reference inside expressions).
     *
     * <p>Pattern listesi:
     * <ul>
     *   <li>{@code th:utext} / {@code data-th-utext} — raw HTML output</li>
     *   <li>{@code [(...)] } — inline raw output (escaped form is {@code [[...]]})</li>
     *   <li>{@code __${...}__} — Thymeleaf preprocessing (dynamic expression
     *       generation gateway; SSTI bypass)</li>
     *   <li>{@code T(...)} — any SpEL type reference (java/javax/org/com/...)</li>
     *   <li>{@code @bean} — any Spring bean reference inside {@code ${...}}</li>
     *   <li>{@code #ctx}, {@code #request}, {@code #session} — context exposure</li>
     *   <li>{@code .getClass(...)}, {@code .class} — reflection</li>
     *   <li>{@code new <anything>} — generic instantiation (case-insensitive,
     *       lowercase package qualified types covered)</li>
     * </ul>
     */
    private static final Pattern[] REJECT_PATTERNS = new Pattern[] {
        Pattern.compile("th:utext\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("data-th-utext\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\[\\([^\\]]*\\)\\]"),         // inline raw [(expr)]
        Pattern.compile("__\\$\\{"),                       // preprocessing __${expr}__
        Pattern.compile("\\bT\\s*\\(\\s*[a-zA-Z]"),    // ANY type ref T(...) (Codex hardening)
        Pattern.compile("\\$\\{[^}]*@\\w"),              // bean ref inside ${...} (Codex hardening)
        Pattern.compile("@\\w+(?:\\.\\w+)*\\s*\\("),    // @bean.method( (legacy form)
        Pattern.compile("#ctx\\b"),
        Pattern.compile("#request\\b"),
        Pattern.compile("#session\\b"),
        Pattern.compile("\\.getClass\\s*\\("),
        Pattern.compile("\\.class\\b"),
        Pattern.compile("\\bnew\\s+\\w")                  // generic new <anything> (Codex hardening)
    };

    private final SpringTemplateEngine htmlEngine;
    private final SpringTemplateEngine textEngine;

    public TemplateRenderer() {
        this.htmlEngine = createEngine(TemplateMode.HTML);
        this.textEngine = createEngine(TemplateMode.TEXT);
    }

    /**
     * Render template body parts using payload variables.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>Validate payload keys against regex</li>
     *   <li>Lint subject/body_html/body_text — reject SSTI/XSS patterns</li>
     *   <li>Render via Thymeleaf with {@code vars} namespace</li>
     *   <li>Subject CRLF normalize</li>
     * </ol>
     */
    public RenderedMessage render(NotificationTemplate template, Map<String, Object> payload) {
        Map<String, Object> vars = sanitizePayload(payload);
        Context ctx = new Context(Locale.forLanguageTag(template.getLocale()));
        ctx.setVariable("vars", vars);

        String subject = renderTextPart(template.getSubject(), ctx, "subject");
        if (subject != null) {
            subject = stripCrlf(subject);
        }

        String bodyHtml = template.getBodyHtml() == null ? null
            : renderHtmlPart(template.getBodyHtml(), ctx, "body_html");
        String bodyText = template.getBodyText() == null ? null
            : renderTextPart(template.getBodyText(), ctx, "body_text");

        return new RenderedMessage(subject, bodyHtml, bodyText, template.getLocale());
    }

    /** Validate payload keys; reject keys not matching {@link #PAYLOAD_KEY}. */
    private Map<String, Object> sanitizePayload(Map<String, Object> payload) {
        if (payload == null) return Map.of();
        Map<String, Object> safe = new HashMap<>(payload.size());
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            if (e.getKey() == null || !PAYLOAD_KEY.matcher(e.getKey()).matches()) {
                log.warn("template render: rejected payload key '{}' (regex mismatch)", e.getKey());
                continue;
            }
            safe.put(e.getKey(), e.getValue());
        }
        return safe;
    }

    private String renderHtmlPart(String body, Context ctx, String partName) {
        lintReject(body, partName);
        return htmlEngine.process(body, ctx);
    }

    private String renderTextPart(String body, Context ctx, String partName) {
        if (body == null) return null;
        lintReject(body, partName);
        return textEngine.process(body, ctx);
    }

    private void lintReject(String body, String partName) {
        if (body == null) return;
        for (Pattern p : REJECT_PATTERNS) {
            if (p.matcher(body).find()) {
                throw new InvalidRequestException(
                    "template " + partName + " contains rejected pattern: " + p.pattern()
                );
            }
        }
    }

    /** Strip CR/LF from subject — RFC 5322 header injection prevention. */
    static String stripCrlf(String subject) {
        return subject.replace("\r", "").replace("\n", " ").trim();
    }

    private static SpringTemplateEngine createEngine(TemplateMode mode) {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(mode);
        resolver.setCacheable(false);  // DB-backed; varying per intent

        // SpringTemplateEngine uses SpEL (NOT OGNL) for variable expression
        // evaluation — consistent with Spring Boot thymeleaf integration and
        // avoids OGNL transitive dep. SpEL's @bean/T(..)/getClass paths are
        // already lint-rejected before render.
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
