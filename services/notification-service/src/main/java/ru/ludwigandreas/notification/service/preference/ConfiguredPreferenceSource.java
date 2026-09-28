package ru.ludwigandreas.notification.service.preference;

/**
 * The preference source for a deployment that has no preference store.
 *
 * <p>It knows nothing about anybody, and says so. Every recipient resolves to the configured
 * defaults for locale and timezone, to no quiet hours of their own, and to "has declined nothing" -
 * which is the honest reading of a store that is not there, and the safe one: the alternative
 * failure mode is a person who never asked for silence being silenced by a missing lookup.
 *
 * <h2>What still works without a store</h2>
 *
 * <p>More than it looks. A caller may name a recipient's locale and timezone on the request itself,
 * which is how a service that already knows them passes them through. Quiet hours configured
 * platform-wide still apply through {@code ludwig.notification.preferences}. And the suppression
 * list - bounces and complaints - is this service's own data and is unaffected, because a hard
 * bounce is a fact about an address rather than a wish of its owner.
 *
 * <h2>What does not</h2>
 *
 * <p>Per-recipient opt-outs, per-recipient quiet windows and per-recipient digest cadence. Those are
 * statements a person made, they are stored where that person manages their account, and this
 * service deliberately does not keep a second copy of them. A deployment that needs them adds the
 * user-settings module - see {@code service.preference.usersettings} - and nothing on the dispatch
 * path changes.
 */
public class ConfiguredPreferenceSource implements RecipientPreferenceSource {

    @Override
    public StoredPreferences lookup(String userId, String tenantId) {
        return StoredPreferences.none();
    }

    @Override
    public String describe() {
        return "configuration defaults (no preference store wired in)";
    }
}
