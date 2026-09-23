package ru.ludwigandreas.reconciliation.api;

import java.util.function.Function;

/**
 * One integration: what to sync, how to address it, how to fetch it, and how to apply it.
 *
 * <p>Published as a Spring bean annotated {@link ReconciliationTask}, whose value names the task's
 * configuration block. Everything operational - schedules, concurrency, batch sizes, retry budgets,
 * quotas, rate limits, staging mode - is configuration and is identical in shape across every task in
 * every service. What is written in Java is only what genuinely differs.
 *
 * <h2>Why there are seven members and not five</h2>
 *
 * <p>The obvious shape of this interface is {@code demand / localKey / fetcher / reconciler}, and
 * that is all a task needs when fetching and applying happen in one breath. This module's default
 * mode does not: it stages what it fetched and applies it later, possibly on another instance,
 * possibly after a restart, which is what buys per-item retry without re-calling the partner. Two of
 * the members below exist purely to make that round trip through a database row possible -
 * {@link #keyCodec()} so the correlation key can be read back out of a text column, and
 * {@link #externalType()} so the staged payload can be deserialized by a process that never saw the
 * response. They are the price of staging, and they are worth it.
 *
 * @param <I> the local record type
 * @param <K> the correlation key that addresses a record in the external system
 * @param <O> the external record type
 */
public interface SyncTask<I, K, O> {

    /**
     * The task's name.
     *
     * @return a key under {@code ludwig.reconciliation.tasks}; must match this bean's
     *         {@link ReconciliationTask} value
     */
    String name();

    /**
     * Which local records need external state, and how to reload one.
     *
     * @return the demand provider
     */
    DemandProvider<I, K> demand();

    /**
     * How a local record is addressed in the external system.
     *
     * @return the correlation key of a local record
     */
    Function<I, K> localKey();

    /**
     * How a correlation key survives a round trip through the {@code correlation_key} column.
     *
     * @return the codec; {@code KeyCodec.ofString()}, {@code ofUuid()} and {@code ofLong()} cover
     *         almost every task
     */
    KeyCodec<K> keyCodec();

    /**
     * How external state is fetched. The shape chosen here must match the task's configured
     * {@code fetch.shape}; the startup validator refuses a context where they disagree, because the
     * engine would otherwise walk the stream in a way the fetcher was not written for.
     *
     * @return the fetcher
     */
    Fetcher<K, O> fetcher();

    /**
     * How external state is applied to a local record.
     *
     * @return the reconciler
     */
    Reconciler<I, O> reconciler();

    /**
     * The external record's runtime type, so a staged payload can be deserialized by a process that
     * did not fetch it.
     *
     * @return the class of {@code O}; must be deserializable by the module's object mapper
     */
    Class<O> externalType();

    /**
     * How recent an external record claims to be.
     *
     * <p>Defaults to no information, which is honest for a partner that publishes none - but a task
     * that can implement this gets the engine's generic stale-write protection for free, and that
     * protection is the difference between out-of-order responses being handled and being invisible.
     *
     * @param record the external record
     * @return its stamp
     */
    default ExternalStamp stampOf(O record) {
        return ExternalStamp.none();
    }
}
