package ru.ludwigandreas.grpc.client.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code spi} package depends on nothing else in this module, and the failures a caller catches
 * do not depend on the machinery that throws them.
 */
class SpiPurityTest {

    private static final String ROOT = "ru.ludwigandreas.grpc.client";

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
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".config..", ROOT + ".channel..",
                        ROOT + ".interceptor..", ROOT + ".auth..", ROOT + ".properties..", ROOT + ".error..",
                        ROOT + ".audit..", ROOT + ".test..")
                .because("an SPI that reaches into the implementation cannot be implemented from outside")
                .check(classes);
    }

    @Test
    @DisplayName("the exceptions do not depend on the pipeline")
    void exceptionsStandAlone() {
        ArchRuleDefinition.noClasses()
                .that().resideInAPackage(ROOT + ".error").and().haveSimpleNameEndingWith("Exception")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".config..", ROOT + ".channel..",
                        ROOT + ".interceptor..", ROOT + ".auth..")
                .because("a caller catches these, so they must be readable and constructible without it")
                .check(classes);
    }

    @Test
    @DisplayName("only the channel factory builds a transport")
    void oneTransportClass() {
        ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage(ROOT + ".channel..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("ChannelBuilder")
                .because("the transport is built in one class per side (design D1)")
                .check(classes);
    }
}
