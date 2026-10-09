package ru.ludwigandreas.notification.web.dto;

/**
 * Whether the recipient may decline this category, as it appears on the wire.
 *
 * <p>Sent by the caller and deliberately so, because the calling service is the one that knows
 * whether what it is sending is a receipt or a campaign. It is not a free pass: which categories may
 * legitimately be transactional is a matter for review of the notification catalogue, and the
 * category itself - not this flag - is what a recipient's preferences are expressed against.
 */
public enum CategoryClassDto {
    TRANSACTIONAL,

    /**
     * Undeclinable but not urgent: a release note, a deprecation warning, an incident notice.
     *
     * <p>Accepted on the wire for completeness and symmetry with the other two, but note that the
     * feature it exists for - a platform announcement - does not let a caller choose it: an
     * announcement names a <em>category</em>, and the deployment's catalogue decides that category's
     * class. See {@code CategoryClass} for why the class is not a request-level decision.
     */
    PLATFORM,

    MARKETING
}
