package ru.ludwigandreas.jira.model.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Jira's {@code {"size": n, "items": [...]}} envelope, used for the truncated collections it embeds inside a
 * larger object - a user's groups, an issue's comments when returned as part of the issue.
 *
 * <p>{@code size} is the true count and {@code items} may hold fewer, because Jira caps embedded
 * collections. When {@link #isTruncated()} says so, re-read the collection from its own endpoint rather than
 * trusting what was embedded - that is the single most common source of "the integration only sees the
 * first 20 comments" bug reports.
 *
 * @param size total number of elements Jira holds
 * @param maxResults cap Jira applied when embedding, when it says
 * @param items the elements actually returned
 * @param <T> element type
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemList<T>(Integer size, Integer maxResults, List<T> items) {

    /** Normalizes {@code items} to an immutable empty list rather than {@code null}. */
    public ItemList {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Whether Jira held back elements that this envelope does not carry. */
    public boolean isTruncated() {
        return size != null && size > items.size();
    }
}
