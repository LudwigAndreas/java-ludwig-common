package ru.ludwigandreas.export.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This module's own internal boundaries.
 *
 * <h2>Why these rules are here and not in architecture-rules</h2>
 *
 * <p>{@code architecture-rules} ships the cross-cutting conventions - layering, package cycles, the
 * REST boundary, JPA persistence, exception architecture, DTO immutability - and every one of them
 * already applies to this module when a service enables them. Nothing needed adding there and nothing
 * needed weakening, which is worth stating because "add a rule" and "relax a rule to fit" look the
 * same in a diff.
 *
 * <p>What a shared rule library cannot express is a boundary that exists only inside one module: that
 * POI is visible to the XLSX writer and nowhere else, that the filter dialect is visible to one class,
 * that the API package is a published surface and must not see the engine. Those are this module's
 * decisions, so they are checked in this module.
 *
 * <h2>Each rule is a decision documented somewhere else</h2>
 *
 * <p>Every rule below has a counterpart in a class comment or the README. The rule is what makes the
 * comment true a year from now - a boundary nothing enforces is a boundary that has already been
 * crossed somewhere.
 */
class ExportArchitectureTest {

    private static final String ROOT = "ru.ludwigandreas.export";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    @DisplayName("the api package is a published surface and does not see the implementation")
    void apiDoesNotDependOnImplementation() {
        noClasses()
                .that().resideInAPackage(ROOT + ".api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".engine..", ROOT + ".entity..", ROOT + ".web..",
                        ROOT + ".lifecycle..", ROOT + ".repository..", ROOT + ".config..",
                        ROOT + ".enrich..", ROOT + ".format..")
                .because("a definition is written against the api package alone; a dependency on the"
                        + " engine would make every consumer compile against the implementation and"
                        + " would make the api unpublishable on its own")
                .check(CLASSES);
    }

    @Test
    @DisplayName("Apache POI is visible to the XLSX writer and nowhere else")
    void poiStaysInsideTheXlsxWriter() {
        noClasses()
                .that().resideOutsideOfPackage(ROOT + ".format.xlsx..")
                .should().dependOnClassesThat().resideInAnyPackage("org.apache.poi..")
                .because("POI is an optional dependency and the XLSX writer is the seam that owns it;"
                        + " a reference from the engine would make a CSV-only service carry a"
                        + " workbook library and would fail at class-load rather than at startup")
                .check(CLASSES);
    }

    @Test
    @DisplayName("the OData filter dialect is visible to the filter package and the wiring")
    void filterDialectStaysInsideTheFilterPackage() {
        noClasses()
                .that().resideOutsideOfPackages(ROOT + ".filter..", ROOT + ".config..")
                .should().dependOnClassesThat().resideInAnyPackage("ru.ludwigandreas.odatafilter..",
                        "org.apache.olingo..")
                .because("the platform has one filter dialect and this module parses none of it; a"
                        + " second reference is how a reporting module grows its own. The"
                        + " autoconfiguration is exempt because it has to name the class it conditions"
                        + " on - that is what makes the dependency optional rather than what makes it"
                        + " used")
                .check(CLASSES);
    }

    @Test
    @DisplayName("the engine does not see the persistence layer")
    void engineDoesNotDependOnPersistence() {
        noClasses()
                .that().resideInAnyPackage(ROOT + ".engine..", ROOT + ".enrich..", ROOT + ".format..",
                        ROOT + ".render..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".entity..", ROOT + ".repository..", ROOT + ".web..")
                .because("the engine takes a plan and produces a file; a service driving it directly -"
                        + " a batch job, an admin tool - gets it without a datasource, and a"
                        + " dependency here would take that away")
                .check(CLASSES);
    }

    @Test
    @DisplayName("nothing in this module ships its own exception advice")
    void noRestControllerAdvice() {
        noClasses()
                .that().resideInAPackage(ROOT + "..")
                .should().beAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
                .orShould().beAnnotatedWith("org.springframework.web.bind.annotation.ControllerAdvice")
                .because("every failure goes through web-core's single ProblemDetail pipeline; a"
                        + " second advice would render this module's errors slightly differently from"
                        + " the rest of the API, which is the problem web-core exists to have solved")
                .check(CLASSES);
    }

    @Test
    @DisplayName("nothing opens its own HTTP client or object mapper")
    void noSelfMadeInfrastructure() {
        noClasses()
                .that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.net.http.HttpClient")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("com.fasterxml.jackson.databind.ObjectMapper")
                .because("every partner call goes through a named @LudwigRestClient, which is where"
                        + " the pool, the timeouts, the retry and the breaker live; a client opened"
                        + " here would have none of them and one slow partner would starve every"
                        + " report on the instance")
                .check(CLASSES);
    }

    @Test
    @DisplayName("thread pools are created only by the configuration")
    void poolsAreCreatedOnlyByTheConfiguration() {
        noClasses()
                .that().resideOutsideOfPackage(ROOT + ".config..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("java.util.concurrent.Executors")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("java.util.concurrent.ThreadPoolExecutor")
                .because("every pool in this module is bounded, named and owned by the"
                        + " autoconfiguration, so an operator can see it in a thread dump and size it"
                        + " from configuration; a pool created elsewhere is a thread count nobody"
                        + " added up")
                .check(CLASSES);
    }

    @Test
    @DisplayName("no mutable static state, because two concurrent runs would share it")
    void noMutableStaticState() {
        noFields()
                .that().areStatic().and().areNotFinal()
                .should().beDeclaredInClassesThat().resideInAPackage(ROOT + "..")
                .because("a run holds a great deal of state - rows written, open sheet, running"
                        + " totals - and any of it shared between two concurrent runs is a wrong"
                        + " number in a finished file, which is a defect that appears only under load")
                // The desired state is that this matches nothing at all, which ArchUnit would
                // otherwise treat as a rule that failed to run.
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    /**
     * No cycles among the packages that carry behaviour.
     *
     * <p>{@code config} is excluded, and the exclusion is the honest part of this rule. The wiring
     * package depends on everything it wires, and everything that reads configuration depends on
     * {@code ExportProperties} - which lives in {@code config} because that is where every other
     * module in this platform puts its properties class. A "cycle" through the package whose job is
     * to know about all the others carries no information, and moving the properties out to satisfy
     * the rule would break a convention that is worth more than the rule.
     *
     * <p>What the rule still catches is the cycle that would matter: {@code engine} and
     * {@code format}, or {@code enrich} and {@code render}, becoming inseparable.
     */
    @Test
    @DisplayName("the behavioural packages have no cycles")
    void noPackageCycles() {
        SlicesRuleDefinition.slices()
                .matching(ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .ignoreDependency(
                        com.tngtech.archunit.base.DescribedPredicate.describe("in config",
                                javaClass -> javaClass.getPackageName().startsWith(ROOT + ".config")),
                        com.tngtech.archunit.base.DescribedPredicate.alwaysTrue())
                .ignoreDependency(
                        com.tngtech.archunit.base.DescribedPredicate.alwaysTrue(),
                        com.tngtech.archunit.base.DescribedPredicate.describe("in config",
                                javaClass -> javaClass.getPackageName().startsWith(ROOT + ".config")))
                .because("a cycle between two packages means neither can be understood, tested or"
                        + " extracted without the other")
                .check(CLASSES);
    }
}
