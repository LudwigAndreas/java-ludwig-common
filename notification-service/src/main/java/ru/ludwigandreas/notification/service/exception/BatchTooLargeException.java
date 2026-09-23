package ru.ludwigandreas.notification.service.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A batch submission larger than this deployment accepts.
 *
 * <p>A typed exception rather than a bare 413 from a container or proxy limit, because the caller
 * needs both numbers to act on it: a translated "send at most 100 at a time, you sent 340" is
 * actionable, and a payload-size rejection from an ingress is not - it does not even name the right
 * unit. The two numbers are also published as machine-readable properties so a client can resize its
 * batches automatically rather than by reading prose.
 *
 * <p>Lives with the other exceptions rather than beside the controller that raises it: the
 * architecture rules keep every failure this service reports in one package, which is how a reviewer
 * can see the whole error surface in one listing.
 */
public class BatchTooLargeException extends LocalizedException {

    public BatchTooLargeException(int submitted, int limit) {
        super(ProblemStatus.PAYLOAD_TOO_LARGE, "error.notification.batch.too-large", submitted, limit);
        withProperty("submitted", submitted);
        withProperty("limit", limit);
    }
}
