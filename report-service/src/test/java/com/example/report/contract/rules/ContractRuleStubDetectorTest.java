package com.example.report.contract.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * R16 close-out discipline guard — RC stub detector.
 *
 * <p>Tarama hedefi: {@code report-service/src/main/java/com/example/report/contract/rules}
 * altındaki tüm {@code RC*.java} dosyaları. Her dosyada {@code validate(ReportDefinition def)}
 * metodu davranışsız ise ({@code return List.of();} + opsiyonel yorum) ve {@code RuleId} explicit
 * {@code deferred-stub-rules.yaml} kaydında değilse FAIL.
 *
 * <p>Pattern: ContractRule.validate empty body kabul edilir, ancak hangi rule'un neden empty
 * olduğu registry'de yazılı olmalı. Yeni stub'lar sessiz CI'da yeşil geçmek yerine açık beyan
 * gerektirir. Bu R16 close-out discipline gap'inin guard'ıdır (bkz. ADR-0017).
 *
 * <h2>Rationale</h2>
 * Faz 2 Program 1c kapanışında RC-009 ActionScopeValid stub implement edilmeden bırakıldı; CI
 * yeşil geçti çünkü `return List.of()` violation üretmez. Aynı pattern başka rule'larda da
 * silent kalabilir. Bu test deferred stub'ları explicit registry'ye bağlar; yeni stub
 * eklendiğinde ya implement edilir ya da deferral gerekçesi belgelenir.
 *
 * <p>Codex 019e27f5 PARTIAL verdict (R16 stratejik karar): "Stub detector global
 * {@code return List.of()} grep'i olmasın; {@code report-service/.../contract/rules/*} gibi
 * dar scoped çalışsın. Mevcut RC-009 gibi legacy no-op'lar ya fail ettirilsin ya da explicit
 * debt registry + expiry + owner olmadan geçemesin. Yeni sessiz stub kesin fail olmalı."
 */
class ContractRuleStubDetectorTest {

    private static final Path RULES_DIR =
            Paths.get("src/main/java/com/example/report/contract/rules");

    private static final Path DEFERRED_REGISTRY =
            Paths.get("src/main/resources/contract/deferred-stub-rules.yaml");

    private static final Pattern RULE_FILE_PATTERN = Pattern.compile("RC\\d+.*\\.java");

    private static final Pattern RULE_ID_PATTERN =
            Pattern.compile("ruleId\\(\\)\\s*\\{[^}]*return\\s+\"([^\"]+)\"");

    /**
     * Validate method body extractor. {@code validate(ReportDefinition def)} açan brace'ten
     * kapanan brace'e kadar olan içerik. Naive but sufficient for {@code contract/rules/*} —
     * tüm RC dosyaları tek validate metodu içerir, nested brace yok.
     */
    private static final Pattern VALIDATE_BODY_PATTERN = Pattern.compile(
            "validate\\(ReportDefinition[^)]*\\)\\s*\\{(.*?)\\n\\s{4}\\}",
            Pattern.DOTALL);

    @Test
    void allRcRulesShouldNotBeStubs_unlessExplicitlyDeferred() throws IOException {
        assertThat(RULES_DIR)
                .as("RC rules directory present (run test from report-service module)")
                .exists();

        Set<String> deferredRuleIds = loadDeferredRuleIds();

        List<String> undeferredStubs = new ArrayList<>();
        List<String> unparseable = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(RULES_DIR, 1)) {
            walk.filter(p -> RULE_FILE_PATTERN.matcher(p.getFileName().toString()).matches())
                    .forEach(file -> {
                        String source;
                        try {
                            source = Files.readString(file);
                        } catch (IOException e) {
                            throw new RuntimeException(
                                    "Failed to read RC file: " + file, e);
                        }
                        String ruleId = extractRuleId(source);
                        if (ruleId == null) {
                            // No ruleId() → not a ContractRule impl, skip.
                            return;
                        }
                        // Codex 019e2804 REVISE P2/P3 absorb: validate body parse-miss
                        // must FAIL (not silently pass). Otherwise small formatter
                        // variations could let new stubs slip through undetected.
                        if (!hasParseableValidateBody(source)) {
                            unparseable.add(ruleId + " (" + file.getFileName()
                                    + " — validate(ReportDefinition) body parse failed;"
                                    + " stub detector cannot inspect this rule)");
                            return;
                        }
                        if (isStub(source) && !deferredRuleIds.contains(ruleId)) {
                            undeferredStubs.add(
                                    ruleId + " (" + file.getFileName() + ")");
                        }
                    });
        }

        assertThat(unparseable)
                .as("RC rule files whose validate(ReportDefinition) body could not be parsed;"
                        + " stub detector regex is too narrow or rule uses unsupported"
                        + " formatting (multi-line param, qualified List name, final qualifier)."
                        + " Either normalize the rule formatting OR widen the detector regex.")
                .isEmpty();

