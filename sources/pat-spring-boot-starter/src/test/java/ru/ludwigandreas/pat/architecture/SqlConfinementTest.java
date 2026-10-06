package ru.ludwigandreas.pat.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This module contains no SQL and no JDBC. Anywhere.
 *
 * <p>The <b>inverse</b> of the confinement tests in {@code file-ingest-spring-boot-starter} and
 * {@code idempotency-spring-boot-starter}. Those two fence SQL into one named package because each has one
 * statement QueryDSL genuinely cannot express. This one asserts there is nothing to fence.
 *
 * <h2>Why there is no third carve-out</h2>
 *
 * <p>Because the instinct is to reach for the idempotency one, and the two problems look alike. "Claim a
 * token atomically" and "claim an idempotency key atomically" are not the same problem:
 *
 * <ul>
 *   <li>The idempotency claim <b>writes in order to claim</b>. It needs
 *       {@code INSERT ... ON CONFLICT (scope, key) DO UPDATE ... RETURNING}, and JPQL has neither
 *       {@code ON CONFLICT} nor {@code RETURNING} while QueryDSL-JPA generates JPQL. The alternatives are
 *       not merely less tidy but wrong: read-then-insert lets two replicas both insert, and
 *       {@code DO NOTHING} returns no row so the loser must re-select, which in {@code READ COMMITTED} can
 *       miss a row whose inserting transaction has not committed.</li>
 *   <li>Token verification <b>only reads</b>. A point read on a unique {@code key_id} index, then a
 *       constant-time digest comparison in the application. There is nothing for {@code ON CONFLICT} to do
 *       and nothing for {@code RETURNING} to return.</li>
 * </ul>
 *
 * <p>So the carve-outs stay at two, and this test is what makes that a property rather than an intention.
 *
 * <h2>Why this is a source scan rather than an ArchUnit rule</h2>
 *
 * <p>SQL is a string literal, and a class file's constant pool does not record which class a literal came
 * from in a form ArchUnit exposes usefully - a literal can also be inlined or concatenated away by the
 * compiler. Scanning source is the only way to see the thing the rule is about, which is also why this lives
 * in this module's own suite rather than in {@code architecture-rules}: that library is a bytecode analyser,
 * and this is not a bytecode question. The same reasoning the other two modules' tests record.
 *
 * <h2>What this deliberately does not scan</h2>
 *
 * <p>The Liquibase changelog under {@code src/main/resources}, which contains one raw
 * {@code <sql dbms="postgresql">} block for a partial unique index Liquibase has no portable element for.
 * That is <b>schema DDL, not data access</b>: it creates the index the point read above depends on, it runs
 * once at startup, and it is not a query. The idempotency changelog sets the same precedent. Scanning
 * resources here would fail the build over the statement that makes the QueryDSL-only rule achievable.
 */
class SqlConfinementTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/ru/ludwigandreas/pat");

    /**
     * Statement keywords that identify a string as SQL.
     *
     * <p>Anchored on a keyword followed by whitespace and looked for inside quoted strings only, so that
     * prose in a comment or a javadoc explaining why there is no SQL here does not trip the rule. A check
     * that fires on its own documentation is a check somebody deletes - and the javadoc above is full of the
     * words this pattern looks for.
     */
    private static final Pattern SQL = Pattern.compile(
            "\"[^\"]*\\b(SELECT\\s+\\w|INSERT\\s+INTO|UPDATE\\s+\\w|DELETE\\s+FROM|MERGE\\s+INTO"
                    + "|COPY\\s+\\w|TRUNCATE\\s+TABLE|CREATE\\s+TABLE|ALTER\\s+TABLE|DROP\\s+TABLE"
                    + "|CREATE\\s+(UNIQUE\\s+)?INDEX)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * JDBC types, which reach a database without going through JPA at all.
     *
     * <p>Checked separately from the SQL pattern because a module can acquire JDBC without any literal a
     * keyword scan would see - a {@code PreparedStatement} built from a string assembled elsewhere, or a
     * {@code JdbcTemplate} whose query lives in a constant in another class.
     */
    private static final Pattern JDBC = Pattern.compile(
            "\\b(PreparedStatement|CallableStatement|JdbcTemplate|CopyManager|createNativeQuery"
                    + "|nativeQuery\\s*=\\s*true|java\\.sql\\.)\\b");

    @Test
    @DisplayName("no SQL statement appears anywhere in this module's main sources")
    void moduleContainsNoSql() throws IOException {
        Assertions.assertThat(offenders(SQL))
                .as("SQL in pat-spring-boot-starter. This module has no carve-out from the QueryDSL-only"
                        + " rule and deliberately needs none: verification is a point read on a unique"
                        + " key_id index followed by a constant-time digest comparison in the application,"
                        + " so there is nothing for ON CONFLICT or RETURNING to do. If a statement here"
                        + " genuinely cannot be written in QueryDSL, that is an argument for a third"
                        + " carve-out to be made in a change's design against the conditions the existing"
                        + " two meet - not for an edit to this test. See CLAUDE.md, 'Module conventions'.")
                .isEmpty();
    }

    @Test
    @DisplayName("no JDBC type appears anywhere in this module's main sources")
    void moduleContainsNoJdbc() throws IOException {
        Assertions.assertThat(offenders(JDBC))
                .as("JDBC in pat-spring-boot-starter. Every read in this module is a QueryDSL predicate"
                        + " against the generated Q-type, and reaching the database any other way is how a"
                        + " module ends up with two data-access mechanisms and no way to tell which one a"
                        + " given read used.")
                .isEmpty();
    }

    private static List<String> offenders(Pattern pattern) throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            sources.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                if (matches(path, pattern)) {
                    offenders.add(SOURCE_ROOT.relativize(path).toString());
                }
            });
        }
        return offenders;
    }

    private static boolean matches(Path source, Pattern pattern) {
        try {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            return pattern.matcher(stripComments(text)).find();
        } catch (IOException cause) {
            throw new IllegalStateException("could not read " + source, cause);
        }
    }

    /**
     * Removes block and line comments before matching.
     *
     * <p>Necessary rather than fastidious: this module's javadoc argues at length about {@code ON CONFLICT},
     * {@code RETURNING} and {@code INSERT INTO} in order to explain why none of them is here, and a scan that
     * could not tell an explanation from a statement would fail on the explanation. Crude - it does not
     * understand a comment delimiter inside a string literal - and that crudeness is safe in this direction:
     * the worst it can do is strip slightly too much and miss a violation in a very strange file, not invent
     * one.
     */
    private static String stripComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}
