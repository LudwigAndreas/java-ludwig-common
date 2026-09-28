package ru.ludwigandreas.outbox.repository;

import ru.ludwigandreas.outbox.entity.OutboxMessage;

import java.time.Instant;
import java.util.List;

/**
 * The claim step, separated from the interface's declared queries because it is built at runtime from
 * {@code job-core}'s shared {@code FOR UPDATE SKIP LOCKED} helper rather than written out as a
 * {@code @Query} string.
 *
 * <p>Keeping it a repository fragment rather than a service means callers - including the integration
 * tests - still reach it as {@code repository.pollBatch(...)}, which is where a claim belongs.
 */
public interface OutboxMessageRepositoryCustom {

    /**
     * Atomically claims up to {@code batchSize} due rows ({@code PENDING}/{@code FAILED} with
     * {@code next_attempt_at <= now}) by flipping them to {@code PROCESSING}, skipping rows another
     * instance is already claiming.
     *
     * @param batchSize maximum rows to claim in this cycle
     * @param now       the instant due-ness is evaluated against
     * @param lockOwner identity written into {@code locked_by}
     * @return the claimed rows, oldest first
     */
    List<OutboxMessage> pollBatch(int batchSize, Instant now, String lockOwner);
}
