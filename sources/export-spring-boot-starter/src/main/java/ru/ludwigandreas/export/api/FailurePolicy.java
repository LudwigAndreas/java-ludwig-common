package ru.ludwigandreas.export.api;

/**
 * What an enrichment stage does when a partner fails, after {@code rest-client} has already
 * exhausted its retry budget and its circuit breaker has had its say.
 *
 * <p>This is deliberately not a retry setting. Retries, timeouts, bulkheads and circuit breaking
 * belong to the named REST client the stage calls through, where they are configured once per
 * partner and apply to every caller; by the time the engine sees a failure, the partner has been
 * given every chance the platform intends to give it. The only remaining question is what the
 * report should be.
 */
public enum FailurePolicy {

    /**
     * Fail the run. The default, and the right default: a report is usually read as a statement of
     * fact, and a partially-enriched file that does not say so is worse than no file at all.
     */
    FAIL_REPORT,

    /**
     * Produce the report without this stage's data, and say so everywhere it can be seen.
     *
     * <p>Degradation is never silent. The affected cells carry a localized marker rather than being
     * left blank, the run record lists the degraded stage, the metadata sheet names it, the download
     * response reports {@code degraded=true}, and a counter is incremented. A blank cell is not an
     * acceptable sole signal, because a reader cannot distinguish it from a value that is genuinely
     * absent.
     */
    DEGRADE
}
