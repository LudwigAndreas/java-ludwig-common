package ru.ludwigandreas.webcore.integration;

/**
 * An exception that knows nothing about this starter - it does not extend {@code LocalizedException}
 * and has no Spring dependency. It stands in for a third-party or legacy exception that an
 * application teaches the pipeline about by contributing a mapper.
 */
public class QuotaExceededException extends RuntimeException {

    private final int limit;

    public QuotaExceededException(int limit) {
        super("Quota of " + limit + " exceeded");
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
