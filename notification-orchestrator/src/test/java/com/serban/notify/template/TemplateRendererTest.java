package com.serban.notify.template;

import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TemplateRenderer unit test (Faz 23.1 PR3 — Codex 019df9ae Q3 REVISE absorb).
 *
 * <p>Codex Q3 mandate'leri:
 * <ul>
 *   <li>{@code vars} namespace (NOT root)</li>
 *   <li>HTML escape default — {@code th:utext} reject</li>
 *   <li>SSTI prevention — {@code T(...)}, {@code @bean}, {@code #ctx}, ... reject</li>
 *   <li>Subject CRLF normalize</li>
 *   <li>Payload key regex {@code [a-zA-Z0-9_.-]{1,64}}</li>
 * </ul>
 */
class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void rendersTextBodyWithVarsNamespace() {
        // PR3 vars namespace; using inline raw [(...)] is REJECTED by lint, so we use escape [[...]]
        NotificationTemplate t = template(null, "Hello [[${vars.user_name}]]", "Sub");
        Map<String, Object> payload = Map.of("user_name", "Halil");

        RenderedMessage out = renderer.render(t, payload);

        assertThat(out.bodyText()).isEqualTo("Hello Halil");
        assertThat(out.subject()).isEqualTo("Sub");
        assertThat(out.locale()).isEqualTo("tr-TR");
    }

    @Test
    void htmlBodyEscapesXssPayload() {
        NotificationTemplate t = template(
            "<p>Welcome <span th:text=\"${vars.user_name}\"></span></p>",
            null,
            "Welcome"
        );
        Map<String, Object> payload = Map.of("user_name", "<script>alert(1)</script>");

        RenderedMessage out = renderer.render(t, payload);

        assertThat(out.bodyHtml()).contains("&lt;script&gt;");
        assertThat(out.bodyHtml()).doesNotContain("<script>");
    }

    @Test
    void rejectsThUtext() {
        NotificationTemplate t = template(
            "<p th:utext=\"${vars.user_name}\"></p>", null, "Welcome"
        );
        assertThatThrownBy(() -> renderer.render(t, Map.of("user_name", "Halil")))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("rejected pattern");
    }

    @Test
    void rejectsInlineRawOutput() {
        NotificationTemplate t = template(null, "Hello [(${vars.user_name})]", "Sub");
        assertThatThrownBy(() -> renderer.render(t, Map.of("user_name", "Halil")))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("rejected pattern");
    }

    @Test
    void rejectsSstiTypeReference() {
        NotificationTemplate t = template(
            null,
            "Now: [[${T(java.lang.System).currentTimeMillis()}]]",
            "Sub"
        );
        assertThatThrownBy(() -> renderer.render(t, Map.of()))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("rejected pattern");
    }

    @Test
    void rejectsSpringBeanReference() {
        NotificationTemplate t = template(
            null, "Bean: [[${@templateRepo.findAll()}]]", "Sub"
        );
        assertThatThrownBy(() -> renderer.render(t, Map.of()))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("rejected pattern");
    }

    @Test
    void rejectsCtxReference() {
        NotificationTemplate t = template(null, "Ctx: [[${#ctx}]]", "Sub");
        assertThatThrownBy(() -> renderer.render(t, Map.of()))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("rejected pattern");
    }

    // Codex 019df9ef P1 absorb: lint hardening tests.

    @Test
    void rejectsThymeleafPreprocessing() {
        // __${...}__ — preprocessing dynamic expression generation gateway
        NotificationTemplate t = template(null, "Pre: __${vars.user_name}__", "Sub");
        assertThatThrownBy(() -> renderer.render(t, Map.of("user_name", "x")))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("rejected pattern");
    }

    @Test
    void rejectsTypeReferenceLowercasePackage() {
        // Codex hardening: T(java.*) only previously caught; org/com/javax now caught
        NotificationTemplate t = template(
            null, "X=[[${T(org.apache.commons.lang3.StringUtils).reverse('a')}]]", "S"
        );
        assertThatThrownBy(() -> renderer.render(t, Map.of()))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("rejected pattern");
    }

    @Test
    void rejectsBeanReferenceInsideExpression() {
        // Codex hardening: ${...@... is rejected even without method call form
        NotificationTemplate t = template(null, "X=[[${@templateRepo}]]", "S");
        assertThatThrownBy(() -> renderer.render(t, Map.of()))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("rejected pattern");
    }

    @Test
    void rejectsNewWithLowercasePackage() {
        // Codex hardening: new java.util.Date() (lowercase package) now caught
        NotificationTemplate t = template(null, "D=[[${new java.util.Date()}]]", "S");
        assertThatThrownBy(() -> renderer.render(t, Map.of()))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("rejected pattern");
    }

    @Test
    void rejectsGetClassReflection() {
        NotificationTemplate t = template(
            null, "Cls: [[${vars.user_name.getClass()}]]", "Sub"
        );
        assertThatThrownBy(() -> renderer.render(t, Map.of("user_name", "x")))
            .isInstanceOf(InvalidRequestException.class)
            .hasMessageContaining("rejected pattern");
    }

    @Test
    void subjectCrlfStrippedHeaderInjectionGuard() {
        NotificationTemplate t = template(null, "Body", "Test\r\nBcc: attacker@evil.com");

        RenderedMessage out = renderer.render(t, Map.of());

        // CR removed, LF replaced with space, trimmed
        assertThat(out.subject()).isEqualTo("Test Bcc: attacker@evil.com");
        assertThat(out.subject()).doesNotContain("\r");
        assertThat(out.subject()).doesNotContain("\n");
    }

    @Test
    void payloadKeyRejectInvalidCharacter() {
        NotificationTemplate t = template(null, "Hello [[${vars.x}]]", "Sub");
        Map<String, Object> bad = new HashMap<>();
        bad.put("x", "ok");
        bad.put("evil key with space", "should be filtered");

        // Render does not throw — bad key silently dropped (warn-log)
        RenderedMessage out = renderer.render(t, bad);
        assertThat(out.bodyText()).isEqualTo("Hello ok");
    }

    @Test
    void payloadKeyAcceptsLetterDigitDotDashUnderscore() {
        // Keys with dash / dot accessed via SpEL bracket form `vars['k']`.
        // Regex [a-zA-Z0-9_.-]{1,64} accepts these.
        NotificationTemplate t = template(
            null,
            "U=[[${vars.user_name}]] R=[[${vars['reset-url']}]] V=[[${vars['v.1']}]]",
            "S"
        );
        Map<String, Object> payload = Map.of(
            "user_name", "H",
            "reset-url", "https://x",
            "v.1", "v"
        );
        RenderedMessage out = renderer.render(t, payload);
        assertThat(out.bodyText()).isEqualTo("U=H R=https://x V=v");
    }

    @Test
    void nullBodyHtmlOk() {
        NotificationTemplate t = template(null, "Just text [[${vars.x}]]", "S");
        RenderedMessage out = renderer.render(t, Map.of("x", "v"));
        assertThat(out.bodyHtml()).isNull();
        assertThat(out.bodyText()).isEqualTo("Just text v");
    }

    @Test
    void stripCrlfStaticUtility() {
        // Implementation: remove \r, replace \n with space, trim
        // a\r\nb\rc\nd → a\nbc\nd → a bc d
        assertThat(TemplateRenderer.stripCrlf("a\r\nb\rc\nd")).isEqualTo("a bc d");
        // Trim case
        assertThat(TemplateRenderer.stripCrlf("\r\nhello\r\n")).isEqualTo("hello");
    }

    private NotificationTemplate template(String html, String text, String subject) {
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateId("test-template");
        t.setVersion(1);
        t.setLocale("tr-TR");
        t.setSubject(subject);
        t.setBodyHtml(html);
        t.setBodyText(text);
        return t;
    }
}
