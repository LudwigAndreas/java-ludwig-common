package ru.ludwigandreas.usersettings.wellknown;

/**
 * How often a subject wants batched notifications delivered.
 *
 * <p>Lives here rather than in the notification service because the <em>preference</em> is the
 * user's and belongs with their other settings; what a service does with it - how it batches, when
 * it flushes - is that service's business. The dependency runs this way deliberately: a settings
 * module that imported a notification service's types would be unusable by anybody who does not run
 * that service.
 */
public enum DigestPreference {

    /** Send each notification as it happens. */
    IMMEDIATE,

    HOURLY,

    DAILY,

    WEEKLY,

    /**
     * Never send batched notifications at all.
     *
     * <p>Distinct from opting out of a category: this says "do not batch for me", and a sender that
     * cannot batch may still have a reason to deliver immediately.
     */
    NEVER
}
