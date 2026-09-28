package ru.ludwigandreas.audit;

import java.util.Optional;

/**
 * The one place the platform answers "who is acting right now".
 *
 * <p>It exists because there were two. {@code db-core}'s {@code SpringSecurityAuditorProvider} stamps
 * {@code created_by} / {@code last_modified_by} onto entity rows, and
 * {@code user-settings-spring-boot-starter}'s audit recorder called
 * {@code SecurityPrincipals.currentSubject()} - two paths to one answer, which is one path too many.
 * When they disagree, a row's {@code last_modified_by} and the audit event describing that
 * modification name the same person differently, and the join an auditor needs does not exist.
 *
 * <h2>What this does not do</h2>
 *
 * <p>Field stamping stays where it is. {@code AuditedEntity} and {@code AuditorProvider} answer "who
 * last touched this row", which is not an event trail, and folding them into this module would change
 * the mapping of every entity in the platform for no gain. What is unified is the <em>resolution</em>:
 * {@code db-core}'s {@code AuditorProvider} bean and {@link AuditEvent#actor()} are both fed from
 * here, so the two agree by construction rather than by coincidence.
 *
 * <p>The return is an {@link Optional} rather than {@link Actor#system()} so that a caller can tell
 * "no principal" from "a principal literally named system" - a distinction that matters the first time
 * somebody creates a service account with an unfortunate name.
 */
@FunctionalInterface
public interface ActorResolver {

    /**
     * The current actor, if one can be resolved.
     *
     * @return the actor, or empty for work with no principal behind it
     */
    Optional<Actor> currentActor();

    /** The current actor, or {@link Actor#system()} for unattributed work. */
    default Actor currentActorOrSystem() {
        return currentActor().orElseGet(Actor::system);
    }

    /**
     * The current actor's subject, in the form {@code db-core}'s {@code AuditorProvider} wants it.
     *
     * <p>This method is the contract between the two mechanisms: whatever a deployment's resolver
     * returns here is both the {@code created_by} column and the {@code actor.subject} of every event
     * describing the same change.
     *
     * @return the subject, or empty for work with no principal behind it
     */
    default Optional<String> currentSubject() {
        return currentActor().map(Actor::subject).filter(subject -> subject != null && !subject.isBlank());
    }

    /** A resolver for a context with no security at all - a scheduled job, a test. */
    static ActorResolver unattributed() {
        return Optional::empty;
    }

    /** A resolver that always answers with {@code actor}, for a job that knows its own identity. */
    static ActorResolver fixed(Actor actor) {
        return () -> Optional.ofNullable(actor);
    }
}
