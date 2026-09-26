package ru.ludwigandreas.audit;

/**
 * Who did the thing an audit event describes.
 *
 * <p>{@link #subject()} is the field that makes the trail joinable, and it must be the same string
 * {@code db-core}'s {@code AuditorProvider} stamps into {@code created_by} / {@code last_modified_by}
 * for the same actor - otherwise a row's {@code last_modified_by} and the event describing that
 * modification name the same person two different ways, and no query can put them side by side. The
 * platform resolves it in one place, {@link ActorResolver}, for exactly that reason.
 *
 * @param subject       the principal's stable id
 * @param principalType what kind of principal it is - a user, a service account, a scheduled job;
 *                      a free string rather than an enum because the set is a deployment's
 *                      security model, not this module's
 * @param displayName   a human-readable name, when one is known and cheap to obtain; never the only
 *                      identification, because display names are neither stable nor unique
 * @param onBehalfOf    the subject this action was taken for, when it differs from the actor - an
 *                      administrator editing someone else's settings, a service impersonating a user
 */
public record Actor(String subject, String principalType, String displayName, String onBehalfOf) {

    /** The actor recorded when no principal could be resolved at all. */
    public static final String SYSTEM = "system";

    /** An actor identified by subject alone, which is the common case. */
    public static Actor of(String subject) {
        return new Actor(subject, null, null, null);
    }

    /** An actor identified by subject and principal type. */
    public static Actor of(String subject, String principalType) {
        return new Actor(subject, principalType, null, null);
    }

    /** The {@link #SYSTEM} actor, for work with no principal behind it. */
    public static Actor system() {
        return new Actor(SYSTEM, "SYSTEM", null, null);
    }

    /** This actor, acting for {@code otherSubject}. */
    public Actor onBehalfOf(String otherSubject) {
        return new Actor(subject, principalType, displayName, otherSubject);
    }
}
