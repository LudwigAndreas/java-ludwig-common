package ru.ludwigandreas.webcore.operation;

import java.net.URI;
import java.time.Duration;
import org.springframework.http.ResponseEntity;

/**
 * Builds the {@code ResponseEntity}s a long-running operation answers with, so that no module
 * hand-rolls {@code accepted().header(...)} again.
 *
 * <h2>The helpers enforce the contract rather than merely shortening it</h2>
 *
 * <p>Each method refuses the responses that are wrong, and each refusal is one that was actually
 * shipped somewhere in this platform before the contract existed:
 *
 * <ul>
 *   <li>a {@code 202} with no header pointing at a status resource - export did exactly this, so a
 *       client was told to poll and not told where;</li>
 *   <li>a non-terminal poll with no {@code Retry-After} - no REST surface in the repository emitted
 *       one, so every client invented its own interval and they all picked one second;</li>
 *   <li>a terminal success with no {@link OperationResult} - a client is told the work is done and
 *       given nothing to fetch;</li>
 *   <li>a terminal failure with no {@link OperationFailure} - the same, with nothing to report.</li>
 * </ul>
 *
 * <p>They throw {@link IllegalArgumentException} rather than quietly producing the broken response.
 * Each of these is a bug in the producer, not a runtime condition, and it fails on the first call in
 * the first test rather than in a client's integration six weeks later.
 *
 * <p>That list is the whole list, and it is kept short on purpose. One check has already been removed
 * for being wrong rather than merely inconvenient - see {@link #accepted}, which no longer insists
 * that a {@code 202} be non-terminal - and a helper that refused a shape a module had a good reason
 * for would simply be worked around, taking the four checks that matter with it.
 *
 * <h2>Two bodies</h2>
 *
 * <p>Every method comes in two forms: one whose body <em>is</em> the envelope, and one that takes
 * the envelope for the contract checks and a body of the module's own type. The second is the one
 * export and notification use, because both publish a richer response that embeds the envelope; the
 * checks are made against the envelope either way, so a module cannot opt out of them by publishing
 * its own shape.
 */
public final class OperationResponses {

    private OperationResponses() {
    }

    /**
     * {@code 202 Accepted}, with a header pointing at its status resource.
     *
     * <h2>A 202 may carry a terminal envelope, and that is deliberate</h2>
     *
     * <p>An earlier version of this method refused one, on the reasoning that a {@code 202} for
     * something already finished tells a client to poll for nothing. Notification showed that to be
     * too strong: it fans a request out in the same transaction that accepts it, so the request
     * operation is finished by the time the response is written - and it answers {@code 202} anyway,
     * because what it has created is a queued intention and whether anything reaches anybody is
     * decided minutes later by a provider it does not control. A {@code 200} there would be a
     * different promise, and a {@code 201} a false one.
     *
     * <p>The invariant that <em>is</em> load-bearing is the one below: a {@code 202} names a status
     * resource, and the caller chose which header names it. The failure this contract exists to stop
     * is a {@code 202} with nowhere to go, not a client that polls once and finds the work done.
     * A terminal envelope still has to carry its evidence, so a {@code 202} saying {@code SUCCEEDED}
     * with nothing to fetch is still refused.
     *
     * @param operation      the envelope
     * @param statusUri      where to poll
     * @param locationHeader which header carries {@code statusUri} - see
     *                       {@link OperationLocationHeader}, which has no default on purpose
     * @return the response
     */
    public static ResponseEntity<OperationResponse> accepted(OperationResponse operation,
                                                             URI statusUri,
                                                             OperationLocationHeader locationHeader) {
        return accepted(operation, operation, statusUri, locationHeader);
    }

    /**
     * {@code 202 Accepted} with the module's own body.
     *
     * @param operation      the envelope the contract is checked against
     * @param body           what is actually written, which must embed {@code operation}
     * @param statusUri      where to poll
     * @param locationHeader which header carries {@code statusUri}
     * @param <T>            the module's response type
     * @return the response
     */
    public static <T> ResponseEntity<T> accepted(OperationResponse operation, T body, URI statusUri,
                                                 OperationLocationHeader locationHeader) {
        require(operation != null, "operation is required");
        require(body != null, "body is required");
        require(statusUri != null, "a 202 must point at a status resource");
        require(locationHeader != null, "choose Location or Operation-Location explicitly");
        if (operation.isTerminal()) {
            requireTerminalEvidence(operation);
        }
        return ResponseEntity.accepted()
                .header(locationHeader.headerName(), statusUri.toString())
                .body(body);
    }

