package ru.ludwigandreas.notification.service.preference.usersettings;

import java.time.ZoneId;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.preference.DigestMode;
import ru.ludwigandreas.notification.service.preference.OptOutMatrix;
import ru.ludwigandreas.notification.service.preference.OptOutState;
import ru.ludwigandreas.notification.service.preference.QuietHoursWindow;
import ru.ludwigandreas.notification.service.preference.RecipientPreferenceSource;
import ru.ludwigandreas.notification.service.preference.StoredPreferences;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.ResolvedValue;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.exception.UnknownSettingException;
import ru.ludwigandreas.usersettings.wellknown.DigestPreference;
import ru.ludwigandreas.usersettings.wellknown.QuietHours;
import ru.ludwigandreas.usersettings.wellknown.WellKnownSettings;

/**
 * Reads a recipient's preferences out of {@code user-settings-spring-boot-starter}.
 *
 * <p>The only class in this service that imports that module's types on the dispatch path, and the
 * only one that has to be absent for the service to run without it. Everything downstream is built
 * from {@code service.preference}'s own types, so removing the dependency removes this adapter and
 * nothing else.
 *
 * <h2>The lookup is local</h2>
 *
 * <p>Whether the module runs in projection mode (a read-only replica fed by the owning service's
 * change stream) or owner mode (this service's own tables), {@link SettingsLookup} reads the
 * database in this process. No call leaves it, which is the property that matters: this runs on a
 * queue worker, and a worker must not acquire a hot-path dependency on another service's
 * availability.
 *
 * <h2>One lookup per recipient, not per setting</h2>
 *
 * <p>{@link SettingsLookup#getAll} returns every declared setting for the subject in one pass, and
 * the {@link ResolvedSettings} it returns is carried into the {@link OptOutMatrix} below. The
 * per-delivery opt-out questions are then map reads against an object that has already been
 * resolved, rather than a query each.
 *
 * <h2>Failures degrade rather than propagate</h2>
 *
 * <p>A settings read that throws - a replica mid-migration, a database blip - answers
 * {@link StoredPreferences#none()} and logs. The contract on {@link RecipientPreferenceSource} asks
 * for that, and the reason is the direction of the failure: defaults mean a notification goes out
 * with platform-wide quiet hours instead of the recipient's own, while a propagated exception means
 * a delivery that retries until it dead-letters. Neither is right, and the first one is recoverable
 * by the recipient's next read.
 */
@Slf4j
@RequiredArgsConstructor
public class UserSettingsPreferenceSource implements RecipientPreferenceSource {

    private final SettingsLookup settings;

    @Override
    public StoredPreferences lookup(String userId, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            // No tenant means no scoped lookup is possible, and an unscoped one is not on offer -
            // settings are stored per tenant and guessing one would read somebody else's. The
            // recipient still gets their notification, with configured defaults.
            log.debug("Recipient {} has no tenant; resolving preferences from configuration", userId);
            return StoredPreferences.none();
        }

        ResolvedSettings resolved;
        try {
            // The tenant is named explicitly rather than taken from a security context: this runs on
            // a queue worker, where there is no caller to take one from.
            resolved = settings.getAll(new SettingsSubject(PrincipalRef.user(userId), tenantId));
        } catch (RuntimeException e) {
            log.warn("Could not resolve settings for recipient {} in tenant {}; "
                    + "falling back to configured defaults", userId, tenantId, e);
            return StoredPreferences.none();
        }

        return new StoredPreferences(
                own(resolved, WellKnownSettings.LOCALE, Locale.class),
                own(resolved, WellKnownSettings.TIMEZONE, ZoneId.class),
                windowOf(resolved),
                digestOf(resolved),
                matrixOf(resolved));
    }

    @Override
    public String describe() {
        return "user-settings module (" + settings.getClass().getSimpleName() + ")";
    }

    /**
     * A value the recipient actually chose, or null when the answer is only the module's default.
     *
     * <p>The distinction is what lets a caller-supplied locale on the request lose to the
     * recipient's own choice and win over a module default. Collapsing them here would make the
     * module's default beat a hint the calling service was explicitly given.
     */
    private <T> T own(ResolvedSettings resolved, SettingDefinition<T> definition, Class<T> type) {
        ResolvedValue<T> value = valueOf(resolved, definition);
        return value == null || value.isDefault() ? null : type.cast(value.value());
    }

    /**
     * One resolved value, or null when this deployment has not declared that setting.
     *
     * <p>{@link ResolvedSettings#resolved} throws for a key the registry does not know, and there
     * are two ordinary ways to reach that here. A category the request names but
     * {@code ludwig.notification.preferences.declinable-categories} does not list has no per-category
     * opt-out definition - which is the documented behaviour, "it can only be declined through the
     * blanket opt-out", and must read as "has said nothing" rather than fail the delivery. And a
     * deployment running the module with its own definition source, without this service's, has no
     * well-known keys either.
     *
     * <p>Both are configuration states an operator can be in, so they degrade to the defaults
     * instead of turning every notification to that recipient into a dead letter.
     */
    private <T> ResolvedValue<T> valueOf(ResolvedSettings resolved, SettingDefinition<T> definition) {
        try {
            return resolved.resolved(definition);
        } catch (UnknownSettingException e) {
            log.debug("Setting {} is not declared in this deployment; treating it as unset",
                    definition.getKey());
            return null;
        }
    }

    private QuietHoursWindow windowOf(ResolvedSettings resolved) {
        ResolvedValue<QuietHours> value = valueOf(resolved, WellKnownSettings.QUIET_HOURS);
        QuietHours window = value == null ? null : value.value();
        if (window == null || !window.enabled()) {
            return QuietHoursWindow.none();
        }
        return new QuietHoursWindow(window.start(), window.end());
    }

    /**
     * Translated by name rather than by ordinal.
     *
     * <p>A constant this deployment's module knows and this service does not is logged and treated
     * as unset, which is the only safe reading: an unknown cadence must not be silently mapped onto
     * a neighbouring one, and {@code NEVER} sitting next to {@code WEEKLY} is exactly the pair an
     * ordinal mistake would confuse.
     */
    private DigestMode digestOf(ResolvedSettings resolved) {
        ResolvedValue<DigestPreference> value = valueOf(resolved, WellKnownSettings.DIGEST);
        DigestPreference preference = value == null ? null : value.value();
        if (preference == null) {
            return null;
        }
        try {
            return DigestMode.valueOf(preference.name());
        } catch (IllegalArgumentException e) {
            log.warn("Settings module reports digest preference '{}', which this service does not "
                    + "know; treating it as unset", preference);
            return null;
        }
    }

    /**
     * The opt-out answers, backed by the already-resolved snapshot.
     *
     * <p>{@code ResolvedValue.isDefault()} is what separates "has not said" from "said no", and a
     * plain boolean read could not - see {@link OptOutState} for what that distinction buys.
     */
    private OptOutMatrix matrixOf(ResolvedSettings resolved) {
        return (category, channel) -> stateOf(resolved, category, channel);
    }

    private OptOutState stateOf(ResolvedSettings resolved, String category, ChannelType channel) {
        ResolvedValue<Boolean> value = valueOf(resolved, NotificationSettings.optOut(category, channel));
        if (value == null || value.isDefault()) {
            return OptOutState.UNSET;
        }
        return Boolean.TRUE.equals(value.value()) ? OptOutState.OPTED_OUT : OptOutState.OPTED_IN;
    }
}
