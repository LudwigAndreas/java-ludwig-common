package ru.ludwigandreas.restclient.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules that keep the extension points extensible.
 *
 * <p>Every one of these is a property that is easy to break by accident and impossible to notice
 * afterwards. A single import of a configuration class from the SPI package would make the SPI
 * un-implementable outside this module without dragging the module's internals in - and the person
 * who adds that import will be adding a perfectly reasonable convenience method.
 */
class SpiPurityTest {

    private static final String ROOT = "ru.ludwigandreas.restclient";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("the SPI package depends on nothing else in this module")
    void spiIsSelfContained() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage(ROOT + ".spi")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".config..", ROOT + ".core..", ROOT + ".auth..",
                        ROOT + ".resilience..", ROOT + ".observability..", ROOT + ".transport..",
                        ROOT + ".registrar..", ROOT + ".error..", ROOT + ".test..")
                .because("an SPI that reaches into the implementation cannot be implemented from "
                        + "outside without dragging the implementation along, which is the whole "
                        + "point of it being an SPI");
        rule.check(classes);
    }

    @Test
    @DisplayName("the SPI package contains only interfaces and records")
    void spiContainsNoImplementation() {
        ArchRule rule = ArchRuleDefinition.classes()
                .that().resideInAPackage(ROOT + ".spi")
                .should().beInterfaces()
                .orShould().beRecords()
                .because("an abstract class in an SPI package is a default implementation, and a "
                        + "default implementation constrains every implementor's class hierarchy");
        rule.check(classes);
    }

    @Test
    @DisplayName("the exception hierarchy does not depend on the pipeline")
    void exceptionsAreIndependentOfThePipeline() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage(ROOT + ".error")
                .and().haveSimpleNameEndingWith("Exception")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".config..", ROOT + ".core..", ROOT + ".resilience..",
                        ROOT + ".transport..", ROOT + ".registrar..")
                .because("a caller catches these types, so they have to be readable - and "
                        + "constructible in a test - without the machinery that threw them");
        rule.check(classes);
    }

    @Test
    @DisplayName("the transport package knows nothing about resilience, auth or the registrar")
    void transportIsALeafLayer() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage(ROOT + ".transport..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".resilience..", ROOT + ".auth..", ROOT + ".registrar..",
                        ROOT + ".core..")
                .because("the transport is how bytes move; a transport that knew about retries "
                        + "would be a second place where retries happen");
        rule.check(classes);
    }

    @Test
    @DisplayName("nothing outside the config package reads the property classes' raw defaults")
    void configurationIsResolvedInOnePlace() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackages(ROOT + ".config..", ROOT + ".test..")
                .should().callMethodWhere(com.tngtech.archunit.base.DescribedPredicate.describe(
                        "is ClientPropertiesMerger.resolve(..)",
                        call -> call.getTargetOwner().getName()
                                .equals(ROOT + ".config.ClientPropertiesMerger")))
                .because("merging happens once, in ClientRuntimeBuilder; a second caller is a "
                        + "second answer to 'what is this client's read timeout'");
        rule.check(classes);
    }

    @Test
    @DisplayName("no class writes to System.out or System.err")
    void nothingPrintsToTheConsole() {
        ArchRule rule = com.tngtech.archunit.library.GeneralCodingRules
                .NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
                .because("a library that prints has no way to be quiet in a service that needs it "
                        + "to be, and its output carries none of the structure the log pipeline "
                        + "expects");
        rule.check(classes);
    }
}
