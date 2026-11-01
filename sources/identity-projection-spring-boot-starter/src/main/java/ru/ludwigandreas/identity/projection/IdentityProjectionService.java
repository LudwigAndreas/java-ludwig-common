package ru.ludwigandreas.identity.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.entity.UserStatus;
import ru.ludwigandreas.identity.feed.ApplyOutcome;
import ru.ludwigandreas.identity.feed.IdentityChange;
import ru.ludwigandreas.identity.feed.IdentityProjection;
import ru.ludwigandreas.identity.feed.IdentitySweep;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.PrincipalDisabledEvent;

/**
 * The identity projection's one write path: applies a source's changes to {@code security_user}.
 *
 * <h2>Ordering</h2>
 *
 * <p>A change whose {@code occurredAt} is not newer than the stored {@code source_timestamp} is skipped as stale. A
 * feed delivers changes in whatever order its transport does - Kafka orders only within a partition, a pulled page
 * re-reads its boundary - and comparing on when the change happened at the source is what makes a replay or a
 * reordering converge instead of restoring a revoked role.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Two replicas applying the first change for the same new subject both see no row and both insert; one loses on
 * the primary key. That exception is deliberately left to propagate: the feed retries, and the retry finds the
 * winner's row and updates it. Catching it here would also swallow a genuinely malformed change.
 *
 * <h2>Ownership</h2>
 *
 * <p>A subject belongs to the source that first projected it ({@code source_system}). A change for it from another
 * source is refused rather than applied, because two providers issuing one subject is a configuration error that
 * silently merging would turn into one person holding another's roles.
 */
@Slf4j
public class IdentityProjectionService implements IdentityProjection {

    private final SecurityUserRepository repository;
    private final IdentityChangeMapper mapper;
    private final IdentitySources sources;
    private final LudwigCache<PrincipalRef, Authorities> authorityCache;
    private final ApplicationEventPublisher events;
    private final IdentityMetrics metrics;
    private final SweepStore sweeps;

    /**
     * Whether the projection keeps contact data. Cleared rather than never merged, so switching the flag off removes
     * what an earlier configuration stored: the next change for each user scrubs their row.
     */
    private final boolean storeContactData;

    /**
     * Creates the projection.
     *
     * @param repository       the user table
     * @param mapper           merges a profile into a row
     * @param sources          the declared sources and their role mappings
     * @param authorityCache   evicted after a change commits
     * @param events           announces disabled principals
     * @param metrics          counts changes and sweeps
     * @param sweeps           stages and completes sweeps
     * @param storeContactData {@code ludwig.identity.contact.enabled}
     */
    public IdentityProjectionService(SecurityUserRepository repository, IdentityChangeMapper mapper,
                                     IdentitySources sources, LudwigCache<PrincipalRef, Authorities> authorityCache,
                                     ApplicationEventPublisher events, IdentityMetrics metrics, SweepStore sweeps,
                                     boolean storeContactData) {
        this.repository = repository;
        this.mapper = mapper;
        this.sources = sources;
        this.authorityCache = authorityCache;
        this.events = events;
        this.metrics = metrics;
        this.sweeps = sweeps;
        this.storeContactData = storeContactData;
    }

    @Override
    @Transactional
    public ApplyOutcome apply(IdentityChange change) {
        IdentitySources.RoleMapping roles = sources.require(change.source());
        ApplyOutcome outcome = applyOne(change, roles);
        metrics.change(change.source(), change.type(), outcome);
        return outcome;
    }

    @Override
    @Transactional
    public List<ApplyOutcome> applyAll(List<? extends IdentityChange> changes) {
        changes.forEach(change -> sources.require(change.source()));
        List<ApplyOutcome> outcomes = new ArrayList<>(changes.size());
        for (IdentityChange change : changes) {
            ApplyOutcome outcome = applyOne(change, sources.require(change.source()));
            metrics.change(change.source(), change.type(), outcome);
            outcomes.add(outcome);
        }
        return outcomes;
    }

    @Override
    public IdentitySweep beginSweep(String source) {
        sources.require(source);
        return sweeps.begin(source);
    }

    private ApplyOutcome applyOne(IdentityChange change, IdentitySources.RoleMapping roles) {
        if (change.subject() == null || change.subject().isBlank()) {
            log.warn("Discarding a {} change from source {} with no subject", change.type(), change.source());
            return ApplyOutcome.DISCARDED;
        }
        Optional<SecurityUserEntity> existing = repository.findById(change.subject());
        if (existing.isPresent()) {
            SecurityUserEntity stored = existing.get();
            if (!change.source().equals(stored.getSourceSystem())) {
                log.error("Refusing a {} change for subject {} from source {}: the subject belongs to source {}",
                        change.type(), change.subject(), change.source(), stored.getSourceSystem());
                return ApplyOutcome.FOREIGN_SOURCE;
            }
            if (stored.getSourceTimestamp() != null && !change.occurredAt().isAfter(stored.getSourceTimestamp())) {
                log.debug("Skipping a {} change for {}: occurredAt={} is not newer than the stored {}",
                        change.type(), change.subject(), change.occurredAt(), stored.getSourceTimestamp());
                return ApplyOutcome.STALE;
            }
        }
        SecurityUserEntity entity = existing.orElseGet(() -> newUser(change));
        UserStatus status;
        if (change instanceof IdentityChange.Upsert upsert) {
            mapper.merge(upsert, entity);
            if (upsert.profile().roles() != null) {
                entity.getRoles().clear();
                entity.getRoles().addAll(roles.map(upsert.profile().roles()));
            }
            status = UserStatus.ACTIVE;
        } else {
            status = change instanceof IdentityChange.Remove ? UserStatus.REMOVED : UserStatus.DISABLED;
        }
        entity.setStatus(status);
        entity.setSourceTimestamp(change.occurredAt());
        entity.setSourceVersion(change.sourceVersion());
        if (!storeContactData) {
            clearContactData(entity);
        }
        repository.save(entity);
        authorityCache.evictAfterCommit(PrincipalRef.user(change.subject()));
        if (status != UserStatus.ACTIVE) {
            // Only for a deactivating change: an announcement on every ordinary update would be one somebody
            // switches off.
            events.publishEvent(new PrincipalDisabledEvent(change.subject(), change.type()));
        }
        return ApplyOutcome.APPLIED;
    }

    private static SecurityUserEntity newUser(IdentityChange change) {
        SecurityUserEntity entity = new SecurityUserEntity();
        entity.setId(change.subject());
        entity.setSourceSystem(change.source());
        return entity;
    }

    private static void clearContactData(SecurityUserEntity entity) {
        entity.setEmail(null);
        entity.setAlternateEmail(null);
        entity.setPhoneNumber(null);
        entity.setChatHandle(null);
        entity.setEmailVerified(null);
        entity.setPhoneVerified(null);
    }
}
