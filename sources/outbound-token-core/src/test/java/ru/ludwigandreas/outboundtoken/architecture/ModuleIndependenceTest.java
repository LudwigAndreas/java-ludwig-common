package ru.ludwigandreas.outboundtoken.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This module reaches no other platform module, which is what lets both client starters depend on
 * it without a reactor cycle.
 */
class ModuleIndependenceTest {

    @Test
    @DisplayName("no class depends on a ru.ludwigandreas type outside this module")
    void dependsOnNoPlatformModule() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ru.ludwigandreas.outboundtoken");

        ArchRuleDefinition.noClasses()
                .should().dependOnClassesThat(JavaClass.Predicates.resideInAPackage("ru.ludwigandreas..")
                        .and(JavaClass.Predicates.resideOutsideOfPackage("ru.ludwigandreas.outboundtoken..")))
                .because("rest-client-spring-boot-starter and grpc-client-spring-boot-starter both depend on "
                        + "this module, so any platform dependency here is where a reactor cycle would close")
                .check(classes);
    }
}
