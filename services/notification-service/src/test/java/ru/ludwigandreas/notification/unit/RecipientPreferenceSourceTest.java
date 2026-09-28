package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.entity.UserStatus;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.RecipientKind;
import ru.ludwigandreas.notification.service.model.RecipientRef;
import ru.ludwigandreas.notification.service.preference.ConfiguredPreferenceSource;
import ru.ludwigandreas.notification.service.preference.DigestMode;
import ru.ludwigandreas.notification.service.preference.OptOutState;
import ru.ludwigandreas.notification.service.preference.QuietHoursWindow;
import ru.ludwigandreas.notification.service.preference.RecipientPreferenceSource;
import ru.ludwigandreas.notification.service.preference.RecipientPreferences;
import ru.ludwigandreas.notification.service.preference.StoredPreferences;
import ru.ludwigandreas.notification.service.preference.usersettings.NotificationSettings;
import ru.ludwigandreas.notification.service.preference.usersettings.UserSettingsPreferenceSource;
import ru.ludwigandreas.notification.service.recipient.DefaultRecipientResolver;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.ResolvedValue;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.wellknown.DigestPreference;
import ru.ludwigandreas.usersettings.wellknown.QuietHours;
import ru.ludwigandreas.usersettings.wellknown.WellKnownSettings;

/**
 * The seam that lets this service run with or without a preference store, tested from both sides.
 *
 * <p>The point of the design is that the dispatch path cannot tell which source answered, so the
 * assertions are mostly about the <em>absent</em> store behaving like a present one with nothing in
 * it: same types, same defaults, no exception, no silence. A test that only exercised the wired case
 * would leave the shape this service is actually deployed in untested.
 *
 * <p>{@link UserSettingsPreferenceSource} is exercised here although the module it adapts is a
 * {@code provided} dependency - that is exactly what {@code provided} buys: the types are on the
 * test classpath and out of the packaged application, so the adapter stays covered while remaining
 * absent at runtime.
 */
class RecipientPreferenceSourceTest {

    private static final String USER = "user-1";
    private static final String TENANT = "acme";
    private static final String CATEGORY = "marketing";

    private final SecurityUserRepository identity = mock(SecurityUserRepository.class);

    // ---------------------------------------------------------------------------------------------
    // Without a store
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("with no store, every recipient resolves to configured defaults and no opt-out")
    void configuredSourceKnowsNobody() {
        StoredPreferences stored = new ConfiguredPreferenceSource().lookup(USER, TENANT);

        assertThat(stored.locale()).isNull();
        assertThat(stored.zone()).isNull();
        assertThat(stored.digest()).isNull();
        assertThat(stored.quietHours().isConfigured()).isFalse();
        assertThat(stored.optOuts().stateOf(CATEGORY, ChannelType.EMAIL)).isEqualTo(OptOutState.UNSET);
    }

