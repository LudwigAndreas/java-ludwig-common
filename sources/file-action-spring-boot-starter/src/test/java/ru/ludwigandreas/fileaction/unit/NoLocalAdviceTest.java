package ru.ludwigandreas.fileaction.unit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.fileaction.exception.SubmissionNotFoundException;
import ru.ludwigandreas.fileaction.exception.SubmissionStateException;
import ru.ludwigandreas.fileaction.exception.UnknownActionException;
import ru.ludwigandreas.webcore.problem.LocalizedException;

/**
 * This module contributes into {@code web-core}'s single RFC 9457 pipeline and ships no advice of its own.
 *
 * <p>A second {@code @RestControllerAdvice} is how a platform comes to render the same failure two ways: one
 * module's 400 carries {@code type}, {@code title} and {@code detail}, another's carries {@code error} and
 * {@code message}, and a client has to handle both. The platform convention is that a starter contributes
 * exception mappers or extends {@code LocalizedException}, and the pipeline does the rendering - which is why
 * every exception below is a {@code LocalizedException} and there is no advice here at all.
 */
class NoLocalAdviceTest {

    private static final String ROOT = "ru.ludwigandreas.fileaction";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("this module declares no exception handler advice")
    void noAdvice() {
        noClasses()
                .should().beAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
                .orShould().beAnnotatedWith("org.springframework.web.bind.annotation.ControllerAdvice")
                .because("a second advice renders the same failure a second way; contribute into web-core's"
                        + " one ProblemDetail pipeline instead")
                .check(production);
    }

    @Test
    @DisplayName("nothing in this module declares an ExceptionHandler method")
    void noExceptionHandlerMethods() {
        noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.web.bind.annotation.ExceptionHandler")
                .because("the same reason: the pipeline maps exceptions, and a handler here would bypass it")
                .check(production);
    }

    @Test
    @DisplayName("every caller-facing failure is a LocalizedException, which is what the pipeline renders")
    void callerFacingFailuresAreLocalized() {
        List<Class<?>> callerFacing = List.of(FileRejectedException.class,
                SubmissionNotFoundException.class, SubmissionStateException.class,
                UnknownActionException.class);

        assertThat(callerFacing).allSatisfy(type ->
                assertThat(LocalizedException.class.isAssignableFrom(type))
                        .as("%s", type.getSimpleName())
                        .isTrue());
    }
}
