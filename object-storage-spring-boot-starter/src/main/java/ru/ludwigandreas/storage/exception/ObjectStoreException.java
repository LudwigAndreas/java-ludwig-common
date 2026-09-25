package ru.ludwigandreas.storage.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The store could not do what was asked, for a reason that is not "it is not there".
 *
 * <p>A {@code LocalizedException} rather than a plain runtime exception, and a 503 rather than a
 * 500, because the honest description of a bucket that will not answer is "this service depends on
 * something that is currently unavailable" - which is a different instruction to whoever receives it
 * than "this service has a bug". The distinction matters operationally: a 503 is retried by the
 * things in front of it and a 500 is not.
 *
 * <p>The cause is always carried and never rendered. The SDK's own message names the bucket, the
 * region, the request id and sometimes the credential source, none of which belongs in a response
 * body; the localized text says which operation failed against which location, and the rest is in
 * the log where an operator can correlate it by request id.
 */
public class ObjectStoreException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /**
     * A failed operation.
     *
     * @param operation what was being attempted, for example {@code head} or {@code list}
     * @param uri       the location it was attempted against
     * @param cause     what the store or the SDK threw
     */
    public ObjectStoreException(String operation, String uri, Throwable cause) {
        super(ProblemStatus.SERVICE_UNAVAILABLE, StorageProblemCodes.STORE_UNAVAILABLE, cause, operation, uri);
        withProperty("operation", operation);
        withProperty("uri", uri);
    }

    /**
     * A failed operation described by a subclass, which chooses its own status and code.
     *
     * @param status    the HTTP status this failure renders as
     * @param code      the problem code
     * @param cause     what the store or the SDK threw, or {@code null}
     * @param arguments the message arguments, in the order the bundle's placeholders expect
     */
    protected ObjectStoreException(ProblemStatus status, String code, Throwable cause, Object... arguments) {
        super(status, code, cause, arguments);
    }
}
