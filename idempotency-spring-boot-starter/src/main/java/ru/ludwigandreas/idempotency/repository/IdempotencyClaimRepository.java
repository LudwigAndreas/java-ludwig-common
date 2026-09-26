package ru.ludwigandreas.idempotency.repository;

import java.util.UUID;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.idempotency.entity.IdempotencyClaimEntity;

/**
 * The claim table.
 *
 * <p>Carries no derived query methods and no {@code @Query}: the reads and the state transitions are in
 * {@link IdempotencyClaimQueryRepository} as QueryDSL, and the claim is the conditional upsert in
 * {@code ru.ludwigandreas.idempotency.sql}. What this interface contributes is {@code db-core}'s
 * {@code BaseRepository} surface, which the tests and an operator endpoint use to read a row by id.
 */
public interface IdempotencyClaimRepository
        extends BaseRepository<IdempotencyClaimEntity, UUID>, IdempotencyClaimQueryRepository {
}
