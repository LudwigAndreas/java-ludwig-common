package ru.ludwigandreas.usersettings.projection;

import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.cache.AfterCommitEviction;
import ru.ludwigandreas.usersettings.cache.SettingsCache;
import ru.ludwigandreas.usersettings.entity.UserConsentEntity;
import ru.ludwigandreas.usersettings.entity.UserSettingValueEntity;
import ru.ludwigandreas.usersettings.event.ConsentChangedEvent;
import ru.ludwigandreas.usersettings.event.UserSettingChangedEvent;
import ru.ludwigandreas.usersettings.metrics.SettingsMetrics;
import ru.ludwigandreas.usersettings.repository.UserConsentRepository;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;

/**
 * Applies one owner event to the local replica.
 *
 * <p>The same two properties that make {@code IdentityProjectionService} safe against an
 * at-least-once, partially-ordered stream are what this class is built for, and it follows that
 * class deliberately closely - a reader who has understood one has understood both.
 *
 * <p><b>Idempotence.</b> A setting event carries the complete state of one row, so applying it twice
 * produces the same row. A consent event carries the owner's primary key, so applying it twice
 * collides on that key and the second application is a no-op. Nothing here is incremental.
 *
 * <p><b>Order tolerance.</b> Kafka guarantees order only within a partition, and a subject's events
 * move between partitions when the topic is scaled or the key changes. An event older than what is
 * already stored is therefore normal rather than an error, and is dropped by comparing
 * {@code occurredAt} against the stored {@code changedAt}. Without that check a replay would restore
 * a preference the user has since changed - silent, delayed, and visible to the user as their
 * settings spontaneously reverting.
 *
 * <p>This is also why a removal is stored as a tombstone rather than as a deleted row: a delete
 * leaves nothing to compare a late "set" against, and the late set would resurrect the value.
 *
 * <p>The settings cache is evicted <em>after commit</em>, not during. Evicting inside the
 * transaction opens a window in which another thread re-reads the old row and repopulates the cache
 * with the stale value, and the eviction would still have happened had the transaction then rolled
 * back. See {@link AfterCommitEviction}.
 *
 * <p><b>Concurrent first events for one row</b> - two partitions delivering the same new setting at
 * the same moment - both see no row and both insert, and one loses on the unique constraint. That
 * exception is deliberately left to propagate: the listener does not acknowledge, the container
 * redelivers, and the retry finds the row the winner wrote and compares timestamps with it as usual.
 * Catching and ignoring it here would be wrong in the case that looks identical from inside the
 * transaction but is not - a constraint violation from a genuinely malformed event, which would then
 * be dropped silently.
 */
@Slf4j
@RequiredArgsConstructor
public class SettingsProjectionService {

    private final UserSettingValueRepository values;
    private final UserConsentRepository consents;
    private final SettingsCache cache;
    private final SettingsMetrics metrics;

    /** {@code sourceSystem} for projected consents: this deployment did not witness the decision. */
    private final String sourceSystem;

    /** Applies one setting change, dropping it when it is older than what is already stored. */
    @Transactional
    public void apply(UserSettingChangedEvent event) {
        if (!isUsable(event)) {
            return;
        }

        SettingScope scope = new SettingScope(event.scopeType(), event.scopeId());
        Optional<UserSettingValueEntity> existing =
                values.findValue(event.tenantId(), scope, event.settingKey());

        if (existing.isPresent() && isStale(event.occurredAt(), existing.get().getChangedAt())) {
            log.debug("Skipping setting event {} for {}/{}: occurredAt={} is not newer than the stored {}",
                    event.eventId(), event.tenantId(), event.settingKey(), event.occurredAt(),
                    existing.get().getChangedAt());
            metrics.recordStaleEventDropped();
            return;
        }

        UserSettingValueEntity entity = existing.orElseGet(UserSettingValueEntity::new);
        entity.setTenantId(event.tenantId());
        entity.setScopeType(event.scopeType());
        entity.setScopeId(event.scopeId());
        entity.setSettingKey(event.settingKey());
        entity.setRemoved(event.removed());
        entity.setValueText(event.removed() ? null : event.valueText());
        entity.setValueType(event.removed() ? null : event.valueType());
        entity.setChangedAt(event.occurredAt());
        values.save(entity);

        evictAfterCommit(event);
    }

