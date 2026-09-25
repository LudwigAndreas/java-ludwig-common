package ru.ludwigandreas.export.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * How a stage gets data another service owns, for the keys in one window of rows.
 *
 * <h2>One abstraction, three walks</h2>
 *
 * <p>The engine never models "a call". It models <em>values addressed by a key</em>, and the three
 * shapes below differ only in how the engine walks them:
 *
 * <pre>
 * window(N) -&gt; distinct keys --+- Batched   -&gt; chunk(batch-size) -&gt; bounded fan-out
 *                              +- PerItem   -&gt; bounded fan-out, one call per key
 *                              +- Dimension -&gt; fetched once per run, then a map lookup
 *                                                |
 *                                       Map&lt;K, V&gt; joined onto the window
 * </pre>
 *
 * <p>This is deliberately the same idea as {@code reconciliation}'s {@code Fetcher}, applied to the
 * other direction of flow: there, external state is pulled in to correct local records; here, local
 * records are decorated with external state on the way out. The shapes are fewer because a report
 * window is bounded and short-lived - there is no equivalent of {@code JobFetcher}, because a
 * partner that answers with a job handle cannot be waited on inside a window without holding the
 * whole pipeline, and a report that needs one should be built on a materialised projection instead.
 *
 * <p>Sealed on purpose, for the reason {@code Fetcher} is sealed: the engine has to know every shape
 * it can be asked to walk, and a fourth one added from outside would fall through to no behaviour at
 * all. Extension here is by choosing a shape, not by inventing one.
 *
 * <h2>Where the HTTP call goes</h2>
 *
 * <p>Nowhere near an implementation of this interface. Every partner call is made through a named
 * {@code @LudwigRestClient}, so it inherits that client's connection pool, timeouts, authentication,
 * retry, circuit breaker, bulkhead, metrics and audit. An enricher that opens its own client gets
 * none of those, and one slow partner then starves every report on the instance.
 *
 * @param <K> the key this stage looks partner data up by
 * @param <V> the value the partner returns for a key
 */
public sealed interface Enricher<K, V> {

    /**
     * N keys per call. The engine slices a window's distinct keys into chunks of the stage's
     * {@code batch-size} and fans the chunks out under a bounded concurrency window.
     *
     * <p>The shape to prefer whenever the partner offers it. At the design point - a million rows,
     * four partners - the difference between this and {@link PerItem} is the difference between
     * thousands of round trips and millions.
     *
     * @param <K> key type
     * @param <V> value type
     */
    non-sealed interface Batched<K, V> extends Enricher<K, V> {

        /**
         * Fetches a chunk.
         *
         * @param keys the keys in this chunk, never larger than the stage's configured batch size
         * @return the values found, keyed by key. <b>A key absent from the returned map means NOT
         *         FOUND, not an error.</b> Implementations must not throw, must not return null and
         *         must not substitute a fabricated value for a key the partner did not know: the
         *         engine treats absence as a first-class outcome with its own policy
         *         ({@link MissingPolicy}) and its own counter, and a fabricated value is a wrong
         *         number in a document somebody will make a decision on
         */
        Map<K, V> fetchBatch(Collection<K> keys);
    }

    /**
     * One call per key, fanned out under the stage's concurrency bound.
     *
     * <p>The right shape when the partner has no batch endpoint, and only then. The concurrency
     * bound is what stops a large report from becoming a denial-of-service attack on a partner that
     * is sized for interactive traffic.
     *
     * @param <K> key type
     * @param <V> value type
     */
    non-sealed interface PerItem<K, V> extends Enricher<K, V> {

        /**
         * Fetches one value.
         *
         * @param key the key
         * @return the value, or empty when the partner does not know this key - which is
         *         <em>not</em> an error; see {@link MissingPolicy}
         */
        Optional<V> fetchOne(K key);
    }

    /**
     * A whole small catalogue, fetched once for the run and then consulted as a map.
     *
     * <p>The right shape for a reference table - currencies, warehouses, statuses - where the
     * catalogue is smaller than the distinct keys in the report and does not change during a run.
     * One call replaces a call per window, which at the design point is five hundred of them.
     *
     * <p>The size bound is not advisory. The engine refuses a catalogue larger than the stage's
     * declared maximum and fails the run naming the stage and both numbers, because the failure
     * this prevents - a "reference table" that has grown to a million rows quietly consuming the
     * heap the engine reserved for windows - presents as an out-of-memory error with no attribution
     * to the stage that caused it.
     *
     * @param <K> key type
     * @param <V> value type
     */
    non-sealed interface Dimension<K, V> extends Enricher<K, V> {

        /**
         * Fetches the whole catalogue.
         *
         * <p>Called once per run, before the first window. Implementations may page internally; what
         * they may not do is return a lazy view that calls the partner as the engine reads it, since
         * the engine holds this map for the life of the run and a lazy view would turn a map lookup
         * per row back into a call per row.
         *
         * @return every key the partner knows for this stage
         */
        Map<K, V> fetchAll();
    }
}