    /**
     * {@code 200 OK} for work that finished on the request thread.
     *
     * <p>The synchronous fast path, which the contract permits deliberately: export answers
     * {@code 200} when a run completed inline and {@code 202} when it was queued, and a client
     * should not poll for something already done. What the contract does require is that a
     * {@code 200} from a submit endpoint carries a <em>terminal</em> envelope, so the two cases are
     * distinguishable without parsing the body.
     *
     * @param operation the envelope, which must be terminal
     * @return the response
     */
    public static ResponseEntity<OperationResponse> completed(OperationResponse operation) {
        return completed(operation, operation);
    }

    /**
     * {@code 200 OK} for inline completion, with the module's own body.
     *
     * @param operation the envelope the contract is checked against
     * @param body      what is actually written
     * @param <T>       the module's response type
     * @return the response
     */
    public static <T> ResponseEntity<T> completed(OperationResponse operation, T body) {
        require(operation != null, "operation is required");
        require(body != null, "body is required");
        require(operation.isTerminal(),
                "a 200 from a submit endpoint must carry a terminal envelope; use accepted() for"
                        + " work that was queued");
        requireTerminalEvidence(operation);
        return ResponseEntity.ok(body);
    }

    /**
     * {@code 200 OK} for a poll of the status resource.
     *
     * @param operation  the envelope
     * @param retryAfter how long to wait before polling again. Required while the operation is
     *                   non-terminal and ignored once it is terminal - a {@code Retry-After} on a
     *                   finished operation tells a client to come back for a state that will never
     *                   change again
     * @return the response
     */
    public static ResponseEntity<OperationResponse> poll(OperationResponse operation,
                                                         Duration retryAfter) {
        return poll(operation, operation, retryAfter);
    }

    /**
     * {@code 200 OK} for a poll, with the module's own body.
     *
     * @param operation  the envelope the contract is checked against
     * @param body       what is actually written
     * @param retryAfter how long to wait before polling again, required while non-terminal
     * @param <T>        the module's response type
     * @return the response
     */
    public static <T> ResponseEntity<T> poll(OperationResponse operation, T body,
                                             Duration retryAfter) {
        require(operation != null, "operation is required");
        require(body != null, "body is required");
        if (operation.isTerminal()) {
            requireTerminalEvidence(operation);
            return ResponseEntity.ok(body);
        }
        require(retryAfter != null && !retryAfter.isNegative() && !retryAfter.isZero(),
                "a non-terminal poll must carry a positive Retry-After; without one every client"
                        + " invents its own interval and they all pick one second");
        return ResponseEntity.ok()
                .header(OperationHeaders.RETRY_AFTER, String.valueOf(retryAfter.toSeconds()))
                .body(body);
    }

    /**
     * {@code 202 Accepted} for a cancellation request.
     *
     * <h2>202, not 204</h2>
     *
     * <p>Cancellation on this platform is cooperative - see {@link Cancellation} - so all a cancel
     * endpoint can truthfully report is that the request was recorded; the operation stops when it
     * next checks, on whichever instance is running it. A {@code 204} claims the work has stopped,
     * which is a lie that clients build retry logic on top of.
     *
     * <p>Cancelling an operation that has already finished is deliberately <em>not</em> an error.
     * A {@code 409} there invites a retry loop over something that will never change; this returns
     * the current envelope with its terminal state, which is both true and actionable.
     *
     * @param operation the envelope as it now stands, terminal or not
     * @return the response
     */
    public static ResponseEntity<OperationResponse> cancellationRequested(
            OperationResponse operation) {
        return cancellationRequested(operation, operation);
    }

    /**
     * {@code 202 Accepted} for a cancellation request, with the module's own body.
     *
     * @param operation the envelope the contract is checked against
     * @param body      what is actually written
     * @param <T>       the module's response type
     * @return the response
     */
    public static <T> ResponseEntity<T> cancellationRequested(OperationResponse operation, T body) {
        require(operation != null, "operation is required");
        require(body != null, "body is required");
        return ResponseEntity.accepted().body(body);
    }

    /**
     * A terminal operation says where its result is, or why there is none.
     *
     * <p>Never neither. An envelope that is finished, has no link and has no failure is the one
     * shape a client cannot do anything with at all: it cannot fetch, it cannot report, and it
     * cannot decide whether to retry.
     */
    private static void requireTerminalEvidence(OperationResponse operation) {
        if (operation.status() == OperationStatus.SUCCEEDED) {
            require(operation.result() != null,
                    "a SUCCEEDED operation must carry a result link");
            return;
        }
        if (operation.status().isFailure()) {
            require(operation.failure() != null,
                    "a FAILED operation must carry a failure code");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
