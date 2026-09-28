package ru.ludwigandreas.ingest.exception;

/**
 * The problem codes this module emits.
 *
 * <h2>Why there is only one</h2>
 *
 * <p>Almost nothing in this module has a caller. A scheduled ingest runs at half past six with nobody
 * waiting on it, so its failures are recorded on the run row and in the log, where the person who
 * needs them will look, in whatever language the log is already in. Giving each of them a code and a
 * translated message would produce a set of bundle keys nothing ever renders - and a bundle key that
 * is never rendered is one nobody notices has gone stale.
 *
 * <p>The exception is the actuator endpoint, which does have a caller: a name that matches no
 * configured task comes back as {@link UnknownIngestTaskException}, localized and rendered by
 * {@code web-core}'s one {@code ProblemDetail} pipeline like every other reachable failure in the
 * platform. The engine's own failures - a balance that does not reconcile, a quarantine rate past its
 * threshold, a lost lease - carry their numbers in the exception message, because the numbers are the
 * diagnosis and an operator reads them from the run row or the endpoint's {@code failure} field.
 *
 * <p>Constants rather than literals at the throw site, for the reason the export starter's equivalent
 * gives: a code is published in a response body and is part of the API contract.
 */
public final class IngestProblemCodes {

    /** Namespace prefix, so a caller can spot an ingest error at a glance. */
    public static final String PREFIX = "ludwig.ingest.error.";

    /** No task is configured, or has a bean, under the requested name. */
    public static final String UNKNOWN_TASK = PREFIX + "unknown-task";

    private IngestProblemCodes() {
    }
}
