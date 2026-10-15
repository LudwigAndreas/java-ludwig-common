package ru.ludwigandreas.grpc.server.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code spi} package depends on nothing else in this module, as in {@code rest-client}.
 */
class SpiPurityTest {

    private static final String ROOT = "ru.ludwigandreas.grpc.server";

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
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage(ROOT + ".spi")
                .should().dependOnClassesThat().resideInAnyPackage(
                        ROOT + ".config..", ROOT + ".server..", ROOT + ".interceptor..", ROOT + ".context..",
                        ROOT + ".error..", ROOT + ".test..")
                .because("an SPI that reaches into the implementation cannot be implemented from outside "
                        + "without dragging the implementation along")
                .check(classes);
    }

    @Test
    @DisplayName("the SPI package contains only interfaces and records")
    void spiContainsNoImplementation() {
        ArchRuleDefinition.classes()
                .that().resideInAPackage(ROOT + ".spi")
                .should().beInterfaces().orShould().beRecords()
                .because("a default implementation in an SPI package constrains every implementor")
                .check(classes);
    }

    @Test
    @DisplayName("only the lifecycle builds a transport")
    void oneTransportClass() {
        ArchRuleDefinition.noClasses()
                .that().doNotHaveFullyQualifiedName(ROOT + ".server.GrpcServerLifecycle")
                .and().doNotHaveFullyQualifiedName(ROOT + ".server.GrpcServerLifecycle$InProcess")
                .and().resideOutsideOfPackage(ROOT + ".test..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("ServerBuilder")
                .because("the transport is built in one class per side (design D1), which is what a later "
                        + "move onto Spring gRPC's server factory replaces")
                .check(classes);
    }
}
