package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import ru.ludwigandreas.jira.error.ErrorCollection;

/**
 * The result of a bulk create: partial success is normal and is not an error.
 *
 * <p>Jira's bulk endpoint answers 201 even when some of the issues failed, putting the failures in
 * {@code errors} keyed by the zero-based index of the input that produced them. A caller that only checks
 * the HTTP status loses issues silently, which is why {@link #hasFailures()} exists and why
 * {@link ru.ludwigandreas.jira.api.IssueApi#createAll} documents that it does not throw on a partial
 * failure.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BulkCreateResult(List<CreatedIssue> issues, List<BulkOperationError> errors) {

    /** Normalizes the two collections to immutable empty lists rather than {@code null}. */
    public BulkCreateResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** Whether any input in the batch failed. */
    public boolean hasFailures() {
        return !errors.isEmpty();
    }

    /**
     * One failed element of a bulk request.
     *
     * @param status HTTP status Jira would have returned for this element on its own
     * @param elementErrors the error collection for this element
     * @param failedElementNumber zero-based index into the request's input list
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BulkOperationError(Integer status,
                                     @com.fasterxml.jackson.annotation.JsonProperty("elementErrors")
                                     ErrorCollection elementErrors,
                                     Integer failedElementNumber) {
    }
}
