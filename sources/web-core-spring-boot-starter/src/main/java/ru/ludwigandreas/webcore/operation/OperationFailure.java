package ru.ludwigandreas.webcore.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Why a terminal operation has no result.
 *
 * <h2>A code, not a sentence</h2>
 *
 * <p>The code is the same kind of value a {@code ProblemDetail} carries in its {@code code} member -
 * a message-bundle key, published and therefore part of the API contract, which a client may branch
 * on. {@code detail} is the already-localized sentence for a human, and a client must not parse it:
 * it is translated, and the translation is allowed to change.
 *
 * <p>There is deliberately no exception message and no stack trace here. An unmapped exception's
 * message was written for whoever operates the service and is as likely to contain a hostname, a
 * recipient address or a SQL fragment as anything useful - the same reason {@code web-core}'s
 * problem pipeline never renders one. What ties this back to the log line that does have the detail
 * is {@link OperationResponse#correlationId()}.
 *
 * @param code   the message-bundle key identifying the failure, published as part of the contract
 * @param detail the localized sentence, or null when the producer has only a code
 * @param args   message-format arguments behind {@code detail}, so a client that renders the code
 *               itself can produce the same sentence. Strings, because they cross a wire
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationFailure(String code, String detail, List<String> args) {

    public OperationFailure {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("OperationFailure.code is required");
        }
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** A failure that is only a code, which is the honest shape when nothing has been localized yet. */
    public static OperationFailure of(String code) {
        return new OperationFailure(code, null, List.of());
    }

    /** A failure with its arguments, for a client that renders the code in its own locale. */
    public static OperationFailure of(String code, List<String> args) {
        return new OperationFailure(code, null, args);
    }
}
