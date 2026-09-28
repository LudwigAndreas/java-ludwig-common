package ru.ludwigandreas.webcore.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.Builder;

/**
 * The platform's envelope for a long-running operation, on submission and on every poll.
 *
 * <h2>What this is, and what it deliberately is not</h2>
 *
 * <p>It is a <em>contract</em>, not a framework. There is no shared operation table behind it and
 * there is not going to be one: four modules of this platform already track runs in tables designed
 * for their own domain - an export run with its outputs and degraded stages, an ingest run with its
 * checkpoint and record counts, a remote job with a ten-state partner lifecycle, a notification
 * request with its fan-out - and a generic table would duplicate all four, add a write to every
 * transition and be worse at every one of their queries. Each module keeps its table and maps onto
 * this envelope at its edge, which is also what keeps {@code web-core} free of any persistence
 * dependency: it sits below {@code db-core} in practice, and a {@code web-core -> db-core} edge
 * would be a reactor cycle waiting for the first module that needs both.
 *
 * <h2>Why the shape is this shape</h2>
 *
 * <p>Derived from {@code reconciliation}'s sealed {@code JobStatus}, which was already the best
 * design in the repository on this subject: it carries a retry hint only on the state where one
 * makes sense and a failure reason only where a failure has one, so it is impossible to read a
 * failure reason off a succeeded operation. A record cannot enforce that the way a sealed interface
 * can, so the invariants it stood for are enforced where the response is built instead - see
 * {@link OperationResponses}, which refuses a terminal success with no {@link #result()} and a
 * non-terminal poll with no {@code Retry-After}.
 *
 * <p>A module publishes this <em>alongside</em> its own response rather than instead of it. Export's
 * run response keeps its degraded stages and its attempt count; notification's keeps its deliveries
 * and its duplicate flag. Those are the things their clients actually branch on, and the envelope is
 * what lets a client that does not care which module it is talking to poll all of them the same way.
 *
 * @param id            the operation, as a string because modules key runs differently - a UUID here,
 *                      a partner's opaque handle there - and a client only ever echoes it back
 * @param status        the common-core state
 * @param detail        the producer's own sub-state when it carries information {@link #status()}
 *                      cannot - {@code COLLECTING}, {@code PENDING_SUBMIT} - or null
 * @param progress      how far along it is, or null when the producer does not track progress
 * @param submittedAt   when it was accepted
 * @param startedAt     when it first started executing, or null while it is still {@code PENDING}
 * @param finishedAt    when it reached a terminal state, or null while it has not
 * @param result        where the result is, on a terminal success. Absent on a terminal success is a
 *                      bug in the producer, and {@link OperationResponses} refuses it
 * @param failure       why there is no result, on a terminal failure
 * @param correlationId the correlation id of the request that submitted it, so an operator can find
 *                      every log line the operation produced from the envelope a client is holding
 */
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationResponse(
        String id,
        OperationStatus status,
        String detail,
        OperationProgress progress,
        Instant submittedAt,
        Instant startedAt,
        Instant finishedAt,
        OperationResult result,
        OperationFailure failure,
        String correlationId) {

    public OperationResponse {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("OperationResponse.id is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("OperationResponse.status is required");
        }
    }

    /** Whether nothing further will happen to this operation. */
    public boolean isTerminal() {
        return status.isTerminal();
    }
}
