package ru.ludwigandreas.db.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.ludwigandreas.db.core.exception.DuplicateEntityException;
import ru.ludwigandreas.db.core.exception.EntityNotFoundException;
import ru.ludwigandreas.db.core.exception.IntegrityViolationException;
import ru.ludwigandreas.db.core.exception.UnsupportedIdTypeException;
import ru.ludwigandreas.webcore.problem.ProblemMessages;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * This module's contribution to the shared problem pipeline. The case that motivates it is
 * {@link EntityNotFoundException}: raised by {@code getByIdOrThrow}, and answered as a 500 on any
 * path where the service layer had not remembered to convert it first.
 */
class DbCoreProblemMapperTest {

    private static final String BUNDLE = "i18n/ludwig-db-messages";

    private final DbCoreProblemMapper mapper = new DbCoreProblemMapper();

    static Stream<Arguments> exceptions() {
        return Stream.of(
                Arguments.of(new EntityNotFoundException(Object.class, UUID.randomUUID()),
                        ProblemStatus.NOT_FOUND, "ludwig.db.error.entity-not-found"),
                Arguments.of(new DuplicateEntityException("sku already taken"),
                        ProblemStatus.CONFLICT, "ludwig.db.error.duplicate-entity"),
                Arguments.of(new IntegrityViolationException("fk violated"),
                        ProblemStatus.CONFLICT, "ludwig.db.error.integrity-violation"),
                Arguments.of(new UnsupportedIdTypeException(Object.class),
                        ProblemStatus.INTERNAL, "ludwig.db.error.unsupported-id-type"));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("exceptions")
    @DisplayName("each exception maps to its own status and code")
    void mapsEachException(RuntimeException exception, ProblemStatus status, String code) {
        assertThat(mapper.supports(exception)).isTrue();
        assertThat(mapper.map(exception).status()).isEqualTo(status);
        assertThat(mapper.map(exception).code()).isEqualTo(code);
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("exceptions")
    @DisplayName("every code it can produce has shipped text, title included, in both locales")
    void everyCodeHasShippedText(RuntimeException exception, ProblemStatus status, String code) {
        ProblemMessages messages = ProblemMessages.ofBundles(BUNDLE);

        for (Locale locale : new Locale[] {Locale.ENGLISH, Locale.forLanguageTag("ru")}) {
            assertThat(messages.resolve(code, null, locale)).as("detail %s/%s", code, locale).isPresent();
            assertThat(messages.resolve(code + ".title", null, locale))
                    .as("title %s/%s", code, locale)
                    .isPresent();
        }
    }

    @Test
    @DisplayName("a missing row is a 404, which is the whole reason this mapper exists")
    void missingRowIsNotAServerError() {
        assertThat(mapper.map(new EntityNotFoundException("no such row")).status().isServerError())
                .isFalse();
    }

    @Test
    @DisplayName("an id type this module cannot convert is a wiring bug, not a client error")
    void unsupportedIdTypeIsOurFault() {
        // The caller sent a path variable and has no way to send a different kind of one.
        assertThat(mapper.map(new UnsupportedIdTypeException(Object.class)).status().isServerError())
                .isTrue();
    }

    @Test
    @DisplayName("it claims nothing it does not own")
    void ignoresForeignExceptions() {
        assertThat(mapper.supports(new IllegalStateException())).isFalse();
    }
}
