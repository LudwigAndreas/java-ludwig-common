package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * An issue's change history, as returned by {@code expand=changelog}.
 *
 * <p>Jira Server truncates an expanded changelog at the instance's embedded-collection limit, and unlike
 * Jira Cloud it has no separate paginated changelog endpoint on 9.12 - so {@code total} greater than the
 * number of histories returned means history is missing and cannot be paged for. For issues with very long
 * histories, {@code expand=changelog} is not a complete source.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Changelog(Integer startAt, Integer maxResults, Integer total, List<ChangeHistory> histories) {

    /** Normalizes {@code histories} to an immutable empty list rather than {@code null}. */
    public Changelog {
        histories = histories == null ? List.of() : List.copyOf(histories);
    }

    /** Whether Jira held back history entries this object does not carry. */
    public boolean isTruncated() {
        return total != null && total > histories.size();
    }
}
