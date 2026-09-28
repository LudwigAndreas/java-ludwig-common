package ru.ludwigandreas.audit.store.actor;

import java.util.Optional;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.db.core.audit.AuditorProvider;

/**
 * Feeds {@code db-core}'s field stamping from the audit trail's actor resolution.
 *
 * <p>This class is the whole of "unify the resolution; leave the stamping alone". {@code AuditedEntity}
 * keeps stamping {@code created_by} / {@code last_modified_by} onto rows, exactly as before - folding
 * that into an event trail would change the mapping of every entity in the platform for no gain, and it
 * answers a different question ("who last touched this row" rather than "what happened"). What changes is
 * that both mechanisms now get their answer from one {@link ActorResolver}, so a row's
 * {@code last_modified_by} and the {@code actor_subject} of the event describing that modification are
 * the same string by construction.
 *
 * <p>Registered as an {@code AuditorProvider} bean, which {@code db-core}'s own
 * {@code @ConditionalOnMissingBean(AuditorProvider.class)} then yields to. A service that had published
 * its own provider keeps it and is responsible for keeping the two in step itself.
 */
public class ActorResolverAuditorProvider implements AuditorProvider<String> {

    private final ActorResolver actors;

    /**
     * Creates the bridge.
     *
     * @param actors the platform's actor resolution
     */
    public ActorResolverAuditorProvider(ActorResolver actors) {
        this.actors = actors;
    }

    @Override
    public Optional<String> getCurrentAuditor() {
        return actors.currentSubject();
    }
}
