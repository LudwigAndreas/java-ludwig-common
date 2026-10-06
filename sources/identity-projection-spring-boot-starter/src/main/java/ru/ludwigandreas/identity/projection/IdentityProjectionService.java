package ru.ludwigandreas.identity.projection;

import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.entity.UserStatus;
import ru.ludwigandreas.identity.kafka.OidcUserEvent;
import ru.ludwigandreas.identity.kafka.OidcUserEventMapper;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.security.authz.Authorities;
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
 * and the eviction would still have happened had the transaction then rolled back. That discipline used to
 * be a {@code TransactionSynchronization} registered by hand in this class, which was the second copy of
 * the same helper - {@code user-settings} had the first - and is now one call into
 * {@link ru.ludwigandreas.cache.api.LudwigCache#evictAfterCommit}, where the reasoning is written down
 * once.
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
    private final LudwigCache<PrincipalRef, Authorities> authorityCache;

    /**
     * Announces a disabled principal to anything holding state keyed on a subject.
     *
     * <p>An {@link org.springframework.context.ApplicationEventPublisher} rather than a direct call to the
     * interested modules, because this module should not know what they are. Today the only listener is the
     * personal-access-token issuer, which revokes the subject's live tokens so that a token list matches
     * reality; tomorrow it could be anything else keyed on a subject.
     */
    private final org.springframework.context.ApplicationEventPublisher events;

    /**
     * Whether the projection keeps the user's contact data.
     *
     * <p>Cleared rather than never mapped, because the mapper is generated and mapping by name is what
     * makes a new field on either side a build failure rather than a silent omission. Dropping the
     * values here instead keeps that property and puts the decision in one visible place.
     */
    private final boolean storeContactData;

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
        if (!storeContactData) {
            clearContactData(entity);
        }
        repository.save(entity);

        evictAfterCommit(event.subject());

        // Published after the save and only for a disabling event. Not for UPSERT, which is the vast
        // majority of traffic - a listener firing on every ordinary user update would be a listener
        // somebody switches off.
        if (statusFor(event) == UserStatus.DISABLED) {
            events.publishEvent(new ru.ludwigandreas.security.principal.PrincipalDisabledEvent(
                    event.subject(), event.type().name()));
        }
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

    /**
     * Removes contact data from a row this deployment has not opted into keeping.
     *
     * <p>Cleared on every apply rather than only on insert, so switching the flag off actually removes
     * what an earlier configuration stored - the next event for each user scrubs their row. A flag that
     * only stopped new writes would leave the old values sitting there indefinitely, which is the
     * failure mode that matters for personal data.
     */
    private void clearContactData(SecurityUserEntity entity) {
        entity.setEmail(null);
        entity.setAlternateEmail(null);
        entity.setPhoneNumber(null);
        entity.setChatHandle(null);
        entity.setEmailVerified(null);
        entity.setPhoneVerified(null);
    }

    private UserStatus statusFor(OidcUserEvent event) {
        return switch (event.type()) {
            case UPSERT -> UserStatus.ACTIVE;
            case DISABLE, DELETE -> UserStatus.DISABLED;
        };
    }

    private void evictAfterCommit(String subject) {
        authorityCache.evictAfterCommit(PrincipalRef.user(subject));
    }
}
