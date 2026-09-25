package ru.ludwigandreas.export.api;

import java.util.Set;
import java.util.stream.Stream;

/**
 * Where a report's base rows come from: a lazy, keyset-paginated stream the engine walks once.
 *
 * <h2>Keyset, never offset</h2>
 *
 * <p>An implementation pages with
 * {@code WHERE (sort_key, id) > (:lastSortKey, :lastId) ORDER BY sort_key, id LIMIT :page}, not with
 * {@code OFFSET}. This is not a preference. {@code OFFSET n} makes the database read and discard
 * {@code n} rows on every page, so walking a million rows in pages of a thousand costs on the order
 * of five hundred million discarded reads, and the last pages of the report are the slowest. Keyset
 * pages cost the same as each other. The second reason matters more: {@code OFFSET} is not stable
 * under concurrent writes, so a row inserted while the report runs shifts the window and a row that
 * existed for the whole run is silently never written.
 *
 * <p>The corollary is that the order has to be total, which is why the source appends its own
 * primary key to whatever {@link SourceContext#sort()} asked for rather than trusting it to be
 * unique.
 *
 * <h2>The stream is lazy and the engine closes it</h2>
 *
 * <p>{@link #open} must return before the first page has been fully read, and the stream must fetch
 * subsequent pages as it is consumed. An implementation that collects into a list and streams the
 * list compiles, passes a small test, and exhausts the heap on the first real run - it is the single
 * most likely way this interface is implemented wrongly, which is why it is stated here rather than
 * in a README.
 *
 * <p>A JPA-backed implementation must clear the {@code EntityManager} between pages. Otherwise the
 * persistence context accumulates every entity the run has touched and becomes the leak that the
 * streaming was meant to prevent.
 *
 * <p>The engine closes the stream on every exit path, including cancellation and failure, so an
 * implementation should register its cleanup with {@link Stream#onClose} rather than relying on
 * exhaustion.
 *
 * @param <P> the definition's parameter type
 * @param <R> the row type
 */
public interface RowSource<P, R> {

    /** Returned by {@link #estimateRows} when the source has no cheap way to tell. */
    long UNKNOWN_ROW_COUNT = -1L;

    /**
     * Opens the stream.
     *
     * @param context the run's parameters, scope predicate, order and page size
     * @return a lazy stream of rows in a total order, to be closed by the caller
     */
    Stream<R> open(SourceContext<P> context);

    /**
     * The column ids this source can order by.
     *
     * <p>Checked at startup against the definition's columns, and at request time against what the
     * requester asked for, so that an unsortable column produces a localized 400 naming the sortable
     * ones rather than a query that quietly ignores the request. A column can be displayed without
     * being sortable - an enriched column is the usual case, since the partner's data is not in the
     * database the order is taken from.
     *
     * @return the sortable column ids; empty means the source imposes its own fixed order
     */
    Set<String> sortableColumns();

    /**
     * Roughly how many rows this run will produce, for the synchronous/asynchronous decision.
     *
     * <p>Deliberately allowed to be an estimate and allowed to decline. The decision it feeds - run
     * on the request thread or hand to the poller - is a scheduling hint, and the limits that
     * actually protect the service (the row cap and the wall-clock budget) are enforced during the
     * run against real counts. An implementation that cannot answer cheaply should return
     * {@link #UNKNOWN_ROW_COUNT} rather than running a {@code COUNT(*)} over the same predicate the
     * report is about to walk: paying for a full scan to decide how to schedule a full scan doubles
     * the cost of every report.
     *
     * @param context the same context {@link #open} will be given
     * @return the estimate, or {@link #UNKNOWN_ROW_COUNT}, which the engine treats as "large"
     */
    default long estimateRows(SourceContext<P> context) {
        return UNKNOWN_ROW_COUNT;
    }
}
