package ru.ludwigandreas.reconciliation.api;

/**
 * A job the partner currently considers active, as reported by
 * {@link Fetcher.JobFetcher#listActive()}.
 *
 * <p>Carries the idempotency key alongside the handle because that is the only thing that makes the
 * listing useful for resolving an ambiguous submit: the engine knows the key it committed before the
 * call that may or may not have been accepted, and needs to find out whether a job carrying that key
 * is running. A list of bare handles cannot answer that question, and adopting the wrong one would
 * attach this run to somebody else's job.
 *
 * @param <H>            the partner's handle type
 * @param handle         the partner's handle for the job
 * @param idempotencyKey the key the job was submitted with, as the partner echoes it back
 */
public record ActiveJob<H>(H handle, IdempotencyKey idempotencyKey) {
}
