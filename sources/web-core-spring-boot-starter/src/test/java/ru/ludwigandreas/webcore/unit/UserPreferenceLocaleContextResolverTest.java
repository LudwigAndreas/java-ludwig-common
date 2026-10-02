package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.TimeZoneAwareLocaleContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import ru.ludwigandreas.webcore.preference.UserPreferenceLocaleContext;
import ru.ludwigandreas.webcore.preference.UserPreferenceLocaleContextResolver;
import ru.ludwigandreas.webcore.preference.UserPreferenceResolver;
import ru.ludwigandreas.webcore.preference.UserPreferenceSource;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * The {@code localeResolver} replacement: that it publishes a zone at all, that it resolves lazily,
 * and that it refuses to be written to.
 */
class UserPreferenceLocaleContextResolverTest {

    private static final ZoneId YEKATERINBURG = ZoneId.of("Asia/Yekaterinburg");

    @Test
    @DisplayName("the published context is timezone-aware, which AcceptHeaderLocaleResolver's never was")
    void publishesATimeZoneAwareContext() {
        LocaleContext context = resolverFor(new AtomicInteger())
                .resolveLocaleContext(new MockHttpServletRequest());

        assertThat(context).isInstanceOf(TimeZoneAwareLocaleContext.class);
        assertThat(context.getLocale()).isEqualTo(Locale.forLanguageTag("ru"));
        assertThat(((TimeZoneAwareLocaleContext) context).getTimeZone().toZoneId().getRules())
                .isEqualTo(YEKATERINBURG.getRules());
    }

    @Test
    @DisplayName("nothing is resolved until something asks, so a dispatch that formats nothing pays nothing")
    void resolutionIsLazy() {
        AtomicInteger resolutions = new AtomicInteger();

        LocaleContext context = resolverFor(resolutions)
                .resolveLocaleContext(new MockHttpServletRequest());

        assertThat(resolutions).hasValue(0);
        assertThat(((UserPreferenceLocaleContext) context).isResolved()).isFalse();

        context.getLocale();
        assertThat(resolutions).hasValue(1);

        // And memoised: twenty formatted fields are one settings read, not twenty.
        context.getLocale();
        ((TimeZoneAwareLocaleContext) context).getTimeZone();
        assertThat(resolutions).hasValue(1);
    }

    @Test
    @DisplayName("the exact ZoneId survives, rather than round-tripping through TimeZone")
    void theZoneIdSurvivesExactly() {
        LocaleContext context = resolverFor(new AtomicInteger())
                .resolveLocaleContext(new MockHttpServletRequest());

        assertThat(((UserPreferenceLocaleContext) context).preferences().zone()).isEqualTo(YEKATERINBURG);
    }

    @Test
    @DisplayName("writing a preference through the request context is refused, and the message says where to write it")
    void settingALocaleContextIsRefused() {
        assertThatThrownBy(() -> resolverFor(new AtomicInteger()).setLocaleContext(
                new MockHttpServletRequest(), new MockHttpServletResponse(), null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("user.timezone");
    }

    private static UserPreferenceLocaleContextResolver resolverFor(AtomicInteger resolutions) {
        UserPreferenceSource counting = new UserPreferenceSource() {
            @Override
            public Optional<Locale> locale() {
                resolutions.incrementAndGet();
                return Optional.of(Locale.forLanguageTag("ru"));
            }

            @Override
            public Optional<ZoneId> zone() {
                return Optional.of(YEKATERINBURG);
            }
        };
        return new UserPreferenceLocaleContextResolver(
                new UserPreferenceResolver(List.of(counting), List.of()));
    }

    @Test
    @DisplayName("a lazily resolved context is readable through UserPreferences.current()")
    void theContextIsReadableThroughTheAmbientAccessor() {
        UserPreferences expected = new UserPreferences(Locale.forLanguageTag("ru"), YEKATERINBURG);
        UserPreferenceLocaleContext context = (UserPreferenceLocaleContext) resolverFor(new AtomicInteger())
                .resolveLocaleContext(new MockHttpServletRequest());

        try (UserPreferences.Scope scope = context.preferences().bind()) {
            assertThat(UserPreferences.current()).isEqualTo(expected);
            assertThat(UserPreferences.currentIfResolved()).contains(expected);
        }
    }
}
