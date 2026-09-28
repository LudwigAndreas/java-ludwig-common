package ru.ludwigandreas.webcore.integration;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.webcore.operation.Cancellation;
import ru.ludwigandreas.webcore.operation.OperationFailure;
import ru.ludwigandreas.webcore.operation.OperationLocationHeader;
import ru.ludwigandreas.webcore.operation.OperationNotFoundException;
import ru.ludwigandreas.webcore.operation.OperationProgress;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResponses;
import ru.ludwigandreas.webcore.operation.OperationResult;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * The smallest module that adopts the operation contract: submit, poll, cancel, in memory.
 *
 * <p>It exists so the contract is exercised through a real dispatch - the headers, the status codes
 * and the problem rendering - rather than only through the builders. The state machine is a
 * {@code Map} because nothing here is about storage; that is the point of the contract being a
 * contract.
 */
@RestController
public class TestOperationController {

    /** How long the fake poller says to wait, so the test can assert the exact header. */
    static final Duration POLL_INTERVAL = Duration.ofSeconds(3);

    private static final String BASE_PATH = "/test/operations";

    private static final String OPERATION_PATH = BASE_PATH + "/{id}";

    private final Map<String, OperationResponse> operations = new ConcurrentHashMap<>();

    private final Map<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();

    /**
     * Submits an operation.
     *
     * @param inline         when true the work "finishes" on the request thread, which is export's
     *                       synchronous fast path
     * @param idempotencyKey a repeated key returns the same operation id rather than starting a
     *                       second one
     * @return 202 with a Location, or 200 with a terminal envelope
     */
    @PostMapping(BASE_PATH)
    public ResponseEntity<OperationResponse> submit(
            @RequestHeader(name = "X-Inline", defaultValue = "false") boolean inline,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {

        if (idempotencyKey != null) {
            OperationResponse existing = operations.get(idempotencyKey);
            if (existing != null) {
                return OperationResponses.accepted(existing, statusUri(existing.id()),
                        OperationLocationHeader.LOCATION);
            }
        }

        String id = idempotencyKey == null ? String.valueOf(operations.size() + 1) : idempotencyKey;
        if (inline) {
            OperationResponse done = OperationResponse.builder()
                    .id(id)
                    .status(OperationStatus.SUCCEEDED)
                    .submittedAt(Instant.EPOCH)
                    .finishedAt(Instant.EPOCH)
                    .result(OperationResult.at(BASE_PATH + "/" + id + "/result"))
                    .build();
            operations.put(id, done);
            return OperationResponses.completed(done);
        }

        OperationResponse queued = OperationResponse.builder()
                .id(id)
                .status(OperationStatus.PENDING)
                .submittedAt(Instant.EPOCH)
                .progress(OperationProgress.of(0L, "rows"))
                .build();
        operations.put(id, queued);
        cancellations.put(id, new AtomicBoolean(false));
        return OperationResponses.accepted(queued, statusUri(id), OperationLocationHeader.LOCATION);
    }

    /**
     * Polls one.
     *
     * @param id the operation
     * @return the envelope, with Retry-After while it is still going
     */
    @GetMapping(OPERATION_PATH)
    public ResponseEntity<OperationResponse> poll(@PathVariable String id) {
        return OperationResponses.poll(require(id), POLL_INTERVAL);
    }

    /**
     * Asks one to stop.
     *
     * @param id the operation
     * @return 202, whatever state the operation is in
     */
    @DeleteMapping(OPERATION_PATH)
    public ResponseEntity<OperationResponse> cancel(@PathVariable String id) {
        OperationResponse operation = require(id);
        if (operation.isTerminal()) {
            return OperationResponses.cancellationRequested(operation);
        }
        cancellations.get(id).set(true);
        return OperationResponses.cancellationRequested(operation);
    }

    /** Runs the cooperative loop the way an engine would, and reports where it stopped. */
    long runUntilCancelled(String id, long rows) {
        Cancellation cancellation = () -> cancellations.get(id).get();
        long written = 0;
        while (written < rows && !cancellation.isCancelled()) {
            written++;
        }
        OperationResponse operation = operations.get(id);
        operations.put(id, operation.toBuilder()
                .status(cancellation.isCancelled() ? OperationStatus.CANCELLED
                        : OperationStatus.SUCCEEDED)
                .progress(OperationProgress.of(written, "rows"))
                .finishedAt(Instant.EPOCH)
                .result(cancellation.isCancelled() ? null
                        : OperationResult.at(BASE_PATH + "/" + id + "/result"))
                .build());
        return written;
    }

    /** Marks one failed, so the terminal-failure half of the contract is exercised too. */
    void fail(String id, String code) {
        OperationResponse operation = operations.get(id);
        operations.put(id, operation.toBuilder()
                .status(OperationStatus.FAILED)
                .failure(OperationFailure.of(code))
                .finishedAt(Instant.EPOCH)
                .build());
    }

    private OperationResponse require(String id) {
        OperationResponse operation = operations.get(id);
        if (operation == null) {
            throw new OperationNotFoundException(id);
        }
        return operation;
    }

    private URI statusUri(String id) {
        return URI.create(OPERATION_PATH.replace("{id}", id));
    }
}
