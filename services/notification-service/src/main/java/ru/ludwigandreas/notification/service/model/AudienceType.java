package ru.ludwigandreas.notification.service.model;

/**
 * How an announcement's audience is described.
 *
 * <p>A <em>kind</em> and a value, never a list of subjects. That is what makes an announcement one
 * row whatever its audience: publishing to a hundred thousand people writes the same rows as
 * publishing to ten, and a user created tomorrow is inside the audience without anything having been
 * rewritten. An ArchUnit rule fails the build if a materialized audience is ever added to the
 * announcement entity as an optimisation.
 *
 * <p>Its persistence twin is {@code repository.entity.AudienceKind} and its wire twin is
 * {@code web.dto.AudienceTypeDto}, named to match {@code ChannelType}/{@code ChannelKind}/
 * {@code ChannelTypeDto}; MapStruct maps between them by constant name at compile time, so adding a
 * kind to one without the others fails the build rather than a request.
 *
 * <p>Which kinds a deployment permits at all is configuration
 * ({@code ludwig.notification.announcements.allowed-audiences}), checked at startup.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>There is no {@code ORG_UNIT} and no {@code GROUP}, and neither is an oversight.
 *
 * <p>An organisational unit is <b>directory data</b>: the identity provider already owns it, it has
 * meaning outside notifications, and somebody else already owns keeping it correct when people move
 * teams. It will arrive through {@code identity-projection} the way roles do, and it becomes one more
 * constant here and one more branch in the visibility predicate - which is why the predicate is
 * written as a disjunction over kinds rather than as two special cases.
 *
 * <p>A notification-local group is not planned at all. A durable, meaningful grouping of people is
 * directory data and belongs to the provider; a one-off "tell these forty people" is already an
 * ordinary notification request naming its recipients. The case in between - a reusable list that
 * exists only here - is the one that rots, because no leaver process touches it and it quietly
 * announces things to people who left eighteen months ago.
 */
public enum AudienceType {

    /**
     * Every user. The audience value is unused and SHALL be null.
     *
     * <p>Combined with an email-sending category this is the most expensive thing this service can
     * be asked to do, which is why the category catalogue rather than a request field decides
     * whether email is sent at all.
     */
    EVERYONE,

    /**
     * Everybody who currently holds a role, by its code.
     *
     * <p>"Currently" is the important word: membership is resolved on every read from the caller's
     * own principal, so a revoked role stops granting visibility immediately. The role code must be
     * on the configured allowlist - see
     * {@code NotificationProperties.Announcements#getTargetableRoles()} for why that is an allowlist
     * and not "any role".
     */
    ROLE
}
