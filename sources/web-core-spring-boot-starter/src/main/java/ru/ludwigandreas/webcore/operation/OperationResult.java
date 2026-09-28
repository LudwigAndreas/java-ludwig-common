package ru.ludwigandreas.webcore.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Where the thing an operation produced can be fetched.
 *
 * <h2>One field on the envelope, not a list</h2>
 *
 * <p>{@link OperationResponse#result()} is a single result rather than a collection, because the
 * contract's invariant is "a terminal success has a result" and a collection makes that invariant
 * ambiguous: a client cannot tell an empty list meaning "none yet" from one meaning "none ever", and
 * a producer that forgot to set it looks exactly like one that had nothing to offer. An operation
 * that genuinely produces several artefacts - an export run asked for CSV and XLSX - names its
 * primary one here and lists the rest in {@link #alternatives()}, so there is always exactly one
 * answer to "where is it?".
 *
 * @param href         the URL the result is fetched from, absolute or relative to the API root
 * @param mediaType    what a {@code GET} of {@code href} returns, or null when the producer does not
 *                     know it in advance
 * @param sizeBytes    the size of the result, or null when it is not known without fetching it
 * @param alternatives other representations of the same result; empty for the overwhelming majority
 *                     of operations, which produce one thing
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationResult(String href, String mediaType, Long sizeBytes,
                              List<OperationResult> alternatives) {

    public OperationResult {
        if (href == null || href.isBlank()) {
            throw new IllegalArgumentException("OperationResult.href is required");
        }
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }

    /** A result that is just a link. */
    public static OperationResult at(String href) {
        return new OperationResult(href, null, null, List.of());
    }

    /** A result whose type and size are known. */
    public static OperationResult at(String href, String mediaType, Long sizeBytes) {
        return new OperationResult(href, mediaType, sizeBytes, List.of());
    }

    /** A copy of this result carrying the other representations of the same thing. */
    public OperationResult withAlternatives(List<OperationResult> others) {
        return new OperationResult(href, mediaType, sizeBytes, others);
    }
}
