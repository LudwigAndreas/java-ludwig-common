package ru.ludwigandreas.idempotency.error;

import java.time.Duration;
import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The key is held by a request that has not finished.
 *
 * <p>A 409 rather than a 202 or a 425. The caller's retry has not been accepted - nothing new was
 * queued by it - and it has not been answered either, because the answer is being computed by
 * somebody else right now. 409 with {@code Retry-After} says exactly that and is the only one of the
 * three a client library will treat as "try again shortly" without also treating it as success.
 *
 * <p>{@link #getRetryAfter()} is derived from the holder's lease rather than from a constant: the
 * honest earliest moment to retry is when the holder would have lost its lease if it died, because
 * until then either the work finishes and the retry gets a replay, or it does not and the key becomes
 * claimable.
 */
@Getter
public class ClaimInProgressException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /** How long the caller should wait before retrying. */
    private final transient Duration retryAfter;

    /**
     * Creates the exception.
     *
     * @param key        the key that is in flight, echoed to the caller so a client holding several
     *                   knows which one collided
     * @param retryAfter how long to wait
     */
    public ClaimInProgressException(String key, Duration retryAfter) {
        super(ProblemStatus.CONFLICT, IdempotencyProblemCodes.IN_PROGRESS, key);
        this.retryAfter = retryAfter;
        withProperty("idempotencyKey", key);
        withProperty("retryAfterSeconds", retryAfter.toSeconds());
    }
}
