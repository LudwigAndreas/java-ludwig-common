package ru.ludwigandreas.notification.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.SuppressionEntity;

/** Suppression-list reads and the compaction the maintenance job runs. */
public interface SuppressionQueryRepository {

    /** Whether this exact destination is currently suppressed on this channel. */
    Optional<SuppressionEntity> findActive(ChannelKind channel, String normalizedAddress, Instant now);

    /**
     * The subset of {@code normalizedAddresses} currently suppressed on this channel.
     *
     * <p>Batched on purpose: the pre-dispatch re-check runs once per claimed batch, and asking one
     * question per delivery would turn a hundred-row batch into a hundred round trips on the hottest
     * path in the service.
     */
    Set<String> findActiveAddresses(ChannelKind channel, Collection<String> normalizedAddresses, Instant now);

    List<SuppressionEntity> listActive(ChannelKind channel, Instant now, int limit);

    /** Drops suppressions whose lease has lapsed - the soft bounces, never the complaints. */
    long purgeExpired(Instant now);
}
