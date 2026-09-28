package ru.ludwigandreas.reconciliation.config;

/**
 * Which of the four fetch shapes a task uses. Must agree with the {@code Fetcher} interface the
 * task's bean implements; the startup validator refuses a context where they disagree, because the
 * engine would otherwise walk the stream in a way the fetcher was not written for and the symptom
 * would be a task that quietly fetches nothing.
 */
public enum FetchShape {

    /** One call per key, fanned out under {@code fetch.max-concurrency}. */
    PER_ITEM,

    /** N keys per call, sliced into chunks of {@code fetch.batch-size}. */
    BATCHED,

    /** A catalogue walked page by page with a checkpointed cursor. */
    PAGED,

    /** Submit, poll, collect against a partner that answers with an asynchronous job. */
    ASYNC_JOB
}
