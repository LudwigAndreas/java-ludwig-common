package ru.ludwigandreas.jira.model.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import ru.ludwigandreas.jira.model.field.FieldSchema;
import ru.ludwigandreas.jira.model.issue.Issue;
import ru.ludwigandreas.jira.page.Page;

/**
 * One page of JQL search results.
 *
 * <p>{@code total} is {@code -1} when the search was run with {@code validateQuery} disabled or against an
 * instance that declined to count, so treat a negative total as "unknown" rather than as zero - which is
 * what {@link Page#totalCount()} does after {@link #toPage()}.
 *
 * <p>{@code warningMessages} carries JQL warnings that did not stop the search - an unknown field name in a
 * clause Jira could still evaluate, a project the caller cannot see. They are easy to ignore and usually
 * explain a result set that is mysteriously smaller than expected.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchResult(String expand,
                           int startAt,
                           int maxResults,
                           int total,
                           List<Issue> issues,
                           Map<String, String> names,
                           Map<String, FieldSchema> schema,
                           List<String> warningMessages) {

    /** Normalizes the optional collections to immutable empty ones rather than {@code null}. */
    public SearchResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
        names = names == null ? Map.of() : Map.copyOf(names);
        schema = schema == null ? Map.of() : Map.copyOf(schema);
        warningMessages = warningMessages == null ? List.of() : List.copyOf(warningMessages);
    }

    /** This result as a generic {@link Page}, so it can be walked by {@link ru.ludwigandreas.jira.page.Pages}. */
    public Page<Issue> toPage() {
        return new Page<>(startAt, maxResults, total, null, issues);
    }
}
