package ru.ludwigandreas.odatafilter.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This module contributes into {@code web-core}'s single RFC 9457 pipeline and ships no advice of its own.
 *
 * <p>Until 3.0.0 it shipped one, {@code ODataFilterExceptionHandler}, for services without web-core. That
 * advice answered in the exceptions' developer-facing English while every other error was localized, and
 * every consumer switched it off and re-handled the same seven exception types by hand. web-core is now a
 * required dependency, and this test is what keeps a fallback advice from coming back.
 */
class NoLocalAdviceTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ru.ludwigandreas.odatafilter");
    }

    @Test
    @DisplayName("this module declares no exception handler advice")
    void noAdvice() {
        noClasses()
                .should().beAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
                .orShould().beAnnotatedWith("org.springframework.web.bind.annotation.ControllerAdvice")
                .because("a second advice renders the same failure a second way; contribute an"
                        + " ExceptionProblemMapper to web-core's one ProblemDetail pipeline instead")
                .check(production);
    }

    @Test
    @DisplayName("nothing in this module declares an ExceptionHandler method")
    void noExceptionHandlerMethods() {
        noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.web.bind.annotation.ExceptionHandler")
                .because("the pipeline maps exceptions, and a handler here would bypass it")
                .check(production);
    }
}
