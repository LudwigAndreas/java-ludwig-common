package ru.ludwigandreas.idempotency.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
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
 * The fence around this module's exception to the QueryDSL-only rule, and its internal boundaries.
 *
 * <h2>Why this test exists at all</h2>
 *
 * <p>{@code CLAUDE.md} mandates QueryDSL against generated Q-types only - no JPQL, no SQL strings - and
 * this module was granted an exception for the conditional upsert that makes it correct. See
 * {@code ru.ludwigandreas.idempotency.sql}'s package documentation for the argument.
 *
 * <p>An exception with no boundary is not an exception, it is a repeal. The condition the carve-out was
 * granted under is that every SQL string stays in that one package, and this test is what makes that
 * condition true a year from now rather than a sentence somebody wrote once. Without it, the first
 * {@code SELECT} added to a repository "because there is already SQL in this module" would be
 * indistinguishable from the sanctioned one.
 *
 * <p>Deliberately the same shape as {@code file-ingest-spring-boot-starter}'s {@code SqlConfinementTest},
 * down to the regex and the comment-stripping, because there are now two carve-outs in this repository and
 * two different fences would be two things to understand.
 *
 * <h2>Why it reads source files rather than bytecode</h2>
 *
 * <p>SQL here is a string literal, and the constant pool of a class file does not record which class a
 * literal came from in a form ArchUnit exposes usefully - a literal can also be inlined or concatenated
 * away by the compiler. Scanning the source is the only way to see the thing the rule is about, which is
 * also why this rule lives in this module's own suite rather than in {@code architecture-rules}: that
 * library is a bytecode analyser, and this is not a bytecode question.
 */
class SqlConfinementTest {

    private static final String ROOT = "ru.ludwigandreas.idempotency";

    private static final Path SOURCE_ROOT = Path.of("src/main/java/ru/ludwigandreas/idempotency");

    /** The package the carve-out permits SQL in, and the only one. */
    private static final String SQL_PACKAGE = "sql";

    /**
     * Statement keywords that identify a string as SQL.
     *
     * <p>Anchored on a keyword followed by whitespace and looked for in quoted strings only, so that prose
     * in a comment or a javadoc explaining <em>why</em> a statement is where it is does not trip the rule.
     * A check that fired on its own documentation is a check somebody deletes.
     */
    private static final Pattern SQL = Pattern.compile(
            "\"[^\"]*\\b(SELECT|INSERT\\s+INTO|UPDATE\\s+\\w|DELETE\\s+FROM|MERGE\\s+INTO|COPY\\s+\\w"
                    + "|TRUNCATE\\s+TABLE|CREATE\\s+TABLE|ALTER\\s+TABLE|DROP\\s+TABLE)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    @DisplayName("SQL appears only in ru.ludwigandreas.idempotency.sql")
    void sqlStaysInsideTheSqlPackage() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isInsideSqlPackage(path))
                    .forEach(path -> {
                        if (containsSql(path)) {
                            offenders.add(SOURCE_ROOT.relativize(path).toString());
                        }
                    });
        }
        Assertions.assertThat(offenders)
                .as("SQL outside ru.ludwigandreas.idempotency.sql. The QueryDSL-only rule is suspended in"
                        + " this module for exactly one statement - the conditional upsert that claims a"
                        + " key - and only inside that package. The reads, the three state transitions and"
                        + " the purge are QueryDSL predicates in"
                        + " ru.ludwigandreas.idempotency.repository. See CLAUDE.md, 'Module conventions'.")
                .isEmpty();
    }

    @Test
    @DisplayName("only the sql package touches JDBC directly")
    void jdbcStaysInsideTheSqlPackage() {
        noClasses()
                .that().resideOutsideOfPackage(ROOT + "." + SQL_PACKAGE + "..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..",
                        "org.springframework.jdbc..", "org.postgresql..")
                .because("the claim statement is the module's one exception to writing every query in"
                        + " QueryDSL, and a JDBC reference elsewhere is how that exception starts to"
                        + " spread. The store goes through ClaimGateway.")
                .check(CLASSES);
    }

    @Test
    @DisplayName("the api package is a published surface and does not see the implementation")
    void apiDoesNotDependOnImplementation() {
        noClasses()
                .that().resideInAPackage(ROOT + ".api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".store..", ROOT + ".entity..", ROOT + ".repository..",
                        ROOT + ".config..", ROOT + ".sql..", ROOT + ".web..", ROOT + ".kafka..",
                        ROOT + ".lifecycle..")
                .because("a consumer writes against the api package alone; a dependency on the store would"
                        + " make every consumer compile against the implementation, and would stop the"
                        + " Redis backend from being a real alternative rather than a subset")
                .check(CLASSES);
    }

    @Test
    @DisplayName("this module ships no @RestControllerAdvice")
    void noAdviceOfItsOwn() {
        noClasses()
                .that().resideInAPackage(ROOT + "..")
                .should().beAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
                .because("web-core owns the one RFC 9457 pipeline; a second advice would render some"
                        + " errors one way and some another depending on which Spring ordered first."
                        + " The filter renders its two refusals through web-core's own"
                        + " ProblemDetailFactory, which is that pipeline rather than a second one")
                .check(CLASSES);
    }

    @Test
    @DisplayName("nothing in this module declares an audit SPI or a logger named *.audit")
    void auditGoesThroughAuditCore() {
        noClasses()
                .that().resideInAPackage(ROOT + "..")
                .should().haveSimpleNameEndingWith("AuditSink")
                .orShould().haveSimpleNameEndingWith("AuditLogger")
                .because("audit-core owns the one AuditSink. This module's IdempotencyAuditEvent is the"
                        + " typed authoring surface and flattens into the platform envelope with"
                        + " toAuditEvent(), which is the shape RuleGroup.AUDIT enforces estate-wide")
                .check(CLASSES);
    }

    /**
     * The tripwire from the POM, expressed as a rule.
     *
     * <p>This module may depend on {@code db-core}, {@code job-core} and {@code web-core} because nothing
     * upstream of them needs the store. What it must never depend on is a module that <em>they</em> depend
     * on, because Maven's reactor DAG ignores scope and the cycle would appear as a reactor failure during
     * somebody else's unrelated change. The POM says this in a comment; here it is a failing test.
     */
    @Test
    @DisplayName("this module does not depend on rest-client or hot-reload")
    void noDependencyOnModulesBelowIt() {
        noClasses()
                .that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "ru.ludwigandreas.restclient..", "ru.ludwigandreas.hotreload..")
                .because("if either of those ever needs the store, this module has to be split into"
                        + " idempotency-core and the starter first - see the tripwire comment in the POM")
                .check(CLASSES);
    }

    private static boolean isInsideSqlPackage(Path path) {
        return SOURCE_ROOT.relativize(path).startsWith(SQL_PACKAGE);
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
     * imprecision is in the safe direction: it can only remove text, so the worst it can do is miss a
     * violation on a line that also held a comment, never invent one.
     */
    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }
}