    /**
     * A consent is inserted or it is not; there is no update path, here or anywhere.
     *
     * <p>The existence check is an optimization over letting the primary key collide - it turns the
     * common redelivery into a select instead of an exception and a rolled-back transaction. The key
     * is still what guarantees correctness: two consumers racing the same first delivery both pass
     * the check and one loses the insert, and that is the case the redelivery handles.
     */
    @Transactional
    public void apply(ConsentChangedEvent event) {
        if (event == null || event.consentId() == null || isBlank(event.tenantId())
                || isBlank(event.subject()) || isBlank(event.consentKey())) {
            log.warn("Discarding malformed consent event (eventId={})",
                    event == null ? null : event.eventId());
            return;
        }
        if (consents.existsById(event.consentId())) {
            log.debug("Consent {} is already recorded; nothing to apply", event.consentId());
            return;
        }

        UserConsentEntity entity = new UserConsentEntity();
        entity.setId(event.consentId());
        entity.setSourceSystem(sourceSystem);
        entity.setSourceTimestamp(event.occurredAt());
        entity.setTenantId(event.tenantId());
        entity.setSubject(event.subject());
        entity.setConsentKey(event.consentKey());
        entity.setTextVersion(event.textVersion());
        entity.setDecision(event.decision());
        entity.setLocale(event.locale());
        entity.setOccurredAt(event.occurredAt());
        entity.setActor(event.actor());
        entity.setEvidenceIp(event.evidenceIp());
        entity.setEvidenceUserAgent(event.evidenceUserAgent());
        entity.setCorrelationId(event.correlationId());
        consents.save(entity);

        metrics.recordConsentDecision(event.consentKey(), event.decision().name());
    }

    private boolean isUsable(UserSettingChangedEvent event) {
        if (event == null || isBlank(event.tenantId()) || isBlank(event.settingKey())
                || event.scopeType() == null || isBlank(event.scopeId())) {
            log.warn("Discarding malformed setting event (eventId={})",
                    event == null ? null : event.eventId());
            return false;
        }
        if (event.occurredAt() == null) {
            // Without a timestamp there is no way to tell this event from a replay of an older one,
            // so applying it could silently revert a newer value. Dropping it loses one change;
            // applying it could lose every later change to that setting.
            log.warn("Discarding setting event {} for {}: no occurredAt, so it cannot be ordered",
                    event.eventId(), event.settingKey());
            metrics.recordStaleEventDropped();
            return false;
        }
        if (!event.removed() && (event.valueText() == null || event.valueType() == null)) {
            log.warn("Discarding setting event {} for {}: not a removal but carries no value",
                    event.eventId(), event.settingKey());
            return false;
        }
        return true;
    }

    /**
     * Equal timestamps count as stale, so a redelivery of the event that is already applied is a
     * no-op rather than a rewrite. The stored state is identical either way; the difference is
     * whether the replica issues a pointless update and an eviction for every redelivered message.
     */
    private boolean isStale(Instant incoming, Instant stored) {
        return stored != null && !incoming.isAfter(stored);
    }

    /**
     * A user-scoped change affects one cache entry; a role- or tenant-scoped one affects everybody
     * who inherits from it, and this replica cannot enumerate them.
     */
    private void evictAfterCommit(UserSettingChangedEvent event) {
        switch (event.scopeType()) {
            case USER -> AfterCommitEviction.evict(cache,
                    new SettingsSubject(PrincipalRef.user(event.scopeId()), event.tenantId()));
            default -> AfterCommitEviction.evictAll(cache);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
