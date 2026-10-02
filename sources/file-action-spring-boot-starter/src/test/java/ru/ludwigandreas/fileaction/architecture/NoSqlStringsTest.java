package ru.ludwigandreas.fileaction.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This module introduces no third SQL carve-out.
 *
 * <h2>Why a module with its own tables needs this test</h2>
 *
 * <p>The platform's rule is QueryDSL against generated Q-types only, and exactly two packages are exempt:
 * {@code ru.ludwigandreas.ingest.bulk}, for Postgres {@code COPY} and a set-based merge, and
 * {@code ru.ludwigandreas.idempotency.sql}, for a conditional upsert with {@code RETURNING}. Both exemptions
 * were argued for a specific statement that JPQL cannot express.
 *
 * <p>Nothing here needs one. The claim this module makes is written through the ORM precisely so that the
 * {@code @Version} column is honoured - which is what stops a concurrent cancel being lost - and every read
 * is a QueryDSL expression. So the honest thing is to say so and let the build enforce it, rather than leave
 * the next person to assume that a module with a lease column must need raw SQL the way the other two did.
 *
 * <h2>Why it reads source rather than bytecode</h2>
 *
 * <p>SQL here would be a string literal, and the constant pool does not record which class a literal came
 * from in a form ArchUnit exposes - a literal can also be concatenated away by the compiler. Scanning source
 * is the only way to see the thing the rule is about, which is also why this is a module-local test and not a
 * rule in {@code architecture-rules}, which is a bytecode analyser.
 */
class NoSqlStringsTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/ru/ludwigandreas/fileaction");

    /**
     * Statement keywords that identify a string as SQL.
     *
     * <p>Looked for inside quoted strings only, and anchored on a keyword followed by whitespace, so that
     * prose in a javadoc explaining why there is no SQL here does not trip the rule. A check that fired on
     * its own documentation is a check somebody switches off.
     *
     * <p>The character class excludes a newline as well as a quote, which is not a detail. Written as
     * {@code [^"]*} the pattern starts at any quote - including a <em>closing</em> one - and runs across
     * lines to the next quote, so it matches the ordinary code and comments <em>between</em> two unrelated
     * string literals. The first version of this test did exactly that and failed on an entity's column
     * declarations. A rule that cries wolf is a rule that gets deleted, so the false positive matters as
     * much as the true one.
     */
    private static final Pattern SQL = Pattern.compile(
            "\"[^\"\\n]*\\b(SELECT\\s+\\w|INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET|DELETE\\s+FROM"
                    + "|MERGE\\s+INTO|COPY\\s+\\w|ON\\s+CONFLICT|RETURNING\\s+\\w)[^\"\\n]*\"",
            Pattern.CASE_INSENSITIVE);

    /** Spring Data's escape hatch from the QueryDSL rule, which this module does not take either. */
    private static final Pattern NATIVE_QUERY = Pattern.compile("@Query\\s*\\(");

    @Test
    @DisplayName("no source file in this module contains a SQL statement")
    void noSqlAnywhere() throws IOException {
        List<String> offenders = scan(SQL);

        Assertions.assertThat(offenders)
                .as("this module introduces no third SQL carve-out: the two that exist are"
                        + " ru.ludwigandreas.ingest.bulk and ru.ludwigandreas.idempotency.sql, and each was"
                        + " argued for a statement JPQL cannot express. If one is genuinely needed here,"
                        + " that is a change to CLAUDE.md and to this test, not a quiet addition")
                .isEmpty();
    }

    @Test
    @DisplayName("no repository uses @Query")
    void noQueryAnnotation() throws IOException {
        List<String> offenders = scan(NATIVE_QUERY);

        Assertions.assertThat(offenders)
                .as("a @Query is a query the compiler does not check and a refactoring tool cannot follow;"
                        + " every read in this module is a QueryDSL expression against a generated Q-type")
                .isEmpty();
    }

    private static List<String> scan(Pattern pattern) throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source, StandardCharsets.UTF_8);
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    offenders.add(SOURCE_ROOT.relativize(source) + ": " + matcher.group());
                }
            }
        }
        return offenders;
    }
}
