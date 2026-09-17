package ru.ludwigandreas.notification.service.idempotency;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.repository.IdempotencyRecordRepository;

/**
 * The dedup table, backed by a single conditional upsert.
 *
 * <p>Everything load-bearing is in the statement rather than here - see
 * {@link IdempotencyRecordRepository#claim} for why {@code ON CONFLICT ... DO UPDATE} and not a read
 * followed by an insert, and not {@code DO NOTHING} either.
 *
 * <h2>Behaviour at three replicas</h2>
 *
 * <p>Two replicas handed the same Kafka record concurrently both call {@link #claim} with the same
 * scope and key. Whichever statement reaches the row first holds the row lock; the second blocks on
 * it, and when the first commits the second's {@code DO UPDATE} sees the committed row and returns
 * the winner's request id. The loser then returns the winner's request to its caller without writing
 * anything, and no message is sent twice.
 *
 * <p>The claim is written in the <em>caller's</em> transaction, the same one that writes the request
 * and its deliveries. That is required rather than convenient: a claim that committed independently
 * would leave a key permanently reserved for a request whose transaction then rolled back, and the
 * retry of that request would be rejected as a duplicate of something that does not exist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostgresIdempotencyStore implements IdempotencyStore {

    private final IdempotencyRecordRepository repository;
    private final NotificationProperties properties;

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public UUID claim(String scope, String key, UUID requestId) {
        Instant now = Instant.now();
        UUID owner = repository.claim(UUID.randomUUID(), scope, key, requestId, now,
                now.plus(properties.getIdempotency().getTtl()));
        if (owner != null && !owner.equals(requestId)) {
            log.debug("Idempotency key {}/{} is already held by request {}", scope, key, owner);
        }
        // A null owner cannot happen - the statement either inserts or updates and always returns a
        // row - but treating it as "we won" rather than throwing keeps a driver-level surprise from
        // dropping a notification.
        return owner == null ? requestId : owner;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isClaimed(String scope, String key) {
        return repository.findActive(scope, key, Instant.now()).isPresent();
    }

    @Override
    @Transactional
    public long purgeExpired() {
        return repository.purgeExpired(Instant.now());
    }
}
