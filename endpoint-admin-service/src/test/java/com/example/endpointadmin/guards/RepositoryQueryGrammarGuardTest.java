package com.example.endpointadmin.guards;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BE-020I follow-up guard (platform-backend#329).
 *
 * <p>Prevents the {@code lower(bytea)} SQL grammar bug class from re-entering
 * the codebase via a future {@code @Query} annotation. The historical defect:
 * Hibernate could not infer the JDBC type of a bare bind parameter inside
 * {@code lower(...)} reliably, so it would resolve {@code lower(:hash)} to the
 * non-existent {@code lower(bytea)} overload and the query would fail at
 * runtime with {@code SQLGrammarException("function lower(bytea) does not exist")}.
 *
 * <p>The mitigation (BE-020I, Codex thread {@code 019e73cf}) wraps every bind
 * parameter referenced inside {@code lower(...)} in an explicit JPQL cast
 * ({@code cast(:param as string)}). This guard enforces that contract: it
 * walks every {@code @Query} value on every repository interface under
 * {@code com.example.endpointadmin.repository} and fails if any
 * {@code lower(...)} call contains a bare {@code :param} occurrence that is
 * not wrapped in {@code cast(:param as string)}.
 *
 * <h4>Why reflection over file/regex scanning</h4>
 * The source files in this package contain javadoc and comments that
 * literally describe the historical bug shape ({@code lower(:hash)},
 * {@code lower(bytea)}); a naive source-level regex would false-positive on
 * those. Reading the compiled {@code @Query.value()} via reflection lets the
 * Java compiler strip comments + concatenate text blocks for us, so the guard
 * sees only the runtime query string.
 *
 * <h4>Why occurrence-level over query-level</h4>
 * "Same query already references {@code cast(:hash as string)} somewhere"
 * is NOT a safe waiver — a stray bare {@code :hash} inside another
 * {@code lower(...)} call in the same query would still hit the overload
 * resolver. The rule is therefore: <strong>every</strong> bind-param
 * occurrence inside <strong>every</strong> {@code lower(...)} argument must
 * be inside {@code cast(:param as string)}.
 *
 * <h4>Scope</h4>
 * endpoint-admin-service repository package only. Other services
 * (e.g. user-service) carry their own pre-fix {@code lower(:email)} patterns
 * which are out of scope for #329 — they need their own audit + guard before
 * the rule is promoted shared. Tracked-by: platform-backend#329 / BE-020I.
 */
@DisplayName("BE-020I guard — @Query lower(...) must wrap bind params in cast(:param as string)")
class RepositoryQueryGrammarGuardTest {

    private static final Path REPOSITORY_ROOT =
            Path.of("src/main/java/com/example/endpointadmin/repository");

    private static final String PACKAGE_ROOT =
            "com.example.endpointadmin.repository";

    /**
     * Match a JPQL/SQL bind parameter reference. The negative lookbehind
     * {@code (?<!:)} prevents matching the second {@code :} of a PostgreSQL
     * native cast like {@code :hash::text} as a separate parameter.
     */
    private static final Pattern BIND_PARAM =
            Pattern.compile("(?<!:):([A-Za-z][A-Za-z0-9_]*)");

    @Test
    @DisplayName("repository @Query values do not pass bare bind params to lower(...)")
    void repositoryQueriesDoNotPassBareBindParamsToLower() throws Exception {
        List<String> violations = new ArrayList<>();

        for (Class<?> repositoryType : repositoryTypes()) {
            for (Method method : repositoryType.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null) {
                    continue;
                }
                violations.addAll(findBareLowerParamViolations(
                        repositoryType.getSimpleName() + "#" + method.getName(),
                        query.value()));
            }
        }

        assertTrue(violations.isEmpty(), () ->
                "BE-020I guard: @Query annotations must not pass bare bind params to lower(...).\n"
                        + "Fix shape: lower(cast(:param as string))\n"
                        + "          lower(concat('%', cast(:param as string), '%'))\n"
                        + "Tracked by: platform-backend#329 / BE-020I (Codex thread 019e73cf).\n"
                        + "Why: Hibernate may pick the non-existent lower(bytea) overload for\n"
                        + "     bare bind params and the query fails at runtime with\n"
                        + "     SQLGrammarException('function lower(bytea) does not exist').\n\n"
                        + String.join("\n", violations));
    }

    // ---------------------------------------------------------------------
    // Repository discovery
    // ---------------------------------------------------------------------

    private static List<Class<?>> repositoryTypes() throws Exception {
        // Deterministic ordering: Files.walk discovery order is FS-dependent;
        // sorting by FQCN keeps the violation list (and the eventual diff in
        // CI logs) stable across runs and OSes (Codex 019e801d hardening #1).
        List<String> fqcns = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(REPOSITORY_ROOT)) {
            for (Path path : paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList()) {
                Path relative = REPOSITORY_ROOT.relativize(path);
                String dotted = relative.toString()
                        .replace('/', '.')
                        .replace('\\', '.')
                        .replaceAll("\\.java$", "");
                fqcns.add(PACKAGE_ROOT + "." + dotted);
            }
        }
        fqcns.sort(String::compareTo);
        List<Class<?>> classes = new ArrayList<>(fqcns.size());
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (String fqcn : fqcns) {
            // initialize=false: don't trigger static initializers, we only
            // need annotation metadata (Codex 019e801d hardening #2).
            classes.add(Class.forName(fqcn, false, loader));
        }
        return classes;
    }

    // ---------------------------------------------------------------------
    // Core guard logic
    // ---------------------------------------------------------------------

    /** package-private for {@link RepositoryQueryGrammarScannerTest}. */
    static List<String> findBareLowerParamViolations(String location, String query) {
        List<String> violations = new ArrayList<>();
        for (String argument : extractLowerArguments(query)) {
            String stripped = stripStringLiterals(argument);
            Matcher matcher = BIND_PARAM.matcher(stripped);
            while (matcher.find()) {
                String param = matcher.group(1);
                int paramStart = matcher.start();
                if (!isInsideCastAsString(stripped, paramStart, param)) {
                    violations.add(location + ": bare :" + param
                            + " inside lower(" + compact(argument) + ")");
                }
            }
        }
        return violations;
    }

    /**
     * Pull the argument string out of every {@code lower(...)} call in a JPQL
     * fragment using a tiny balanced-parenthesis scanner that respects
     * single-quoted string literals (so a stray {@code (} inside a literal
     * does not throw off the balance count).
     *
     * <p>Returns the contents <em>between</em> the opening and matching
     * closing paren, e.g. for {@code lower(concat('%', :x, '%'))} it returns
     * {@code "concat('%', :x, '%')"}.
     */
    static List<String> extractLowerArguments(String query) {
        List<String> out = new ArrayList<>();
        // (?i) = case-insensitive, (?<![A-Za-z0-9_]) prevents matching the
        // tail of identifiers like "tolower" or "_lower".
        Pattern lowerCall = Pattern.compile("(?i)(?<![A-Za-z0-9_])lower\\s*\\(");
        Matcher matcher = lowerCall.matcher(query);
        while (matcher.find()) {
            int openParenIdx = query.indexOf('(', matcher.start());
            int closeIdx = findMatchingClose(query, openParenIdx);
            if (closeIdx > openParenIdx) {
                out.add(query.substring(openParenIdx + 1, closeIdx));
            }
        }
        return out;
    }

    /**
     * Scan forward from {@code openIdx} (the position of an opening
     * {@code '('} in {@code text}) and return the index of the matching
     * closing {@code ')'}. Single-quoted segments ({@code '...''}) are
     * treated as opaque so nested parens / quotes inside literals do not
     * disturb the depth counter. JPQL escapes a single quote inside a
     * literal by doubling it ({@code ''}), which this scanner handles.
     * Returns {@code -1} if no matching close is found.
     */
    private static int findMatchingClose(String text, int openIdx) {
        int depth = 0;
        boolean inLiteral = false;
        for (int i = openIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inLiteral) {
                if (c == '\'') {
                    // JPQL doubled-quote escape: '' stays inside the literal.
                    if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                        i++;
                        continue;
                    }
                    inLiteral = false;
                }
                continue;
            }
            if (c == '\'') {
                inLiteral = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Replace every single-quoted literal in {@code argument} with an
     * equally-long run of spaces. Preserves character positions (so a
     * downstream {@link Matcher#start()} stays aligned with the original
     * string) while ensuring {@code :param}-looking text inside literals
     * (e.g. {@code '%:fakeparam%'}) is never interpreted as a bind ref.
     */
    static String stripStringLiterals(String argument) {
        StringBuilder out = new StringBuilder(argument.length());
        boolean inLiteral = false;
        for (int i = 0; i < argument.length(); i++) {
            char c = argument.charAt(i);
            if (inLiteral) {
                out.append(' ');
                if (c == '\'') {
                    if (i + 1 < argument.length() && argument.charAt(i + 1) == '\'') {
                        out.append(' '); // mirror the escape consumed below
                        i++;
                        continue;
                    }
                    inLiteral = false;
                }
                continue;
            }
            if (c == '\'') {
                out.append(' ');
                inLiteral = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * True iff the bind-param occurrence at {@code paramStart} lies inside
     * a {@code cast(:param as string)} fragment (whitespace-tolerant,
     * case-insensitive) that quotes the same param name. Same-name match
     * matters: a {@code cast(:other as string)} elsewhere in the argument
     * does not whitewash a bare {@code :hash}.
     */
    private static boolean isInsideCastAsString(String argument, int paramStart, String param) {
        Pattern cast = Pattern.compile(
                "(?is)cast\\s*\\(\\s*:" + Pattern.quote(param)
                        + "\\s+as\\s+string\\s*\\)");
        Matcher matcher = cast.matcher(argument);
        while (matcher.find()) {
            if (matcher.start() <= paramStart && paramStart < matcher.end()) {
                return true;
            }
        }
        return false;
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    // ---------------------------------------------------------------------
    // Scanner unit tests — pin the guard's own behavior with table-driven
    // safe / unsafe samples (so a future refactor of the regex/scanner
    // cannot silently break the rule).
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("scanner: safe shapes pass (no violations)")
    void scannerAcceptsSafeShapes() {
        String[] safe = {
                "select s from S s where lower(cast(:hash as string)) = lower(cast(:other as string))",
                "and lower(i.publisher) = lower(cast(:publisher as string))",
                "or lower(i.displayName) like lower(concat('%', cast(:q as string), '%'))",
                "order by lower(i.displayName)",
                "where lower('CONSTANT') = lower(s.field)",
                "where lower(trim(r.namePattern)) like 'a%'",
                "-- :looks_like_param but it's inside a literal\n"
                        + "where lower(field) = '%:notparam%'",
                // Doubled-quote literal: the inner '' is an escaped single
                // quote that stays inside the literal, so `:fake` must be
                // masked out while `:real` outside the literal is preserved
                // (Codex 019e801d hardening #4).
                "where lower('it''s :fake' || cast(:real as string)) = 'x'",
                // PG native cast :hash::text — the second ':' is not a
                // standalone param; the BIND_PARAM lookbehind guarantees we
                // don't double-count. (cast(:hash as string) is the JPQL
                // form actually used in repository queries; the native cast
                // form is not currently used in endpoint-admin, but the
                // lookbehind keeps the guard future-proof if it ever lands.)
                "where lower(cast(:hash as string)) = lower(s.col)",
        };
        for (String q : safe) {
            List<String> v = findBareLowerParamViolations("safe", q);
            assertTrue(v.isEmpty(),
                    () -> "Expected NO violations for safe query but got " + v + "\nQuery: " + q);
        }
    }

    @Test
    @DisplayName("scanner: unsafe shapes are flagged")
    void scannerFlagsUnsafeShapes() {
        record Case(String query, String expectedParam) {}
        Case[] unsafe = {
                new Case("where lower(:hash) = 'x'", "hash"),
                new Case("or lower(concat('%', :term, '%')) like '%a%'", "term"),
                new Case("LOWER ( :EMAIL )", "EMAIL"),
                // Pre-fix shape: outer query also references cast(:hash) but
                // the inner lower(:hash) is still bare → must fail (occurrence-
                // level rule, not query-level).
                new Case("cast(:hash as string) is null or lower(:hash) = 'x'", "hash"),
                // Nested concat with one safe and one unsafe param → still flagged.
                new Case("lower(concat('%', cast(:a as string), :b, '%'))", "b"),
        };
        for (Case c : unsafe) {
            List<String> v = findBareLowerParamViolations("unsafe", c.query());
            assertFalse(v.isEmpty(),
                    () -> "Expected a violation for unsafe query but got none.\nQuery: " + c.query());
            assertTrue(v.get(0).contains(":" + c.expectedParam()),
                    () -> "Violation for " + c.query() + " did not mention :"
                            + c.expectedParam() + " — got " + v);
        }
    }

    @Test
    @DisplayName("scanner: lower(...) inside identifier names is ignored (e.g. mylower(x))")
    void scannerIgnoresIdentifiersEndingInLower() {
        // 'mylower(' should not be picked up as a lower() call — the boundary
        // lookbehind (?<![A-Za-z0-9_]) is what guarantees that.
        List<String> v = findBareLowerParamViolations(
                "boundary",
                "select mylower(:x) from t");
        assertTrue(v.isEmpty(), () -> "Expected no violations, got " + v);
    }

    @Test
    @DisplayName("scanner: balanced-paren extractor returns the full argument including nested parens")
    void scannerExtractsBalancedArguments() {
        List<String> args = extractLowerArguments(
                "lower(concat('%', cast(:term as string), '%')) || lower('x')");
        assertEquals(2, args.size(), () -> "Expected 2 lower(...) calls, got " + args);
        assertTrue(args.get(0).contains("concat("), () -> "First arg: " + args.get(0));
        assertTrue(args.get(0).contains("cast(:term as string)"),
                () -> "First arg: " + args.get(0));
        assertEquals("'x'", args.get(1));
    }

    @Test
    @DisplayName("scanner: stripStringLiterals masks literals while keeping positions aligned")
    void stripStringLiteralsKeepsPositionsAligned() {
        String input = "lower(':notparam' || :realparam)";
        String stripped = stripStringLiterals(input);
        assertEquals(input.length(), stripped.length(),
                "Stripped output must preserve character positions for Matcher.start() alignment");
        // ':notparam' must be fully masked out of param scanning.
        assertFalse(BIND_PARAM.matcher(stripped).results()
                        .anyMatch(m -> m.group(1).equals("notparam")),
                "':notparam' inside a literal must not be parsed as a bind parameter");
        assertTrue(BIND_PARAM.matcher(stripped).results()
                        .anyMatch(m -> m.group(1).equals("realparam")),
                "Bare :realparam outside the literal must still be discoverable");
    }
}