    @Test
    @DisplayName("with no store, a recipient falls back to configuration rather than being silenced")
    void resolverFallsBackToConfiguration() {
        givenIdentity();
        RecipientPreferences preferences = resolver(new ConfiguredPreferenceSource())
                .preferences(userRef(null, null));

        assertThat(preferences.locale()).isEqualTo(Locale.forLanguageTag("en"));
        assertThat(preferences.zone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(preferences.digest()).isEqualTo(DigestMode.IMMEDIATE);
        assertThat(preferences.quietHours().isConfigured()).isFalse();
        assertThat(preferences.optedOut(CATEGORY, ChannelType.EMAIL)).isFalse();
    }

    /**
     * The request's hints are the only per-recipient information a store-less deployment has, so a
     * caller that knows the recipient's language must be able to say so.
     */
    @Test
    @DisplayName("the request's locale and timezone are honoured with no store at all")
    void requestHintsApplyWithoutAStore() {
        givenIdentity();
        RecipientPreferences preferences = resolver(new ConfiguredPreferenceSource())
                .preferences(userRef("ru-RU", "Europe/Moscow"));

        assertThat(preferences.locale()).isEqualTo(Locale.forLanguageTag("ru-RU"));
        assertThat(preferences.zone()).isEqualTo(ZoneId.of("Europe/Moscow"));
    }

    // ---------------------------------------------------------------------------------------------
    // With the user-settings module
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("stored preferences are translated into this service's own types")
    void userSettingsAreTranslated() {
        StoredPreferences stored = userSettingsSource(resolved(Map.of(
                WellKnownSettings.LOCALE.getKey(),
                        new ResolvedValue<>(Locale.forLanguageTag("ru-RU"), SettingLayer.USER, USER),
                WellKnownSettings.TIMEZONE.getKey(),
                        new ResolvedValue<>(ZoneId.of("Europe/Moscow"), SettingLayer.USER, USER),
                WellKnownSettings.DIGEST.getKey(),
                        new ResolvedValue<>(DigestPreference.DAILY, SettingLayer.USER, USER),
                WellKnownSettings.QUIET_HOURS.getKey(),
                        new ResolvedValue<>(new QuietHours(true, LocalTime.of(22, 0), LocalTime.of(7, 0)),
                                SettingLayer.USER, USER))))
                .lookup(USER, TENANT);

        assertThat(stored.locale()).isEqualTo(Locale.forLanguageTag("ru-RU"));
        assertThat(stored.zone()).isEqualTo(ZoneId.of("Europe/Moscow"));
        assertThat(stored.digest()).isEqualTo(DigestMode.DAILY);
        assertThat(stored.quietHours()).isEqualTo(new QuietHoursWindow(LocalTime.of(22, 0), LocalTime.of(7, 0)));
    }

    /**
     * A value nobody stored must stay distinguishable from one somebody did, or the module's own
     * default would outrank a locale the calling service was explicitly given.
     */
    @Test
    @DisplayName("a value that is only the module's default reads as unset")
    void moduleDefaultsAreNotChoices() {
        StoredPreferences stored = userSettingsSource(resolved(Map.of(
                WellKnownSettings.LOCALE.getKey(),
                        new ResolvedValue<>(Locale.ENGLISH, SettingLayer.DEFAULT, "global"))))
                .lookup(USER, TENANT);

        assertThat(stored.locale()).isNull();
    }

    @Test
    @DisplayName("the three opt-out states survive the translation")
    void optOutStatesSurvive() {
        StoredPreferences stored = userSettingsSource(resolved(Map.of(
                NotificationSettings.optOut(CATEGORY, ChannelType.EMAIL).getKey(),
                        new ResolvedValue<>(true, SettingLayer.USER, USER),
                NotificationSettings.optOut("security", ChannelType.EMAIL).getKey(),
                        new ResolvedValue<>(false, SettingLayer.USER, USER),
                NotificationSettings.blanketOptOut(ChannelType.EMAIL).getKey(),
                        new ResolvedValue<>(false, SettingLayer.DEFAULT, "global"))))
                .lookup(USER, TENANT);

        assertThat(stored.optOuts().stateOf(CATEGORY, ChannelType.EMAIL)).isEqualTo(OptOutState.OPTED_OUT);
        assertThat(stored.optOuts().stateOf("security", ChannelType.EMAIL)).isEqualTo(OptOutState.OPTED_IN);
        assertThat(stored.optOuts().stateOf(NotificationSettings.ALL_CATEGORIES, ChannelType.EMAIL))
                .isEqualTo(OptOutState.UNSET);
    }

    /**
     * A category the platform never declared has no definition, so the module throws rather than
     * answering. It must read as "has said nothing" - the documented behaviour is that such a
     * category can still be declined through the blanket opt-out - and must certainly not turn every
     * notification in that category into a failed delivery.
     */
    @Test
    @DisplayName("a category with no declared setting reads as unset rather than failing")
    void undeclaredCategoryIsUnset() {
        StoredPreferences stored = userSettingsSource(resolved(Map.of())).lookup(USER, TENANT);

        assertThat(stored.optOuts().stateOf("never-declared", ChannelType.EMAIL))
                .isEqualTo(OptOutState.UNSET);
        assertThat(stored.locale()).isNull();
        assertThat(stored.quietHours().isConfigured()).isFalse();
    }

    @Test
    @DisplayName("a recipient with no tenant resolves to defaults rather than another tenant's values")
    void noTenantMeansNoLookup() {
        SettingsLookup lookup = mock(SettingsLookup.class);

        assertThat(new UserSettingsPreferenceSource(lookup).lookup(USER, null))
                .isEqualTo(StoredPreferences.none());
    }

    /**
     * A queue worker's alternative to a default is a delivery that retries until it dead-letters, so
     * a settings store that is briefly unreadable must degrade rather than propagate.
     */
    @Test
    @DisplayName("a settings lookup that throws degrades to defaults instead of failing the delivery")
    void lookupFailureDegrades() {
        SettingsLookup lookup = mock(SettingsLookup.class);
        when(lookup.getAll(any(SettingsSubject.class)))
                .thenThrow(new IllegalStateException("replica is mid-migration"));

        assertThat(new UserSettingsPreferenceSource(lookup).lookup(USER, TENANT))
                .isEqualTo(StoredPreferences.none());
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private UserSettingsPreferenceSource userSettingsSource(ResolvedSettings settings) {
        SettingsLookup lookup = mock(SettingsLookup.class);
        when(lookup.getAll(any(SettingsSubject.class))).thenReturn(settings);
        return new UserSettingsPreferenceSource(lookup);
    }

    private ResolvedSettings resolved(Map<String, ResolvedValue<?>> values) {
        return new ResolvedSettings(new SettingsSubject(PrincipalRef.user(USER), TENANT), values);
    }

    private DefaultRecipientResolver resolver(RecipientPreferenceSource source) {
        return new DefaultRecipientResolver(identity, source, new NotificationProperties());
    }

    private void givenIdentity() {
        SecurityUserEntity user = new SecurityUserEntity();
        user.setId(USER);
        user.setTenantId(TENANT);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmail("ada@example.com");
        user.setEmailVerified(true);
        user.setSourceSystem("test");
        user.setSourceTimestamp(Instant.now());
        when(identity.findById(USER)).thenReturn(Optional.of(user));
    }

    private static RecipientRef userRef(String locale, String timezone) {
        return new RecipientRef(RecipientKind.USER, USER, null,
                locale == null ? null : Locale.forLanguageTag(locale), timezone, Map.of());
    }
}
