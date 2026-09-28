package ru.ludwigandreas.jira.page;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Turns a page-at-a-time Jira endpoint into a lazy {@link Stream} over its rows.
 *
 * <p>This is what every {@code ...All(...)} method on the API classes is built from. Lazy on purpose: a JQL
 * query matching 200 000 issues must be consumable with a {@code filter} and a {@code findFirst} without
 * fetching 4 000 pages first, and a caller that stops early must stop making requests immediately.
 *
 * <p>Two safety properties are deliberate. A page that comes back empty ends the walk even if the endpoint
 * still claims more rows exist - otherwise a server that answers an out-of-range offset with an empty page
 * and {@code isLast: false}, which some Jira endpoints do, spins forever. And the next window is computed
 * from the rows actually returned rather than from the page size requested, so a Jira that capped
 * {@code maxResults} does not cause the walk to skip the rows it did not send.
 */
public final class Pages {

    private Pages() {
    }

    /**
     * Streams every row of a paginated endpoint.
     *
     * @param fetcher fetches one page for a given window
     * @param pageSize rows to request per page
     * @param <T> row type
     * @return a lazy, sequential stream over all rows
     */
    public static <T> Stream<T> stream(Function<PageRequest, Page<T>> fetcher, int pageSize) {
        Iterator<Page<T>> pages = pageIterator(fetcher, pageSize);
        Stream<Page<T>> pageStream = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(pages, Spliterator.ORDERED | Spliterator.NONNULL), false);
        return pageStream.flatMap(page -> page.values().stream());
    }

    /**
     * Streams the pages themselves, for callers that want to process a batch at a time - a bulk update, a
     * database write - rather than a row at a time.
     *
     * @param fetcher fetches one page for a given window
     * @param pageSize rows to request per page
     * @param <T> row type
     * @return a lazy, sequential stream over the pages
     */
    public static <T> Stream<Page<T>> streamPages(Function<PageRequest, Page<T>> fetcher, int pageSize) {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(
                        pageIterator(fetcher, pageSize), Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    /**
     * Collects every row into a list. Convenient, and a loaded gun on a large result set - prefer
     * {@link #stream} unless the bound is known.
     *
     * @param fetcher fetches one page for a given window
     * @param pageSize rows to request per page
     * @param <T> row type
     * @return every row, in page order
     */
    public static <T> List<T> all(Function<PageRequest, Page<T>> fetcher, int pageSize) {
        return stream(fetcher, pageSize).toList();
    }

    private static <T> Iterator<Page<T>> pageIterator(Function<PageRequest, Page<T>> fetcher, int pageSize) {
        return new Iterator<>() {

            private PageRequest next = PageRequest.ofSize(pageSize);
            private boolean exhausted;

            @Override
            public boolean hasNext() {
                return !exhausted;
            }

            @Override
            public Page<T> next() {
                if (exhausted) {
                    throw new NoSuchElementException("The paginated walk is already finished");
                }
                Page<T> page = fetcher.apply(next);
                if (page.values().isEmpty() || !page.hasMore()) {
                    exhausted = true;
                } else {
                    next = next.after(page);
                }
                return page;
            }
        };
    }
}
