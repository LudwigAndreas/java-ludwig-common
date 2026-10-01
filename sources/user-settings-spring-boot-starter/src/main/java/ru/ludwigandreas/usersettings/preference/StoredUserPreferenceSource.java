package ru.ludwigandreas.usersettings.preference;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.ResolvedValue;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.wellknown.WellKnownSettings;
import ru.ludwigandreas.webcore.preference.UserPreferenceSource;

/**
 * The caller's stored locale and timezone, as the highest-priority preference source.
 *
 * <p>The adapter between this module and {@code web-core}'s caller-preference contract, and the only
 * class here that names a {@code webcore.preference} type. The dependency direction is the one that
 * already exists: {@code user-settings} depends on {@code web-core}, the SPI is declared there and
 * implemented here, and that is what keeps {@code web-core} free of the persistence dependency it
 * must not have.
 *
 * <h2>Only an actually stored value counts - the detail everything else rests on</h2>
 *
 * <p>{@link SettingsLookup#getAll} always answers for a declared definition. A user who has never
 * opened a settings screen resolves {@code user.timezone} from {@link SettingLayer#DEFAULT} with the
 * definition's own {@code UTC}, and {@code user.locale} with {@code en}. A source that answered from
 * that would be answering "the definition's default" while claiming to be "the user's choice" - and
 * because this source is first in the chain, it would make every caller in the platform UTC and
 * English, ignore every {@code Accept-Language} header ever sent, and leave the two sources below it
 * permanently unreachable.
 *
 * <p>So the test is {@code layer.isMoreSpecificThan(SettingLayer.PLATFORM)}: {@code USER},
 * {@code ROLE} and {@code TENANT} are answers, {@code PLATFORM} and {@code DEFAULT} are abstentions.
 * {@code PLATFORM} abstains for the reason one step up: it is supplied by deployment configuration,
 * and {@code web-core}'s own {@code ConfiguredPreferenceSource} already is the deployment's
 * answer - sitting <em>below</em> the request headers, which is where a deployment-wide default
 * belongs. A {@code PLATFORM} locale answered from here would outrank every caller's
 * {@code Accept-Language}, which is exactly the half-translated response the {@code i18n-bundles}
 * capability forbids.
 *
 * <h2>Two reads, not one</h2>
 *
 * <p>The SPI is per dimension, so a resolution asks this source twice and it calls
 * {@code getAll} twice. That is deliberate rather than overlooked: {@code DefaultSettingsLookup}
 * holds a {@code LudwigCache<SettingsSubject, ResolvedSettings>}, so the second call is a map read,
 * and the per-dimension SPI is what makes "stored zone, header locale" expressible at all. Caching a
 * per-resolution result here instead would be a second cache primitive, which
 * {@code RuleGroup.CACHING} fails the build over, for a saving of one hit on an already-warm cache.
 *
 * <h2>A service that never declared the definitions abstains explicitly</h2>
 *
 * <p>{@code WellKnownSettings} registers nothing automatically, and that promise is load-bearing:
 * {@code getAll} returns exactly the settings a service declares, so a service with four settings of
 * its own does not suddenly resolve nine it has no screen for. This source therefore asks the
 * {@link SettingDefinitionRegistry} whether {@code user.locale} and {@code user.timezone} are
 * declared at all, per dimension, and abstains for the ones that are not.
 *
 * <p>Asking the registry rather than catching {@code UnknownSettingException} is the difference
 * between a decision and an accident: the registry answer is also what {@link #sourceName()}
 * reports, so the startup line names which dimensions this source can actually answer. That line is
 * the only signal distinguishing a service that wanted stored preferences and forgot the
 * {@code SettingDefinitionSource} bean from one that never wanted them - a difference that is an
 * absent bean, and so one no build-time check can see.
 *
 * <h2>A failure abstains</h2>
 *
 * <p>A settings read that throws - a replica mid-migration, a database blip, a caller whose tenant
 * cannot be resolved - abstains and logs. The direction of the failure is the argument, and it is
 * the same one {@code UserSettingsPreferenceSource} makes for recipient preferences: abstaining
 * renders a date in the wrong zone and is recovered by the next read, while propagating turns a
 * formatting concern into a 500 on an endpoint that had nothing to do with settings.
 */
@Slf4j
public class StoredUserPreferenceSource implements UserPreferenceSource {

    private final SettingsLookup settings;

    private final SettingDefinitionRegistry registry;

    /**
     * @param settings the module's read side; already cache-backed, which is why this source does
     *                 not cache
     * @param registry asked whether the well-known definitions are declared at all
     */
    public StoredUserPreferenceSource(SettingsLookup settings, SettingDefinitionRegistry registry) {
        if (settings == null || registry == null) {
            throw new IllegalArgumentException(
                    "The stored preference source needs a settings lookup and a definition registry");
        }
        this.settings = settings;
        this.registry = registry;
    }

    @Override
    public Optional<Locale> locale() {
        return stored(WellKnownSettings.LOCALE);
    }

    @Override
    public Optional<ZoneId> zone() {
        return stored(WellKnownSettings.TIMEZONE);
    }

    @Override
    public int getOrder() {
        return STORED_ORDER;
    }

    @Override
    public String sourceName() {
        return "StoredUserPreferenceSource" + declaredDimensions();
    }

    /**
     * Which dimensions this source can answer, for the startup log.
     *
     * @return the declared well-known keys, or a statement that neither is declared
     */
    public String declaredDimensions() {
        boolean locale = isDeclared(WellKnownSettings.LOCALE);
        boolean zone = isDeclared(WellKnownSettings.TIMEZONE);
        if (!locale && !zone) {
            return "(inactive: neither user.locale nor user.timezone is a declared setting)";
        }
        if (locale && zone) {
            return "(user.locale, user.timezone)";
        }
        return locale ? "(user.locale; user.timezone is not declared)"
                : "(user.timezone; user.locale is not declared)";
    }

    private <T> Optional<T> stored(SettingDefinition<T> definition) {
        if (!isDeclared(definition)) {
            return Optional.empty();
        }
        return SecurityPrincipals.current()
                .map(principal -> PrincipalRef.user(principal.subject()))
                .flatMap(ref -> chosen(ref, definition));
    }

    private boolean isDeclared(SettingDefinition<?> definition) {
        return registry.find(definition.getKey()).isPresent();
    }

    private <T> Optional<T> chosen(PrincipalRef ref, SettingDefinition<T> definition) {
        return read(ref, resolved -> resolved.resolved(definition))
                .filter(value -> value.layer().isMoreSpecificThan(SettingLayer.PLATFORM))
                .map(ResolvedValue::value);
    }

    private <R> Optional<R> read(PrincipalRef ref, Function<ResolvedSettings, R> extract) {
        try {
            return Optional.ofNullable(extract.apply(settings.getAll(ref)));
        } catch (RuntimeException e) {
            // Debug rather than warn, and this is a considered choice: on a path that runs for every
            // request, a tenant that cannot be resolved for an anonymous-but-authenticated caller is
            // an ordinary occurrence, and at warn level it would be the loudest line in the log while
            // the request it belongs to succeeded.
            log.debug("Could not read stored preferences for {}; falling through to the next source",
                    ref.subject(), e);
            return Optional.empty();
        }
    }
}
