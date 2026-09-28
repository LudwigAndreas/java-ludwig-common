package ru.ludwigandreas.audit;

import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolves the actor from the Spring Security context.
 *
 * <p>The same three conditions {@code db-core}'s {@code SpringSecurityAuditorProvider} applies, on
 * purpose and not by coincidence: no authentication, an unauthenticated one, or an anonymous token all
 * mean "no principal". That is what makes a row's {@code created_by} and the audit event describing the
 * same change agree - if the two resolvers disagreed about anonymous, one would say {@code anonymousUser}
 * and the other would say nothing, and the join an auditor needs would not exist.
 *
 * <p>A deployment with a richer principal model publishes its own {@link ActorResolver} bean and this one
 * steps aside; {@code security-spring-boot-starter} does exactly that, because it knows the platform's
 * principal type and this module deliberately does not depend on it.
 *
 * <p>In {@code audit-core} rather than in the starter, and that placement is load-bearing rather than
 * tidiness. It has to be registered by the same autoconfiguration class as
 * {@link ActorResolver#unattributed()}, because {@code @ConditionalOnMissingBean} only sees beans a
 * <em>different</em> autoconfiguration has already registered - so with the two in different classes the
 * unattributed fallback won whenever it was ordered first, and an administrator's own id silently became
 * {@code system} in every audit event. {@code db-core}'s {@code DatabaseAutoConfiguration} declares its
 * {@code SpringSecurityAuditorProvider} and its system fallback in one class for exactly this reason.
 */
public class SpringSecurityActorResolver implements ActorResolver {

    @Override
    public Optional<Actor> currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return Optional.ofNullable(authentication.getName())
                .filter(name -> !name.isBlank())
                .map(name -> Actor.of(name, authentication.getClass().getSimpleName()));
    }
}
