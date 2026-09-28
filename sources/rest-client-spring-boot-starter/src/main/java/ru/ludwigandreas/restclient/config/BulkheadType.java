package ru.ludwigandreas.restclient.config;

/** Which of Resilience4j's two bulkhead implementations a client uses. */
public enum BulkheadType {

    /**
     * Semaphore. The caller's own thread makes the call once it holds a permit, so the concurrency
     * cap is enforced without moving work between threads - which is what you want when the caller
     * is already a request thread that is going to block regardless.
     */
    SEMAPHORE,

    /**
     * Thread pool. The call is handed to a bounded pool with a bounded queue, so the calling thread
     * is released. Meaningful for a {@code sync} client called from a thread you must not occupy;
     * for {@code async} it is redundant, because nothing is occupied in the first place.
     */
    THREAD_POOL
}
