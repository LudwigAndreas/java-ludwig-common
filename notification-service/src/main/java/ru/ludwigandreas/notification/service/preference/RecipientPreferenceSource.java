package ru.ludwigandreas.notification.service.preference;

/**
 * Where a recipient's stored preferences come from.
 *
 * <p>The seam that lets this service run with or without a preference store. Preferences - locale,
 * timezone, quiet hours, digest cadence, per-category opt-outs - are a user's, not a notification
 * system's, and on this platform the account service owns them and publishes them. That store is a
 * separate deployment with its own transport, and this service must be able to boot, fan out and
 * deliver without it: a notification that goes out with the configured defaults is a far better
 * outcome than one that does not go out at all.
 *
 * <p>So the dispatch path depends on this interface, and the module that knows how to read the real
 * store is an adapter behind it - see {@code service.preference.usersettings}. Adding the store
 * later is a dependency and a property, not a change to any code on the path.
 *
 * <h2>Contract</h2>
 *
 * <p>Implementations must be cheap enough to call once per recipient per request and must never
 * throw for an unknown recipient: not knowing somebody is the ordinary case, answered with
 * {@link StoredPreferences#none()}. An implementation that reaches out of the process must degrade
 * to that answer rather than propagate a failure, because this runs on a queue worker where the
 * alternative to a default is a stuck delivery.
 */
public interface RecipientPreferenceSource {

    /**
     * The stored preferences for one recipient.
     *
     * @param userId   the subject; never blank - a literal address is not asked about
     * @param tenantId the tenant the subject belongs to, or null when the directory does not say.
     *                 A source that scopes by tenant answers {@link StoredPreferences#none()} rather
     *                 than guessing: a missing tenant must not resolve to another tenant's values.
     * @return never null
     */
    StoredPreferences lookup(String userId, String tenantId);

    /**
     * A short name for the startup log and the {@code /actuator/info} entry.
     *
     * <p>Which source is live is the first thing anybody asks when a recipient's quiet hours appear
     * not to be honoured, and reading it out of the log beats inferring it from the classpath.
     */
    String describe();
}
