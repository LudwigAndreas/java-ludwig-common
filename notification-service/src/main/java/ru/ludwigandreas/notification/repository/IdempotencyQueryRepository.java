package ru.ludwigandreas.notification.repository;

import java.time.Instant;
import java.util.Optional;
import ru.ludwigandreas.notification.repository.entity.IdempotencyRecordEntity;

/** The read and retention halves of the dedup table; the claim itself is the native upsert. */
public interface IdempotencyQueryRepository {

    /** The live claim on this key, if there is one. An expired row is not a claim. */
    Optional<IdempotencyRecordEntity> findActive(String scope, String key, Instant now);

    /** Drops keys past their window, freeing them for reuse. Runs under the distributed lock. */
    long purgeExpired(Instant now);
}
