package ru.ludwigandreas.notification.service.model;

/**
 * Who an announcement is for, as a rule rather than as a list.
 *
 * <p>Two fields and no collection, which is the property the whole announcement aggregate rests on:
 * one row describes an audience of ten or of a hundred thousand, somebody created tomorrow is inside
 * an {@link AudienceType#EVERYONE} audience with nothing rewritten, and nothing has to be swept when
 * the announcement expires.
 *
 * <p>Constructed only through {@code AnnouncementAudienceResolver}, which is the single place the
 * deployment's allowlist is consulted. An ArchUnit rule forbids a second construction path for a
 * role audience, because a second path that skipped the allowlist would behave perfectly for every
 * valid role and silently accept every invalid one.
 *
 * @param type  how the audience is described
 * @param value the role code for {@link AudienceType#ROLE}; null for
 *              {@link AudienceType#EVERYONE}, which a database check constraint also enforces
 */
public record Audience(AudienceType type, String value) {

    public Audience {
        if (type == null) {
            throw new IllegalArgumentException("An audience needs a type");
        }
        // Enforced here as well as by a check constraint in the database, because the consequence of
        // getting it wrong is silent in the worst way: a ROLE audience with no code makes the
        // visibility predicate match nobody, for the whole life of the announcement, with the
        // announcement itself looking perfectly published.
        if (type == AudienceType.ROLE && (value == null || value.isBlank())) {
            throw new IllegalArgumentException("A ROLE audience needs a role code");
        }
        if (type == AudienceType.EVERYONE && value != null) {
            throw new IllegalArgumentException(
                    "An EVERYONE audience must not carry a value; it is ambiguous whether it means "
                            + "everybody or only that value");
        }
    }

    /** Everybody. */
    public static Audience everyone() {
        return new Audience(AudienceType.EVERYONE, null);
    }

    /**
     * Everybody who currently holds this role.
     *
     * <p>Deliberately package-visible in intent rather than in fact: callers outside the resolver
     * must not use it, and an ArchUnit rule enforces that. It cannot be literally package-private
     * because the resolver lives in the service layer and this type lives in the model, which is the
     * split the layering rules require - so the rule stands in for the keyword.
     */
    public static Audience ofRole(String roleCode) {
        return new Audience(AudienceType.ROLE, roleCode);
    }
}
