package ru.ludwigandreas.ingest.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
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
 * The fence around this module's exception to the QueryDSL-only rule.
 *
 * <h2>Why this test exists at all</h2>
 *
 * <p>{@code CLAUDE.md} mandates QueryDSL against generated Q-types only - no JPQL, no SQL strings -
 * and this module was granted an exception for two statements that cannot be written that way:
 * Postgres {@code COPY}, and the set-based upsert that merges staging into the target. See
 * {@code ru.ludwigandreas.ingest.bulk}'s package documentation for the full argument.
 *
 * <p>An exception with no boundary is not an exception, it is a repeal. The condition the carve-out
 * was granted under is that every SQL string stays in that one package, and this test is what makes
 * that condition true a year from now rather than a sentence somebody wrote once. Without it, the
 * first {@code SELECT} added to a repository "because there is already SQL in this module" would be
 * indistinguishable from the sanctioned ones.
 *
 * <h2>Why it reads source files rather than bytecode</h2>
 *
 * <p>SQL in this codebase is a string literal, and the constant pool of a class file does not record
 * which class a literal came from in a form ArchUnit exposes usefully - a literal can also be inlined
 * or concatenated away by the compiler. Scanning the source is the only way to see the thing the rule
 * is about. That is stated here rather than worked around, because it is also the reason this rule
 * lives in this module's own test suite rather than in {@code architecture-rules}: the shared library
 * is a bytecode analyser, and this is not a bytecode question.
 *
 * <p>This is the same convention the repository already uses for a check bytecode analysis cannot
 * carry - record the boundary in a comment where the code is, and enforce what can be enforced.
 */
class SqlConfinementTest {

    private static final String ROOT = "ru.ludwigandreas.ingest";

    private static final Path SOURCE_ROOT = Path.of("src/main/java/ru/ludwigandreas/ingest");

    /** The package the carve-out permits SQL in, and the only one. */
    private static final String BULK_PACKAGE = "bulk";

    /**
     * Statement keywords that identify a string as SQL.
     *
     * <p>Deliberately anchored on a keyword followed by whitespace and looked for in quoted strings
     * only, so that prose in a comment or a javadoc explaining <em>why</em> a statement is where it is
     * does not trip the rule. A check that fired on its own documentation is a check somebody deletes.
     */
    private static final Pattern SQL = Pattern.compile(
            "\"[^\"]*\\b(SELECT|INSERT\\s+INTO|UPDATE\\s+\\w|DELETE\\s+FROM|MERGE\\s+INTO|COPY\\s+\\w"
                    + "|TRUNCATE\\s+TABLE|CREATE\\s+TABLE|ALTER\\s+TABLE|DROP\\s+TABLE)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    @DisplayName("SQL appears only in ru.ludwigandreas.ingest.bulk")
    void sqlStaysInsideTheBulkPackage() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isInsideBulkPackage(path))
                    .forEach(path -> {
                        if (containsSql(path)) {
                            offenders.add(SOURCE_ROOT.relativize(path).toString());
                        }
                    });
        }
        Assertions.assertThat(offenders)
                .as("SQL outside ru.ludwigandreas.ingest.bulk. The QueryDSL-only rule is suspended in"
                        + " this module for exactly two statements - Postgres COPY and the set-based"
                        + " upsert - and only inside that package. Everything else queries this"
                        + " module's own tables through QueryDSL predicates in"
                        + " ru.ludwigandreas.ingest.repository. See CLAUDE.md, 'Module conventions'.")
                .isEmpty();
    }

    @Test
    @DisplayName("only the bulk package touches JDBC directly")
    void jdbcStaysInsideTheBulkPackage() {
        noClasses()
                .that().resideOutsideOfPackage(ROOT + "." + BULK_PACKAGE + "..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..",
                        "org.springframework.jdbc..", "org.postgresql..")
                .because("the bulk path is the module's one exception to writing every query in"
                        + " QueryDSL, and a JDBC reference elsewhere is how that exception starts to"
                        + " spread. The engine goes through StagingWriter and StagingMerge.")
                .check(CLASSES);
    }

    @Test
    @DisplayName("nothing in this module materialises an object in memory")
    void nothingReadsAWholeObject() {
        // The requirement this module is built around, expressed as a rule rather than only as prose
        // in RecordParser's javadoc. The integration suite additionally runs a 200MB object under a
        // measured heap ceiling, because a rule about calls cannot catch an accumulating collection.
        //
        // classes().should(...), NOT noClasses().should(...). The condition below reports a violation
        // when it finds a forbidden call, so negating the subject as well would read "no class should
        // avoid these calls" - a rule that passes only when every class DOES call them. It was written
        // that way first and passed against a class that called readAllBytes, which is a good argument
        // for reading a green architecture rule at least once before trusting it.
        classes()
                .that().resideInAPackage(ROOT + "..")
                .should(notCall("readAllBytes", "readAllLines", "readString"))
                .because("this module streams; a parser or an engine that read a whole object would"
                        + " need a heap the size of the file, and the failure would arrive in"
                        + " production on the day a partner sent a bigger drop than usual")
                .check(CLASSES);
    }

    @Test
    @DisplayName("the api package is a published surface and does not see the implementation")
    void apiDoesNotDependOnImplementation() {
        noClasses()
                .that().resideInAPackage(ROOT + ".api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".engine..", ROOT + ".entity..", ROOT + ".repository..",
                        ROOT + ".config..", ROOT + ".bulk..", ROOT + ".actuator..")
                .because("an ingest is written against the api package alone; a dependency on the"
                        + " engine would make every consumer compile against the implementation")
                .check(CLASSES);
    }

    @Test
    @DisplayName("this module ships no @RestControllerAdvice")
    void noAdviceOfItsOwn() {
        noClasses()
                .that().resideInAPackage(ROOT + "..")
                .should().beAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
                .because("web-core owns the one RFC 9457 pipeline; a second advice would render some"
                        + " errors one way and some another depending on which Spring ordered first")
                .check(CLASSES);
    }

    private static boolean isInsideBulkPackage(Path path) {
        return SOURCE_ROOT.relativize(path).startsWith(BULK_PACKAGE);
    }

    private static boolean containsSql(Path path) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            return SQL.matcher(stripComments(source)).find();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }

    /**
     * Removes comments so that prose explaining the rule does not trip it.
     *
     * <p>Crude on purpose - it does not understand a {@code //} inside a string literal - and that
     * imprecision is in the safe direction here: it can only remove text, so the worst it can do is
     * miss a violation on a line that also held a comment, never invent one.
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }

    private static ArchCondition<JavaClass> notCall(String... methodNames) {
        List<String> forbidden = List.of(methodNames);
        return new ArchCondition<>("not call " + String.join(", ", forbidden)) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                item.getMethodCallsFromSelf().forEach(call -> {
                    String name = call.getName();
                    if (forbidden.stream().anyMatch(f -> f.equalsIgnoreCase(name))) {
                        events.add(SimpleConditionEvent.violated(call,
                                item.getName() + " calls " + name + " at " + call.getSourceCodeLocation()));
                    }
                });
            }
        };
    }

}
