package ru.ludwigandreas.security.principal;

import java.util.Objects;

/**
 * A principal has been disabled or removed upstream.
 *
 * <p>Published by whatever module owns the identity projection and consumed by anything holding state keyed
 * on a subject that must not outlive them - today that is the personal-access-token issuer, which revokes
 * the subject's live tokens.
 *
 * <h2>Why this type is here rather than in either of the modules that use it</h2>
 *
 * <p>Because both already depend on this one, and neither should depend on the other. The publisher is
 * {@code identity-projection-spring-boot-starter}; the consumer is {@code pat-spring-boot-starter}. A
 * dependency between them in either direction would couple the token issuer to how identities happen to be
 * projected, or the projection to what else cares - and a second definition of this event in each would be
 * the drift that single-definition types exist to prevent.
 *
 * <p>Spring's own event bus rather than a Kafka topic, deliberately. The projection already consumes the
 * upstream topic and turns it into local state; a second consumer of the same topic in the same application
 * would be a second group id, a second offset to manage, and a second place the topic's contract is
 * interpreted. What the issuer needs is not the upstream message - it is the <em>conclusion</em> the
 * projection reached, which is exactly what an in-process event carries.
 *
 * <p><b>Consequence worth stating:</b> this event does not cross a process boundary. A deployment where the
 * projection and the token issuer are different services needs the issuer to reach the same conclusion
 * another way - the simplest being that the attenuation already makes a disabled owner's tokens inert,
 * because a disabled owner resolves to no authorities. This event exists so the token <em>list</em> an
 * auditor reads matches reality, not to make the tokens stop working.
 *
 * @param subject the principal who was disabled
 * @param reason  why, as the publisher understood it - for the audit record, never for a decision
 */
public record PrincipalDisabledEvent(String subject, String reason) {

    public PrincipalDisabledEvent {
        Objects.requireNonNull(subject, "subject");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("a disabled principal must be named");
        }
    }
}
