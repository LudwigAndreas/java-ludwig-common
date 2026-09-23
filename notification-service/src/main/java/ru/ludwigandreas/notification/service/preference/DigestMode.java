package ru.ludwigandreas.notification.service.preference;

/**
 * How often a recipient wants batched notifications delivered.
 *
 * <p>This service's own copy of a preference that another system owns, and that is the point. The
 * user-settings module ships a {@code DigestPreference} with the same constants, and reusing it
 * directly would make every class that touches a recipient's preferences - the resolver, the
 * fan-out, the digest scheduler - fail to load when that module is not deployed. The preference is
 * translated once, at the adapter that reads it, so the rest of the service depends on a type it
 * owns.
 *
 * <p>The constants are deliberately identical to the module's, so the translation is a name lookup
 * and a new constant on either side shows up as an unmapped name rather than as silent data loss.
 */
public enum DigestMode {

    /** Send each notification as it happens. */
    IMMEDIATE,

    HOURLY,

    DAILY,

    WEEKLY,

    /**
     * Never batch for this recipient.
     *
     * <p>Distinct from opting out of a category: it says "do not collapse my notifications", and a
     * sender that cannot batch may still have every reason to deliver immediately.
     */
    NEVER
}
