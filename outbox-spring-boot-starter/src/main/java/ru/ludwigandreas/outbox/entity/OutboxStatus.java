package ru.ludwigandreas.outbox.entity;

/**
 * Lifecycle of an {@link OutboxMessage}: {@code PENDING}/{@code FAILED} rows are eligible for polling,
 * {@code PROCESSING} rows are claimed by exactly one poller instance, and a row settles into either
 * {@code PUBLISHED} or, once retries are exhausted, {@code DEAD_LETTER}.
 */
public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED,
    DEAD_LETTER
}
