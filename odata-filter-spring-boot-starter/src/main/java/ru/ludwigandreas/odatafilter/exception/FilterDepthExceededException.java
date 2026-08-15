package ru.ludwigandreas.odatafilter.exception;

/** The parsed filter's boolean nesting depth exceeds the configured limit. Maps to HTTP 400. */
public class FilterDepthExceededException extends ODataFilterException {

    private final int actualDepth;
    private final int maxDepth;

    public FilterDepthExceededException(int actualDepth, int maxDepth) {
        super("Filter nesting depth %d exceeds the allowed maximum of %d".formatted(actualDepth, maxDepth));
        this.actualDepth = actualDepth;
        this.maxDepth = maxDepth;
    }

    public int actualDepth() {
        return actualDepth;
    }

    public int maxDepth() {
        return maxDepth;
    }
}
