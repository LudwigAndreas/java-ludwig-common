package ru.ludwigandreas.odatafilter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.ludwigandreas.odatafilter.exception.FilterAccessDeniedException;
import ru.ludwigandreas.odatafilter.exception.FilterDepthExceededException;
import ru.ludwigandreas.odatafilter.exception.FilterSyntaxException;
import ru.ludwigandreas.odatafilter.exception.FilterValidationException;
import ru.ludwigandreas.odatafilter.exception.ODataFilterException;
import ru.ludwigandreas.odatafilter.exception.PageSizeExceededException;
import ru.ludwigandreas.odatafilter.exception.UnfilterableFieldException;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemMessages;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The module's contribution to the shared problem pipeline: what each exception means, and that the
 * text for it actually ships. The second half is the one worth automating - a mapper that produces a
 * code the bundle does not define still compiles, and only shows up as a response whose detail is a
 * raw message key.
 */
class ODataFilterProblemMapperTest {

    private static final String BUNDLE = "i18n/ludwig-odata-filter-messages";

    private final ODataFilterProblemMapper mapper = new ODataFilterProblemMapper();

    static Stream<Arguments> exceptions() {
        return Stream.of(
                Arguments.of(new FilterSyntaxException("at offset 12"),
                        ProblemStatus.INVALID, "ludwig.odata.error.syntax"),
                Arguments.of(new UnfilterableFieldException("description", "not filterable"),
                        ProblemStatus.INVALID, "ludwig.odata.error.unfilterable-field"),
                Arguments.of(new FilterAccessDeniedException("supplierCost"),
                        ProblemStatus.FORBIDDEN, "ludwig.odata.error.field-forbidden"),
                Arguments.of(new FilterDepthExceededException(9, 4),
                        ProblemStatus.INVALID, "ludwig.odata.error.depth-exceeded"),
                Arguments.of(new PageSizeExceededException(500, 100),
                        ProblemStatus.INVALID, "ludwig.odata.error.page-size-exceeded"),
                Arguments.of(new FilterValidationException("rejected by rule"),
                        ProblemStatus.INVALID, "ludwig.odata.error.validation"));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("exceptions")
    @DisplayName("each exception maps to its own status and code")
    void mapsEachException(ODataFilterException exception, ProblemStatus status, String code) {
        assertThat(mapper.supports(exception)).isTrue();

        ProblemDefinition definition = mapper.map(exception);

        assertThat(definition.status()).isEqualTo(status);
        assertThat(definition.code()).isEqualTo(code);
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("exceptions")
    @DisplayName("every code it can produce has shipped text, title included, in both locales")
    void everyCodeHasShippedText(ODataFilterException exception, ProblemStatus status, String code) {
        ProblemMessages messages = ProblemMessages.ofBundles(BUNDLE);

        for (Locale locale : new Locale[] {Locale.ENGLISH, Locale.forLanguageTag("ru")}) {
            assertThat(messages.resolve(code, mapper.map(exception).args(), locale))
                    .as("detail for %s in %s", code, locale)
                    .isPresent();
            assertThat(messages.resolve(code + ".title", null, locale))
                    .as("title for %s in %s", code, locale)
                    .isPresent();
        }
    }

    @Test
    @DisplayName("the rejected property is published as a member, not only inside the sentence")
    void publishesThePropertyPath() {
        // A client cannot parse a translated sentence to find out which field it has to fix.
        assertThat(mapper.map(new UnfilterableFieldException("category/code", "unknown")).properties())
                .containsEntry("property", "category/code");
        assertThat(mapper.map(new FilterAccessDeniedException("supplierCost")).properties())
                .containsEntry("property", "supplierCost");
    }

    @Test
    @DisplayName("limits are published alongside the message that quotes them")
    void publishesLimits() {
        assertThat(mapper.map(new FilterDepthExceededException(9, 4)).properties())
                .containsEntry("maxDepth", 4)
                .containsEntry("actualDepth", 9);
        assertThat(mapper.map(new PageSizeExceededException(500, 100)).properties())
                .containsEntry("maxPageSize", 100)
                .containsEntry("requestedPageSize", 500);
    }

    @Test
    @DisplayName("a subtype added later still gets a localized 400 rather than a 500")
    void mapsUnknownSubtypesToTheBaseCode() {
        ODataFilterException future = new ODataFilterException("added in a later release") {
        };

        assertThat(mapper.supports(future)).isTrue();
        assertThat(mapper.map(future).code()).isEqualTo("ludwig.odata.error.invalid-query");
        assertThat(mapper.map(future).status()).isEqualTo(ProblemStatus.INVALID);
    }

    @Test
    @DisplayName("it claims nothing it does not own")
    void ignoresForeignExceptions() {
        assertThat(mapper.supports(new IllegalStateException())).isFalse();
    }

    @Test
    @DisplayName("it registers late, so an application's own mapping wins")
    void registersAtModuleOrder() {
        assertThat(mapper.getOrder()).isEqualTo(ODataFilterProblemMapper.DEFAULT_MODULE_ORDER);
    }
}
