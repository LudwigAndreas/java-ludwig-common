package ru.ludwigandreas.usersettings.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.ResolvedValue;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.convert.SettingValueConverterRegistry;
import ru.ludwigandreas.usersettings.preference.StoredUserPreferenceSource;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.wellknown.WellKnownSettings;
import ru.ludwigandreas.webcore.preference.ConfiguredPreferenceSource;
import ru.ludwigandreas.webcore.preference.RequestHeaderPreferenceSource;
import ru.ludwigandreas.webcore.preference.UserPreferenceResolver;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * The precedence decision, across the three real classes that implement it.
 *
 * <p>Named {@code ...IntegrationTest} so failsafe runs it - surefire excludes the suffix - and it is
 * an integration of the <em>chain</em> rather than of the database. The two sources, the resolver and
 * the layer test are the real implementations; only {@link SettingsLookup} is a stub, and
 * deliberately so: what is under test is which source wins a dimension, and standing up Postgres to
 * answer a question about ordering would make the test slower, require Docker, and test nothing more.
 * {@link OwnerModeIntegrationTest} covers the read path against a real database.
 *
 * <p>The reason this exists as its own test rather than as another unit test is that the precedence
 * is a property of the composition. Each class in isolation looks correct under either ordering; only
 * the three together say whether a stored choice beats a browser header, and that is the decision a
 * user notices.
 */
class StoredPreferencePrecedenceIntegrationTest {

    private static final ZoneId YEKATERINBURG = ZoneId.of("Asia/Yekaterinburg");

    private static final UserPreferences CONFIGURED =
            new UserPreferences(Locale.ENGLISH, ZoneId.of("UTC"));

    private final SettingsLookup lookup = mock(SettingsLookup.class);

    @AfterEach
    void clearHolders() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("a USER-layer stored locale beats the browser's Accept-Language")
    void aStoredChoiceBeatsTheHeader() {
        authenticate();
        request("en-GB", "Europe/London");
        stored(Map.of(
                WellKnownSettings.LOCALE, value(Locale.forLanguageTag("ru-RU"), SettingLayer.USER),
                WellKnownSettings.TIMEZONE, value(YEKATERINBURG, SettingLayer.USER)));

        assertThat(resolver().resolve())
                .isEqualTo(new UserPreferences(Locale.forLanguageTag("ru-RU"), YEKATERINBURG));
    }

    @Test
    @DisplayName("a DEFAULT-layer stored value does not, so the header still decides")
    void anUnsetSettingLetsTheHeaderThrough() {
        authenticate();
        request("ru-RU", "Asia/Yekaterinburg");
        stored(Map.of(
                WellKnownSettings.LOCALE, value(Locale.ENGLISH, SettingLayer.DEFAULT),
                WellKnownSettings.TIMEZONE, value(ZoneId.of("UTC"), SettingLayer.DEFAULT)));

        // This is the case that would have been broken by a stored source that answered from the
        // definition's default: every caller UTC and English, with the header unreachable.
        assertThat(resolver().resolve())
                .isEqualTo(new UserPreferences(Locale.forLanguageTag("ru-RU"), YEKATERINBURG));
    }

    @Test
    @DisplayName("the two halves resolve independently: a stored zone with a header locale")
    void dimensionsMixAcrossSources() {
        authenticate();
        request("ru-RU", null);
        stored(Map.of(
                WellKnownSettings.LOCALE, value(Locale.ENGLISH, SettingLayer.DEFAULT),
                WellKnownSettings.TIMEZONE, value(YEKATERINBURG, SettingLayer.USER)));

        assertThat(resolver().resolve())
                .isEqualTo(new UserPreferences(Locale.forLanguageTag("ru-RU"), YEKATERINBURG));
    }

    @Test
    @DisplayName("with no stored choice and no headers, the deployment's configuration answers")
    void configurationIsTheFloor() {
        authenticate();
        request(null, null);
        stored(Map.of(
                WellKnownSettings.LOCALE, value(Locale.forLanguageTag("ru"), SettingLayer.PLATFORM),
                WellKnownSettings.TIMEZONE, value(YEKATERINBURG, SettingLayer.PLATFORM)));

        // PLATFORM is deployment configuration, and the configured source IS the deployment's answer -
        // sitting below the headers, which is where a deployment-wide default belongs.
        assertThat(resolver().resolve()).isEqualTo(CONFIGURED);
    }

    private UserPreferenceResolver resolver() {
        SettingDefinitionRegistry registry = new SettingDefinitionRegistry(
                List.of(WellKnownSettings.source()),
                new SettingValueConverterRegistry(List.of(), new ObjectMapper().findAndRegisterModules()));
        return new UserPreferenceResolver(
                List.of(new ConfiguredPreferenceSource(CONFIGURED),
                        new RequestHeaderPreferenceSource("X-Time-Zone", true),
                        new StoredUserPreferenceSource(lookup, registry)),
                List.of(Locale.ENGLISH, Locale.forLanguageTag("ru")));
    }

    private void stored(Map<SettingDefinition<?>, ResolvedValue<?>> values) {
        Map<String, ResolvedValue<?>> byKey = new LinkedHashMap<>();
        values.forEach((definition, value) -> byKey.put(definition.getKey(), value));
        when(lookup.getAll(any(PrincipalRef.class))).thenReturn(new ResolvedSettings(
                new SettingsSubject(PrincipalRef.user("u-1"), "t-1"), byKey));
    }

    private static <T> ResolvedValue<T> value(T value, SettingLayer layer) {
        return new ResolvedValue<>(value, layer, "u-1");
    }

    private static void request(String acceptLanguage, String zone) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (acceptLanguage != null) {
            request.addHeader("Accept-Language", acceptLanguage);
        }
        if (zone != null) {
            request.addHeader("X-Time-Zone", zone);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static void authenticate() {
        LudwigPrincipal principal = LudwigPrincipal.builder()
                .subject("u-1")
                .type(PrincipalType.USER)
                .tenantId("t-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
    }
}
