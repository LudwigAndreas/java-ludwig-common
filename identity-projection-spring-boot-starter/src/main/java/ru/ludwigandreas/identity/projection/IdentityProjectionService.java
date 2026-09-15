package ru.ludwigandreas.identity.projection;

import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.entity.UserStatus;
import ru.ludwigandreas.identity.kafka.OidcUserEvent;
import ru.ludwigandreas.identity.kafka.OidcUserEventMapper;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.security.authz.AuthorityCache;
import ru.ludwigandreas.security.authz.PrincipalRef;

/**
 * Applies one user event to the local projection.
 *
 * <p>Two properties make this safe to run against an at-least-once, partially-ordered stream:
 *
 * <p><b>Idempotence.</b> Every event carries the user's complete state, so applying the same event twice
 * produces the same row. Nothing here is incremental.
 *
 * <p><b>Order tolerance.</b> Kafka guarantees order only within a partition, and a user's events move
 * between partitions when the topic is scaled or the key changes. An event older than what is already
 * stored is therefore not an error - it is normal - and is dropped by comparing
 * {@code occurredAt}. Without that check a replay would resurrect a role that was revoked days ago, which
 * is the worst kind of bug here: silent, delayed, and a privilege escalation.
 *
 * <p>The authority cache is evicted <em>after commit</em>, not during. Evicting inside the transaction
 * opens a window where another thread re-reads the old row and re-populates the cache with stale roles,
 * and the eviction would still have happened had the transaction then rolled back.
 *
 * <p><b>Concurrent first events for one subject</b> - two partitions delivering the same new user at the
 * same moment - both see no row and both insert, and one loses on the primary key. That exception is
 * deliberately left to propagate: the listener does not acknowledge, the container redelivers, and the
 * retry finds the row the winner wrote and updates it instead. Catching and ignoring it here would be
 * wrong in the case that looks identical from inside the transaction but is not - a constraint violation
 * from a genuinely malformed event, which would then be dropped silently.
 */
@Slf4j
@RequiredArgsConstructor
public class IdentityProjectionService {

    private final SecurityUserRepository repository;
    private final OidcUserEventMapper mapper;
    private final AuthorityCache authorityCache;

    @Transactional
    public void apply(OidcUserEvent event) {
        if (event == null || event.subject() == null || event.subject().isBlank()) {
            log.warn("Discarding user event with no subject (eventId={})",
                    event == null ? null : event.eventId());
            return;
        }

        Optional<SecurityUserEntity> existing = repository.findById(event.subject());
        if (existing.isPresent() && isStale(event, existing.get())) {
            log.debug("Skipping user event {} for {}: occurredAt={} is not newer than the stored {}",
                    event.eventId(), event.subject(), event.occurredAt(),
                    existing.get().getSourceTimestamp());
            return;
        }

        SecurityUserEntity entity = existing.orElse(null);
        if (entity == null) {
            entity = mapper.toEntity(event);
        } else {
            mapper.update(event, entity);
        }
        entity.setStatus(statusFor(event));
        repository.save(entity);

        evictAfterCommit(event.subject());
    }

    /**
     * An event with no {@code occurredAt} is always applied: the alternative - treating "unknown" as old
     * and dropping it - would silently stop projecting from a producer that omits the field.
     */
    private boolean isStale(OidcUserEvent event, SecurityUserEntity stored) {
        Instant incoming = event.occurredAt();
        Instant current = stored.getSourceTimestamp();
        return incoming != null && current != null && !incoming.isAfter(current);
    }

    private UserStatus statusFor(OidcUserEvent event) {
        return switch (event.type()) {
            case UPSERT -> UserStatus.ACTIVE;
            case DISABLE, DELETE -> UserStatus.DISABLED;
        };
    }

    private void evictAfterCommit(String subject) {
        PrincipalRef ref = PrincipalRef.user(subject);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            authorityCache.evict(ref);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                authorityCache.evict(ref);
            }
        });
    }
}
