package ru.ludwigandreas.jira.error;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Jira's standard error payload: {@code {"errorMessages": [...], "errors": {"field": "message"}}}.
 *
 * <p>Both halves are always present in the model even when the server omits one, so callers never have to
 * null-check before iterating.
 *
 * @param errorMessages global messages not attached to any particular field
 * @param errors per-field messages, keyed by field id
 * @param status optional numeric status some endpoints repeat inside the body
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErrorCollection(List<String> errorMessages, Map<String, String> errors, Integer status)
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Normalizes the two collections to empty rather than {@code null}. */
    public ErrorCollection {
        errorMessages = errorMessages == null ? List.of() : List.copyOf(errorMessages);
        errors = errors == null ? Map.of() : Map.copyOf(errors);
    }

    /** An error collection carrying nothing, used when the body was not a Jira error payload. */
    public static ErrorCollection empty() {
        return new ErrorCollection(List.of(), Map.of(), null);
    }

    /** True when the server reported neither a global message nor a field message. */
    public boolean isEmpty() {
        return errorMessages.isEmpty() && errors.isEmpty();
    }

    /** One-line rendering of every message, global first, used to build exception messages. */
    public String describe() {
        return Stream.concat(
                        errorMessages.stream(),
                        errors.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()))
                .collect(Collectors.joining("; "));
    }
}
