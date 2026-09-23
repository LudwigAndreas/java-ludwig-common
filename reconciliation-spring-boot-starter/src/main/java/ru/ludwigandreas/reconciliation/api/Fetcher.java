package ru.ludwigandreas.reconciliation.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How a task gets external state for a set of correlation keys.
 *
 * <h2>One abstraction, four walks</h2>
 *
 * <p>The unification this module is built on is to stop modelling "a call" and model <em>a stream of
 * external records addressed by a correlation key</em>. All four shapes below produce exactly that
 * and differ only in how the engine walks them:
 *
 * <pre>
 * Demand(Set&lt;K&gt;) --+- PerItem    -&gt; concurrent fan-out bounded by max-concurrency
 *                  +- Batched    -&gt; chunk(batch-size) -&gt; fan-out
 *                  +- Paged      -&gt; walk pages, checkpoint the cursor, join against demand
 *                  +- JobFetcher -&gt; submit / poll / collect across ticks
 *                                    |
 *                          Stream&lt;FetchOutcome&lt;K, O&gt;&gt;
 * </pre>
 *
 * <p>Sealed on purpose: the engine has to know every shape it can be asked to walk, and a fifth one
 * added from outside would silently fall through to no behaviour at all. Extension here is by
 * choosing a shape, not by inventing one - the shapes are the parts that are genuinely different
 * between integrations, and there are four of them.
 *
 * @param <K> correlation key type
 * @param <O> external record type
 */
public sealed interface Fetcher<K, O> {

    /**
     * One call per key. The engine fans out under a bounded concurrency window.
     *
     * <p>The right shape when the partner has no batch endpoint, and only then: N keys means N round
     * trips, and the concurrency bound is what stops a backlog from turning into a denial of service
     * against a partner that is already struggling.
     *
     * @param <K> correlation key type
     * @param <O> external record type
     */
    non-sealed interface PerItem<K, O> extends Fetcher<K, O> {

        /**
         * Fetches one record.
         *
         * @param key the correlation key
         * @return the record, or empty when the partner does not know this key - which is
         *         <em>not</em> an error; see {@link FetchOutcome.NotFound}
         */
        Optional<O> fetchOne(K key);
    }

    /**
     * N keys per call. The engine slices demand into chunks of the configured {@code batch-size} and
     * fans the chunks out.
     *
     * @param <K> correlation key type
     * @param <O> external record type
     */
    non-sealed interface Batched<K, O> extends Fetcher<K, O> {

        /**
         * Fetches a chunk.
         *
         * @param keys the keys in this chunk, never larger than {@code batch-size}
         * @return the records found, keyed by correlation key. <b>A key absent from the returned map
         *         means NOT FOUND, not an error.</b> Implementations must not throw, return null or
         *         substitute a placeholder for a key the partner did not know - the engine treats
         *         absence as a first-class outcome with its own policy and its own metric, and
         *         conflating it with failure is the single most common cause of runaway retry loops
         *         in this class of system
         */
        Map<K, O> fetchBatch(Collection<K> keys);
    }

    /**
     * A full or static pull the engine walks page by page, checkpointing the cursor as it goes and
     * joining the results against demand.
     *
     * <p>The right shape when the partner publishes a catalogue rather than answering questions about
     * individual keys, and when the catalogue is small enough - or incremental enough - to walk.
     *
     * @param <K> correlation key type
     * @param <O> external record type
     */
    non-sealed interface Paged<K, O> extends Fetcher<K, O> {

        /**
         * Fetches one page.
         *
         * @param cursor  the token from the previous page's {@link PageResult#nextCursor()}, or null
         *                to start at the beginning
         * @param request page size and, for an incremental task, the persisted watermark
         * @return the page and the token for the next one
         */
        PageResult<O> fetchPage(String cursor, PageRequest request);

        /**
         * Extracts the correlation key from a record the partner returned.
         *
         * <p>A paged pull arrives unaddressed - the partner sends records, not answers to questions -
         * so this is what lets the engine join a page back to the demand that asked for it.
         *
         * @param record a record from a page
         * @return its correlation key
         */
        K keyOf(O record);
    }

