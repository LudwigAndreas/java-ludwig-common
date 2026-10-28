package ru.ludwigandreas.outbox.entity;

/**
 * Lifecycle of an {@link OutboxMessage}: {@code PENDING}/{@code FAILED} rows are eligible for polling,
 * {@code PROCESSING} rows are claimed by exactly one poller instance, and a row settles into either
 * {@code PUBLISHED} or, once retries are exhausted, {@code DEAD_LETTER}. An administrator may move a message that
 * must never be sent to {@code DISCARDED}, which is terminal and which no poller claims.
 *
 * <p>{@code DISCARDED} needed no migration: the column is a {@code VARCHAR(20)} with no check constraint, and the
 * poll index's predicate ({@code status IN ('PENDING', 'FAILED')}) already excludes it.
 */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED,
    DEAD_LETTER,
    DISCARDED
}
