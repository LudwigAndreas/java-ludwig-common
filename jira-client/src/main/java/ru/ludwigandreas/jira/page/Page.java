package ru.ludwigandreas.jira.page;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * One page of a paginated Jira response.
 *
 * <p>Jira Server is not consistent about how it says a walk is finished. Some endpoints send
 * {@code isLast}, some send {@code total} and no {@code isLast}, and some send neither - and {@code total}
 * is {@code -1} on issue search when the caller asked Jira not to compute it.
 * {@link #hasMore()} folds all three cases into one answer, preferring the most reliable signal available,
 * so callers never have to reimplement that ladder.
 *
 * @param startAt zero-based index of the first row in this page
 * @param maxResults page size Jira actually applied, which may be smaller than the one requested
 * @param total total number of rows, when the endpoint computes one
 * @param isLast whether this is the final page, when the endpoint says
 * @param values the rows
 * @param <T> row type
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Page<T>(int startAt, int maxResults, Integer total, Boolean isLast, List<T> values) {

    /** Normalizes {@code values} to an immutable empty list rather than {@code null}. */
    public Page {
        values = values == null ? List.of() : List.copyOf(values);
    }

    /** An empty final page, for endpoints that answer 204 rather than an empty collection. */
    public static <T> Page<T> empty() {
        return new Page<>(0, 0, 0, Boolean.TRUE, List.of());
    }

    /** The total row count, absent when Jira did not compute one or reported it as {@code -1}. */
    public Optional<Integer> totalCount() {
        return total == null || total < 0 ? Optional.empty() : Optional.of(total);
    }

    /**
     * Whether another page is worth requesting.
     *
     * <p>In order of reliability: an explicit {@code isLast}; otherwise a known {@code total} compared
     * against the rows seen so far; otherwise the heuristic that a full page probably has a successor. The
     * heuristic costs one extra empty request at the end of a walk, which is the right trade against
     * silently truncating a result set.
     */
    public boolean hasMore() {
        if (isLast != null) {
            return !isLast;
        }
        if (total != null && total >= 0) {
            return startAt + values.size() < total;
        }
        return !values.isEmpty() && values.size() >= maxResults;
    }

    /** This page with every row mapped, preserving the pagination metadata. */
    public <R> Page<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = values.stream().<R>map(mapper).toList();
        return new Page<>(startAt, maxResults, total, isLast, mapped);
    }
}
