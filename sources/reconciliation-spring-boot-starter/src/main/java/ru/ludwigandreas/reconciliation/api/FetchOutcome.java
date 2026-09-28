package ru.ludwigandreas.reconciliation.api;

/**
 * What came back for one correlation key, whichever shape fetched it.
 *
 * <p>The three cases are what every shape is reduced to before staging, and the separation of
 * {@link NotFound} from {@link Failed} is the most important distinction in this module.
 *
 * <h2>Why "the partner does not know this id" is not an error</h2>
 *
 * <p>Because it is usually permanent, and errors are retried. A key the partner has never heard of -
 * a record deleted on their side, an id that was mistyped into our system years ago, a test row -
 * will still be unknown on the next attempt, and on every attempt after that. Classifying it as a
 * failure produces a record that retries forever, exhausts its budget, quarantines itself, and
 * generates an alert per record; multiplied across a backlog, it is the single most common cause of
 * runaway retry loops in this class of system. It gets its own per-task policy
 * ({@code ignore | mark-missing | fail}) and its own metric so that "the partner has forgotten 4,000
 * of our ids" is a number an operator can see rather than a wave of failures.
 *
 * @param <K> correlation key type
 * @param <O> external record type
 */
public sealed interface FetchOutcome<K, O> {

    /**
     * The key this outcome is about.
     *
     * @return the correlation key
     */
    K key();

    /**
     * The partner returned a record.
     *
     * @param <K>    correlation key type
     * @param <O>    external record type
     * @param key    the correlation key
     * @param record the external record
     */
    record Found<K, O>(K key, O record) implements FetchOutcome<K, O> {
    }

    /**
     * The partner answered, and does not know this key.
     *
     * @param <K> correlation key type
     * @param <O> external record type
     * @param key the correlation key
     */
    record NotFound<K, O>(K key) implements FetchOutcome<K, O> {
    }

    /**
     * The fetch did not complete.
     *
     * @param <K>       correlation key type
     * @param <O>       external record type
     * @param key       the correlation key
     * @param reason    what went wrong, recorded on the staged row and in the audit trail
     * @param retryable whether trying again could plausibly succeed. A timeout is retryable; a 400
     *                  saying the request is malformed is not, and retrying it eight times only
     *                  delays the moment a human finds out
     */
    record Failed<K, O>(K key, String reason, boolean retryable) implements FetchOutcome<K, O> {
    }

    /** The partner returned a record for this key. */
    static <K, O> FetchOutcome<K, O> found(K key, O record) {
        return new Found<>(key, record);
    }

    /** The partner does not know this key. */
    static <K, O> FetchOutcome<K, O> notFound(K key) {
        return new NotFound<>(key);
    }

    /** The fetch failed; trying again may work. */
    static <K, O> FetchOutcome<K, O> failed(K key, String reason) {
        return new Failed<>(key, reason, true);
    }

    /** The fetch failed and will keep failing. */
    static <K, O> FetchOutcome<K, O> permanentlyFailed(K key, String reason) {
        return new Failed<>(key, reason, false);
    }
}