    /**
     * Submit, poll, collect: for a partner that answers a request with a job handle rather than with
     * data.
     *
     * <p>Unlike the other three, this shape spans scheduler ticks and process restarts, and it holds
     * a resource on the partner's side for its whole life. That is why it has persisted state, a
     * quota lease held across the job, and three independent schedulers - and why
     * {@code on-ambiguous-submit} is a required setting rather than a defaulted one.
     *
     * @param <K> correlation key type
     * @param <H> the partner's job handle type; must be representable as text, because it is stored
     * @param <O> external record type
     */
    non-sealed interface JobFetcher<K, H, O> extends Fetcher<K, O> {

        /**
         * Submits a job covering {@code keys}.
         *
         * <p>Called <em>after</em> the engine has committed a row carrying {@code idempotencyKey} and
         * acquired the quota lease. Implementations should pass the key to the partner whenever it
         * honours one: it is what turns an ambiguous resubmission into a no-op on their side.
         *
         * @param keys           the demand this job covers
         * @param idempotencyKey the key committed before this call
         * @return the partner's handle for the job
         */
        H submit(Collection<K> keys, IdempotencyKey idempotencyKey);

        /**
         * Asks the partner how a job is doing.
         *
         * @param handle the handle from {@link #submit}
         * @return the job's current status
         */
        JobStatus poll(H handle);

        /**
         * Collects one page of a finished job's result.
         *
         * <p>Deliberately returns a {@link PageResult} so that this shape reuses the paged walker and
         * its checkpointing rather than duplicating them - collecting a large result is exactly as
         * interruptible, and exactly as expensive to restart, as a paged sweep.
         *
         * @param handle the handle from {@link #submit}
         * @param cursor the previous page's cursor, or null to start
         * @return the page and the token for the next one
         */
        PageResult<O> collect(H handle, String cursor);

        /**
         * Extracts the correlation key from a collected record.
         *
         * <p>Same role as {@link Paged#keyOf}: a job's result is a catalogue that happened to be
         * produced on request, so it arrives unaddressed and has to be joined back to the demand that
         * asked for it.
         *
         * @param record a record from a collected page
         * @return its correlation key
         */
        K keyOf(O record);

        /**
         * Renders a handle for storage, and parses it back.
         *
         * <p>The job survives restarts, so the handle has to. For the common case of a string or
         * numeric job id this is {@code KeyCodec.ofString()} or {@code KeyCodec.ofLong()}; a
         * structured handle needs its own rendering.
         *
         * @return the codec for this partner's handles
         */
        KeyCodec<H> handleCodec();

        /**
         * Asks the partner to stop a job. Optional: the default does nothing, which is correct for a
         * partner with no cancellation endpoint.
         *
         * <p>Called when a job outlives its {@code max-lifetime}, and when an expired quota lease is
         * reclaimed under {@code reclaim: verify-remote}. Implementing it is what keeps a reclaimed
         * slot from corresponding to work that is still running and still costing money.
         *
         * @param handle the job to cancel
         */
        default void cancel(H handle) {
            // No cancellation endpoint by default; see the class comment.
        }

        /**
         * Lists the jobs the partner currently considers active for this integration.
         *
         * <p>This is the clean way out of an ambiguous submit: a row stuck in {@code PENDING_SUBMIT}
         * means the POST may or may not have been accepted, and a partner that can list its active
         * jobs lets the engine find out by matching on the idempotency key and adopting the handle,
         * instead of guessing. Returning empty means "this partner cannot tell me", which is different
         * from returning an empty list, which means "it told me there are none".
         *
         * @return the active jobs with the keys they were submitted under, or empty if the partner
         *         offers no such endpoint
         */
        default Optional<List<ActiveJob<H>>> listActive() {
            return Optional.empty();
        }
    }
}