        assertThat(undeferredStubs)
                .as("Undeferred RC stub rules found — implement validate() behavior or add"
                        + " entry to report-service/src/main/resources/contract/deferred-stub-rules.yaml")
                .isEmpty();
    }

    @Test
    void deferredStubRegistry_shouldNotReferenceMissingRules() throws IOException {
        assertThat(DEFERRED_REGISTRY)
                .as("deferred-stub-rules.yaml present")
                .exists();

        Set<String> deferredRuleIds = loadDeferredRuleIds();
        Set<String> existingRuleIds = collectExistingRuleIds();

        List<String> staleEntries = new ArrayList<>();
        for (String deferredId : deferredRuleIds) {
            if (!existingRuleIds.contains(deferredId)) {
                staleEntries.add(deferredId);
            }
        }

        assertThat(staleEntries)
                .as("deferred-stub-rules.yaml references rules that don't exist anymore;"
                        + " remove stale entries or restore the rule class")
                .isEmpty();
    }

    @Test
    void deferredStubRegistry_shouldHaveValidExpiryDates() throws IOException {
        assertThat(DEFERRED_REGISTRY).exists();

        List<Map<String, Object>> entries = loadDeferredEntries();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        List<String> invalidEntries = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Object ruleId = entry.get("rule_id");
            Object expiry = entry.get("deferral_until");
            Object owner = entry.get("owner");
            Object reason = entry.get("reason");

            if (ruleId == null || expiry == null || owner == null || reason == null) {
                invalidEntries.add(String.valueOf(ruleId)
                        + " (missing required fields: rule_id, deferral_until, owner, reason)");
                continue;
            }
            // Codex 019e2804 REVISE P2 absorb: expiry semantik gate, format-only değil.
            // SnakeYAML date'i java.util.Date olarak da parse edebilir; string'e çevirip
            // ISO_LOCAL_DATE ile parse et + UTC today ile karşılaştır.
            String expiryStr = expiry.toString();
            LocalDate expiryDate;
            try {
                expiryDate = LocalDate.parse(expiryStr.substring(0, Math.min(10, expiryStr.length())),
                        DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException ex) {
                invalidEntries.add(ruleId + " (invalid expiry format '" + expiryStr
                        + "' — expected ISO YYYY-MM-DD)");
                continue;
            }
            if (expiryDate.isBefore(today)) {
                invalidEntries.add(ruleId + " (deferral_until=" + expiryStr
                        + " is in the past, today=" + today
                        + "; either implement the rule or extend the deferral with"
                        + " justification + ADR reference)");
            }
            // Non-blank owner / reason / tracking_pr enforcement.
            if (owner.toString().isBlank()) {
                invalidEntries.add(ruleId + " (owner is blank)");
            }
            if (reason.toString().isBlank()) {
                invalidEntries.add(ruleId + " (reason is blank)");
            }
            Object trackingPr = entry.get("tracking_pr");
            if (trackingPr == null || trackingPr.toString().isBlank()) {
                invalidEntries.add(ruleId + " (tracking_pr field missing — link the original"
                        + " PR/issue that introduced the deferral)");
            }
        }

        assertThat(invalidEntries)
                .as("deferred-stub-rules.yaml entries must declare rule_id, deferral_until,"
                        + " owner, reason, tracking_pr fields with ISO YYYY-MM-DD expiry that"
                        + " is not in the past (UTC). Codex 019e2804 REVISE P2 absorb.")
                .isEmpty();
    }

    // ----- helpers -----

    private static String extractRuleId(String source) {
        Matcher m = RULE_ID_PATTERN.matcher(source);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Validate body parse edilebilir mi? VALIDATE_BODY_PATTERN regex'in eşleşmediği
     * dosyalar stub detector için kör nokta yaratır. Codex 019e2804 P2/P3 absorb:
     * parse-miss durumunda silent pass yerine açık FAIL liste girilir.
     */
    private static boolean hasParseableValidateBody(String source) {
        Matcher m = VALIDATE_BODY_PATTERN.matcher(source);
        return m.find();
    }

    /**
     * Validate body stub mi? Yorumlar + boş satırlar strip edildikten sonra sadece
     * {@code return List.of();} (veya tam-qualified {@code java.util.List.of();})
     * kalıyorsa stub. Bu metoda girmek için önce {@link #hasParseableValidateBody}
     * true dönmüş olmalı.
     */
    private static boolean isStub(String source) {
        Matcher m = VALIDATE_BODY_PATTERN.matcher(source);
        if (!m.find()) return false;
        String body = m.group(1);
        // Strip // line comments
        body = body.replaceAll("//[^\n]*", "");
        // Strip /* block */ comments
        body = body.replaceAll("(?s)/\\*.*?\\*/", "");
        // Normalize whitespace
        String stripped = body.replaceAll("\\s+", " ").trim();
        // Accept both `return List.of();` and `return java.util.List.of();` —
        // Codex 019e2804 P2/P3 absorb: küçük varyasyon stub'ı kaçırmasın.
        return stripped.equals("return List.of();")
                || stripped.equals("return java.util.List.of();");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> loadDeferredRuleIds() throws IOException {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> entry : loadDeferredEntries()) {
            Object id = entry.get("rule_id");
            if (id != null) ids.add(id.toString());
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> loadDeferredEntries() throws IOException {
        if (!Files.exists(DEFERRED_REGISTRY)) return List.of();
        Yaml yaml = new Yaml();
        try (var in = Files.newBufferedReader(DEFERRED_REGISTRY)) {
            Map<String, Object> root = yaml.load(in);
            if (root == null) return List.of();
            Object entries = root.get("deferred_stub_rules");
            if (entries instanceof List<?> list) {
                List<Map<String, Object>> typed = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        typed.add((Map<String, Object>) map);
                    }
                }
                return typed;
            }
            return List.of();
        }
    }

    private static Set<String> collectExistingRuleIds() throws IOException {
        Set<String> ids = new HashSet<>();
        try (Stream<Path> walk = Files.walk(RULES_DIR, 1)) {
            walk.filter(p -> RULE_FILE_PATTERN.matcher(p.getFileName().toString()).matches())
                    .forEach(file -> {
                        try {
                            String source = Files.readString(file);
                            String id = extractRuleId(source);
                            if (id != null) ids.add(id);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        return ids;
    }
}
