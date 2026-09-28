package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;
import ru.ludwigandreas.webcore.problem.ProblemMessages;

/**
 * The resolution chain is the contract the whole "modules contribute bundles" design rests on, so
 * each link is pinned separately: the application always wins, the modules are consulted in their
 * declared order, and a key nobody defines falls through to the caller's default rather than to the
 * server's locale or to the key itself.
 */
class ProblemMessagesTest {

    private static final String MODULE_BUNDLE = "i18n/test-module-messages";
    private static final String OTHER_MODULE_BUNDLE = "i18n/test-other-module-messages";

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("the application's bundle overrides a module's wording for the same key")
    void applicationOverridesModule() {
        StaticMessageSource application = new StaticMessageSource();
        application.addMessage("test.module.greeting", Locale.ENGLISH, "application wording");
        ProblemMessages messages =
                new ProblemMessages(application, List.of(ProblemMessageBundle.of(MODULE_BUNDLE)));

        assertThat(messages.get("test.module.greeting", null, "fallback", Locale.ENGLISH))
                .isEqualTo("application wording");
    }

    @Test
    @DisplayName("a key the application does not define falls through to the module's bundle")
    void fallsThroughToModuleBundle() {
        ProblemMessages messages =
                new ProblemMessages(new StaticMessageSource(), List.of(ProblemMessageBundle.of(MODULE_BUNDLE)));

        assertThat(messages.get("test.module.greeting", null, "fallback", Locale.ENGLISH))
                .isEqualTo("module wording");
    }

    @Test
    @DisplayName("works with no application MessageSource at all")
    void resolvesWithoutApplicationMessageSource() {
        ProblemMessages messages = ProblemMessages.ofBundles(MODULE_BUNDLE);

        assertThat(messages.get("test.module.greeting", null, "fallback", Locale.ENGLISH))
                .isEqualTo("module wording");
    }

    @Test
    @DisplayName("contributed bundles are consulted in their declared order, not discovery order")
    void bundleOrderDecidesPrecedence() {
        // Declared in the order that would give the wrong answer if `order` were ignored.
        List<ProblemMessageBundle> bundles = List.of(
                ProblemMessageBundle.of(MODULE_BUNDLE, 20),
                ProblemMessageBundle.of(OTHER_MODULE_BUNDLE, 10));
        ProblemMessages messages = new ProblemMessages(null, bundles);

        assertThat(messages.basenames()).containsExactly(OTHER_MODULE_BUNDLE, MODULE_BUNDLE);
        assertThat(messages.get("test.shared.key", null, "fallback", Locale.ENGLISH))
                .isEqualTo("other module wins");
    }

    @Test
    @DisplayName("message arguments are formatted")
    void formatsArguments() {
        ProblemMessages messages = ProblemMessages.ofBundles(MODULE_BUNDLE);

        assertThat(messages.get("test.module.with-args", new Object[] {"42"}, "fallback", Locale.ENGLISH))
                .isEqualTo("the answer is 42");
    }

    @Test
    @DisplayName("a locale with no bundle falls back to the default one, not to the server's locale")
    void unknownLocaleFallsBackToDefaultBundle() {
        ProblemMessages messages = ProblemMessages.ofBundles(MODULE_BUNDLE);

        assertThat(messages.get("test.module.greeting", null, "fallback", Locale.FRENCH))
                .isEqualTo("module wording");
        assertThat(messages.get("test.module.greeting", null, "fallback", Locale.forLanguageTag("ru")))
                .isEqualTo("модуль");
    }

    @Test
    @DisplayName("a key nothing defines yields the caller's default, never the key itself")
    void unknownKeyYieldsDefault() {
        ProblemMessages messages = ProblemMessages.ofBundles(MODULE_BUNDLE);

        assertThat(messages.get("test.module.absent", null, "fallback", Locale.ENGLISH))
                .isEqualTo("fallback");
        assertThat(messages.resolve("test.module.absent", null, Locale.ENGLISH)).isEmpty();
    }

    @Test
    @DisplayName("resolution without an explicit locale uses the current request's locale")
    void usesLocaleContextWhenNoLocaleGiven() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("ru"));
        ProblemMessages messages = ProblemMessages.ofBundles(MODULE_BUNDLE);

        assertThat(messages.get("test.module.greeting", null, "fallback")).isEqualTo("модуль");
    }

    @Test
    @DisplayName("a basename given with an extension or a locale suffix is rejected at construction")
    void rejectsMalformedBasename() {
        // Caught here rather than silently resolving nothing at the first error a service renders.
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> ProblemMessageBundle.of("i18n/messages.properties"));
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> ProblemMessageBundle.of("  "));
    }
}
