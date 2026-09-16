package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import ru.ludwigandreas.security.web.SecurityProblemMapper;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemMessages;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The mapper exists so that the same rejection carries the same code wherever it was raised, so the
 * codes it produces are pinned against the ones the filter-chain handlers write, and the wording is
 * checked to exist in both locales.
 */
class SecurityProblemMapperTest {

    private static final String BUNDLE = "i18n/ludwig-security-messages";

    private final SecurityProblemMapper mapper = new SecurityProblemMapper();

    @Test
    @DisplayName("an in-dispatch denial gets the same code the AccessDeniedHandler writes")
    void forbiddenMatchesTheFilterChainHandler() {
        // ProblemDetailAccessDeniedHandler uses this exact code for the same condition; a client
        // must not have to know which layer denied it.
        assertThat(mapper.map(new AccessDeniedException("no ROLE_EDITOR")).code())
                .isEqualTo("ludwig.security.error.forbidden");
        assertThat(mapper.map(new AccessDeniedException("no ROLE_EDITOR")).status())
                .isEqualTo(ProblemStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("an in-dispatch authentication failure gets the entry point's code")
    void unauthorizedMatchesTheEntryPoint() {
        assertThat(mapper.map(new BadCredentialsException("expired at 12:03")).code())
                .isEqualTo("ludwig.security.error.unauthorized");
        assertThat(mapper.map(new BadCredentialsException("expired")).status())
                .isEqualTo(ProblemStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("neither definition carries anything about why access was denied")
    void leaksNothingAboutTheDecision()  {
        assertThat(mapper.map(new AccessDeniedException("missing scope partner=42")).args()).isEmpty();
        assertThat(mapper.map(new AccessDeniedException("missing scope partner=42")).properties())
                .isEmpty();
    }

    @Test
    @DisplayName("both codes have shipped text, title included, in both locales")
    void bothCodesHaveShippedText() {
        ProblemMessages messages = ProblemMessages.ofBundles(BUNDLE);

        for (String code : new String[] {
                "ludwig.security.error.forbidden", "ludwig.security.error.unauthorized"}) {
            for (Locale locale : new Locale[] {Locale.ENGLISH, Locale.forLanguageTag("ru")}) {
                assertThat(messages.resolve(code, null, locale))
                        .as("detail %s/%s", code, locale)
                        .isPresent();
                assertThat(messages.resolve(code + ".title", null, locale))
                        .as("title %s/%s", code, locale)
                        .isPresent();
            }
        }
    }

    @Test
    @DisplayName("it outranks the web-core mapper for the same two exception types")
    void outranksTheGenericMapper() {
        assertThat(mapper.getOrder()).isLessThan(ExceptionProblemMapper.DEFAULT_MODULE_ORDER);
    }

    @Test
    @DisplayName("it claims nothing it does not own")
    void ignoresForeignExceptions() {
        assertThat(mapper.supports(new IllegalStateException())).isFalse();
    }
}
