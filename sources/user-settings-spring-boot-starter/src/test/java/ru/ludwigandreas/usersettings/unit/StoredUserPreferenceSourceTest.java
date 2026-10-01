package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.ResolvedValue;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingDefinitionSource;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.convert.SettingValueConverterRegistry;
import ru.ludwigandreas.usersettings.preference.StoredUserPreferenceSource;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.wellknown.WellKnownSettings;
import ru.ludwigandreas.webcore.preference.UserPreferenceSource;

/**
 * The stored source, and the one decision everything else in the chain depends on: that a value
 * resolved from the {@code DEFAULT} or {@code PLATFORM} layer is an abstention rather than an answer.
 */
class StoredUserPreferenceSourceTest {

    private static final ZoneId YEKATERINBURG = ZoneId.of("Asia/Yekaterinburg");

    private final SettingsLookup lookup = mock(SettingsLookup.class);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a USER-layer value is the caller's own choice, and is answered")
    void aUserLayerValueIsAnswered() {
        authenticate();
        resolve(Map.of(
                WellKnownSettings.LOCALE, value(Locale.forLanguageTag("ru-RU"), SettingLayer.USER),
                WellKnownSettings.TIMEZONE, value(YEKATERINBURG, SettingLayer.USER)));

        StoredUserPreferenceSource source = source(WellKnownSettings.source());

        assertThat(source.locale()).contains(Locale.forLanguageTag("ru-RU"));
        assertThat(source.zone()).contains(YEKATERINBURG);
    }

    @Test
    @DisplayName("a ROLE-layer and a TENANT-layer value are answered too")
    void inheritedChoicesAreAnswered() {
        authenticate();
        resolve(Map.of(
                WellKnownSettings.LOCALE, value(Locale.forLanguageTag("ru"), SettingLayer.ROLE),
                WellKnownSettings.TIMEZONE, value(YEKATERINBURG, SettingLayer.TENANT)));

        StoredUserPreferenceSource source = source(WellKnownSettings.source());

        assertThat(source.locale()).contains(Locale.forLanguageTag("ru"));
        assertThat(source.zone()).contains(YEKATERINBURG);
    }

    @Test
    @DisplayName("a DEFAULT-layer value is an abstention, or every caller in the platform becomes UTC")
    void aDefaultLayerValueAbstains() {
        authenticate();
        resolve(Map.of(
                WellKnownSettings.LOCALE, value(Locale.ENGLISH, SettingLayer.DEFAULT),
                WellKnownSettings.TIMEZONE, value(ZoneId.of("UTC"), SettingLayer.DEFAULT)));

        StoredUserPreferenceSource source = source(WellKnownSettings.source());

        // The single most load-bearing assertion in this change. getAll always answers for a declared
        // definition, so a user who has never opened a settings screen resolves UTC and English from
        // the DEFAULT layer. Answering from that - while sitting first in the chain - would ignore
        // every Accept-Language header ever sent and leave the two sources below permanently unreachable.
        assertThat(source.locale()).isEmpty();
        assertThat(source.zone()).isEmpty();
    }

    @Test
    @DisplayName("a PLATFORM-layer value abstains, because configuration belongs below the headers")
    void aPlatformLayerValueAbstains() {
        authenticate();
        resolve(Map.of(
                WellKnownSettings.LOCALE, value(Locale.forLanguageTag("ru"), SettingLayer.PLATFORM),
                WellKnownSettings.TIMEZONE, value(YEKATERINBURG, SettingLayer.PLATFORM)));

        StoredUserPreferenceSource source = source(WellKnownSettings.source());

        assertThat(source.locale()).isEmpty();
        assertThat(source.zone()).isEmpty();
    }

    @Test
    @DisplayName("a dimension the service never declared abstains, and the lookup is not even called")
    void anUndeclaredDimensionAbstains() {
        authenticate();
        StoredUserPreferenceSource source = source(SettingDefinitionSource.of(WellKnownSettings.TIMEZONE));

        assertThat(source.locale()).isEmpty();
        assertThat(source.declaredDimensions()).contains("user.locale is not declared");
        verify(lookup, never()).getAll(any(PrincipalRef.class));
    }

    @Test
    @DisplayName("a service that declared neither says so in the startup line")
    void neitherDeclaredIsReported() {
        StoredUserPreferenceSource source = source(SettingDefinitionSource.of(TestSettings.PAGE_SIZE));

        assertThat(source.sourceName()).contains("inactive");
        assertThat(source.locale()).isEmpty();
        assertThat(source.zone()).isEmpty();
    }

    @Test
    @DisplayName("with no authenticated caller there is nobody to read, so it abstains")
    void noCallerAbstains() {
        resolve(Map.of(WellKnownSettings.LOCALE, value(Locale.forLanguageTag("ru"), SettingLayer.USER)));

        assertThat(source(WellKnownSettings.source()).locale()).isEmpty();
    }

    @Test
    @DisplayName("a failing read abstains rather than turning a date into a 500")
    void aFailingReadAbstains() {
        authenticate();
        when(lookup.getAll(any(PrincipalRef.class)))
                .thenThrow(new IllegalStateException("replica mid-migration"));

        StoredUserPreferenceSource source = source(WellKnownSettings.source());

        assertThat(source.locale()).isEmpty();
        assertThat(source.zone()).isEmpty();
    }

    @Test
    @DisplayName("it sits first in the chain")
    void orderIsTheStoredPosition() {
        assertThat(source(WellKnownSettings.source()).getOrder())
                .isEqualTo(UserPreferenceSource.STORED_ORDER);
    }

    private StoredUserPreferenceSource source(SettingDefinitionSource definitions) {
        // findAndRegisterModules(), not a bare ObjectMapper: the registry round-trips every declared
        // default through its converter at construction, and WellKnownSettings.QUIET_HOURS holds
        // LocalTime, which Jackson refuses without jackson-datatype-jsr310. A bare mapper here fails
        // in the registry rather than in anything this test is about - and a real application gets
        // the modules from Spring Boot.
        SettingDefinitionRegistry registry = new SettingDefinitionRegistry(List.of(definitions),
                new SettingValueConverterRegistry(List.of(), new ObjectMapper().findAndRegisterModules()));
        return new StoredUserPreferenceSource(lookup, registry);
    }

    private void resolve(Map<SettingDefinition<?>, ResolvedValue<?>> values) {
        Map<String, ResolvedValue<?>> byKey = new LinkedHashMap<>();
        values.forEach((definition, value) -> byKey.put(definition.getKey(), value));
        ResolvedSettings resolved = new ResolvedSettings(
                new SettingsSubject(PrincipalRef.user("u-1"), "t-1"), byKey);
        when(lookup.getAll(any(PrincipalRef.class))).thenReturn(resolved);
    }

    private static <T> ResolvedValue<T> value(T value, SettingLayer layer) {
        return new ResolvedValue<>(value, layer, "u-1");
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
