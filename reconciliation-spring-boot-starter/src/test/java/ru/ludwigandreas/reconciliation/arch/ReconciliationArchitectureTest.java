package ru.ludwigandreas.reconciliation.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * The structural rules this module would otherwise lose one commit at a time.
 *
 * <p>Both of them are about keeping promises the README makes. A service author reads that a task is
 * written against a small SPI and that the module owns its own scheduling; neither claim survives a
 * well-meaning change that imports an entity into the SPI package or reaches for {@code @Scheduled}
 * because it is familiar.
 */
class ReconciliationArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("ru.ludwigandreas.reconciliation");

    /**
     * The SPI a task is written against must not drag the implementation in with it.
     *
     * <p>{@code api} is what a service author reads and implements. If it depends on entities,
     * repositories, the engine or the quota machinery, then implementing a task means compiling
     * against all of it, and every change to a staging column becomes a change to the public
     * contract.
     */
    @Test
    void theSpiPackageDependsOnNoImplementation() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..reconciliation.api..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..reconciliation.engine..",
                        "..reconciliation.entity..",
                        "..reconciliation.repository..",
                        "..reconciliation.quota..",
                        "..reconciliation.job..",
                        "..reconciliation.actuator..",
                        "..reconciliation.payload..",
                        "..reconciliation.metrics..")
                .because("a task is written against the SPI, so the SPI must be implementable without "
                        + "compiling against the engine or its schema");

        rule.check(CLASSES);
    }

    /**
     * Nothing in this module uses {@code @Scheduled}.
     *
     * <p>It would require the consuming application to have enabled {@code @EnableScheduling}, and the
     * failure when it has not is silent: nothing runs, nothing logs, and the backlog grows. Every pass
     * schedules itself against the {@code TaskScheduler} this module owns.
     */
    @Test
    void nothingUsesTheScheduledAnnotation() {
        String because = "a starter that only works when the service remembers @EnableScheduling is "
                + "not plug-and-play, and the failure when it forgets is silent";

        noMethods().should().beAnnotatedWith(Scheduled.class).because(because).check(CLASSES);
        noClasses().should().beAnnotatedWith(Scheduled.class).because(because).check(CLASSES);
    }

    /**
     * The SPI holds no Spring wiring beyond the one annotation that marks a task.
     *
     * <p>{@code @ReconciliationTask} is meta-annotated {@code @Component} on purpose, so it is the
     * single exception; anything else would mean the contract a task implements is entangled with how
     * this particular module happens to find it.
     */
    @Test
    void theSpiPackageCarriesNoSpringWiringBeyondTheTaskAnnotation() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..reconciliation.api..")
                .and().haveSimpleNameNotStartingWith("ReconciliationTask")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.context..",
                        "org.springframework.boot..")
                .because("the SPI is plain Java so that a task can be unit-tested without a context");

        rule.check(CLASSES);
    }

    /**
     * Entities stay behind their repositories and the engine.
     *
     * <p>The staging table's shape is this module's business. An actuator view or an SPI type that
     * exposed it would make every column a published contract.
     */
    @Test
    void entitiesAreNotExposedThroughTheSpi() {
        ArchRule rule = classes()
                .that().resideInAPackage("..reconciliation.entity..")
                .should().onlyBeAccessed().byAnyPackage(
                        "..reconciliation.entity..",
                        "..reconciliation.repository..",
                        "..reconciliation.engine..",
                        "..reconciliation.job..",
                        "..reconciliation.quota..",
                        "..reconciliation.actuator..",
                        "..reconciliation.config..")
                .because("the staging schema is an implementation detail, not part of the contract a "
                        + "task is written against");

        rule.check(CLASSES);
    }
}
