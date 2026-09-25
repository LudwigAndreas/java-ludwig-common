package ru.ludwigandreas.storage.exception;

import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The store understood the request, resolved a principal, and says that principal may not do this.
 *
 * <p>Separated from the generic failure because it is the one storage error that is never transient:
 * retrying it wastes the caller's whole backoff budget on a request that will be refused identically
 * every time, and the fix is a bucket policy or an IAM role rather than a wait. A caller that can
 * distinguish it can fail fast and say something an operator can act on.
 *
 * <p>Renders as 502 rather than 403. A 403 from this service would tell the caller that
 * <em>their</em> credentials were insufficient, which is wrong and sends them to the wrong team; the
 * principal that was refused is this service's own.
 */
public class ObjectStoreAccessDeniedException extends ObjectStoreException {

    private static final long serialVersionUID = 1L;

    /**
     * A refused operation.
     *
     * @param operation what was being attempted
     * @param uri       the location it was attempted against
     * @param cause     what the store threw
     */
    public ObjectStoreAccessDeniedException(String operation, String uri, Throwable cause) {
        super(ProblemStatus.BAD_GATEWAY, StorageProblemCodes.ACCESS_DENIED, cause, operation, uri);
        withProperty("operation", operation);
        withProperty("uri", uri);
    }
}
