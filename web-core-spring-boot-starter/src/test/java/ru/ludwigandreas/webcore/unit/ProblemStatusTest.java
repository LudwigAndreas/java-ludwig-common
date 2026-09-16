package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The enum is the service layer's only vocabulary for outcomes, so its codes have to be real HTTP
 * statuses and every constant has to have text to render with.
 */
class ProblemStatusTest {

    @ParameterizedTest
    @EnumSource(ProblemStatus.class)
    @DisplayName("every outcome maps to a real HTTP status")
    void everyOutcomeIsAKnownHttpStatus(ProblemStatus status) {
        assertThat(HttpStatus.resolve(status.code())).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(ProblemStatus.class)
    @DisplayName("every outcome has a generic code, and that code is in the shipped bundle")
    void everyOutcomeHasShippedText(ProblemStatus status) {
        // Guards the one gap this design can still have: a new enum constant whose generic code
        // nobody wrote a message for would render the key itself as the detail.
        String code = ProblemCodes.forStatus(status);

        assertThat(code).startsWith(ProblemCodes.PREFIX);
        assertThat(ru.ludwigandreas.webcore.problem.ProblemMessages
                        .ofBundles("i18n/ludwig-web-messages")
                        .resolve(code, null, java.util.Locale.ENGLISH))
                .as("no English message for %s", code)
                .isPresent();
        assertThat(ru.ludwigandreas.webcore.problem.ProblemMessages
                        .ofBundles("i18n/ludwig-web-messages")
                        .resolve(code + ".title", null, java.util.Locale.forLanguageTag("ru")))
                .as("no Russian title for %s", code)
                .isPresent();
    }

    @Test
    @DisplayName("ofCode round-trips a known status and degrades sensibly for an unknown one")
    void ofCodeRoundTripsAndDegrades() {
        assertThat(ProblemStatus.ofCode(409)).isEqualTo(ProblemStatus.CONFLICT);
        assertThat(ProblemStatus.ofCode(418)).isEqualTo(ProblemStatus.INVALID);
        assertThat(ProblemStatus.ofCode(507)).isEqualTo(ProblemStatus.INTERNAL);
        assertThat(ProblemStatus.ofCode(200)).isEqualTo(ProblemStatus.INTERNAL);
    }

    @Test
    @DisplayName("isServerError separates our faults from refused requests")
    void separatesServerErrors() {
        assertThat(ProblemStatus.NOT_FOUND.isServerError()).isFalse();
        assertThat(ProblemStatus.TOO_MANY_REQUESTS.isServerError()).isFalse();
        assertThat(ProblemStatus.INTERNAL.isServerError()).isTrue();
        assertThat(ProblemStatus.GATEWAY_TIMEOUT.isServerError()).isTrue();
    }

    @Test
    @DisplayName("the generic code is derived from the constant's name")
    void derivesCodeFromName() {
        assertThat(ProblemCodes.forStatus(ProblemStatus.UNSUPPORTED_MEDIA_TYPE))
                .isEqualTo("ludwig.web.error.unsupported-media-type");
    }
}
