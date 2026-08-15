package ru.ludwigandreas.odatafilter.exception;

/** The caller's {@code $top} exceeds the configured maximum page size. Maps to HTTP 400. */
public class PageSizeExceededException extends ODataFilterException {

    private final int requested;
    private final int max;

    public PageSizeExceededException(int requested, int max) {
        super("Requested page size %d exceeds the allowed maximum of %d".formatted(requested, max));
        this.requested = requested;
        this.max = max;
    }

    public int requested() {
        return requested;
    }

    public int max() {
        return max;
    }
}
