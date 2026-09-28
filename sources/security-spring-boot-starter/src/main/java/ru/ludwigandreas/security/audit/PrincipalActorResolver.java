package ru.ludwigandreas.security.audit;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * Resolves the audit actor from this platform's own principal.
 *
 * <p>Published by this module so that the one resolver the whole platform uses - the audit trail's
 * {@code actor_subject} and {@code db-core}'s {@code created_by} both - knows about
 * {@link LudwigPrincipal} and its {@code PrincipalType}. {@code audit-spring-boot-starter} ships a
 * plainer resolver over {@code Authentication.getName()} and steps aside when this one is present; it
 * has to, because {@code audit-core} deliberately depends on nothing in this repository and so cannot
 * know this type exists.
 *
 * <p>Falls back to {@code Authentication.getName()} for an authentication this module did not create -
 * an actuator's basic auth, a test's {@code TestingAuthenticationToken} - rather than reporting no
 * actor, because "someone we cannot name did this" is a far more useful audit row than silence.
 */
public class PrincipalActorResolver implements ActorResolver {

    @Override
    public Optional<Actor> currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof LudwigPrincipal principal) {
            return Optional.of(new Actor(principal.subject(),
                    principal.type() == null ? null : principal.type().name(), null, null));
        }
        return Optional.ofNullable(authentication.getName())
                .filter(name -> !name.isBlank())
                .map(Actor::of);
    }
}
